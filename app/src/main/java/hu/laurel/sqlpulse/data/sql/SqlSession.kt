package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.EngineConnector
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialects
import java.io.Closeable
import java.sql.Connection
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class JdbcConfig(
    /** Loopback for a tunnelled connection, the database's own address for a direct one. */
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String?,
    val readOnly: Boolean,
    val sslMode: SslMode = SslMode.DISABLED,
    /** PEM file with the CA that signed the server certificate; needed by the verifying modes. */
    val caCertificatePath: String? = null,
    val connectTimeoutMs: Int = 10_000,
    val socketTimeoutMs: Int = 30_000,
    /**
     * Whether something already encrypts this link — an SSH tunnel, in practice.
     *
     * It decides one thing: whether the driver may ask the server for its RSA public key. See
     * MySqlConnector (data/sql/dialect/MySqlDialect.kt).
     */
    val tunnelled: Boolean = false,
    /** Which engine — and so which connector, driver and URL — this session uses. */
    val engine: DatabaseEngine = DatabaseEngine.MYSQL,
    /**
     * The database file, for an engine without a server (SQLite): an absolute path in app-private
     * storage. [host] and [port] are unused then. Null for every server engine.
     */
    val localFile: String? = null,
)

/**
 * A small connection pool (§4: at most three connections, so a forward is not overloaded).
 *
 * Connections are created lazily and handed out one at a time; [close] drops them all, which is
 * what happens when the tunnel — or, for a direct connection, the session — goes away.
 */
class SqlSession(
    config: JdbcConfig,
    /** How a connection is opened; the engine's own unless a test hands one in. */
    private val connector: EngineConnector = SqlDialects.forEngine(config.engine).connector(config),
) : Closeable {

    private val idle = ArrayBlockingQueue<Connection>(MAX_CONNECTIONS)
    private val created = AtomicInteger(0)

    @Volatile
    private var closed = false

    /**
     * Which MySQL driver the first connection settled on (see MySqlConnector), so the rest of the
     * pool does not try both. Null before then and for every other engine.
     */
    val settled: JdbcDriverKind? get() = connector.settled

    /** Borrows a connection, runs [block], and returns the connection to the pool. */
    fun <T> use(block: (Connection) -> T): T {
        check(!closed) { "session is closed" }
        val connection = borrow()
        return try {
            block(connection)
        } finally {
            release(connection)
        }
    }

    /**
     * Takes a connection out of the pool until [giveBack] returns it.
     *
     * Used by a manual transaction, which has to stay on one connection: a COMMIT is meaningless
     * if the statements before it were spread over three.
     */
    fun take(): Connection {
        check(!closed) { "session is closed" }
        return borrow()
    }

    fun giveBack(connection: Connection) = release(connection)

    private fun borrow(): Connection {
        idle.poll()?.let { pooled ->
            return if (pooled.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                pooled
            } else {
                runCatching { pooled.close() }
                created.decrementAndGet()
                borrow()
            }
        }
        if (created.get() < MAX_CONNECTIONS) {
            created.incrementAndGet()
            return try {
                open()
            } catch (e: Exception) {
                created.decrementAndGet()
                throw e
            }
        }
        return idle.poll(BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?: throw IllegalStateException("no free connection in the pool")
    }

    private fun release(connection: Connection) {
        if (closed || connection.isClosed) {
            runCatching { connection.close() }
            created.decrementAndGet()
            return
        }
        if (!idle.offer(connection)) {
            runCatching { connection.close() }
            created.decrementAndGet()
        }
    }

    /** Opens one connection the engine's way (MySQL: MySqlConnector's modern driver and fallback). */
    private fun open(): Connection = connector.open()

    override fun close() {
        closed = true
        while (true) {
            val connection = idle.poll() ?: break
            runCatching { connection.close() }
        }
        created.set(0)
    }

    companion object {
        /** §4: one pool per connection, at most three JDBC connections. */
        const val MAX_CONNECTIONS = 3
        private const val VALIDATION_TIMEOUT_SECONDS = 2
        private const val BORROW_TIMEOUT_SECONDS = 30L
    }
}
