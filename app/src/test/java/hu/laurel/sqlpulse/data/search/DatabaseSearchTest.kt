package hu.laurel.sqlpulse.data.search

import hu.laurel.sqlpulse.data.schema.SchemaColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseSearchTest {

    private fun column(name: String, type: String, pk: Boolean = false) = SchemaColumn(
        name = name, typeName = type, nullable = true, defaultValue = null,
        isPrimaryKey = pk, extra = null, comment = null,
    )

    private val customers = listOf(
        column("id", "int(11)", pk = true),
        column("code", "varchar(20)"),
        column("note", "text"),
        column("kind", "enum('a','b')"),
        column("photo", "longblob"),
        column("meta", "json"),
        column("created", "datetime"),
        column("loc", "point"),
    )

    @Test
    fun `text columns are searched and binary, json, geometry and dates are not`() {
        val plan = DatabaseSearch.plan("shop", "customers", customers, "ab-12", SearchMode.CONTAINS, 50)!!
        assertEquals(listOf("code", "note", "kind"), plan.searchColumns)
        assertEquals(listOf("id"), plan.keyColumns)
    }

    @Test
    fun `number columns join in only for a numeric term`() {
        val text = DatabaseSearch.plan("d", "t", customers, "abc", SearchMode.CONTAINS, 5)!!
        val number = DatabaseSearch.plan("d", "t", customers, "42", SearchMode.CONTAINS, 5)!!
        assertFalse("id" in text.searchColumns)
        assertTrue("id" in number.searchColumns)
    }

    @Test
    fun `statement is parameterised, quoted, and limited`() {
        val plan = DatabaseSearch.plan(
            "my`db", "t", listOf(column("id", "int", pk = true), column("na`me", "varchar(5)")),
            "x", SearchMode.CONTAINS, 25,
        )!!
        assertEquals(
            "SELECT `id`, LEFT(`na``me`, 1000) FROM `my``db`.`t` " +
                "WHERE LOWER(CAST(`na``me` AS CHAR)) LIKE LOWER(?) ESCAPE '!' LIMIT 25",
            plan.sql,
        )
        assertEquals(listOf("%x%"), plan.parameters)
    }

    @Test
    fun `one placeholder per searched column`() {
        val plan = DatabaseSearch.plan("d", "t", customers, "x", SearchMode.CONTAINS, 5)!!
        assertEquals(plan.searchColumns.size, plan.sql.count { it == '?' })
        assertEquals(plan.searchColumns.size, plan.parameters.size)
        assertEquals(2, Regex(" OR ").findAll(plan.sql).count())
    }

    @Test
    fun `like wildcards and the escape character in the term are literal`() {
        assertEquals("50!%!_!!", DatabaseSearch.escapeLike("50%_!"))
        assertEquals("%a!_b%", DatabaseSearch.pattern("a_b", SearchMode.CONTAINS))
        assertEquals("a!%", DatabaseSearch.pattern("a%", SearchMode.EXACT))
    }

    @Test
    fun `a term can never reach the statement text`() {
        val plan = DatabaseSearch.plan("d", "t", customers, "'; DROP TABLE t; --", SearchMode.CONTAINS, 5)!!
        assertFalse(plan.sql.contains("DROP"))
    }

    @Test
    fun `no searchable column or blank term gives no plan`() {
        assertNull(DatabaseSearch.plan("d", "t", listOf(column("b", "blob")), "x", SearchMode.CONTAINS, 5))
        assertNull(DatabaseSearch.plan("d", "t", customers, "   ", SearchMode.CONTAINS, 5))
    }

    @Test
    fun `a table without a primary key selects only the searched cells`() {
        val plan = DatabaseSearch.plan("d", "t", listOf(column("a", "char(3)")), "x", SearchMode.EXACT, 5)!!
        assertTrue(plan.keyColumns.isEmpty())
        assertTrue(plan.sql.startsWith("SELECT LEFT(`a`, 1000) FROM"))
        assertEquals(listOf("x"), plan.parameters)
    }

    @Test
    fun `numeric term detection`() {
        assertTrue(DatabaseSearch.isNumeric("42"))
        assertTrue(DatabaseSearch.isNumeric(" -3.5 "))
        assertFalse(DatabaseSearch.isNumeric("4a"))
        assertFalse(DatabaseSearch.isNumeric(""))
    }

    @Test
    fun `base type ignores length, sign and values`() {
        assertEquals("varchar", DatabaseSearch.baseType("VARCHAR(255)"))
        assertEquals("int", DatabaseSearch.baseType("int(10) unsigned"))
        assertEquals("enum", DatabaseSearch.baseType("enum('a','b')"))
    }

    @Test
    fun `match range follows the mode and ignores case`() {
        assertEquals(4..6, DatabaseSearch.matchRange("The ABC code", "abc", SearchMode.CONTAINS))
        assertNull(DatabaseSearch.matchRange("The ABC code", "abc", SearchMode.EXACT))
        assertNotNull(DatabaseSearch.matchRange("ABC", "abc", SearchMode.EXACT))
        assertNull(DatabaseSearch.matchRange("nothing", "abc", SearchMode.CONTAINS))
    }

    @Test
    fun `snippet keeps the hit visible in a long value`() {
        val value = "a".repeat(200) + "NEEDLE" + "b".repeat(200)
        val range = DatabaseSearch.matchRange(value, "needle", SearchMode.CONTAINS)
        val (text, shown) = DatabaseSearch.snippet(value, range, radius = 10)
        assertEquals("NEEDLE", text.substring(shown!!.first, shown.last + 1))
        assertTrue(text.startsWith("…") && text.endsWith("…"))
    }

    @Test
    fun `short values pass through the snippet unchanged but flattened`() {
        val (text, range) = DatabaseSearch.snippet("a\nb", 0..0)
        assertEquals("a b", text)
        assertEquals(0..0, range)
    }
}
