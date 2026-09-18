package hu.laurel.sqlpulse.data.sql

import java.sql.Types
import org.junit.Assert.assertEquals
import org.junit.Test

class ResultTableTest {

    @Test
    fun `numeric jdbc types map to the number cell type`() {
        listOf(Types.TINYINT, Types.INTEGER, Types.BIGINT, Types.DECIMAL, Types.DOUBLE)
            .forEach { assertEquals(CellType.NUMBER, ResultTable.cellTypeOf(it)) }
    }

    @Test
    fun `temporal types map to the date cell type`() {
        listOf(Types.DATE, Types.TIME, Types.TIMESTAMP)
            .forEach { assertEquals(CellType.DATE, ResultTable.cellTypeOf(it)) }
    }

    @Test
    fun `binary types map to blob so contents are never rendered`() {
        listOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)
            .forEach { assertEquals(CellType.BLOB, ResultTable.cellTypeOf(it)) }
    }

    @Test
    fun `anything else is text`() {
        assertEquals(CellType.TEXT, ResultTable.cellTypeOf(Types.VARCHAR))
        assertEquals(CellType.TEXT, ResultTable.cellTypeOf(Types.OTHER))
    }

    @Test
    fun `an empty table reports no rows`() {
        assertEquals(0, ResultTable.EMPTY.rowCount)
    }

    @Test
    fun `sorting a numeric column compares numbers, not text`() {
        val table = ResultTable(
            columns = listOf(ColumnMeta("id", CellType.NUMBER, "INT", "t")),
            rows = listOf(
                listOf(CellValue.Number("10")),
                listOf(CellValue.Number("9")),
                listOf(CellValue.Number("100")),
            ),
        )

        val ascending = table.sortedBy(0, descending = false).rows
            .map { (it[0] as CellValue.Number).value }

        assertEquals(listOf("9", "10", "100"), ascending)
    }

    @Test
    fun `sorting descending reverses the order`() {
        val table = ResultTable(
            columns = listOf(ColumnMeta("name", CellType.TEXT, "VARCHAR", "t")),
            rows = listOf(
                listOf(CellValue.Text("beta")),
                listOf(CellValue.Text("alpha")),
            ),
        )

        val descending = table.sortedBy(0, descending = true).rows
            .map { (it[0] as CellValue.Text).value }

        assertEquals(listOf("beta", "alpha"), descending)
    }

    @Test
    fun `NULL sorts lowest, as MySQL does`() {
        val table = ResultTable(
            columns = listOf(ColumnMeta("note", CellType.TEXT, "VARCHAR", "t")),
            rows = listOf(
                listOf(CellValue.Text("b")),
                listOf(CellValue.Null),
                listOf(CellValue.Text("a")),
            ),
        )

        val ascending = table.sortedBy(0, descending = false).rows.map { it[0] }

        assertEquals(CellValue.Null, ascending.first())
    }

    @Test
    fun `sorting an unknown column leaves the rows alone`() {
        val table = ResultTable(
            columns = listOf(ColumnMeta("a", CellType.TEXT, "VARCHAR", "t")),
            rows = listOf(listOf(CellValue.Text("x"))),
        )

        assertEquals(table, table.sortedBy(7, descending = false))
    }
}
