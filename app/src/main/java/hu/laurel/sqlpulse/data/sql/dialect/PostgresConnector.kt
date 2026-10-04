package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SslMode
import java.net.URLEncoder
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties

/**
 * Opens a PostgreSQL connection with pgjdbc for a session's pool.
 *
 * The driver is an instance rather than a DriverManager registration, for the reason MySqlConnector
 * gives: the registry is filled from a jar's service declaration, which Android does not reliably
 * read. Whatever comes back is in auto-commit mode (a manual transaction is the session manager's
 * to start) and as read-only as the server can be made to hold it.
 */
class PostgresConnector(private val config: JdbcConfig) : EngineConnector {

    override fun open(): Connection {
        val url = urlFor(config)
        return (driver.connect(url, propertiesFor(config))
            ?: throw SQLException("the driver did not accept $url")).apply {
            isReadOnly = config.readOnly
            autoCommit = true
        }
    }

    companion object {
        // See the class comment: named, not discovered.
        private val driver: Driver by lazy { org.postgresql.Driver() }

        /** `jdbc:postgresql://host:port/database`, with an IPv6 literal bracketed. */
        fun urlFor(config: JdbcConfig): String {
            val host = if (config.host.contains(':') && !config.host.startsWith("[")) "[${config.host}]" else config.host
            // The path is decoded by the driver, so a database called "a b" or "a/b" has to be
            // percent-encoded; URLEncoder writes a space as '+', which a path does not read as one.
            val database = URLEncoder.encode(config.database, "UTF-8").replace("+", "%20")
            return "jdbc:postgresql://$host:${config.port}/$database"
        }

        /** pgjdbc takes its timeouts in seconds, rounded up so 500 ms does not become "none". */
        private fun seconds(millis: Int): String = ((millis + 999) / 1000).coerceAtLeast(0).toString()

        fun propertiesFor(config: JdbcConfig): Properties = Properties().apply {
            setProperty("user", config.user)
            config.password?.let { setProperty("password", it) }
            setProperty("ApplicationName", "SQLPulse")
            setProperty("connectTimeout", seconds(config.connectTimeoutMs))
            setProperty("loginTimeout", seconds(config.connectTimeoutMs))
            setProperty("socketTimeout", seconds(config.socketTimeoutMs))
            setProperty("tcpKeepAlive", "true")
            // The app binds every value with setString and lets the user's column decide what it
            // means (RowSqlBuilder, QueryParameters). pgjdbc would otherwise send those as
            // varchar and the server refuses `integer_column = $1` or `SET created = $1`.
            // "unspecified" sends them untyped, so the server infers the type from the column —
            // which is exactly what a literal in the SQL editor would have done.
            setProperty("stringtype", "unspecified")
            // No server-side prepared statements: the app moves between schemas, and a cached
            // plan that was built for another search_path ("cached plan must not change result
            // type") would turn a harmless switch into an error. One round trip is the price of
            // never meeting that.
            setProperty("prepareThreshold", "0")
            // Auto-commit reads are buffered whole by the driver; the cap turns a runaway
            // `SELECT *` into a clear error instead of an out-of-memory crash on a phone. An
            // absolute number: the percentage form asks java.lang.management, which Android lacks.
            setProperty("maxResultBuffer", MAX_RESULT_BYTES)
            // GSSAPI (Kerberos) encryption and authentication need org.ietf.jgss, which Android
            // does not have; asking the driver not to try keeps it from ever touching those classes.
            setProperty("gssEncMode", "disable")
            // `setReadOnly(true)` alone only takes effect inside a transaction, and these
            // connections run in auto-commit. "always" makes the driver put the whole session in
            // read-only mode, so the server refuses a write whatever the app's own guards said.
            if (config.readOnly) setProperty("readOnlyMode", "always")
            sslProperties(config.sslMode, config.caCertificatePath)
                .forEach { (key, value) -> setProperty(key, value) }
        }

        /**
         * The connection's TLS properties. `require` encrypts without checking the certificate
         * (the same promise as [SslMode.REQUIRED] makes for MySQL); the verifying modes check it
         * against the CA file the user imported, since a database server's certificate is usually
         * signed by an internal CA no public store knows.
         */
        fun sslProperties(mode: SslMode, caCertificatePath: String?): Map<String, String> = when (mode) {
            SslMode.DISABLED -> mapOf("sslmode" to "disable")
            SslMode.REQUIRED -> mapOf("sslmode" to "require")
            SslMode.VERIFY_CA -> verifying("verify-ca", caCertificatePath)
            SslMode.VERIFY_IDENTITY -> verifying("verify-full", caCertificatePath)
        }

        private fun verifying(driverMode: String, caCertificatePath: String?): Map<String, String> {
            require(!caCertificatePath.isNullOrBlank()) { "$driverMode needs a CA certificate" }
            return mapOf("sslmode" to driverMode, "sslrootcert" to caCertificatePath)
        }

        private const val MAX_RESULT_BYTES = "67108864"
    }
}
