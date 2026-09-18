package hu.laurel.sqlpulse.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.db.ConnectionDao
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.KnownHostDao
import hu.laurel.sqlpulse.data.keys.SshKeyRepository
import hu.laurel.sqlpulse.di.ApplicationScope
import hu.laurel.sqlpulse.di.IoDispatcher
import hu.laurel.sqlpulse.security.NoDeviceCredentialException
import hu.laurel.sqlpulse.security.UnlockCancelledException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyPair
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
    private val knownHosts: KnownHostDao,
    private val keys: SshKeyRepository,
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun connect(connectionId: Long) {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { runConnect(connectionId) }
    }

    fun disconnect() {
        connectJob?.cancel()
        backgroundTimeoutJob?.cancel()
        unregisterNetworkCallback()
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
        val keyPair: KeyPair = try {
            val keyId = connection.sshKeyId
                ?: throw IllegalStateException("a tunnelled connection without a key (§5)")
            val key = withContext(io) { keys.byId(keyId) }
                ?: throw IllegalStateException("connection references a key that is gone")
            keys.unlockKeyPair(key)
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
        val fresh = SshTunnel(connection.toTunnelConfig(), keyPair, verifier)
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
        registerNetworkCallback()
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
                context.getString(R.string.error_bastion_unreachable),
                e.toString(),
            )

            is TransportException, is IOException -> TunnelFailure(
                FailureLayer.SSH_NETWORK,
                e.message ?: context.getString(R.string.error_bastion_unreachable),
                e.toString(),
            )

            else -> TunnelFailure(FailureLayer.SSH_NETWORK, e.message.orEmpty(), e.toString())
        }
    }

    private fun keyFailureMessage(e: Exception): String =
        e.message ?: context.getString(R.string.error_key_unreadable)

    private fun networkDropFailure() = TunnelFailure(
        layer = FailureLayer.SSH_NETWORK,
        message = context.getString(R.string.error_bastion_unreachable),
    )

    /**
     * Wi-Fi to mobile and back tears the TCP connection down. We do not silently reconnect: the
     * key is no longer in memory, and an automatic retry during a write would be dangerous (§11).
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) = checkLiveness()
            override fun onAvailable(network: Network) = checkLiveness()
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
    }

    private fun unregisterNetworkCallback() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { runCatching { manager?.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun checkLiveness() {
        val current = _state.value
        if (current !is TunnelState.Active && current !is TunnelState.Paused) return
        // Only a tunnel can go stale here; a direct connection is the JDBC pool's business.
        val tunnelled = (current as? TunnelState.Active)?.tunnelled
            ?: (current as? TunnelState.Paused)?.tunnelled ?: false
        if (!tunnelled) return
        if (tunnel?.isAlive == true) return
        val id = current.connectionId ?: return
        withTunnel { it.close() }
        tunnel = null
        unregisterNetworkCallback()
        TunnelService.stop(context)
        _state.value = TunnelState.Failed(id, networkDropFailure())
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
    bastionHost = bastionHost,
    bastionPort = bastionPort,
    sshUser = sshUser,
    dbHost = dbHost,
    dbPort = dbPort,
)
