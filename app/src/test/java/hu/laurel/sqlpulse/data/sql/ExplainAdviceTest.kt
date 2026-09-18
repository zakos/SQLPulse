package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplainAdviceTest {

    private fun explain(vararg cells: Pair<String, String?>): ResultTable {
        val labels = listOf("select_type", "table", "type", "key", "rows", "Extra")
        val values = labels.map { label ->
            val value = cells.toMap()[label]
            when {
                value == null -> CellValue.Null
                label == "rows" -> CellValue.Number(value)
                else -> CellValue.Text(value)
            }
        }
        return ResultTable(
            columns = labels.map { ColumnMeta(it, CellType.TEXT, "VARCHAR", null) },
            rows = listOf(values),
        )
    }

    @Test
    fun `a full table scan is worth saying out loud`() {
        val notes = ExplainAdvice.of(explain("type" to "ALL", "rows" to "1200"))
        assertTrue(notes.toString(), ExplainNote.FULL_TABLE_SCAN in notes)
        assertTrue(notes.toString(), ExplainNote.NO_INDEX in notes)
    }

    @Test
    fun `a query that uses an index has nothing to report`() {
        val notes = ExplainAdvice.of(
            explain("type" to "ref", "key" to "idx_customer", "rows" to "12", "Extra" to "Using where"),
        )
        assertEquals(emptyList<ExplainNote>(), notes)
    }

    @Test
    fun `sorting and temporary tables are picked out of Extra`() {
        val notes = ExplainAdvice.of(
            explain(
                "type" to "ref",
                "key" to "idx",
                "rows" to "10",
                "Extra" to "Using where; Using temporary; Using filesort",
            ),
        )
        assertTrue(ExplainNote.FILESORT in notes)
        assertTrue(ExplainNote.TEMPORARY_TABLE in notes)
    }

    @Test
    fun `a large estimate is flagged, a small one is not`() {
        assertTrue(
            ExplainNote.MANY_ROWS in
                ExplainAdvice.of(explain("type" to "ref", "key" to "idx", "rows" to "250000")),
        )
        assertTrue(
            ExplainNote.MANY_ROWS !in
                ExplainAdvice.of(explain("type" to "ref", "key" to "idx", "rows" to "99999")),
        )
    }

    @Test
    fun `walking the whole index is not the same as scanning the table`() {
        val notes = ExplainAdvice.of(explain("type" to "index", "key" to "idx", "rows" to "500"))
        assertEquals(listOf(ExplainNote.FULL_INDEX_SCAN), notes)
    }

    @Test
    fun `a result that is not an EXPLAIN is not interpreted`() {
        val ordinary = ResultTable(
            columns = listOf(ColumnMeta("id", CellType.NUMBER, "INT", "orders")),
            rows = listOf(listOf(CellValue.Number("1"))),
        )
        assertEquals(emptyList<ExplainNote>(), ExplainAdvice.of(ordinary))
    }

    @Test
    fun `each note is reported once, however many rows the plan has`() {
        val table = explain("type" to "ALL", "rows" to "10")
        val twoRows = table.copy(rows = table.rows + table.rows)
        assertEquals(ExplainAdvice.of(table), ExplainAdvice.of(twoRows))
    }
}
