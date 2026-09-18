package hu.laurel.sqlpulse.data.sql

import java.io.Closeable
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties
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
)

/**
 * A small connection pool (§4: at most three connections, so a forward is not overloaded).
 *
 * Connections are created lazily and handed out one at a time; [close] drops them all, which is
 * what happens when the tunnel — or, for a direct connection, the session — goes away.
 */
class SqlSession(private val config: JdbcConfig) : Closeable {

    private val idle = ArrayBlockingQueue<Connection>(MAX_CONNECTIONS)
    private val created = AtomicInteger(0)

    @Volatile
    private var closed = false

    /** Set once the first connection succeeds, so the rest of the pool does not try both. */
    @Volatile
    var settled: JdbcDriverKind? = null
        private set

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

    /**
     * Opens a connection with whichever driver this server speaks.
     *
     * The modern driver is tried first and is what nearly every server gets. It builds
     * `SET NAMES utf8mb4` into the handshake, and a server older than MySQL 5.5.3 has never heard
     * of utf8mb4 and refuses the session — that refusal, and only that one, moves the session to
     * the legacy driver for good. Anything else (a wrong password, an unreachable host) is the
     * caller's to hear about at once.
     */
    private fun open(): Connection {
        settled?.let { return openWith(it) }
        return try {
            openWith(JdbcDriverKind.MODERN).also { settled = JdbcDriverKind.MODERN }
        } catch (e: SQLException) {
            if (!SqlFailures.isCharacterSetRefusal(e)) throw e
            openWith(JdbcDriverKind.LEGACY).also { settled = JdbcDriverKind.LEGACY }
        }
    }

    private fun openWith(kind: JdbcDriverKind): Connection {
        val properties = Properties().apply {
            setProperty("user", config.user)
            config.password?.let { setProperty("password", it) }
            setProperty("connectTimeout", config.connectTimeoutMs.toString())
            setProperty("socketTimeout", config.socketTimeoutMs.toString())
            // §11: a dropped connection is never retried behind the user's back.
            setProperty("autoReconnect", "false")
            setProperty("tcpKeepAlive", "true")
            // A malicious or compromised server can otherwise ask the client for local files.
            when (kind) {
                JdbcDriverKind.MODERN -> setProperty("allowLocalInfile", "false")
                JdbcDriverKind.LEGACY -> {
                    setProperty("allowLoadLocalInfile", "false")
                    // The old driver needs telling; the new one is UTF-8 without being asked.
                    setProperty("useUnicode", "true")
                    setProperty("characterEncoding", "UTF-8")
                    // Its own statement cache and metadata cache are memory we do not need.
                    setProperty("cachePrepStmts", "false")
                }
            }
            SslProperties.propertiesFor(config.sslMode, config.caCertificatePath, kind)
                .forEach { (key, value) -> setProperty(key, value) }
        }

        val scheme = if (kind == JdbcDriverKind.MODERN) "mariadb" else "mysql"
        val url = "jdbc:$scheme://${config.host}:${config.port}/${config.database}"
        // The driver class is named rather than discovered: DriverManager finds its drivers
        // through a declaration in META-INF, and that lookup is not reliable on Android.
        val driver = if (kind == JdbcDriverKind.MODERN) modernDriver else legacyDriver
        return (driver.connect(url, properties)
            ?: throw SQLException("the driver did not accept $url")).apply {
            // Belt and braces next to the MySQL grants (§3): the server rejects writes anyway.
            isReadOnly = config.readOnly
            autoCommit = true
        }
    }

    override fun close() {
        closed = true
        while (true) {
            val connection = idle.poll() ?: break
            runCatching { connection.close() }
        }
        created.set(0)
    }

    companion object {
        // Instances rather than DriverManager registrations: both classes register themselves
        // through java.sql.DriverManager, and on Android that registry is not reliably populated
        // from a jar's service declaration.
        private val modernDriver: Driver by lazy { org.mariadb.jdbc.Driver() }
        private val legacyDriver: Driver by lazy { com.mysql.jdbc.Driver() }

        /** §4: one pool per connection, at most three JDBC connections. */
        const val MAX_CONNECTIONS = 3
        private const val VALIDATION_TIMEOUT_SECONDS = 2
        private const val BORROW_TIMEOUT_SECONDS = 30L
    }
}
