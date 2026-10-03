package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlSyntax

/** One sorted column. Absence of a [ColumnSort] means the table's own order. */
data class ColumnSort(val column: String, val descending: Boolean)

/**
 * A "contains" filter on one column, which is what a quick search on a table needs (§7.1).
 *
 * [exact] makes it an equality instead, and [also] adds further equalities on other columns; both
 * exist for "show me the row this search hit was", where a composite primary key has several
 * columns and `contains` would also match "10" when the row is "1". The filter box edits only the
 * first column, so typing in it turns the filter back into a plain "contains".
 */
data class ColumnFilter(
    val column: String,
    val contains: String,
    val exact: Boolean = false,
    val also: List<Pair<String, String>> = emptyList(),
) {
    /** A blank "contains" matches everything; an exact blank is the empty string and does not. */
    val isEmpty: Boolean get() = !exact && contains.isBlank()
}

/**
 * SQL fragments for sorting and filtering a table page.
 *
 * Column names are identifiers and cannot be bound, so they are quoted the way [SqlSyntax] says
 * (backticks for MySQL, the default); the filter value is a bound parameter and never interpolated.
 */
object TableQuery {

    /** `ORDER BY` for [sort], or an empty string. */
    fun orderBy(sort: ColumnSort?, syntax: SqlSyntax = MySqlDialect): String = when (sort) {
        null -> ""
        else -> " ORDER BY ${syntax.quoteIdentifier(sort.column)} " + if (sort.descending) "DESC" else "ASC"
    }

    /** `WHERE` for [filter], or an empty string. A blank filter matches everything. */
    fun where(filter: ColumnFilter?, syntax: SqlSyntax = MySqlDialect): String = when {
        filter == null || filter.isEmpty -> ""
        else -> " WHERE " + (
            listOf(
                if (filter.exact) {
                    "${syntax.quoteIdentifier(filter.column)} = ?"
                } else {
                    // The bound pattern escapes % and _ with a backslash; the engine is told so
                    // where backslash is not already LIKE's escape.
                    "${syntax.quoteIdentifier(filter.column)} LIKE ?${syntax.likeEscape}"
                },
            ) + filter.also.map { "${syntax.quoteIdentifier(it.first)} = ?" }
            ).joinToString(" AND ")
    }

    /**
     * The value to bind for the first `?` of [where], or null when there is nothing to bind.
     *
     * `%` and `_` in what the user typed are escaped, so searching for "50%" looks for the literal
     * text rather than matching everything.
     */
    fun whereParameter(filter: ColumnFilter?): String? = whereParameters(filter).firstOrNull()

    /** Every value to bind for [where], in order. */
    fun whereParameters(filter: ColumnFilter?): List<String> = when {
        filter == null || filter.isEmpty -> emptyList()
        else -> listOf(
            if (filter.exact) {
                filter.contains
            } else {
                "%" + filter.contains
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_") + "%"
            },
        ) + filter.also.map { it.second }
    }

    /** Cycles a column through ascending, descending and back to the table's own order. */
    fun nextSort(current: ColumnSort?, column: String): ColumnSort? = when {
        current == null || current.column != column -> ColumnSort(column, descending = false)
        !current.descending -> ColumnSort(column, descending = true)
        else -> null
    }
}
