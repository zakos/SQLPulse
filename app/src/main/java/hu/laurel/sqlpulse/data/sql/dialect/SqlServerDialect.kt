package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.SqlFailure
import hu.laurel.sqlpulse.data.sql.SqlFailureKind
import hu.laurel.sqlpulse.data.sql.SqlFailures
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.WritePreviewQuery
import java.sql.Connection
import java.sql.SQLException

/**
 * Microsoft SQL Server and Azure SQL Database, over Microsoft's mssql-jdbc (jre8 build).
 *
 * Scope: SQL authentication only. Azure AD / Entra sign-in, Kerberos and Always Encrypted need
 * driver libraries that are not in the app (docs/tobb-motor-terv.md, 1.1) and are not offered.
 *
 * **Read-only is the app's job here.** mssql-jdbc ignores `Connection.setReadOnly` (measured:
 * `isReadOnly()` stays false), so a read-only connection is protected by [classify] — which
 * is stricter than MySQL's, see [TSql] — by the write gate, and by whatever the server's grants
 * say. The connection editor says so; an SQL Server connection that must not write should log in
 * as a user that has no write permission.
 *
 * The database model: the connection names one *database* (`databaseName`), the picker lists that
 * database's *schemas*, and every statement the app builds names its table by schema. Switching
 * to another database is another connection (T-SQL `USE` moves one pooled connection and not the
 * others), so a `USE` typed in the editor is refused like any statement that is not a query.
 */
object SqlServerDialect : SqlDialect {

    override val engine = DatabaseEngine.SQLSERVER
    override val grammar = TSql.GRAMMAR
    override val connectable = true

    /**
     * What is built and tested against a real server. Missing on purpose: EXPLAIN (SHOWPLAN needs
     * `SET SHOWPLAN_XML ON` on the same connection), TABLE_DDL (no `SHOW CREATE TABLE`; views
     * and routines do show their definition), DATABASE_SEARCH and SCHEMA_DIFF (written against
     * MySQL's catalog), and the server screens (they read MySQL status variables).
     */
    override val features: Set<EngineFeature> = setOf(
        EngineFeature.ROW_EDITING,
        EngineFeature.EDITABLE_RESULTS,
        EngineFeature.WRITE_PREVIEW,
        EngineFeature.CSV_IMPORT,
        EngineFeature.ROW_LINKS,
        EngineFeature.SCHEMA_MAP,
        EngineFeature.ROUTINES,
        EngineFeature.TRIGGERS,
    )

    // ---------------------------------------------------------------- names and text

    override fun quoteIdentifier(name: String): String = TSql.bracket(name)

    override fun stringLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    /**
     * `=` is false for NULL = NULL; INTERSECT treats two NULLs as equal, which is the NULL-safe
     * comparison T-SQL has no operator for. Exactly one `?`.
     */
    override fun nullSafeEquals(quotedColumn: String): String = "EXISTS (SELECT $quotedColumn INTERSECT SELECT ?)"

    /** T-SQL has no default LIKE escape character; the bound pattern escapes with a backslash. */
    override val likeEscape: String = " ESCAPE '\\'"

    /**
     * OFFSET … FETCH is part of ORDER BY in T-SQL, so an unordered select gets the no-op
     * `ORDER BY (SELECT NULL)` — the order is then whatever the server picks, as it would be
     * without paging. An offset of 0 is spelled out: FETCH is not allowed without OFFSET.
     */
    override fun limit(select: String, limit: Int, offset: Int?, ordered: Boolean): String {
        val order = if (ordered) "" else " ORDER BY (SELECT NULL)"
        return "$select$order OFFSET ${offset ?: 0} ROWS FETCH NEXT $limit ROWS ONLY"
    }

    override fun blobLengthAndHead(quotedColumn: String, maxBytes: Int): String =
        "DATALENGTH($quotedColumn), SUBSTRING($quotedColumn, 1, $maxBytes)"

    // ---------------------------------------------------------------- statements

    override fun classify(sql: String): StatementKind = TSql.classify(sql)

    override fun applyDefaultLimit(sql: String, limit: Int): SqlGuards.LimitResult =
        TSql.applyDefaultLimit(sql, limit)

    override fun isUnguardedWrite(sql: String): Boolean = TSql.isUnguardedWrite(sql)

    /** A database is a different connection here (see the class comment): nothing to intercept. */
    override fun namespaceSwitch(sql: String): String? = null

    override fun writeCountQuery(sql: String): String? = TSql.writeCountQuery(sql)

    override fun writePreviewQuery(sql: String, limit: Int): WritePreviewQuery? =
        TSql.writePreviewQuery(sql, limit)

    override fun resultEditability(sql: String): ResultEditability = TSql.resultEditability(sql)

    // ---------------------------------------------------------------- session

    override fun connector(config: JdbcConfig): EngineConnector = SqlServerConnector(config)

    /** The app always qualifies names with their schema; there is no per-connection schema to set. */
    override fun useNamespace(connection: Connection, namespace: String) = Unit

    override fun currentNamespace(connection: Connection): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT SCHEMA_NAME()").use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    /** The connection's saved "database" is what it connects to; the namespace is a schema in it. */
    override fun initialNamespace(connection: Connection, configured: String): String? =
        currentNamespace(connection) ?: "dbo"

    // ---------------------------------------------------------------- schema

    override val catalog: SchemaCatalog = SqlServerCatalog

    override val systemNamespaces: Set<String> = SqlServerCatalog.SYSTEM_SCHEMAS

    // ---------------------------------------------------------------- errors

    override fun failureOf(error: SQLException): SqlFailure = SqlServerFailures.of(error)
}

/**
 * SQL Server error numbers, which mssql-jdbc reports as [SQLException.getErrorCode]. MySQL's
 * table in SqlFailures must not be consulted for them: the two number spaces overlap (1205 means
 * "deadlock" in both, 1045 means a MySQL login failure and nothing in SQL Server).
 */
object SqlServerFailures {

    fun of(e: SQLException): SqlFailure {
        val message = SqlFailures.fullMessage(e)
        return SqlFailure(
            kind = classify(e.errorCode, e.sqlState, message),
            errorCode = e.errorCode,
            sqlState = e.sqlState,
            serverMessage = message,
        )
    }

    fun classify(errorCode: Int, sqlState: String?, message: String): SqlFailureKind {
        byNumber(errorCode)?.let { return it }
        val text = message.lowercase()
        return when {
            // A failed handshake carries SQLState 08S01, which the shared table would call a lost
            // connection; the wording is what says it was the certificate or the encryption.
            text.containsAny("secure sockets layer", "ssl", "tls", "pkix", "certificate") ->
                SqlFailureKind.TLS

            // The driver's wording for a socket that never opened. Its SQLState is 08S01, which
            // the shared table would call a lost connection.
            text.contains("tcp/ip connection to the host") || text.contains("unknown host") ||
                text.contains("connection refused") || text.contains("no route to host") ->
                SqlFailureKind.UNREACHABLE

            text.contains("login failed for user") -> SqlFailureKind.AUTHENTICATION

            text.contains("the query has timed out") -> SqlFailureKind.TIMEOUT

            // Errors that carry no SQL Server number at all: classify as the shared reader does.
            else -> SqlFailures.classify(0, sqlState, message)
        }
    }

    private fun String.containsAny(vararg needles: String) = needles.any { contains(it) }

    private fun byNumber(code: Int): SqlFailureKind? = when (code) {
        // 18456: login failed. 18452/18451: login from an untrusted domain / login refused.
        18456, 18451, 18452, 18470, 18486, 18487, 18488 -> SqlFailureKind.AUTHENTICATION
        // 229/230/262/297/300: permission denied on object/column/database. 916: the login may
        // not use the database; 15247: no permission to execute the statement.
        229, 230, 262, 297, 300, 916, 15247 -> SqlFailureKind.PRIVILEGE
        4060, 911 -> SqlFailureKind.UNKNOWN_DATABASE
        // 208 invalid object, 207 invalid column, 4104 identifier could not be bound, 2812 no such procedure.
        208, 207, 4104, 2812, 4121 -> SqlFailureKind.UNKNOWN_OBJECT
        102, 103, 105, 156, 170, 319 -> SqlFailureKind.SYNTAX
        1205, 1222 -> SqlFailureKind.LOCK
        2627, 2601 -> SqlFailureKind.DUPLICATE_KEY
        // 3906 read-only database; 40501/10928/10929 Azure throttling; 40613/40197/40540 database
        // unavailable or being moved.
        3906, 40501, 10928, 10929, 40613, 40197, 40540, 40544, 40549, 40550, 40552, 40553 ->
            SqlFailureKind.SERVER_BUSY_OR_READ_ONLY
        else -> null
    }
}
