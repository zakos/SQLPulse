package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.connection.CertificateStore
import hu.laurel.sqlpulse.data.connection.ConnectionRepository
import hu.laurel.sqlpulse.data.connection.ConnectionTimeouts
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.di.ApplicationScope
import hu.laurel.sqlpulse.di.IoDispatcher
import hu.laurel.sqlpulse.security.UnlockCancelledException
import hu.laurel.sqlpulse.ssh.TunnelManager
import hu.laurel.sqlpulse.ssh.TunnelState
import java.sql.Connection
import java.sql.SQLException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope scope: CoroutineScope,
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

    init {
        scope.launch {
            tunnelManager.state.collect { tunnel ->
                when (tunnel) {
                    is TunnelState.Active -> open(tunnel)
                    is TunnelState.Paused -> Unit // The forward is still up; keep the pool.
                    else -> closeSession()
                }
            }
        }
    }

    /** Runs [block] on a pooled JDBC connection, off the main thread (§4). */
    suspend fun <T> withConnection(block: (Connection) -> T): T {
        val live = session ?: throw NoSqlSessionException()
        val target = _database.value
        val open = transaction
        return withContext(io) {
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

    private suspend fun open(tunnel: TunnelState.Active) {
        if (_state.value is SqlSessionState.Ready) return
        val entity = connections.byId(tunnel.connectionId) ?: return
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
            _database.value = entity.database.takeIf { it.isNotBlank() }
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
        _database.value = null
        if (_state.value != SqlSessionState.Closed) _state.value = SqlSessionState.Closed
    }
}
