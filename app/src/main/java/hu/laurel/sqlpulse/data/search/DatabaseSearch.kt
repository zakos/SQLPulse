package hu.laurel.sqlpulse.data.search

import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.quoteIdentifier

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
    fun isSearchable(columnType: String, numbersToo: Boolean): Boolean {
        val base = baseType(columnType)
        return base in textTypes || (numbersToo && base in numberTypes)
    }

    /** Escapes `%`, `_` and the escape character itself, so the term is matched literally. */
    fun escapeLike(term: String): String = buildString {
        for (c in term) {
            if (c == '%' || c == '_' || c == ESCAPE) append(ESCAPE)
            append(c)
        }
    }

    /** The bound `LIKE` pattern for [term]. Lower-casing is done by the statement itself. */
    fun pattern(term: String, mode: SearchMode): String = when (mode) {
        SearchMode.CONTAINS -> "%${escapeLike(term)}%"
        SearchMode.EXACT -> escapeLike(term)
    }

    /**
     * The statement for one table, or null when it has no column worth looking in.
     *
     * Every searched column is selected as `LEFT(col, CELL_CHARS)` for the reason on [CELL_CHARS];
     * the `WHERE` still tests the whole value.
     */
    fun plan(
        database: String,
        table: String,
        columns: List<SchemaColumn>,
        term: String,
        mode: SearchMode,
        rowLimit: Int,
    ): SearchPlan? {
        val needle = term.trim()
        if (needle.isEmpty()) return null
        val searched = columns.filter { isSearchable(it.typeName, isNumeric(needle)) }
        if (searched.isEmpty()) return null
        val keys = columns.filter { it.isPrimaryKey }.map { it.name }
        val searchNames = searched.map { it.name }

        val select = buildList {
            keys.forEach { add(quoteIdentifier(it)) }
            searchNames.forEach { add("LEFT(${quoteIdentifier(it)}, $CELL_CHARS)") }
        }.joinToString(", ")
        val where = searchNames.joinToString(" OR ") {
            "LOWER(CAST(${quoteIdentifier(it)} AS CHAR)) LIKE LOWER(?) ESCAPE '$ESCAPE'"
        }
        val sql = "SELECT $select FROM ${quoteIdentifier(database)}.${quoteIdentifier(table)} " +
            "WHERE $where LIMIT ${rowLimit.coerceAtLeast(1)}"
        return SearchPlan(
            database = database,
            table = table,
            keyColumns = keys,
            searchColumns = searchNames,
            sql = sql,
            parameters = List(searchNames.size) { pattern(needle, mode) },
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
