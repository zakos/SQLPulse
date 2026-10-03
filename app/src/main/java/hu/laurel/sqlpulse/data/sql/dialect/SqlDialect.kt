package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.SqlFailure
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.WritePreviewQuery
import java.net.InetSocketAddress
import java.net.Socket
import java.sql.Connection
import java.sql.SQLException

/**
 * Building SQL text for one engine: names, literals, paging. Pure and JVM-testable.
 *
 * Split from [SqlDialect] because the statement builders (RowSqlBuilder, TableQuery, RowLinks)
 * need only this much, and their unit tests should not have to fake a whole engine.
 */
interface SqlSyntax {

    val engine: DatabaseEngine

    /** The lexical rules the shared scanners (SqlGuards) read this engine's SQL with. */
    val grammar: SqlGrammar

    /** [name] as a quoted identifier: `` `a``b` `` in MySQL, `"a""b"` in PostgreSQL, `[a]]b]` in T-SQL. */
    fun quoteIdentifier(name: String): String

    /** `namespace.name`, both quoted. */
    fun qualify(namespace: String, name: String): String =
        "${quoteIdentifier(namespace)}.${quoteIdentifier(name)}"

    /**
     * [value] as a string literal, for display only (the confirmation dialog, the write log) —
     * what runs is always a prepared statement with bound values.
     */
    fun stringLiteral(value: String): String

    /**
     * A NULL-safe equality between [quotedColumn] and exactly one `?` placeholder: the optimistic
     * check of a row edit, which must also match a value that was NULL when it was read.
     * MySQL `c <=> ?`, PostgreSQL `c IS NOT DISTINCT FROM ?`, SQLite `c IS ?`,
     * SQL Server `EXISTS (SELECT c INTERSECT SELECT ?)`.
     */
    fun nullSafeEquals(quotedColumn: String): String

    /**
     * What follows `LIKE ?` so a backslash in the bound pattern escapes `%` and `_`. Empty where
     * backslash already is LIKE's escape (MySQL, PostgreSQL); `ESCAPE '\'` elsewhere.
     */
    val likeEscape: String

    /**
     * [quotedColumn] as the left side of a `LIKE`. MySQL and SQL Server compare any type with a
     * pattern; PostgreSQL has no `LIKE` for numbers, dates, uuids or arrays and needs a cast.
     */
    fun likeOperand(quotedColumn: String): String = quotedColumn

    /**
     * [select] limited to [limit] rows, skipping [offset] when given. [ordered] says whether it
     * already has an ORDER BY, which T-SQL's OFFSET … FETCH needs and has to invent otherwise.
     */
    fun limit(select: String, limit: Int, offset: Int? = null, ordered: Boolean = false): String

    /** `LENGTH(c), SUBSTRING(c, 1, n)` — a BLOB's size and its first [maxBytes] bytes, as two columns. */
    fun blobLengthAndHead(quotedColumn: String, maxBytes: Int): String
}

/**
 * Everything the app needs to know about one engine, in one place.
 *
 * One implementation per engine, one file each (MySqlDialect, PostgresDialect, SqlServerDialect,
 * SqliteDialect), looked up by [SqlDialects.forEngine]. The engine-neutral layers ask the dialect
 * of the live session (SqlSessionManager.dialect()) instead of assuming MySQL; MySQL's dialect
 * delegates to the code that has always done the work, so its behaviour did not move.
 *
 * A new engine starts from [UnsupportedDialect], which refuses everything and is not
 * [connectable]; it becomes selectable in the connection editor once it overrides that.
 */
interface SqlDialect : SqlSyntax {

    /** False until the engine's implementation is complete: the editor offers it as "coming soon". */
    val connectable: Boolean

    /** The screens and capabilities this engine backs; see [EngineFeature] for the baseline. */
    val features: Set<EngineFeature>

    fun supports(feature: EngineFeature): Boolean = feature in features

    // ---------------------------------------------------------------- statements (§7.4)

    fun classify(sql: String): StatementKind

    /** Adds the engine's row limit to an unlimited read (LIMIT, TOP or FETCH FIRST). */
    fun applyDefaultLimit(sql: String, limit: Int): SqlGuards.LimitResult

    /** An UPDATE or DELETE that would touch every row (no WHERE). */
    fun isUnguardedWrite(sql: String): Boolean

    /**
     * The namespace a bare "switch" statement names — MySQL `USE db`, T-SQL `USE db` (a database,
     * see the design doc), PostgreSQL `SET search_path TO s` — or null. Such a statement is
     * answered by moving the session (SqlSessionManager.selectDatabase), never sent, because the
     * pool would move only one of its connections.
     */
    fun namespaceSwitch(sql: String): String?

    /** `:name` placeholders rewritten to JDBC `?`, skipping this engine's quotes and comments. */
    fun bindParameters(sql: String): SqlGuards.BoundStatement = SqlGuards.bindParameters(sql, grammar)

    /** The `:name` placeholders of [sql], in order of first appearance. */
    fun parameters(sql: String): List<String> = SqlGuards.parameters(sql, grammar)

    /** The statement that explains [sql] in a machine-readable form, or null when unsupported. */
    fun explain(sql: String): String? = null

    // ---------------------------------------------------------------- writes

    /** `SELECT COUNT(*)` of the rows a typed UPDATE/DELETE would touch, or null (WriteImpact). */
    fun writeCountQuery(sql: String): String?

    /** A SELECT showing up to [limit] rows a typed write would change, or null. */
    fun writePreviewQuery(sql: String, limit: Int): WritePreviewQuery?

    /** Whether a typed SELECT's result can be edited in place (ResultEditabilities). */
    fun resultEditability(sql: String): ResultEditability

    // ---------------------------------------------------------------- session

    /** A connector for one session's pool; see [EngineConnector]. */
    fun connector(config: JdbcConfig): EngineConnector

    /**
     * Points [connection] at [namespace] before it is used — the database picker's choice, applied
     * per borrowed connection because the pool has several. MySQL sets the catalog; PostgreSQL
     * sets the schema (search_path).
     */
    fun useNamespace(connection: Connection, namespace: String)

    /** The namespace [connection] is in right now. */
    fun currentNamespace(connection: Connection): String? = connection.catalog

    /**
     * The namespace a new session starts on. [configured] is the connection's saved "database"
     * field: for MySQL that is the namespace itself (and blank means none, with no round trip);
     * an engine whose saved database is the thing it connects to — PostgreSQL, SQL Server —
     * overrides this to ask the connection which schema it landed in.
     */
    fun initialNamespace(connection: Connection, configured: String): String? =
        configured.takeIf { it.isNotBlank() }

    /**
     * The "is anything listening, and is it this engine" check that runs before the pool opens
     * (the connection indicator's database step). Returns the server version when the protocol
     * volunteers one, null when it only proved the port answers. Throws when nothing answers.
     */
    fun probe(host: String, port: Int, timeoutMs: Int): String? {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        return null
    }

    // ---------------------------------------------------------------- schema

    val catalog: SchemaCatalog

    /** The engine's own namespaces, listed after the user's in the picker. */
    val systemNamespaces: Set<String>

    // ---------------------------------------------------------------- errors

    /** The driver's error, classified for the explanation shown under it (§11). */
    fun failureOf(error: SQLException): SqlFailure
}

/** The engine is known but its implementation has not landed yet. */
class EngineNotSupportedException(engine: DatabaseEngine, what: String) :
    UnsupportedOperationException("${engine.name}: $what is not implemented yet")

/** The registry. Phase 2 fills the three stubs in place; nothing here has to change for that. */
object SqlDialects {
    fun forEngine(engine: DatabaseEngine): SqlDialect = when (engine) {
        DatabaseEngine.MYSQL -> MySqlDialect
        DatabaseEngine.POSTGRESQL -> PostgresDialect
        DatabaseEngine.SQLSERVER -> SqlServerDialect
        DatabaseEngine.SQLITE -> SqliteDialect
    }
}
