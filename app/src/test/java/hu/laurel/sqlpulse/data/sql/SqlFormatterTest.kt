package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlFormatterTest {

    @Test
    fun `each clause gets a line`() {
        assertEquals(
            """
            SELECT id,
                name
            FROM orders
            WHERE paid = 1
            ORDER BY id
            LIMIT 100
            """.trimIndent(),
            SqlFormatter.format("SELECT id, name FROM orders WHERE paid = 1 ORDER BY id LIMIT 100"),
        )
    }

    @Test
    fun `a two word clause stays on one line`() {
        val formatted = SqlFormatter.format("SELECT a FROM t GROUP BY a ORDER BY a")
        assertTrue(formatted, formatted.contains("GROUP BY a"))
        assertTrue(formatted, formatted.contains("ORDER BY a"))
    }

    @Test
    fun `a join breaks before its qualifier, not between the words`() {
        assertEquals(
            """
            SELECT o.id
            FROM orders o
            LEFT JOIN customers c ON c.id = o.customer_id
            """.trimIndent(),
            SqlFormatter.format("SELECT o.id FROM orders o LEFT JOIN customers c ON c.id = o.customer_id"),
        )
    }

    @Test
    fun `a string is never touched, whatever it contains`() {
        val sql = "SELECT * FROM t WHERE note = 'select from where'"
        assertTrue(SqlFormatter.format(sql).contains("'select from where'"))
    }

    @Test
    fun `an identifier that happens to be a keyword keeps its backticks`() {
        val formatted = SqlFormatter.format("SELECT `order` FROM `from`")
        assertTrue(formatted, formatted.contains("`order`"))
        assertTrue(formatted, formatted.contains("`from`"))
    }

    @Test
    fun `a comment keeps its own line so it swallows nothing`() {
        val formatted = SqlFormatter.format("SELECT 1 -- a note\nFROM t")
        assertEquals(
            """
            SELECT 1
            -- a note
            FROM t
            """.trimIndent(),
            formatted,
        )
    }

    @Test
    fun `a subquery is indented`() {
        val formatted = SqlFormatter.format("SELECT * FROM (SELECT id FROM t) x")
        assertTrue(formatted, formatted.contains("\n    SELECT id"))
    }

    @Test
    fun `a function call does not become a subquery`() {
        val formatted = SqlFormatter.format("SELECT count(*) FROM t")
        assertEquals("SELECT count(*)\nFROM t", formatted)
    }

    @Test
    fun `keyword case is left to the author`() {
        assertTrue(SqlFormatter.format("select a from t").startsWith("select a"))
    }

    @Test
    fun `formatting twice changes nothing the second time`() {
        val once = SqlFormatter.format("SELECT id, name FROM orders WHERE paid = 1")
        assertEquals(once, SqlFormatter.format(once))
    }

    @Test
    fun `empty text stays empty`() {
        assertEquals("", SqlFormatter.format(""))
        // Whitespace-only text has nothing to lay out, so it comes back empty rather than padded.
        assertEquals("", SqlFormatter.format("   "))
    }
}
