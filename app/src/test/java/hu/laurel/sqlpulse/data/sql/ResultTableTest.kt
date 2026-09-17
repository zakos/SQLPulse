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
}
