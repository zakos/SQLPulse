package hu.laurel.sqlpulse.data.search

import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlSyntax

/** How the term is compared with a cell. */
enum class SearchMode { CONTAINS, EXACT }

/**
 * One read-only `SELECT` for one table, with the columns it looks in and the ones that identify a row.
 *
 * The statement is a prepared one: the term is only ever bound, never written into the SQL.
 */
data class SearchPlan(
    val database: String,
    val table: String,
    /** Primary key columns, selected first so that a hit can be told apart from its neighbours. */
    val keyColumns: List<String>,
    /** The columns the term is looked for in, in the order they are selected after the key. */
    val searchColumns: List<String>,
    val sql: String,
    /** One value per `?`, in order. */
    val parameters: List<String>,
)

/**
 * "Search the whole database": which columns are worth looking in, and the statement for each table.
 *
 * Follows dbx's `database_search_sql.rs` (MySQL branch): every column is compared as
 * `LOWER(CAST(col AS CHAR)) LIKE LOWER(?)`, one statement per table, each with its own row limit.
 * Deliberately different from dbx in the LIKE escape character: `!` with an explicit `ESCAPE`
 * clause instead of the default backslash, because what a backslash means inside a string literal
 * depends on `NO_BACKSLASH_ESCAPES`, and a search that silently treats `\_` differently on two
 * servers is worse than one that looks slightly odd in the SQL.
 *
 * Other engines differ in three places only, all spelled out in [Flavor]: how a column becomes
 * text (`CAST(c AS CHAR)`, `AS text`, `AS NVARCHAR(MAX)`, `AS TEXT`), how a long cell is cut
 * (`LEFT`, `SUBSTR`), and which declared types count as text or number. Comparison stays
 * `LOWER(text) LIKE LOWER(?) ESCAPE '!'` everywhere: SQL Server's default collations are already
 * case-insensitive, PostgreSQL gets it from LOWER, and SQLite's `LOWER` and `LIKE` only fold
 * ASCII, so a search for "árvíz" does not find "Árvíz" there (unlike MySQL and SQL Server, whose
 * collations fold accents and case).
 */
object DatabaseSearch {

    private const val ESCAPE = '!'

    /** A long text cell is cut here on the server, so one huge TEXT hit cannot flood the tunnel. */
    const val CELL_CHARS = 1000

    private val textTypes = setOf(
        "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum", "set",
    )
    private val numberTypes = setOf(
        "tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric",
        "float", "double", "real",
    )

    // PostgreSQL types as `format_type` writes them (the part before any length or `[]`).
    // json/jsonb are searched as their text form, uuid because ids are what people paste in.
    private val postgresTextTypes = setOf(
        "text", "character varying", "varchar", "character", "char", "bpchar", "citext", "name",
        "uuid", "json", "jsonb", "enum",
    )
    private val postgresNumberTypes = setOf(
        "smallint", "integer", "bigint", "numeric", "decimal", "real", "double precision",
        "int2", "int4", "int8", "float4", "float8",
    )

    private val sqlServerTextTypes = setOf(
        "char", "varchar", "nchar", "nvarchar", "text", "ntext", "uniqueidentifier",
    )
    private val sqlServerNumberTypes = setOf(
        "tinyint", "smallint", "int", "bigint", "decimal", "numeric", "float", "real",
        "money", "smallmoney",
    )

    /** How one engine turns a column into searchable text and cuts a cell short. */
    private class Flavor(val textOf: (quoted: String) -> String, val cut: (quoted: String) -> String)

    private fun flavor(engine: DatabaseEngine): Flavor = when (engine) {
        // The old statement, character for character: MySQL's LEFT takes any column.
        DatabaseEngine.MYSQL -> Flavor({ "CAST($it AS CHAR)" }, { "LEFT($it, $CELL_CHARS)" })
        // LEFT exists only for text in PostgreSQL, so a uuid or a jsonb column is cast first.
        DatabaseEngine.POSTGRESQL -> Flavor({ "CAST($it AS text)" }, { "LEFT(CAST($it AS text), $CELL_CHARS)" })
        // LOWER and LEFT reject text/ntext outright; the cast to NVARCHAR(MAX) makes them work.
        DatabaseEngine.SQLSERVER -> Flavor(
            { "CAST($it AS NVARCHAR(MAX))" },
            { "LEFT(CAST($it AS NVARCHAR(MAX)), $CELL_CHARS)" },
        )
        DatabaseEngine.SQLITE -> Flavor({ "CAST($it AS TEXT)" }, { "SUBSTR(CAST($it AS TEXT), 1, $CELL_CHARS)" })
    }
    private val numericTerm = Regex("""[+-]?\d+(\.\d+)?""")

    /** True for a term that can plausibly be a number, which is when number columns are searched too. */
    fun isNumeric(term: String): Boolean = numericTerm.matches(term.trim())

    /** The type name without its length, signedness or value list: `varchar(255)` becomes `varchar`. */
    fun baseType(columnType: String): String =
        columnType.trim().lowercase().takeWhile { it.isLetter() }

    /**
     * Whether a column of this type is looked in.
     *
     * BLOB, binary, geometry and JSON are left out: they either hold bytes that mean nothing as
     * text, or are large documents where a substring hit would be noise. Dates are left out too —
     * their text form depends on the server, and a date is better found with a query.
     */
    fun isSearchable(columnType: String, numbersToo: Boolean, engine: DatabaseEngine = DatabaseEngine.MYSQL): Boolean =
        when (engine) {
            DatabaseEngine.MYSQL -> {
                val base = baseType(columnType)
                base in textTypes || (numbersToo && base in numberTypes)
            }
            DatabaseEngine.POSTGRESQL -> {
                val lower = columnType.trim().lowercase()
                // An array of text is a list, not a value; domains and ranges are not looked into.
                val base = lower.substringBefore('(').trim()
                !lower.endsWith("[]") && (base in postgresTextTypes || (numbersToo && base in postgresNumberTypes))
            }
            DatabaseEngine.SQLSERVER -> {
                val base = baseType(columnType)
                base in sqlServerTextTypes || (numbersToo && base in sqlServerNumberTypes)
            }
            DatabaseEngine.SQLITE -> when (sqliteAffinity(columnType)) {
                SqliteAffinity.TEXT, SqliteAffinity.NONE -> true
                SqliteAffinity.NUMBER -> numbersToo
                SqliteAffinity.OTHER -> false
            }
        }

    private enum class SqliteAffinity { TEXT, NUMBER, NONE, OTHER }

    /**
     * SQLite's own rules for a declared type (its "type affinity", section 3.1 of the datatype
     * page), narrowed to what a search cares about. A column with no declared type holds
     * whatever was put in, which is usually text, so it is searched; BLOB is not; date and time
     * names get NUMERIC affinity from SQLite but are stored as text in a form worth finding with
     * a query, so they are left out like in every other engine.
     */
    private fun sqliteAffinity(columnType: String): SqliteAffinity {
        val type = columnType.trim().uppercase()
        return when {
            type.isEmpty() -> SqliteAffinity.NONE
            "INT" in type -> SqliteAffinity.NUMBER
            "CHAR" in type || "CLOB" in type || "TEXT" in type -> SqliteAffinity.TEXT
            "BLOB" in type -> SqliteAffinity.OTHER
            "REAL" in type || "FLOA" in type || "DOUB" in type -> SqliteAffinity.NUMBER
            "DATE" in type || "TIME" in type -> SqliteAffinity.OTHER
            type.startsWith("NUMERIC") || type.startsWith("DECIMAL") -> SqliteAffinity.NUMBER
            "BOOL" in type -> SqliteAffinity.OTHER
            else -> SqliteAffinity.OTHER
        }
    }

    /** Escapes `%`, `_` and the escape character itself, so the term is matched literally. */
    fun escapeLike(term: String, engine: DatabaseEngine = DatabaseEngine.MYSQL): String = buildString {
        for (c in term) {
            // `[` opens a character class in T-SQL's LIKE; nowhere else does it mean anything.
            val special = c == '%' || c == '_' || c == ESCAPE || (c == '[' && engine == DatabaseEngine.SQLSERVER)
            if (special) append(ESCAPE)
            append(c)
        }
    }

    /** The bound `LIKE` pattern for [term]. Lower-casing is done by the statement itself. */
    fun pattern(term: String, mode: SearchMode, engine: DatabaseEngine = DatabaseEngine.MYSQL): String = when (mode) {
        SearchMode.CONTAINS -> "%${escapeLike(term, engine)}%"
        SearchMode.EXACT -> escapeLike(term, engine)
    }

    /**
     * The statement for one table, or null when it has no column worth looking in.
     *
     * Every searched column is selected cut to [CELL_CHARS] for the reason on that constant (`LEFT`
     * or `SUBSTR`, see [Flavor]); the `WHERE` still tests the whole value. Names are quoted and the
     * row limit written by [syntax], so the same plan reads right in every engine.
     */
    fun plan(
        database: String,
        table: String,
        columns: List<SchemaColumn>,
        term: String,
        mode: SearchMode,
        rowLimit: Int,
        syntax: SqlSyntax = MySqlDialect,
    ): SearchPlan? {
        val needle = term.trim()
        if (needle.isEmpty()) return null
        val searched = columns.filter { isSearchable(it.typeName, isNumeric(needle), syntax.engine) }
        val flavor = flavor(syntax.engine)
        val quote = syntax::quoteIdentifier
        if (searched.isEmpty()) return null
        val keys = columns.filter { it.isPrimaryKey }.map { it.name }
        val searchNames = searched.map { it.name }

        val select = buildList {
            keys.forEach { add(quote(it)) }
            searchNames.forEach { add(flavor.cut(quote(it))) }
        }.joinToString(", ")
        val where = searchNames.joinToString(" OR ") {
            "LOWER(${flavor.textOf(quote(it))}) LIKE LOWER(?) ESCAPE '$ESCAPE'"
        }
        val sql = syntax.limit(
            "SELECT $select FROM ${syntax.qualify(database, table)} WHERE $where",
            rowLimit.coerceAtLeast(1),
        )
        return SearchPlan(
            database = database,
            table = table,
            keyColumns = keys,
            searchColumns = searchNames,
            sql = sql,
            parameters = List(searchNames.size) { pattern(needle, mode, syntax.engine) },
        )
    }

    /**
     * Where [term] sits in [value] as the app can see it, or null.
     *
     * The server may call something a match that this does not — an accent-insensitive collation
     * finds "é" for "e" — and the screen then shows the cell without a highlight rather than
     * hiding a row the server did return.
     */
    fun matchRange(value: String, term: String, mode: SearchMode): IntRange? {
        val needle = term.trim()
        if (needle.isEmpty()) return null
        return when (mode) {
            SearchMode.CONTAINS -> {
                val at = value.indexOf(needle, ignoreCase = true)
                if (at < 0) null else at until at + needle.length
            }
            SearchMode.EXACT ->
                if (value.equals(needle, ignoreCase = true)) value.indices else null
        }
    }

    /**
     * A window of [value] around the hit, so a long cell shows the part that matched.
     *
     * Returns the text and where the match sits in it. Ellipses are part of the text, and the
     * range is already shifted to account for them. Line breaks become spaces: a hit is one line.
     */
    fun snippet(value: String, range: IntRange?, radius: Int = 60): Pair<String, IntRange?> {
        val flat = value.replace('\n', ' ').replace('\r', ' ')
        if (flat.length <= radius * 2) return flat to range
        if (range == null) return (flat.take(radius * 2) + "…") to null
        val start = (range.first - radius).coerceAtLeast(0)
        val end = (range.last + 1 + radius).coerceAtMost(flat.length)
        val lead = if (start > 0) "…" else ""
        val tail = if (end < flat.length) "…" else ""
        val shift = lead.length - start
        return (lead + flat.substring(start, end) + tail) to (range.first + shift)..(range.last + shift)
    }
}
