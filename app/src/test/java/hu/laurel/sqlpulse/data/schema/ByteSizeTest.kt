package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ByteSizeTest {

    @Test
    fun `small sizes stay in bytes`() {
        assertEquals("0 B", formatByteSize(0))
        assertEquals("1023 B", formatByteSize(1023))
    }

    @Test
    fun `each step up uses the next unit`() {
        assertEquals("1 KB", formatByteSize(1024))
        assertEquals("1 MB", formatByteSize(1024L * 1024))
        assertEquals("1 GB", formatByteSize(1024L * 1024 * 1024))
        assertEquals("1 TB", formatByteSize(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `one decimal, and none once the number is large enough to carry itself`() {
        assertEquals("1.5 KB", formatByteSize(1536))
        assertEquals("2.5 MB", formatByteSize((2.5 * 1024 * 1024).toLong()))
        assertEquals("512 MB", formatByteSize(512L * 1024 * 1024))
    }

    @Test
    fun `a table with neither data nor index size reports nothing`() {
        val view = SchemaTable(
            database = "shop",
            name = "v_orders",
            kind = TableKind.VIEW,
            approximateRows = null,
            comment = null,
        )
        assertNull(view.totalBytes)
    }

    @Test
    fun `data and index size are added up`() {
        val table = SchemaTable(
            database = "shop",
            name = "orders",
            kind = TableKind.TABLE,
            approximateRows = 1000,
            comment = null,
            engine = "InnoDB",
            dataBytes = 1024,
            indexBytes = 512,
        )
        assertEquals(1536L, table.totalBytes)
        assertEquals("1.5 KB", formatByteSize(table.totalBytes!!))
    }
}
