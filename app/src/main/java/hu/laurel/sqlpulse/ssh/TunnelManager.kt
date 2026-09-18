package hu.laurel.sqlpulse.ssh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.connection.ConnectionRepository
import hu.laurel.sqlpulse.data.db.ConnectionDao
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.KnownHostDao
import hu.laurel.sqlpulse.data.keys.SshKeyRepository
import hu.laurel.sqlpulse.di.ApplicationScope
import hu.laurel.sqlpulse.di.IoDispatcher
import hu.laurel.sqlpulse.net.NetworkChange
import hu.laurel.sqlpulse.net.NetworkWatcher
import hu.laurel.sqlpulse.security.NoDeviceCredentialException
import hu.laurel.sqlpulse.security.UnlockCancelledException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException

/**
 * Owns the single live tunnel (§5). One connection at a time: the phone is not a workstation, and
 * a second tunnel would double the attack surface for no real gain.
 *
 * The decrypted private key lives only for as long as the handshake takes — after authentication
 * the reference is dropped, which is why a reconnect after a network change needs a fresh unlock.
 */
@Singleton
class TunnelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connections: ConnectionDao,
    /** Only for the sealed SSH password; everything else here goes through the DAO. */
    private val secrets: ConnectionRepository,
    private val knownHosts: KnownHostDao,
    private val keys: SshKeyRepository,
    private val network: NetworkWatcher,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt.asStateFlow()

    /** Last MySQL greeting seen, shown by the connection test (§7.2). */
    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    private var tunnel: SshTunnel? = null
    private var pendingHostKeyDecision: CompletableDeferred<Boolean>? = null
    private var connectJob: Job? = null
    private var backgroundTimeoutJob: Job? = null
    private var networkJob: Job? = null

    /**
     * The connection that was last asked for, so [reconnect] can go through the ordinary connect
     * path again without the caller having to remember which one it was.
     */
    @Volatile
    private var lastConnectionId: Long? = null

    fun connect(connectionId: Long) {
        if (connectJob?.isActive == true) return
        lastConnectionId = connectionId
        connectJob = scope.launch { runConnect(connectionId) }
    }

    /**
     * Dials the last connection again, unlock and all.
     *
     * Deliberately nothing more than [connect]: a reconnect has to unwrap the key, verify the host
     * key and re-probe MySQL exactly as the first attempt did — the decrypted key is dropped after
     * each handshake (§5), so there is no shorter path, and a second one would be a second thing
     * to keep correct.
     *
     * @return false when there is nothing to reconnect to.
     */
    fun reconnect(): Boolean {
        val id = lastConnectionId ?: return false
        connect(id)
        return true
    }

    fun disconnect() {
        connectJob?.cancel()
        backgroundTimeoutJob?.cancel()
        stopWatchingNetwork()
        withTunnel { it.close() }
        tunnel = null
        _serverVersion.value = null
        _hostKeyPrompt.value = null
        pendingHostKeyDecision?.complete(false)
        pendingHostKeyDecision = null
        _state.value = TunnelState.Disconnected
        TunnelService.stop(context)
    }

    fun acceptHostKey() {
        _hostKeyPrompt.value = null
        pendingHostKeyDecision?.complete(true)
        pendingHostKeyDecision = null
    }

    fun rejectHostKey() {
        _hostKeyPrompt.value = null
        pendingHostKeyDecision?.complete(false)
        pendingHostKeyDecision = null
    }

    /** Explicit unblock from the connection editor: the only way past a changed host key (§5). */
    suspend fun forgetHostKey(host: String, port: Int) = withContext(io) {
        knownHosts.delete(host, port)
    }

    /** Called when the app goes to the background: the tunnel survives five minutes (§5). */
    fun onAppBackgrounded() {
        val current = _state.value
        if (current !is TunnelState.Active) return
        _state.value = TunnelState.Paused(
            connectionId = current.connectionId,
            host = current.host,
            port = current.port,
            pausedAt = System.currentTimeMillis(),
            tunnelled = current.tunnelled,
        )
        backgroundTimeoutJob = scope.launch {
            delay(BACKGROUND_GRACE_MS)
            if (_state.value is TunnelState.Paused) disconnect()
        }
    }

    fun onAppForegrounded() {
        backgroundTimeoutJob?.cancel()
        val current = _state.value
        if (current !is TunnelState.Paused) return
        // A direct connection has nothing to keep alive: the JDBC pool reconnects on its own.
        val alive = !current.tunnelled || tunnel?.isAlive == true
        _state.value = if (alive) {
            TunnelState.Active(
                connectionId = current.connectionId,
                host = current.host,
                port = current.port,
                since = current.pausedAt,
                tunnelled = current.tunnelled,
            )
        } else {
            TunnelState.Failed(current.connectionId, networkDropFailure())
        }
        // Nothing left to watch over once the tunnel is gone; a fresh connect starts a new watch.
        if (_state.value is TunnelState.Failed) stopWatchingNetwork()
    }

    private suspend fun runConnect(connectionId: Long) {
        val connection = withContext(io) { connections.byId(connectionId) }
        if (connection == null) {
            _state.value = TunnelState.Failed(connectionId, TunnelFailure(FailureLayer.LOCAL, "Connection not found"))
            return
        }

        if (!connection.useSshTunnel) {
            connectDirectly(connection)
            return
        }

        _state.value = TunnelState.Unlocking(connectionId)
        val credential: SshCredential = try {
            when (SshAuthMethod.fromName(connection.sshAuthMethod)) {
                SshAuthMethod.KEY -> {
                    val keyId = connection.sshKeyId
                        ?: throw IllegalStateException("a key tunnel without a key (§5)")
                    val key = withContext(io) { keys.byId(keyId) }
                        ?: throw IllegalStateException("connection references a key that is gone")
                    SshCredential.Key(keys.unlockKeyPair(key))
                }

                SshAuthMethod.PASSWORD -> {
                    val password = secrets.sshPassword(connectionId, connection.name)
                        ?: throw IllegalStateException("no SSH password stored for this connection")
                    SshCredential.Password(password)
                }
            }
        } catch (e: UnlockCancelledException) {
            _state.value = TunnelState.Disconnected
            return
        } catch (e: NoDeviceCredentialException) {
            _state.value = TunnelState.Failed(
                connectionId,
                TunnelFailure(FailureLayer.KEY, context.getString(R.string.error_no_device_credential)),
            )
            return
        } catch (e: Exception) {
            _state.value = TunnelState.Failed(connectionId, TunnelFailure(FailureLayer.KEY, keyFailureMessage(e), e.toString()))
            return
        }

        _state.value = TunnelState.Connecting(connectionId, ConnectStep.SSH)
        val verifier = PinningHostKeyVerifier(knownHosts, ::askAboutHostKey)
        val fresh = SshTunnel(connection.toTunnelConfig(), credential, verifier)
        tunnel = fresh

        val port = try {
            withContext(io) {
                fresh.connect()
                fresh.startForwarding()
            }
        } catch (e: Exception) {
            fresh.close()
            tunnel = null
            _state.value = TunnelState.Failed(connectionId, sshFailure(e, verifier))
            return
        } finally {
            // The handshake is over either way; the password has no reason to stay in memory.
            (credential as? SshCredential.Password)?.password?.fill(Char(0))
        }

        _state.value = TunnelState.Connecting(connectionId, ConnectStep.MYSQL)
        try {
            _serverVersion.value = withContext(io) { MysqlProbe.serverVersion(port = port) }
        } catch (e: Exception) {
            fresh.close()
            tunnel = null
            _state.value = TunnelState.Failed(
                connectionId,
                TunnelFailure(FailureLayer.MYSQL, e.message ?: "MySQL did not answer", e.toString()),
            )
            return
        }

        withContext(io) { connections.touch(connectionId, System.currentTimeMillis()) }
        _state.value = TunnelState.Active(
            connectionId = connectionId,
            host = SshTunnel.LOOPBACK,
            port = port,
            since = System.currentTimeMillis(),
            tunnelled = true,
        )
        startWatchingNetwork()
        TunnelService.start(context, connection.name, port, connection.dbHost, connection.dbPort)
    }

    /**
     * No tunnel: the phone dials the database itself.
     *
     * There is no key to unlock and no foreground service to run — nothing has to be kept alive
     * between statements, so the connection indicator goes straight to the MySQL step.
     */
    private suspend fun connectDirectly(connection: ConnectionEntity) {
        val connectionId = connection.id
        _state.value = TunnelState.Connecting(connectionId, ConnectStep.MYSQL)
        try {
            _serverVersion.value = withContext(io) {
                MysqlProbe.serverVersion(port = connection.dbPort, host = connection.dbHost)
            }
        } catch (e: Exception) {
            _state.value = TunnelState.Failed(
                connectionId,
                TunnelFailure(FailureLayer.MYSQL, e.message ?: "MySQL did not answer", e.toString()),
            )
            return
        }

        withContext(io) { connections.touch(connectionId, System.currentTimeMillis()) }
        _state.value = TunnelState.Active(
            connectionId = connectionId,
            host = connection.dbHost,
            port = connection.dbPort,
            since = System.currentTimeMillis(),
            tunnelled = false,
        )
        // A direct connection has no forward to lose, but its JDBC pool is bound to the network
        // just the same, so it is watched too.
        startWatchingNetwork()
    }

    private suspend fun askAboutHostKey(prompt: HostKeyPrompt): Boolean {
        if (prompt.storedFingerprint != null) return false
        val decision = CompletableDeferred<Boolean>()
        pendingHostKeyDecision = decision
        _hostKeyPrompt.value = prompt
        return decision.await()
    }

    private fun sshFailure(e: Exception, verifier: PinningHostKeyVerifier): TunnelFailure {
        verifier.blocked?.let { blocked ->
            return TunnelFailure(
                layer = FailureLayer.HOST_KEY,
                message = context.getString(
                    R.string.host_key_changed_body,
                    blocked.storedFingerprint.orEmpty(),
                    blocked.offeredFingerprint,
                ),
                detail = e.toString(),
            )
        }
        return when (e) {
            is UserAuthException -> TunnelFailure(
                FailureLayer.SSH_AUTH,
                context.getString(R.string.error_auth_rejected),
                e.toString(),
            )

            is UnknownHostException, is ConnectException, is SocketTimeoutException -> TunnelFailure(
                FailureLayer.SSH_NETWORK,
                context.getString(R.string.error_ssh_unreachable),
                e.toString(),
            )

            is TransportException, is IOException -> TunnelFailure(
                FailureLayer.SSH_NETWORK,
                e.message ?: context.getString(R.string.error_ssh_unreachable),
                e.toString(),
            )

            else -> TunnelFailure(FailureLayer.SSH_NETWORK, e.message.orEmpty(), e.toString())
        }
    }

    private fun keyFailureMessage(e: Exception): String =
        e.message ?: context.getString(R.string.error_key_unreadable)

    private fun networkDropFailure(change: NetworkChange? = null) = TunnelFailure(
        layer = FailureLayer.SSH_NETWORK,
        message = when (change) {
            // Two different sentences, because they need two different reactions from the user:
            // one is "wait for coverage", the other is "you are on another network now".
            NetworkChange.SWITCHED -> context.getString(R.string.network_changed)
            NetworkChange.LOST -> context.getString(R.string.network_lost)
            else -> context.getString(R.string.error_ssh_unreachable)
        },
    )

    /**
     * Wi-Fi to mobile and back tears the TCP connection down, and so does the default network
     * simply being replaced by another one — the socket is bound to the network it was opened on.
     *
     * Neither throws anything at us: the forward and every JDBC connection behind it sit there
     * looking healthy until a statement waits out its timeout. So the change itself is the signal,
     * and we tear the tunnel down on it rather than waiting to be told (§5, §11).
     *
     * Whether anything is dialled again afterwards is not decided here — SqlSessionManager asks
     * ReconnectPolicy, because only it knows whether a write or a transaction was in the air.
     */
    private fun startWatchingNetwork() {
        if (networkJob?.isActive == true) return
        // One change is all this job is for: the tunnel it was watching does not exist afterwards.
        // Ending here is also what unregisters the ConnectivityManager callback, since the watcher
        // registers only while something is collecting it.
        networkJob = scope.launch {
            val change = network.changes.first { it.invalidatesConnections }
            onNetworkInvalidated(change)
        }
    }

    private fun stopWatchingNetwork() {
        networkJob?.cancel()
        networkJob = null
    }

    private fun onNetworkInvalidated(change: NetworkChange) {
        val current = _state.value
        // Unlocking or Connecting fails on its own, with a better message than "network dropped".
        if (current !is TunnelState.Active && current !is TunnelState.Paused) return
        val id = current.connectionId ?: return
        // No isAlive check: after a hand-over sshj still believes it is connected, and that belief
        // is exactly what makes the next query hang for its full timeout.
        withTunnel { it.close() }
        tunnel = null
        TunnelService.stop(context)
        _state.value = TunnelState.Failed(id, networkDropFailure(change))
    }

    private inline fun withTunnel(block: (SshTunnel) -> Unit) {
        tunnel?.let(block)
    }

    companion object {
        /** §5: at most five minutes in the background, then the tunnel drops. */
        const val BACKGROUND_GRACE_MS = 5 * 60 * 1000L
    }
}

fun ConnectionEntity.toTunnelConfig() = TunnelConfig(
    sshHost = sshHost,
    sshPort = sshPort,
    sshUser = sshUser,
    dbHost = dbHost,
    dbPort = dbPort,
    jumpHost = sshJumpHost,
    jumpPort = sshJumpPort,
    jumpUser = sshJumpUser,
)
