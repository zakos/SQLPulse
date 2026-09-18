package hu.laurel.sqlpulse.data.csv

import hu.laurel.sqlpulse.data.schema.SchemaColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvImportTest {

    private fun column(
        name: String,
        nullable: Boolean = true,
        default: String? = null,
        extra: String? = null,
    ) = SchemaColumn(
        name = name,
        typeName = "VARCHAR(50)",
        nullable = nullable,
        defaultValue = default,
        isPrimaryKey = false,
        extra = extra,
        comment = null,
    )

    private val orders = listOf(
        column("id", nullable = false, extra = "auto_increment"),
        column("customer", nullable = false),
        column("note"),
    )

    @Test
    fun `columns are matched by name, whatever their case or order`() {
        val match = CsvImport.match(listOf("NOTE", " customer "), orders)
        assertEquals(mapOf("NOTE" to "note", " customer " to "customer"), match.matched)
        assertTrue(match.canImport)
    }

    @Test
    fun `a column the table does not have is named rather than imported`() {
        val match = CsvImport.match(listOf("customer", "discount"), orders)
        assertEquals(listOf("discount"), match.unmatched)
        assertEquals(mapOf("customer" to "customer"), match.matched)
    }

    @Test
    fun `a column the table fills itself may be left out`() {
        // id is auto_increment and note is nullable: neither needs to be in the file.
        val match = CsvImport.match(listOf("customer"), orders)
        assertEquals(listOf("id", "note"), match.missing)
        assertEquals(emptyList<String>(), match.blocking)
        assertTrue(match.canImport)
    }

    @Test
    fun `a NOT NULL column with no default blocks the import`() {
        val match = CsvImport.match(listOf("note"), orders)
        assertEquals(listOf("customer"), match.blocking)
        assertFalse(match.canImport)
    }

    @Test
    fun `a file with nothing in common with the table cannot be imported`() {
        assertFalse(CsvImport.match(listOf("a", "b"), orders).canImport)
    }

    @Test
    fun `one INSERT per row, values bound in the table's own column order`() {
        val header = listOf("note", "customer")
        val match = CsvImport.match(header, orders)
        val statements = CsvImport.statements(
            database = "shop",
            table = "orders",
            match = match,
            header = header,
            rows = listOf(listOf("first", "Anna"), listOf(null, "Béla")),
        )
        assertEquals(2, statements.size)
        assertEquals(
            "INSERT INTO `shop`.`orders` (`note`, `customer`) VALUES (?, ?)",
            statements[0].sql,
        )
        assertEquals(listOf("first", "Anna"), statements[0].parameters)
        assertEquals(listOf(null, "Béla"), statements[1].parameters)
    }

    @Test
    fun `a row that is empty from end to end is skipped`() {
        val header = listOf("customer", "note")
        val match = CsvImport.match(header, orders)
        val statements = CsvImport.statements(
            "shop",
            "orders",
            match,
            header,
            listOf(listOf(null, null), listOf("Anna", null)),
        )
        assertEquals(1, statements.size)
    }
}
