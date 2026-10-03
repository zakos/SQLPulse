package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.NotEditableReason
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.SqlFailure
import hu.laurel.sqlpulse.data.sql.SqlFailureKind
import hu.laurel.sqlpulse.data.sql.SqlFailures
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.WriteImpact
import hu.laurel.sqlpulse.data.sql.WritePreviewQuery
import java.sql.Connection
import java.sql.SQLException

/**
 * A SQLite database file on the phone (docs/tobb-motor-terv.md, "SQLite ügynök").
 *
 * Driver: xerial sqlite-jdbc, instantiated as `org.sqlite.JDBC()` (see [SqliteConnector]); the JVM
 * unit tests use the same driver, so the integration tests under `integration/sqlite/` run in the
 * ordinary check with no server at all.
 *
 * The connection is a file, not a server: TunnelManager never builds a tunnel or probes a port for
 * it, and JdbcConfig.localFile carries the path of the app's private copy
 * (LocalDatabaseFiles). The file is opened read-only unless the connection's read-only switch is
 * off, and then it is the copy that changes, never the file the user picked.
 *
 * What works and what does not, by design:
 *  - Row editing needs a primary key. A table without one has an implicit `rowid`, but the grid's
 *    `SELECT *` does not carry it, so such a table is shown read-only ("no primary key") rather
 *    than edited by a key the user cannot see. Giving it a rowid fallback is a change to the shared
 *    table query and the table screen, left for later.
 *  - EXPLAIN stays off: `EXPLAIN QUERY PLAN` returns a flat list of `detail` strings, not the
 *    JSON the plan tree reads. [explain] still names the statement so a later tree can use it, and
 *    the user can type it into the editor today and read the rows.
 *  - Editable query results, database search and schema comparison are not enabled; they read
 *    MySQL's catalog or analysis directly (docs/tobb-motor-terv.md §7).
 *  - A statement cannot be time-limited, only cancelled: sqlite-jdbc maps the JDBC query timeout
 *    to the lock wait, and `Statement.cancel()` interrupts the running statement.
 */
object SqliteDialect : SqlDialect {

    override val engine = DatabaseEngine.SQLITE

    /**
     * SQLite accepts `"x"`, `` `x` `` (MySQL compatibility) and `[x]` (T-SQL compatibility) for
     * names, and has no backslash escape in strings. A statement pasted from either neighbour
     * still has to be read right, or a keyword inside a quoted name is taken for the statement.
     */
    override val grammar = SqlGrammar(
        quotes = mapOf('\'' to '\'', '"' to '"', '`' to '`', '[' to ']'),
        backslashEscapes = false,
        readStarters = setOf("select", "with", "values", "explain"),
        writeStarters = setOf("insert", "update", "delete", "replace"),
    )

    override val connectable = true

    override val features: Set<EngineFeature> = setOf(
        EngineFeature.ROW_EDITING,
        EngineFeature.CSV_IMPORT,
        EngineFeature.TABLE_DDL,
        EngineFeature.ROW_LINKS,
        EngineFeature.SCHEMA_MAP,
        EngineFeature.TRIGGERS,
        EngineFeature.WRITE_PREVIEW,
    )

    override fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    override fun stringLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    /** `IS` compares NULL-safely, and is the only operator that does so in SQLite. */
    override fun nullSafeEquals(quotedColumn: String): String = "$quotedColumn IS ?"

    /** SQLite's LIKE has no default escape character; the backslash has to be declared. */
    override val likeEscape: String = " ESCAPE '\\'"

    override fun limit(select: String, limit: Int, offset: Int?, ordered: Boolean): String =
        if (offset == null) "$select LIMIT $limit" else "$select LIMIT $limit OFFSET $offset"

    /** The length of a BLOB is its byte count; `substr` of a BLOB is a BLOB. */
    override fun blobLengthAndHead(quotedColumn: String, maxBytes: Int): String =
        "length($quotedColumn), substr($quotedColumn, 1, $maxBytes)"

    // ---------------------------------------------------------------- statements

    override fun classify(sql: String): StatementKind {
        val stripped = SqlGuards.strip(sql, grammar).trim()
        val first = stripped.takeWhile { it.isLetter() || it == '_' }.lowercase()
        if (first == "pragma") return if (readsOnly(stripped)) StatementKind.READ else StatementKind.OTHER
        return SqlGuards.classify(sql, grammar)
    }

    /**
     * A PRAGMA is a read only when it asks and does not set. `PRAGMA user_version = 5` and
     * `PRAGMA user_version(5)` both write the file, `PRAGMA writable_schema` unlocks the schema
     * table, and `PRAGMA journal_mode` with an argument rewrites the file's mode. So a PRAGMA is
     * accepted only from a short list of query pragmas, without an assignment and, unless the
     * pragma takes a table or index name, without an argument. Everything else is refused as
     * "not supported", as DDL is (§2).
     */
    private fun readsOnly(strippedPragma: String): Boolean {
        if (strippedPragma.contains('=')) return false
        val body = strippedPragma.substring("pragma".length).trim().trimEnd(';').trim()
        val name = body.substringBefore('(').trim().substringAfterLast('.').trim().lowercase()
        val hasArgument = body.contains('(')
        return when (name) {
            in QUERY_PRAGMAS_WITH_ARGUMENT -> true
            in QUERY_PRAGMAS -> !hasArgument
            else -> false
        }
    }

    override fun applyDefaultLimit(sql: String, limit: Int): SqlGuards.LimitResult =
        SqlGuards.applyDefaultLimit(sql, limit, grammar)

    override fun isUnguardedWrite(sql: String): Boolean = SqlGuards.isUnguardedWrite(sql, grammar)

    /** SQLite has one namespace per connection; `USE` is not a statement here. */
    override fun namespaceSwitch(sql: String): String? = null

    /** The plan as rows of text; the plan tree reads MySQL's JSON and is not enabled for SQLite. */
    override fun explain(sql: String): String = "EXPLAIN QUERY PLAN $sql"

    // ---------------------------------------------------------------- writes

    /**
     * MySQL's analysis understands `UPDATE t SET … WHERE …` and `DELETE FROM t WHERE …`, which
     * is also the plain SQLite statement. SQLite adds forms it does not model, and for those the
     * count would be wrong rather than merely absent, so they are refused here: `UPDATE OR
     * REPLACE` (also deletes the rows it collides with), `UPDATE … FROM` (a join), `RETURNING`
     * and `INDEXED BY`.
     */
    override fun writeCountQuery(sql: String): String? =
        sql.takeIf(::countable)?.let(WriteImpact::countQuery)

    override fun writePreviewQuery(sql: String, limit: Int): WritePreviewQuery? =
        sql.takeIf(::countable)?.let { WriteImpact.previewQuery(it, limit) }

    private fun countable(sql: String): Boolean {
        val words = topLevelWords(SqlGuards.strip(sql, grammar))
        if ("returning" in words || "indexed" in words) return false
        if (words.firstOrNull() == "update" && (words.getOrNull(1) == "or" || "from" in words)) return false
        return true
    }

    /** The words that stand outside every parenthesis, lowercased. */
    private fun topLevelWords(stripped: String): List<String> {
        val words = mutableListOf<String>()
        var depth = 0
        var index = 0
        while (index < stripped.length) {
            val c = stripped[index]
            when {
                c == '(' -> { depth++; index++ }
                c == ')' -> { depth--; index++ }
                c.isLetter() || c == '_' -> {
                    val end = (index until stripped.length).firstOrNull {
                        !(stripped[it].isLetterOrDigit() || stripped[it] == '_')
                    } ?: stripped.length
                    if (depth <= 0) words += stripped.substring(index, end).lowercase()
                    index = end
                }

                else -> index++
            }
        }
        return words
    }

    /** Not enabled: the analysis behind editable results reads MySQL's text and column rules. */
    override fun resultEditability(sql: String): ResultEditability =
        ResultEditability.NotEditable(NotEditableReason.UNSUPPORTED)

    // ---------------------------------------------------------------- session

    override fun connector(config: JdbcConfig): EngineConnector = SqliteConnector(config)

    /** A file has one namespace, so there is nothing to switch. */
    override fun useNamespace(connection: Connection, namespace: String) = Unit

    override fun currentNamespace(connection: Connection): String = SqliteCatalog.MAIN

    override fun initialNamespace(connection: Connection, configured: String): String = SqliteCatalog.MAIN

    /** Never called: there is no server to ask. */
    override fun probe(host: String, port: Int, timeoutMs: Int): String? = null

    // ---------------------------------------------------------------- schema

    override val catalog: SchemaCatalog = SqliteCatalog

    override val systemNamespaces: Set<String> = setOf("temp")

    // ---------------------------------------------------------------- errors

    /**
     * SQLite's result codes are the stable signal, as MySQL's error numbers are for MySQL; the
     * driver reports the extended code where there is one (`SQLITE_CONSTRAINT_UNIQUE` is 2067),
     * whose low byte is the primary code. The message decides only what a code leaves open:
     * SQLITE_ERROR covers both a missing table and a syntax error.
     */
    override fun failureOf(error: SQLException): SqlFailure {
        val base = SqlFailures.of(error)
        val message = base.serverMessage
        val code = error.errorCode
        val primary = code and 0xFF
        val kind = when {
            primary == SQLITE_BUSY || primary == SQLITE_LOCKED -> SqlFailureKind.LOCK
            primary == SQLITE_READONLY -> SqlFailureKind.SERVER_BUSY_OR_READ_ONLY
            primary == SQLITE_INTERRUPT -> SqlFailureKind.TIMEOUT
            primary == SQLITE_PERM || primary == SQLITE_AUTH -> SqlFailureKind.PRIVILEGE
            primary == SQLITE_CANTOPEN -> SqlFailureKind.UNKNOWN_DATABASE
            primary == SQLITE_CONSTRAINT ->
                if (code == SQLITE_CONSTRAINT_UNIQUE || code == SQLITE_CONSTRAINT_PRIMARYKEY ||
                    message.contains("UNIQUE constraint failed", ignoreCase = true)
                ) {
                    SqlFailureKind.DUPLICATE_KEY
                } else {
                    SqlFailureKind.OTHER
                }

            primary == SQLITE_ERROR -> when {
                NO_SUCH.containsMatchIn(message) -> SqlFailureKind.UNKNOWN_OBJECT
                SYNTAX.containsMatchIn(message) -> SqlFailureKind.SYNTAX
                else -> SqlFailureKind.OTHER
            }

            else -> SqlFailureKind.OTHER
        }
        return base.copy(kind = kind)
    }

    private const val SQLITE_ERROR = 1
    private const val SQLITE_PERM = 3
    private const val SQLITE_BUSY = 5
    private const val SQLITE_LOCKED = 6
    private const val SQLITE_READONLY = 8
    private const val SQLITE_INTERRUPT = 9
    private const val SQLITE_CANTOPEN = 14
    private const val SQLITE_AUTH = 23
    private const val SQLITE_CONSTRAINT = 19
    private const val SQLITE_CONSTRAINT_PRIMARYKEY = 1555
    private const val SQLITE_CONSTRAINT_UNIQUE = 2067

    private val NO_SUCH = Regex("(?i)\\bno such (table|column|index|view|function|collation sequence|trigger)\\b")
    private val SYNTAX = Regex("(?i)\\bsyntax error\\b|\\bincomplete input\\b|\\bunrecognized token\\b")

    /** Pragmas that only report; `PRAGMA name(arg)` for these names a table or index, not a value. */
    private val QUERY_PRAGMAS_WITH_ARGUMENT = setOf(
        "table_info", "table_xinfo", "index_list", "index_info", "index_xinfo",
        "foreign_key_list", "foreign_key_check", "integrity_check", "quick_check",
    )

    /** Pragmas that report when asked bare and change something when given a value. */
    private val QUERY_PRAGMAS = setOf(
        "database_list", "collation_list", "compile_options", "function_list", "module_list",
        "pragma_list", "table_list", "foreign_keys", "user_version", "schema_version", "page_count",
        "page_size", "freelist_count", "encoding", "journal_mode", "application_id", "data_version",
    )
}
