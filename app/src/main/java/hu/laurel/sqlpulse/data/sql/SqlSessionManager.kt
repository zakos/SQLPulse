package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.connection.CertificateStore
import hu.laurel.sqlpulse.data.connection.ConnectionRepository
import hu.laurel.sqlpulse.data.connection.ConnectionTimeouts
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.di.ApplicationScope
import hu.laurel.sqlpulse.di.IoDispatcher
import hu.laurel.sqlpulse.net.ManualReason
import hu.laurel.sqlpulse.net.NetworkStatus
import hu.laurel.sqlpulse.net.NetworkWatcher
import hu.laurel.sqlpulse.net.ReconnectDecision
import hu.laurel.sqlpulse.net.ReconnectPolicy
import hu.laurel.sqlpulse.net.SessionSnapshot
import hu.laurel.sqlpulse.security.UnlockCancelledException
import hu.laurel.sqlpulse.ssh.FailureLayer
import hu.laurel.sqlpulse.ssh.TunnelManager
import hu.laurel.sqlpulse.ssh.TunnelState
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SqlSessionState {
    data object Closed : SqlSessionState
    data object Opening : SqlSessionState
    data class Ready(val connection: ConnectionEntity, val serverVersion: String?) : SqlSessionState
    /** [failure] is present when the driver reported a server error we could classify. */
    data class Failed(
        val message: String,
        val detail: String? = null,
        val failure: SqlFailure? = null,
    ) : SqlSessionState

    /**
     * The network moved under a live session (§5).
     *
     * Separate from [Failed] on purpose: nothing went wrong with the database, the pool is simply
     * gone, and the session can be had back — either by itself or by one tap — without losing what
     * is on screen. [Closed] would say the user had disconnected, which they did not.
     */
    data class Lost(
        val connection: ConnectionEntity,
        /**
         * A manual transaction was open. The server rolled it back when the connection went, so
         * the user is told that rather than being reconnected quietly into a fresh transaction.
         */
        val rolledBackTransaction: Boolean,
        /** True while a reconnect is being attempted, automatically or because the user asked. */
        val reconnecting: Boolean = false,
        /** Which automatic attempt is running, for the banner. Null for a manual reconnect. */
        val attempt: Int? = null,
        /** Why we stopped trying by ourselves; null while automatic attempts are still running. */
        val reason: ManualReason? = null,
    ) : SqlSessionState
}

/** No live session: the caller asked for data while the tunnel was down. */
class NoSqlSessionException : Exception("no MySQL session")

/**
 * Binds a JDBC session to the tunnel's lifetime: opened as soon as the tunnel is up, dropped the
 * moment it is not. Nothing here survives a disconnect, which is what §9 means by "results live in
 * memory only, for the session".
 */
@Singleton
class SqlSessionManager @Inject constructor(
    private val tunnelManager: TunnelManager,
    private val connections: ConnectionRepository,
    private val certificates: CertificateStore,
    private val network: NetworkWatcher,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<SqlSessionState>(SqlSessionState.Closed)
    val state: StateFlow<SqlSessionState> = _state.asStateFlow()

    /**
     * The database everything runs against. It starts as the connection's own, and the schema
     * browser, the database picker and a typed `USE` all move it.
     *
     * It is applied per borrowed connection rather than by running `USE`, because the pool hands
     * out several connections and `USE` would only move one of them.
     */
    private val _database = MutableStateFlow<String?>(null)
    val database: StateFlow<String?> = _database.asStateFlow()

    @Volatile
    private var session: SqlSession? = null

    /**
     * The connection a manual transaction is running on, or null when statements commit as they go.
     *
     * A transaction has to stay on one connection, so this one is taken out of the pool for as long
     * as it is open. Everything the app does then runs on it, which is the point: a COMMIT should
     * cover what the user has done since they started, not a subset that happened to share a
     * connection.
     */
    @Volatile
    private var transaction: Connection? = null

    private val _inTransaction = MutableStateFlow(false)
    val inTransaction: StateFlow<Boolean> = _inTransaction.asStateFlow()

    /**
     * How many statements are on the wire right now.
     *
     * Only ever read to answer "did we lose the connection in the middle of something?", which is
     * one of the three things that stop an automatic reconnect: we cannot know whether the server
     * ran what we sent.
     */
    private val inFlight = AtomicInteger(0)

    /** Whether anything that could have changed the database has run since this session opened. */
    @Volatile
    private var wroteSinceConnect = false

    private var reconnectJob: Job? = null

    /**
     * The database the user was on when the session went, so a reconnect puts them back on it
     * rather than on the connection's default. Cleared once it has been handed back.
     */
    @Volatile
    private var databaseBeforeLoss: String? = null

    init {
        scope.launch {
            tunnelManager.state.collect { tunnel ->
                when (tunnel) {
                    is TunnelState.Active -> open(tunnel)
                    is TunnelState.Paused -> Unit // The forward is still up; keep the pool.
                    is TunnelState.Failed -> onTunnelFailed(tunnel)

                    // A reconnect walks back through these on its way up. Closing here would drop
                    // the "connection lost" banner the user is looking at and replace it with a
                    // blank screen, so while we are in Lost they are left alone; the pool was
                    // released when the loss was noticed and there is nothing to free.
                    is TunnelState.Unlocking, is TunnelState.Connecting ->
                        if (_state.value !is SqlSessionState.Lost) closeSession()

                    is TunnelState.Disconnected -> {
                        // The user (or the lock) ended it: stop trying to get it back.
                        cancelReconnect()
                        closeSession()
                    }
                }
            }
        }
    }

    /** Runs [block] on a pooled JDBC connection, off the main thread (§4). */
    suspend fun <T> withConnection(block: (Connection) -> T): T {
        val live = session ?: throw NoSqlSessionException()
        val target = _database.value
        val open = transaction
        inFlight.incrementAndGet()
        return try {
            withContext(io) {
                if (open != null) {
                    if (target != null && open.catalog != target) open.catalog = target
                    block(open)
                } else {
                    live.use { connection ->
                        if (target != null && connection.catalog != target) connection.catalog = target
                        block(connection)
                    }
                }
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /**
     * Records that a statement which may have written has run on this session.
     *
     * The session does not parse SQL — the query layer already classifies every statement — so the
     * knowledge has to come in from there. Until it does, the read-only flag on the connection is
     * what keeps an automatic reconnect away from a writable session, which is the same guarantee
     * one step coarser.
     */
    fun noteWrite() {
        wroteSinceConnect = true
    }

    /**
     * Starts a manual transaction: from here nothing is written until [commit].
     *
     * Losing the session — the tunnel dropping, the app locking — rolls it back, because the
     * server will do that anyway when the connection goes.
     */
    suspend fun beginTransaction() {
        val live = session ?: throw NoSqlSessionException()
        if (transaction != null) return
        withContext(io) {
            val connection = live.take()
            connection.autoCommit = false
            transaction = connection
        }
        _inTransaction.value = true
    }

    suspend fun commit() = endTransaction { it.commit() }

    suspend fun rollback() = endTransaction { it.rollback() }

    private suspend fun endTransaction(finish: (Connection) -> Unit) {
        val open = transaction ?: return
        transaction = null
        _inTransaction.value = false
        withContext(io) {
            try {
                finish(open)
            } finally {
                runCatching { open.autoCommit = true }
                session?.giveBack(open)
            }
        }
    }

    /** Switches the current database. The next statement runs against it. */
    fun selectDatabase(name: String) {
        _database.value = name
    }

    /**
     * How long a single statement on this connection may run, in seconds.
     *
     * Per connection rather than one number for the app: the same query that is a runaway on a
     * production server is a legitimate report on a development one.
     */
    fun queryTimeoutSeconds(): Int = ConnectionTimeouts.sane(
        currentConnection()?.queryTimeoutSeconds ?: ConnectionTimeouts.DEFAULT_QUERY_SECONDS,
        ConnectionTimeouts.DEFAULT_QUERY_SECONDS,
    )

    /** Which driver the live session opened with, or null when there is no session. */
    fun driverInUse(): JdbcDriverKind? = session?.settled

    fun currentConnection(): ConnectionEntity? = (_state.value as? SqlSessionState.Ready)?.connection

    /** Reconnects the connection that was lost, through the same unlock and tunnel path as ever. */
    fun reconnect() {
        val lost = _state.value as? SqlSessionState.Lost ?: return
        cancelReconnect()
        _state.value = lost.copy(reconnecting = true, attempt = null, reason = null)
        if (!tunnelManager.reconnect()) {
            _state.value = lost.copy(reconnecting = false, reason = ManualReason.RETRIES_EXHAUSTED)
        }
    }

    private fun onTunnelFailed(tunnel: TunnelState.Failed) {
        // Already lost and already being worked on: the retry loop owns the state from here, and a
        // second loop started from a failed attempt would fight it.
        if (_state.value is SqlSessionState.Lost) {
            updateLost { it.copy(reconnecting = false) }
            return
        }

        val ready = _state.value as? SqlSessionState.Ready
        val snapshot = SessionSnapshot(
            readOnly = ready?.connection?.readOnly ?: true,
            transactionOpen = transaction != null,
            statementInFlight = inFlight.get() > 0,
            wroteSinceConnect = wroteSinceConnect,
        )
        val hadTransaction = transaction != null
        databaseBeforeLoss = _database.value
        closeSession()

        // Only a network drop is recoverable this way. An authentication or host key failure has
        // to be read and acted on, and retrying it would just lock the account out faster.
        if (ready == null || tunnel.failure.layer != FailureLayer.SSH_NETWORK) return

        _state.value = SqlSessionState.Lost(
            connection = ready.connection,
            rolledBackTransaction = hadTransaction,
        )
        startAutomaticReconnect(snapshot)
    }

    /**
     * Gets the session back by itself when — and only when — nothing could have been half-done
     * (§11). [ReconnectPolicy] makes every one of those calls; this is the machinery around it.
     */
    private fun startAutomaticReconnect(snapshot: SessionSnapshot) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempts = 0
            while (true) {
                when (val decision = ReconnectPolicy.decide(snapshot, network.state.value, attempts)) {
                    is ReconnectDecision.Manual -> {
                        updateLost { it.copy(reconnecting = false, attempt = null, reason = decision.reason) }
                        return@launch
                    }

                    // No network to dial over. Any network waking up is worth another look —
                    // including a different one, which is a perfectly good one to reconnect on.
                    ReconnectDecision.Wait -> {
                        updateLost { it.copy(reconnecting = false, attempt = null) }
                        network.state.first { it is NetworkStatus.Available }
                    }

                    is ReconnectDecision.Retry -> {
                        attempts = decision.attempt
                        delay(decision.delayMs)
                        // The network may have gone again while we waited. Going round rather than
                        // dialling means a second loss does not burn the remaining attempts on a
                        // connection that cannot succeed.
                        if (network.state.value !is NetworkStatus.Available) continue
                        updateLost { it.copy(reconnecting = true, attempt = decision.attempt) }
                        if (!tunnelManager.reconnect()) {
                            updateLost {
                                it.copy(reconnecting = false, reason = ManualReason.RETRIES_EXHAUSTED)
                            }
                            return@launch
                        }
                        if (awaitTunnelOutcome()) return@launch
                    }
                }
            }
        }
    }

    /**
     * Waits for the attempt to land. @return true once the tunnel is up — the session collector
     * opens the pool from there, so there is nothing more for the loop to do.
     *
     * [dropWhile] skips the failure we are reacting to, which is still the current value of the
     * tunnel's state flow when the attempt starts.
     */
    private suspend fun awaitTunnelOutcome(): Boolean {
        val outcome = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
            tunnelManager.state
                .dropWhile { it is TunnelState.Failed }
                .first { it is TunnelState.Active || it is TunnelState.Failed || it is TunnelState.Disconnected }
        }
        return outcome is TunnelState.Active
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun updateLost(block: (SqlSessionState.Lost) -> SqlSessionState.Lost) {
        val lost = _state.value as? SqlSessionState.Lost ?: return
        _state.value = block(lost)
    }

    private suspend fun open(tunnel: TunnelState.Active) {
        if (_state.value is SqlSessionState.Ready) return
        val entity = connections.byId(tunnel.connectionId) ?: return
        cancelReconnect()
        _state.value = SqlSessionState.Opening

        val password = try {
            connections.password(entity.id, entity.name)
        } catch (e: UnlockCancelledException) {
            _state.value = SqlSessionState.Closed
            return
        } catch (e: Exception) {
            _state.value = SqlSessionState.Failed(e.message.orEmpty(), e.toString())
            return
        }

        try {
            val fresh = SqlSession(
                JdbcConfig(
                    host = tunnel.host,
                    port = tunnel.port,
                    database = entity.database,
                    user = entity.dbUser,
                    password = password?.concatToString(),
                    readOnly = entity.readOnly,
                    sslMode = SslMode.fromName(entity.sslMode),
                    caCertificatePath = entity.caCertificate
                        ?.let { certificates.pathFor(it) },
                    connectTimeoutMs = ConnectionTimeouts.connectMillis(entity.connectTimeoutSeconds),
                    socketTimeoutMs = ConnectionTimeouts.socketMillis(entity.queryTimeoutSeconds),
                ),
            )
            val version = withContext(io) {
                fresh.use { it.metaData.databaseProductVersion }
            }
            session = fresh
            wroteSinceConnect = false
            // Back on the database the user was on, not on the connection's default: the reconnect
            // is meant to be invisible, and a silently different catalog is the opposite of that.
            _database.value = databaseBeforeLoss?.takeIf { it.isNotBlank() }
                ?: entity.database.takeIf { it.isNotBlank() }
            databaseBeforeLoss = null
            _state.value = SqlSessionState.Ready(entity, version)
        } catch (e: SQLException) {
            // §11: the MySQL error is shown verbatim, with the likely cause beside it.
            _state.value = SqlSessionState.Failed(
                message = "${e.errorCode}: ${e.message}",
                detail = e.toString(),
                failure = SqlFailures.of(e),
            )
        } catch (e: Exception) {
            _state.value = SqlSessionState.Failed(e.message.orEmpty(), e.toString())
        } finally {
            password?.fill(Char(0))
        }
    }

    private fun closeSession() {
        // An open transaction dies with the connection; rolling it back first is only tidier.
        transaction?.let { open ->
            runCatching { open.rollback() }
            runCatching { open.close() }
        }
        transaction = null
        _inTransaction.value = false
        session?.close()
        session = null
        inFlight.set(0)
        wroteSinceConnect = false
        _database.value = null
        if (_state.value != SqlSessionState.Closed) _state.value = SqlSessionState.Closed
    }

    private companion object {
        /**
         * How long one automatic attempt may take before it counts as failed. Generous, because it
         * covers an unlock the user may be looking at, and it exists only so the loop cannot wait
         * for a tunnel state that never arrives.
         */
        const val ATTEMPT_TIMEOUT_MS = 60_000L
    }
}
