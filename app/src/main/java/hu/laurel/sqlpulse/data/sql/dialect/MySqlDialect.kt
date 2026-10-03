package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.quoteIdentifier as backtickQuote
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.JdbcDriverKind
import hu.laurel.sqlpulse.data.sql.ResultEditabilities
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.SqlFailure
import hu.laurel.sqlpulse.data.sql.SqlFailures
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.data.sql.SslProperties
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.WriteImpact
import hu.laurel.sqlpulse.data.sql.WritePreviewQuery
import hu.laurel.sqlpulse.data.sql.plan.MySqlPlanReader
import hu.laurel.sqlpulse.data.sql.plan.PlanReader
import hu.laurel.sqlpulse.ssh.MysqlProbe
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties

/**
 * MySQL and MariaDB — the engine the app was built for.
 *
 * Nothing here is new logic: every member delegates to the code that did the job before engines
 * existed (SqlGuards, WriteImpact, ResultEditabilities, SqlFailures, MysqlProbe, MySqlCatalog),
 * or is that code moved verbatim ([MySqlConnector]). The other dialects are measured against this
 * one, and MySqlDialectTest pins that it still says exactly what those objects say.
 */
object MySqlDialect : SqlDialect {

    override val engine = DatabaseEngine.MYSQL
    override val grammar = SqlGrammar.MYSQL
    override val connectable = true
    override val features = EngineFeature.ALL

    override fun quoteIdentifier(name: String): String = backtickQuote(name)

    /** A backslash is an escape in MySQL's default SQL mode, so it is doubled along with quotes. */
    override fun stringLiteral(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"

    override fun nullSafeEquals(quotedColumn: String): String = "$quotedColumn <=> ?"

    /** Backslash is LIKE's default escape in MySQL. */
    override val likeEscape: String = ""

    override fun limit(select: String, limit: Int, offset: Int?, ordered: Boolean): String =
        if (offset == null) "$select LIMIT $limit" else "$select LIMIT $limit OFFSET $offset"

    override fun blobLengthAndHead(quotedColumn: String, maxBytes: Int): String =
        "LENGTH($quotedColumn), SUBSTRING($quotedColumn, 1, $maxBytes)"

    override fun classify(sql: String): StatementKind = SqlGuards.classify(sql)

    override fun applyDefaultLimit(sql: String, limit: Int): SqlGuards.LimitResult =
        SqlGuards.applyDefaultLimit(sql, limit)

    override fun isUnguardedWrite(sql: String): Boolean = SqlGuards.isUnguardedWrite(sql)

    override fun namespaceSwitch(sql: String): String? = SqlGuards.useTarget(sql)

    /**
     * The JSON plan, which the plan tree reads. The editor falls back to the plain form on a
     * server older than 5.6 (QueryEditorViewModel), so only the JSON form is named here.
     */
    override fun explain(sql: String): String = "$EXPLAIN_JSON$sql"

    override val planReader: PlanReader get() = MySqlPlanReader

    override fun writeCountQuery(sql: String): String? = WriteImpact.countQuery(sql)

    override fun writePreviewQuery(sql: String, limit: Int): WritePreviewQuery? =
        WriteImpact.previewQuery(sql, limit)

    override fun resultEditability(sql: String): ResultEditability = ResultEditabilities.analyse(sql)

    override fun connector(config: JdbcConfig): EngineConnector = MySqlConnector(config)

    /**
     * Sets the catalog only when it differs: every borrowed connection passes through here, and a
     * `setCatalog` is a round trip (the driver sends `USE`).
     */
    override fun useNamespace(connection: Connection, namespace: String) {
        if (connection.catalog != namespace) connection.catalog = namespace
    }

    /** MySQL speaks first, so the probe reads the server's version off its handshake packet. */
    override fun probe(host: String, port: Int, timeoutMs: Int): String =
        MysqlProbe.serverVersion(port = port, host = host, timeoutMs = timeoutMs)

    override val catalog: SchemaCatalog = MySqlCatalog

    override val systemNamespaces: Set<String> = MySqlCatalog.SYSTEM_SCHEMAS

    override fun failureOf(error: SQLException): SqlFailure = SqlFailures.of(error)

    const val EXPLAIN_JSON = "EXPLAIN FORMAT=JSON "
}

/**
 * Opens a MySQL connection with whichever driver this server speaks.
 *
 * Moved from SqlSession unchanged. The modern driver is tried first and is what nearly every
 * server gets. It builds `SET NAMES utf8mb4` into the handshake, and a server older than MySQL
 * 5.5.3 has never heard of utf8mb4 and refuses the session — that refusal, and only that one,
 * moves the session to the legacy driver for good. Anything else (a wrong password, an
 * unreachable host) is the caller's to hear about at once.
 */
class MySqlConnector(private val config: JdbcConfig) : EngineConnector {

    /** Set once the first connection succeeds, so the rest of the pool does not try both. */
    @Volatile
    override var settled: JdbcDriverKind? = null
        private set

    override fun open(): Connection {
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
            // MySQL 8 authenticates with caching_sha2_password by default. Over an unencrypted
            // link the driver will not send the password unless it can encrypt it with the
            // server's RSA public key, and it refuses to fetch that key on its own — a server
            // that handed over its own key could be an impostor collecting the password. Inside
            // an SSH tunnel that objection is already answered: the whole conversation is
            // encrypted and the host key was pinned when the tunnel was built. So the retrieval
            // is allowed there and nowhere else; a direct, unencrypted connection to such a
            // server is told to turn on TLS instead (see SqlFailures.AUTHENTICATION_UNPROTECTED).
            if (config.tunnelled && config.sslMode == SslMode.DISABLED) {
                setProperty("allowPublicKeyRetrieval", "true")
            }
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

    private companion object {
        // Instances rather than DriverManager registrations: both classes register themselves
        // through java.sql.DriverManager, and on Android that registry is not reliably populated
        // from a jar's service declaration.
        val modernDriver: Driver by lazy { org.mariadb.jdbc.Driver() }
        val legacyDriver: Driver by lazy { com.mysql.jdbc.Driver() }
    }
}
