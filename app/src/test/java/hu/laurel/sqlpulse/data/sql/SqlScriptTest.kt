package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SqlScriptTest {

    private fun parts(text: String) = SqlScript.split(text).map { it.sql }

    @Test
    fun `statements are separated on semicolons`() {
        assertEquals(
            listOf("SELECT 1", "SELECT 2"),
            parts("SELECT 1; SELECT 2"),
        )
    }

    @Test
    fun `a trailing semicolon does not produce an empty statement`() {
        assertEquals(listOf("SELECT 1"), parts("SELECT 1;"))
        assertEquals(listOf("SELECT 1"), parts("  SELECT 1 ;  \n"))
        assertEquals(emptyList<String>(), parts("   \n  "))
        assertEquals(emptyList<String>(), parts(";;;"))
    }

    @Test
    fun `a semicolon inside a string is not a separator`() {
        assertEquals(
            listOf("UPDATE t SET note = 'a; b'", "SELECT 1"),
            parts("UPDATE t SET note = 'a; b'; SELECT 1"),
        )
        assertEquals(
            listOf("""SELECT "x;y" """.trim()),
            parts("""SELECT "x;y" """),
        )
    }

    @Test
    fun `an escaped quote does not end the string early`() {
        assertEquals(
            listOf("""SELECT 'it''s; fine'""", "SELECT 2"),
            parts("""SELECT 'it''s; fine'; SELECT 2"""),
        )
        assertEquals(
            listOf("""SELECT 'it\'s; fine'""", "SELECT 2"),
            parts("""SELECT 'it\'s; fine'; SELECT 2"""),
        )
    }

    @Test
    fun `a semicolon inside an identifier is not a separator`() {
        assertEquals(
            listOf("SELECT * FROM `odd;name`", "SELECT 2"),
            parts("SELECT * FROM `odd;name`; SELECT 2"),
        )
    }

    @Test
    fun `comments are carried with their statement, semicolons and all`() {
        assertEquals(
            listOf("-- first; still a comment\nSELECT 1", "SELECT 2"),
            parts("-- first; still a comment\nSELECT 1; SELECT 2"),
        )
        assertEquals(
            listOf("/* a; b */ SELECT 1", "SELECT 2"),
            parts("/* a; b */ SELECT 1; SELECT 2"),
        )
        assertEquals(
            listOf("SELECT 1 # trailing; note", "SELECT 2"),
            parts("SELECT 1 # trailing; note\n; SELECT 2"),
        )
    }

    @Test
    fun `a statement that is only a comment is not run`() {
        assertEquals(emptyList<String>(), parts("-- nothing to do here"))
        assertEquals(listOf("SELECT 1"), parts("/* note */;SELECT 1"))
    }

    @Test
    fun `an unterminated string does not lose the rest of the text`() {
        // Broken SQL is the server's to complain about, but it must still arrive whole.
        assertEquals(listOf("SELECT 'unfinished; still here"), parts("SELECT 'unfinished; still here"))
    }

    @Test
    fun `the statement under the cursor is the one that gets run`() {
        val text = "SELECT 1;\nSELECT 2;\nSELECT 3;"
        assertEquals("SELECT 1", SqlScript.statementAt(text, 3)?.sql)
        assertEquals("SELECT 2", SqlScript.statementAt(text, 12)?.sql)
        assertEquals("SELECT 3", SqlScript.statementAt(text, text.length)?.sql)
    }

    @Test
    fun `a cursor in empty text has no statement`() {
        assertNull(SqlScript.statementAt("   ", 1))
    }

    @Test
    fun `positions point back into the original text`() {
        val text = "SELECT 1;\nSELECT 2"
        val second = SqlScript.split(text)[1]
        assertEquals("SELECT 2", text.substring(second.start, second.end))
    }
}
