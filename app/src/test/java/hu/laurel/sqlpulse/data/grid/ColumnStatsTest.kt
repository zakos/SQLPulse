package hu.laurel.sqlpulse.data.grid

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class ColumnStatsTest {
    @Test
    fun testIntegerColumn() {
        val column = ColumnMeta("id", CellType.NUMBER, "INT", "t")
        val table = ResultTable(
            columns = listOf(column),
            rows = listOf(
                listOf(CellValue.Number("1")),
                listOf(CellValue.Number("2")),
                listOf(CellValue.Number("3")),
                listOf(CellValue.Null),
            ),
        )

        val stats = ColumnStatsComputer.compute(table, 0)!!

        assertEquals(4, stats.rowCount)
        assertEquals(1, stats.nullCount)
        assertEquals(3, stats.nonNullCount)
        assertEquals(3, stats.distinctCount)
        assertEquals(BigDecimal(6), stats.sum)
        assertEquals("2", stats.average)
        assertEquals("1", stats.min)
        assertEquals("3", stats.max)
    }

    @Test
    fun testDecimalColumn() {
        val column = ColumnMeta("price", CellType.NUMBER, "DECIMAL", "t")
        val table = ResultTable(
            columns = listOf(column),
            rows = listOf(
                listOf(CellValue.Number("10.5")),
                listOf(CellValue.Number("20.75")),
                listOf(CellValue.Number("10.5")), // duplicate
            ),
        )

        val stats = ColumnStatsComputer.compute(table, 0)!!

        assertEquals(3, stats.rowCount)
        assertEquals(0, stats.nullCount)
        assertEquals(2, stats.distinctCount)
        assertEquals(BigDecimal("41.75"), stats.sum)
        assertEquals("13.916667", stats.average)
    }

    @Test
    fun testTextColumn() {
        val column = ColumnMeta("name", CellType.TEXT, "VARCHAR", "t")
        val table = ResultTable(
            columns = listOf(column),
            rows = listOf(
                listOf(CellValue.Text("Alice")),
                listOf(CellValue.Text("Bob")),
                listOf(CellValue.Text("Alice")),
                listOf(CellValue.Null),
            ),
        )

        val stats = ColumnStatsComputer.compute(table, 0)!!

        assertEquals(4, stats.rowCount)
        assertEquals(1, stats.nullCount)
        assertEquals(2, stats.distinctCount) // "Alice", "Bob" (NULL is not counted)
        assertNull(stats.sum)
        assertNull(stats.average)
        assertEquals("Alice", stats.min)
        assertEquals("Bob", stats.max)
    }

    @Test
    fun testDateColumn() {
        val column = ColumnMeta("created", CellType.DATE, "DATETIME", "t")
        val table = ResultTable(
            columns = listOf(column),
            rows = listOf(
                listOf(CellValue.Date("2024-01-01")),
                listOf(CellValue.Date("2024-12-31")),
                listOf(CellValue.Date("2024-06-15")),
            ),
        )

        val stats = ColumnStatsComputer.compute(table, 0)!!

        assertEquals(3, stats.rowCount)
        assertEquals(0, stats.nullCount)
        assertEquals(3, stats.distinctCount)
        assertNull(stats.sum)
        assertNull(stats.average)
        assertEquals("2024-01-01", stats.min)
        assertEquals("2024-12-31", stats.max)
    }

    @Test
    fun testBooleanColumn() {
        val column = ColumnMeta("active", CellType.BOOLEAN, "BOOLEAN", "t")
        val table = ResultTable(
            columns = listOf(column),
            rows = listOf(
                listOf(CellValue.Bool(true)),
                listOf(CellValue.Bool(false)),
                listOf(CellValue.Bool(true)),
            ),
        )

        val stats = ColumnStatsComputer.compute(table, 0)!!

        assertEquals(3, stats.rowCount)
        assertEquals(0, stats.nullCount)
        assertEquals(2, stats.distinctCount) // "0", "1"
        assertEquals(BigDecimal(2), stats.sum)
        assertEquals("0.666667", stats.average)
    }

    @Test
    fun testAllNull() {
        val column = ColumnMeta("maybe", CellType.TEXT, "VARCHAR", "t")
        val table = ResultTable(
            columns = listOf(column),
            rows = listOf(
                listOf(CellValue.Null),
                listOf(CellValue.Null),
            ),
        )

        val stats = ColumnStatsComputer.compute(table, 0)!!

        assertEquals(2, stats.rowCount)
        assertEquals(2, stats.nullCount)
        assertEquals(0, stats.nonNullCount)
        assertEquals(0, stats.distinctCount) // no non-null values
        assertNull(stats.sum)
        assertNull(stats.average)
        assertNull(stats.min)
        assertNull(stats.max)
    }

    @Test
    fun testEmptyTable() {
        val column = ColumnMeta("x", CellType.NUMBER, "INT", "t")
        val table = ResultTable(columns = listOf(column), rows = emptyList())

        val stats = ColumnStatsComputer.compute(table, 0)
        assertEquals(null, stats)
    }

    @Test
    fun testInvalidColumnIndex() {
        val column = ColumnMeta("x", CellType.NUMBER, "INT", "t")
        val table = ResultTable(
            columns = listOf(column),
            rows = listOf(listOf(CellValue.Number("1"))),
        )

        val stats = ColumnStatsComputer.compute(table, 5)
        assertEquals(null, stats)
    }

    @Test
    fun testMixedNumbers() {
        val column = ColumnMeta("mixed", CellType.TEXT, "VARCHAR", "t")
        val table = ResultTable(
            columns = listOf(column),
            rows = listOf(
                listOf(CellValue.Text("abc")),
                listOf(CellValue.Number("123")),
                listOf(CellValue.Text("def")),
            ),
        )

        val stats = ColumnStatsComputer.compute(table, 0)!!

        // Mixed types: should not compute sum/avg
        assertNull(stats.sum)
        assertNull(stats.average)
        assertEquals("123", stats.min)
        assertEquals("def", stats.max)
    }

    @Test
    fun testAverageTrailingZeros() {
        val column = ColumnMeta("val", CellType.NUMBER, "DECIMAL", "t")
        val table = ResultTable(
            columns = listOf(column),
            rows = listOf(
                listOf(CellValue.Number("1")),
                listOf(CellValue.Number("3")),
            ),
        )

        val stats = ColumnStatsComputer.compute(table, 0)!!

        // 4/2 = 2.0, should strip to "2"
        assertEquals("2", stats.average)
    }
}
