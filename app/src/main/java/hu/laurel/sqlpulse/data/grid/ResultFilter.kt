package hu.laurel.sqlpulse.data.grid

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable

/**
 * How one condition compares a cell with what the user typed.
 *
 * The set is deliberately short. Everything here has to be understandable from a two-word label on
 * a phone, and anything that needs more than that — a regular expression, a date range, an OR
 * between columns — is a query, and the query editor is one tap away.
 */
enum class FilterOperator {
    CONTAINS,
    NOT_CONTAINS,
    EQUALS,
    NOT_EQUALS,
    GREATER,
    LESS,

    /** NULL, or text with nothing but spaces in it. */
    EMPTY,
    NOT_EMPTY,
}

/** One column's condition. [text] is ignored by [FilterOperator.EMPTY] and its opposite. */
data class CellCondition(
    val columnIndex: Int,
    val operator: FilterOperator,
    val text: String = "",
)

/**
 * Everything narrowing the visible rows: one box searching every column, and a condition per
 * column.
 *
 * Conditions are ANDed. OR would need a second axis on the screen and a reader who knows which
 * binds tighter; a result narrowed by mistake looks exactly like a result with nothing in it,
 * and that is the one mistake a filter must not make easy.
 */
data class ResultFilter(
    val search: String = "",
    val conditions: List<CellCondition> = emptyList(),
) {
    val isActive: Boolean
        get() = search.isNotBlank() || conditions.any { it.isMeaningful }

    /** The condition on [columnIndex], or null. One column carries at most one. */
    fun conditionOn(columnIndex: Int): CellCondition? =
        conditions.firstOrNull { it.columnIndex == columnIndex }

    /** Replaces, adds or removes one column's condition, keeping the others in place. */
    fun with(condition: CellCondition?): ResultFilter = when (condition) {
        null -> this
        else -> {
            val rest = conditions.filterNot { it.columnIndex == condition.columnIndex }
            copy(conditions = if (condition.isMeaningful) rest + condition else rest)
        }
    }

    fun without(columnIndex: Int): ResultFilter =
        copy(conditions = conditions.filterNot { it.columnIndex == columnIndex })
}

/** False for a condition that would match everything, so an empty text box filters nothing. */
private val CellCondition.isMeaningful: Boolean
    get() = when (operator) {
        FilterOperator.EMPTY, FilterOperator.NOT_EMPTY -> true
        else -> text.isNotBlank()
    }

/**
 * Filtering of rows that are already on the phone.
 *
 * This narrows what was loaded and nothing else: it does not go back to the server, and it cannot
 * see rows the query never returned. Every screen showing it has to say so, because a filter that
 * silently hides the fact that it only saw the first thousand rows is a way of answering "there
 * are none" when the answer is "there are none here".
 */
object ResultFilters {

    /** The operators worth offering for a column of this type. */
    fun operatorsFor(type: CellType): List<FilterOperator> = when (type) {
        // A BLOB never has its contents in the grid — only a size — so there is no text to
        // compare and only the presence of a value can be asked about.
        CellType.BLOB -> listOf(FilterOperator.EMPTY, FilterOperator.NOT_EMPTY)

        CellType.BOOLEAN -> listOf(
            FilterOperator.EQUALS,
            FilterOperator.NOT_EQUALS,
            FilterOperator.EMPTY,
            FilterOperator.NOT_EMPTY,
        )

        CellType.NUMBER, CellType.DATE -> listOf(
            FilterOperator.EQUALS,
            FilterOperator.NOT_EQUALS,
            FilterOperator.GREATER,
            FilterOperator.LESS,
            FilterOperator.CONTAINS,
            FilterOperator.EMPTY,
            FilterOperator.NOT_EMPTY,
        )

        CellType.TEXT -> FilterOperator.entries
    }

    /**
     * The indexes of the rows that pass, in the table's own order.
     *
     * Indexes rather than rows: the caller often needs to know which row of the original it is
     * looking at — row editing does — and rebuilding that from a copied row is guesswork once two
     * rows are identical.
     */
    fun matchingRows(table: ResultTable, filter: ResultFilter): List<Int> {
        if (!filter.isActive) return table.rows.indices.toList()
        val conditions = filter.conditions.filter { it.isMeaningful && it.columnIndex in table.columns.indices }
        val search = filter.search.trim()
        return table.rows.indices.filter { index ->
            val row = table.rows[index]
            conditions.all { matches(row.getOrNull(it.columnIndex), table.columns[it.columnIndex].type, it) } &&
                (search.isEmpty() || rowContains(row, search))
        }
    }

    /** The same table with only the passing rows in it. */
    fun apply(table: ResultTable, filter: ResultFilter): ResultTable {
        if (!filter.isActive) return table
        val kept = matchingRows(table, filter)
        return table.copy(rows = kept.map { table.rows[it] })
    }

    /**
     * The text a cell is matched against, which is what the grid prints for it.
     *
     * A BLOB gives an empty string rather than its size: "1024" in a search box means the number
     * one thousand and twenty-four, and matching a picture because it happens to be that many
     * bytes long is a coincidence dressed up as a result.
     */
    fun textOf(cell: CellValue?): String = when (cell) {
        null, CellValue.Null -> ""
        is CellValue.Text -> cell.value
        is CellValue.Number -> cell.value
        is CellValue.Date -> cell.value
        is CellValue.Bool -> if (cell.value) "1" else "0"
        is CellValue.Blob -> ""
    }

    private fun rowContains(row: List<CellValue>, search: String): Boolean =
        row.any { textOf(it).contains(search, ignoreCase = true) }

    private fun matches(cell: CellValue?, type: CellType, condition: CellCondition): Boolean {
        val empty = isEmpty(cell)
        return when (condition.operator) {
            FilterOperator.EMPTY -> empty
            FilterOperator.NOT_EMPTY -> !empty
            // A comparison with an unknown value is unknown, and unknown does not pass — the same
            // answer MySQL gives for `NULL > 5`.
            else -> if (empty) false else compare(textOf(cell), type, condition)
        }
    }

    private fun isEmpty(cell: CellValue?): Boolean = when (cell) {
        null, CellValue.Null -> true
        is CellValue.Text -> cell.value.isBlank()
        is CellValue.Blob -> cell.sizeBytes == 0L
        else -> false
    }

    private fun compare(text: String, type: CellType, condition: CellCondition): Boolean {
        val wanted = condition.text.trim()
        return when (condition.operator) {
            FilterOperator.CONTAINS -> text.contains(wanted, ignoreCase = true)
            FilterOperator.NOT_CONTAINS -> !text.contains(wanted, ignoreCase = true)
            FilterOperator.EQUALS -> text.equals(wanted, ignoreCase = true)
            FilterOperator.NOT_EQUALS -> !text.equals(wanted, ignoreCase = true)
            FilterOperator.GREATER -> ordered(text, wanted, type) > 0
            FilterOperator.LESS -> ordered(text, wanted, type) < 0
            FilterOperator.EMPTY, FilterOperator.NOT_EMPTY -> true
        }
    }

    /**
     * Compares as numbers where both sides are numbers, and as text otherwise.
     *
     * A number column whose value does not parse — a driver that hands back "12,5", say — falls
     * back to text rather than dropping the row, and dates compare as text on purpose: the server
     * writes them big-endian, so that ordering is the chronological one.
     */
    private fun ordered(text: String, wanted: String, type: CellType): Int {
        if (type == CellType.NUMBER) {
            val left = text.toBigDecimalOrNull()
            val right = wanted.toBigDecimalOrNull()
            if (left != null && right != null) return left.compareTo(right)
        }
        return text.compareTo(wanted, ignoreCase = true)
    }
}
