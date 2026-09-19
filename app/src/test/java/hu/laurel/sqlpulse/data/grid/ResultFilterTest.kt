package hu.laurel.sqlpulse.data.grid

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultFilterTest {

    private fun column(label: String, type: CellType) = ColumnMeta(label, type, label, "t")

    private val table = ResultTable(
        columns = listOf(
            column("id", CellType.NUMBER),
            column("name", CellType.TEXT),
            column("created", CellType.DATE),
            column("photo", CellType.BLOB),
        ),
        rows = listOf(
            listOf(
                CellValue.Number("9"),
                CellValue.Text("Kovács Anna"),
                CellValue.Date("2026-01-03 10:00:00"),
                CellValue.Blob(1024),
            ),
            listOf(
                CellValue.Number("10"),
                CellValue.Text("kovács béla"),
                CellValue.Date("2025-12-31 23:59:00"),
                CellValue.Null,
            ),
            listOf(
                CellValue.Number("11"),
                CellValue.Null,
                CellValue.Date("2026-02-01 00:00:00"),
                CellValue.Blob(0),
            ),
            listOf(
                CellValue.Number("12"),
                CellValue.Text("   "),
                CellValue.Null,
                CellValue.Blob(7),
            ),
        ),
    )

    private fun rows(filter: ResultFilter) = ResultFilters.matchingRows(table, filter)

    @Test
    fun `an inactive filter keeps every row`() {
        assertEquals(listOf(0, 1, 2, 3), rows(ResultFilter()))
        assertEquals(listOf(0, 1, 2, 3), rows(ResultFilter(search = "   ")))
        assertFalse(ResultFilter(search = " ").isActive)
    }

    @Test
    fun `the search box looks in every column and ignores case`() {
        assertEquals(listOf(0, 1), rows(ResultFilter(search = "kovács")))
        assertEquals(listOf(1), rows(ResultFilter(search = "béla")))
    }

    @Test
    fun `a blob is never matched by its size`() {
        // 1024 is the first row's blob length. Matching it would be a coincidence, not a hit.
        assertEquals(emptyList<Int>(), rows(ResultFilter(search = "1024")))
    }

    @Test
    fun `numbers compare as numbers, not as text`() {
        val greater = ResultFilter(
            conditions = listOf(CellCondition(0, FilterOperator.GREATER, "9")),
        )
        // As text "10" sorts below "9"; as numbers it does not.
        assertEquals(listOf(1, 2, 3), rows(greater))
    }

    @Test
    fun `a comparison never passes an unknown value`() {
        val greater = ResultFilter(
            conditions = listOf(CellCondition(2, FilterOperator.GREATER, "2026-01-01")),
        )
        // Row 3 has no date at all, so it is not greater and not less either.
        assertEquals(listOf(0, 2), rows(greater))
        val less = ResultFilter(
            conditions = listOf(CellCondition(2, FilterOperator.LESS, "2026-01-01")),
        )
        assertEquals(listOf(1), rows(less))
    }

    @Test
    fun `empty covers NULL, blank text and a zero-length blob`() {
        assertEquals(
            listOf(2, 3),
            rows(ResultFilter(conditions = listOf(CellCondition(1, FilterOperator.EMPTY)))),
        )
        assertEquals(
            listOf(1, 2),
            rows(ResultFilter(conditions = listOf(CellCondition(3, FilterOperator.EMPTY)))),
        )
        assertEquals(
            listOf(0, 1),
            rows(ResultFilter(conditions = listOf(CellCondition(1, FilterOperator.NOT_EMPTY)))),
        )
    }

    @Test
    fun `conditions are ANDed with each other and with the search`() {
        val filter = ResultFilter(
            search = "kovács",
            conditions = listOf(CellCondition(0, FilterOperator.GREATER, "9")),
        )
        assertEquals(listOf(1), rows(filter))
    }

    @Test
    fun `a condition with nothing typed into it filters nothing`() {
        val filter = ResultFilter(conditions = listOf(CellCondition(1, FilterOperator.CONTAINS, "  ")))
        assertFalse(filter.isActive)
        assertEquals(listOf(0, 1, 2, 3), rows(filter))
    }

    @Test
    fun `a condition on a column the result does not have is ignored`() {
        val filter = ResultFilter(conditions = listOf(CellCondition(9, FilterOperator.CONTAINS, "x")))
        assertEquals(listOf(0, 1, 2, 3), rows(filter))
    }

    @Test
    fun `one column carries one condition, and replacing it keeps the others`() {
        val filter = ResultFilter()
            .with(CellCondition(0, FilterOperator.GREATER, "9"))
            .with(CellCondition(1, FilterOperator.CONTAINS, "kov"))
            .with(CellCondition(0, FilterOperator.LESS, "12"))
        assertEquals(2, filter.conditions.size)
        assertEquals(FilterOperator.LESS, filter.conditionOn(0)?.operator)
        // id < 12 and a name containing "kov": the first two rows, the replaced condition on
        // column 0 having taken the place of the one before it rather than joining it.
        assertEquals(listOf(0, 1), ResultFilters.matchingRows(table, filter))

        val dropped = filter.with(CellCondition(1, FilterOperator.CONTAINS, ""))
        assertEquals(1, dropped.conditions.size)
        assertEquals(null, dropped.conditionOn(1))
    }

    @Test
    fun `apply keeps the table's own metadata`() {
        val filtered = ResultFilters.apply(
            table.copy(truncated = true, limitAdded = true, durationMs = 42),
            ResultFilter(search = "béla"),
        )
        assertEquals(1, filtered.rowCount)
        assertTrue(filtered.truncated)
        assertTrue(filtered.limitAdded)
        assertEquals(42, filtered.durationMs)
        assertEquals(table.columns, filtered.columns)
    }

    @Test
    fun `a blob column offers only presence, text offers everything`() {
        assertEquals(
            listOf(FilterOperator.EMPTY, FilterOperator.NOT_EMPTY),
            ResultFilters.operatorsFor(CellType.BLOB),
        )
        assertEquals(FilterOperator.entries, ResultFilters.operatorsFor(CellType.TEXT))
        assertFalse(FilterOperator.CONTAINS in ResultFilters.operatorsFor(CellType.BOOLEAN))
    }
}
