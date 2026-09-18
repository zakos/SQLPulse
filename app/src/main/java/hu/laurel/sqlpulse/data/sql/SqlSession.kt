package hu.laurel.sqlpulse.data.sql

import java.io.Closeable
import java.sql.Connection
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.mariadb.jdbc.MariaDbConnection
import org.mariadb.jdbc.UrlParser

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

    private fun open(): Connection {
        val properties = Properties().apply {
            setProperty("user", config.user)
            config.password?.let { setProperty("password", it) }
            setProperty("connectTimeout", config.connectTimeoutMs.toString())
            setProperty("socketTimeout", config.socketTimeoutMs.toString())
            // A malicious or compromised server can otherwise ask the client for local files.
            setProperty("allowLocalInfile", "false")
            // §11: a dropped connection is never retried behind the user's back.
            setProperty("autoReconnect", "false")
            setProperty("tcpKeepAlive", "true")
            SslProperties.propertiesFor(config.sslMode, config.caCertificatePath)
                .forEach { (key, value) -> setProperty(key, value) }
        }
        val url = "jdbc:mariadb://${config.host}:${config.port}/${config.database}"

        // Neither DriverManager nor the driver's own Driver class can be used here. The 2.7
        // driver registers itself with DriverManager.registerDriver(Driver, DriverAction), and
        // java.sql.DriverAction is JDBC 4.2, which Android does not have — merely loading that
        // class throws NoClassDefFoundError, which is also why the service lookup came back
        // empty. Its DataSource is out too: it implements javax.sql.XADataSource.
        //
        // What is left is what the DataSource itself calls, and that is enough: parse the URL,
        // open the connection. It is internal API, so the driver version is pinned.
        val parser = UrlParser.parse(url, properties)
        return MariaDbConnection.newConnection(parser, null).apply {
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
        /** §4: one pool per connection, at most three JDBC connections. */
        const val MAX_CONNECTIONS = 3
        private const val VALIDATION_TIMEOUT_SECONDS = 2
        private const val BORROW_TIMEOUT_SECONDS = 30L
    }
}
