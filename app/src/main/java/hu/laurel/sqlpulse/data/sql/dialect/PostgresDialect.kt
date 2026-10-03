package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.ResultEditabilities
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
import java.util.Collections
import java.util.WeakHashMap

/**
 * PostgreSQL (and the servers that speak it: Aurora, Cloud SQL, Supabase, CockroachDB mostly).
 *
 * Driver: pgjdbc, opened by [PostgresConnector]; the schema is read by [PostgresCatalog]. Decisions
 * worth knowing:
 *
 *  - **The namespace is a schema.** The connection's "database" is the database the server hands
 *    the session to; the picker lists its schemas and a switch is `SET search_path` (see
 *    [useNamespace]). Another database is another connection (docs/tobb-motor-terv.md, 3.1).
 *  - **No LIMIT on UPDATE/DELETE.** PostgreSQL has none, so the row editor's statements are
 *    key-based (RowSqlBuilder never adds one) and the one-row check stays in the editor.
 *  - **EXPLAIN ANALYZE executes the statement.** [classify] therefore counts `EXPLAIN ANALYZE
 *    DELETE …` as a write, which is what keeps it behind the read-only flag and the write gate.
 *  - **Not offered yet:** the plan tree (the JSON plan has a different shape from MySQL's, and
 *    ExplainJson only reads MySQL's), search across the database, schema comparison and the
 *    storage screen — none of them is in [features], so the UI never gets there.
 */
object PostgresDialect : SqlDialect {

    override val engine = DatabaseEngine.POSTGRESQL

    /**
     * Standard quoting plus `$$ … $$` strings. `show` and `table` are statements that only read;
     * there is no `#` comment here (`#` is an operator) and a backslash is an ordinary character.
     */
    override val grammar: SqlGrammar = SqlGrammar.ANSI.copy(
        dollarQuotes = true,
        readStarters = SqlGrammar.ANSI.readStarters + setOf("show", "table"),
    )

    override val connectable = true

    override val features = setOf(
        EngineFeature.ROW_EDITING,
        EngineFeature.EDITABLE_RESULTS,
        EngineFeature.WRITE_PREVIEW,
        EngineFeature.CSV_IMPORT,
        EngineFeature.ROW_LINKS,
        EngineFeature.SCHEMA_MAP,
        EngineFeature.TABLE_DDL,
        EngineFeature.ROUTINES,
        EngineFeature.TRIGGERS,
        // The Server and Pulse screens, through [PostgresServerCatalog].
        EngineFeature.SERVER_ACTIVITY,
        EngineFeature.REPLICATION,
        EngineFeature.SLOW_QUERIES,
        EngineFeature.PULSE,
    )

    override val server: ServerCatalog = PostgresServerCatalog

    // ---------------------------------------------------------------- SqlSyntax

    override fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /** With `standard_conforming_strings` (the default since 9.1) only the quote needs doubling. */
    override fun stringLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    override fun nullSafeEquals(quotedColumn: String): String = "$quotedColumn IS NOT DISTINCT FROM ?"

    /** Backslash is LIKE's default escape in PostgreSQL. */
    override val likeEscape: String = ""

    override fun likeOperand(quotedColumn: String): String = "CAST($quotedColumn AS text)"

    override fun limit(select: String, limit: Int, offset: Int?, ordered: Boolean): String =
        if (offset == null) "$select LIMIT $limit" else "$select LIMIT $limit OFFSET $offset"

    override fun blobLengthAndHead(quotedColumn: String, maxBytes: Int): String =
        "octet_length($quotedColumn), substring($quotedColumn from 1 for $maxBytes)"

    // ---------------------------------------------------------------- statements

    override fun classify(sql: String): StatementKind = classifyStripped(SqlGuards.strip(sql, grammar).trim())

    /** [classify] on text whose comments and literals are already gone. */
    private fun classifyStripped(stripped: String): StatementKind {
        explainedStatement(stripped)?.let { (analyzes, inner) ->
            // Plain EXPLAIN only plans. With ANALYZE the statement really runs, so an explained
            // write is a write.
            if (analyzes && classifyStripped(inner.trim()) == StatementKind.WRITE) return StatementKind.WRITE
        }
        val kind = SqlGuards.classify(stripped, grammar)
        if (kind != StatementKind.READ) return kind
        // `SELECT … INTO new_table` creates a table: DDL in a SELECT's clothes (§2).
        if (SELECT_INTO.containsMatchIn(stripped)) return StatementKind.OTHER
        // PostgreSQL lets a WITH hold INSERT/UPDATE/DELETE and then select from them, so a
        // `WITH … SELECT` is only a read when none of its bodies writes.
        if (stripped.startsWith("with", ignoreCase = true) && dataModifyingBodies(stripped).isNotEmpty()) {
            return StatementKind.WRITE
        }
        return kind
    }

    /**
     * Adds `LIMIT n` to an unlimited SELECT. Anything that already limits itself — LIMIT in any
     * form (`ALL` included) or the standard `FETCH FIRST n ROWS` — is left alone, because a second
     * limit is a syntax error. A statement whose last line ends in a `--` comment gets the LIMIT on
     * a line of its own, or it would be swallowed by the comment.
     */
    override fun applyDefaultLimit(sql: String, limit: Int): SqlGuards.LimitResult {
        val trimmed = sql.trim().trimEnd(';')
        if (EXISTING_LIMIT.containsMatchIn(SqlGuards.strip(trimmed, grammar))) return SqlGuards.LimitResult(trimmed, false)
        val limited = SqlGuards.applyDefaultLimit(sql, limit, grammar)
        if (limited.limitAdded && trimmed.substringAfterLast('\n').contains("--")) {
            return SqlGuards.LimitResult("$trimmed\nLIMIT $limit", true)
        }
        return limited
    }

    override fun isUnguardedWrite(sql: String): Boolean = unguarded(SqlGuards.strip(sql, grammar).trim())

    private fun unguarded(stripped: String): Boolean {
        explainedStatement(stripped)?.let { (analyzes, inner) -> return analyzes && unguarded(inner.trim()) }
        if (SqlGuards.isUnguardedWrite(stripped, grammar)) return true
        // A DELETE or UPDATE inside a CTE body is guarded by its own WHERE, not by the SELECT after it.
        return stripped.startsWith("with", ignoreCase = true) &&
            dataModifyingBodies(stripped).any { (keyword, text) ->
                (keyword == "update" || keyword == "delete") && !WHERE.containsMatchIn(text)
            }
    }

    /**
     * The INSERT/UPDATE/DELETE/MERGE statements written inside a WITH of [stripped], each as its
     * keyword and the text up to the parenthesis that closes it. A `FOR UPDATE` or
     * `FOR NO KEY UPDATE` lock clause is not one.
     */
    private fun dataModifyingBodies(stripped: String): List<Pair<String, String>> =
        DATA_MODIFYING.findAll(stripped).mapNotNull { match ->
            val keyword = match.value.lowercase()
            val before = stripped.substring(0, match.range.first).trimEnd()
                .takeLastWhile { !it.isWhitespace() }.lowercase()
            if (keyword == "update" && (before == "for" || before == "key")) return@mapNotNull null
            var depth = 0
            var end = stripped.length
            for (index in match.range.last until stripped.length) {
                val c = stripped[index]
                if (c == '(') depth++
                if (c == ')' && --depth < 0) {
                    end = index
                    break
                }
            }
            keyword to stripped.substring(match.range.first, end)
        }.toList()

    /**
     * `SET search_path TO s`, `SET SESSION search_path = "s"` and `SET SCHEMA 's'` with one name.
     * Anything else (a list of schemas, `DEFAULT`, `SET LOCAL`) is sent as it is.
     */
    override fun namespaceSwitch(sql: String): String? {
        val match = SEARCH_PATH.find(sql.trim().trimEnd(';').trim()) ?: return null
        val raw = match.groupValues[1]
        if (raw.equals("default", ignoreCase = true)) return null
        val name = when (raw.first()) {
            '"' -> raw.substring(1, raw.length - 1).replace("\"\"", "\"")
            '\'' -> raw.substring(1, raw.length - 1).replace("''", "'")
            // A bare name is folded to lower case, exactly as the server would.
            else -> raw.lowercase()
        }
        return name.takeIf { it.isNotEmpty() }
    }

    /**
     * The placeholders that [bindParameters] actually binds. The shared regex would also read the
     * cast in `a::text` as a parameter called `text`.
     */
    override fun parameters(sql: String): List<String> = bindParameters(sql).parameterOrder.distinct()

    override fun explain(sql: String): String = "EXPLAIN (FORMAT JSON) $sql"

    // ---------------------------------------------------------------- writes

    override fun writeCountQuery(sql: String): String? = WriteImpact.countQuery(sql, this)

    override fun writePreviewQuery(sql: String, limit: Int): WritePreviewQuery? =
        WriteImpact.previewQuery(sql, limit, this)

    override fun resultEditability(sql: String): ResultEditability = ResultEditabilities.analyse(sql, this)

    // ---------------------------------------------------------------- session

    override fun connector(config: JdbcConfig): EngineConnector = PostgresConnector(config)

    /**
     * The connections whose search_path is already [useNamespace]'s last choice. Every borrowed
     * connection passes through [useNamespace], and a SET is a round trip through the tunnel, so
     * it is skipped when it would change nothing. Only auto-commit connections are remembered: a
     * SET inside a transaction is undone by its rollback.
     */
    private val applied: MutableMap<Connection, String> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Points the connection's search_path at [namespace], with `public` behind it so the
     * extension objects that live there (citext, PostGIS, pg_trgm operators) stay reachable from
     * any schema. `setSchema` would replace the whole path.
     */
    override fun useNamespace(connection: Connection, namespace: String) {
        val inTransaction = !connection.autoCommit
        if (!inTransaction && applied[connection] == namespace) return
        val path = if (namespace == "public") quoteIdentifier(namespace) else "${quoteIdentifier(namespace)}, public"
        connection.createStatement().use { it.execute("SET search_path TO $path") }
        if (inTransaction) applied.remove(connection) else applied[connection] = namespace
    }

    /**
     * `current_schema()` (asked in SQL: Android's JDBC has no `getSchema`): the first schema of the
     * search path that exists, null if none does.
     */
    override fun currentNamespace(connection: Connection): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_schema()").use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    /**
     * The connection's "database" is the database the server hands the session to, not a schema:
     * the session starts in whichever schema the search_path puts first (`public` normally).
     */
    override fun initialNamespace(connection: Connection, configured: String): String? = currentNamespace(connection)

    override val catalog: SchemaCatalog = PostgresCatalog

    override val systemNamespaces: Set<String> = PostgresCatalog.SYSTEM_SCHEMAS

    // ---------------------------------------------------------------- errors

    /**
     * Classified by SQLSTATE, which PostgreSQL sets on every error (the driver's error code is
     * always 0). A connection that never got made says why only in the exception text and its
     * cause, so the 08 class is read from the message: TLS, a refused connection and a timeout
     * need different advice.
     */
    override fun failureOf(error: SQLException): SqlFailure {
        val message = SqlFailures.fullMessage(error)
        val state = error.sqlState
        val kind = when {
            state == null || state.startsWith("08") -> connectionFailure(state, message)
            else -> byState(state) ?: SqlFailures.classify(0, state, message)
        }
        return SqlFailure(kind = kind, errorCode = error.errorCode, sqlState = state, serverMessage = message)
    }

    private fun connectionFailure(state: String?, message: String): SqlFailureKind {
        val guess = SqlFailures.classify(0, null, message)
        return when {
            guess == SqlFailureKind.TLS || guess == SqlFailureKind.TIMEOUT ||
                guess == SqlFailureKind.UNREACHABLE || guess == SqlFailureKind.CONNECTION_LOST -> guess
            state == "08001" || state == "08004" -> SqlFailureKind.UNREACHABLE
            state == null -> guess
            else -> SqlFailureKind.CONNECTION_LOST
        }
    }

    private fun byState(state: String): SqlFailureKind? = when (state) {
        "28P01", "28000" -> SqlFailureKind.AUTHENTICATION
        "42501" -> SqlFailureKind.PRIVILEGE
        // 3D000: no such database; 3F000: no such schema — both mean "the place you named".
        "3D000", "3F000" -> SqlFailureKind.UNKNOWN_DATABASE
        "42P01", "42703", "42883", "42704", "42P02" -> SqlFailureKind.UNKNOWN_OBJECT
        "42601", "42000", "42602", "42611" -> SqlFailureKind.SYNTAX
        "40P01", "40001", "55P03" -> SqlFailureKind.LOCK
        "23505" -> SqlFailureKind.DUPLICATE_KEY
        // 57014 is a cancelled statement: the user's own cancel or statement_timeout.
        "57014" -> SqlFailureKind.TIMEOUT
        "25006", "53300", "53000", "53100", "53200", "57P03" -> SqlFailureKind.SERVER_BUSY_OR_READ_ONLY
        "57P01", "57P02" -> SqlFailureKind.CONNECTION_LOST
        else -> null
    }

    // ---------------------------------------------------------------- helpers

    /**
     * For an `EXPLAIN` statement (already stripped of comments and literals): whether it analyzes,
     * and the statement it explains. Null for anything that is not an EXPLAIN.
     */
    private fun explainedStatement(stripped: String): Pair<Boolean, String>? {
        val match = EXPLAIN_PREFIX.find(stripped) ?: return null
        val options = match.value.lowercase()
        return Regex("\\banaly[sz]e\\b").containsMatchIn(options) to stripped.substring(match.range.last + 1)
    }

    /** `EXPLAIN`, then any mix of `(option, …)` and the bare ANALYZE / VERBOSE words. */
    private val EXPLAIN_PREFIX = Regex(
        "(?is)^explain\\b(\\s*(\\((?:[^()]|\\([^()]*\\))*\\)|\\b(?:analy[sz]e|verbose)\\b))*",
    )

    private val DATA_MODIFYING = Regex("(?i)\\b(insert|update|delete|merge)\\b")

    private val WHERE = Regex("(?i)\\bwhere\\b")

    private val SELECT_INTO = Regex("(?i)^\\s*(\\(?\\s*)*select\\b[^;]*\\binto\\b")

    private val EXISTING_LIMIT = Regex("(?i)\\blimit\\b|\\bfetch\\s+(first|next)\\b")

    private val SEARCH_PATH = Regex(
        "(?i)^set\\s+(?:session\\s+)?(?:search_path\\s*(?:=|to)\\s*|schema\\s+)" +
            "(\"(?:[^\"]|\"\")+\"|'(?:[^']|'')+'|[A-Za-z_][A-Za-z0-9_$]*)$",
    )
}
