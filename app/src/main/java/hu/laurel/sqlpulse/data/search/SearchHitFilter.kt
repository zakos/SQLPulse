package hu.laurel.sqlpulse.data.search

import hu.laurel.sqlpulse.data.sql.ColumnFilter

/**
 * The table filter that shows the row a search hit was, and nothing else.
 *
 * Opening the table unfiltered left the user to find the row again among the first page of a
 * table that may hold millions. The primary key is the right handle: it names exactly one row,
 * and a composite key simply adds one equality per column.
 */
object SearchHitFilter {

    /**
     * Null only for a row that carries nothing to filter on, which a hit never is.
     *
     * Without a primary key the best handle is the cell that matched: it narrows the table to the
     * rows holding that value, the hit among them. A cell cut short on the server
     * ([DatabaseSearch.CELL_CHARS]) is no longer the whole value, so there it falls back to
     * "contains", which still finds the row.
     */
    fun forRow(row: SearchRow, term: String, mode: SearchMode): ColumnFilter? {
        val key = row.key.mapNotNull { (column, value) -> value?.let { column to it } }
        // A NULL key column cannot be compared with `=`, so a key that has one is not enough.
        if (key.isNotEmpty() && key.size == row.key.size) {
            return ColumnFilter(
                column = key.first().first,
                contains = key.first().second,
                exact = true,
                also = key.drop(1),
            )
        }
        val cell = row.cells.firstOrNull { DatabaseSearch.matchRange(it.second, term, mode) != null }
            ?: row.cells.firstOrNull()
            ?: return null
        return ColumnFilter(
            column = cell.first,
            contains = cell.second,
            exact = cell.second.length < DatabaseSearch.CELL_CHARS,
        )
    }
}
