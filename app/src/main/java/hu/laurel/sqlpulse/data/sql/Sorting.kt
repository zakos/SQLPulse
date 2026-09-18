package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.schema.quoteIdentifier

/** One sorted column. Absence of a [ColumnSort] means the table's own order. */
data class ColumnSort(val column: String, val descending: Boolean)

/** A "contains" filter on one column, which is what a quick search on a table needs (§7.1). */
data class ColumnFilter(val column: String, val contains: String)

/**
 * SQL fragments for sorting and filtering a table page.
 *
 * Column names are identifiers and cannot be bound, so they are backtick-quoted; the filter value
 * is a bound parameter and never interpolated.
 */
object TableQuery {

    /** `ORDER BY` for [sort], or an empty string. */
    fun orderBy(sort: ColumnSort?): String = when (sort) {
        null -> ""
        else -> " ORDER BY ${quoteIdentifier(sort.column)} " + if (sort.descending) "DESC" else "ASC"
    }

    /** `WHERE` for [filter], or an empty string. A blank filter matches everything. */
    fun where(filter: ColumnFilter?): String = when {
        filter == null || filter.contains.isBlank() -> ""
        else -> " WHERE ${quoteIdentifier(filter.column)} LIKE ?"
    }

    /**
     * The value to bind for [where], or null when there is nothing to bind.
     *
     * `%` and `_` in what the user typed are escaped, so searching for "50%" looks for the literal
     * text rather than matching everything.
     */
    fun whereParameter(filter: ColumnFilter?): String? = when {
        filter == null || filter.contains.isBlank() -> null
        else -> "%" + filter.contains
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"
    }

    /** Cycles a column through ascending, descending and back to the table's own order. */
    fun nextSort(current: ColumnSort?, column: String): ColumnSort? = when {
        current == null || current.column != column -> ColumnSort(column, descending = false)
        !current.descending -> ColumnSort(column, descending = true)
        else -> null
    }
}
