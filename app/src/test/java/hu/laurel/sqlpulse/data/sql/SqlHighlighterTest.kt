package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlHighlighterTest {

    private fun rolesOf(sql: String) = SqlHighlighter.tokenize(sql).map { it.role }

    private fun textOf(sql: String, role: TokenRole) =
        SqlHighlighter.tokenize(sql).filter { it.role == role }.map { sql.substring(it.start, it.end) }

    @Test
    fun `keywords are found regardless of case`() {
        assertEquals(listOf("SELECT", "from"), textOf("SELECT a from t", TokenRole.KEYWORD))
    }

    @Test
    fun `a word merely containing a keyword is not highlighted`() {
        assertEquals(emptyList<String>(), textOf("SELECTED_ITEMS", TokenRole.KEYWORD))
        assertEquals(emptyList<String>(), textOf("my_from_column", TokenRole.KEYWORD))
    }

    @Test
    fun `string literals are one token including their quotes`() {
        assertEquals(listOf("'from where'"), textOf("SELECT 'from where'", TokenRole.STRING))
    }

    @Test
    fun `keywords inside a string are not highlighted separately`() {
        val sql = "SELECT 'select from'"

        assertEquals(listOf("SELECT"), textOf(sql, TokenRole.KEYWORD))
    }

    @Test
    fun `backticked identifiers get their own role`() {
        assertEquals(listOf("`order`"), textOf("SELECT `order` FROM t", TokenRole.QUOTED_IDENTIFIER))
    }

    @Test
    fun `comments run to the end of the line`() {
        assertEquals(listOf("-- select all"), textOf("SELECT 1 -- select all\nFROM t", TokenRole.COMMENT))
    }

    @Test
    fun `block comments are a single token`() {
        assertEquals(listOf("/* note */"), textOf("SELECT /* note */ 1", TokenRole.COMMENT))
    }

    @Test
    fun `numbers are highlighted but not inside identifiers`() {
        assertEquals(listOf("42"), textOf("SELECT 42 FROM t1", TokenRole.NUMBER))
    }

    @Test
    fun `named parameters are highlighted and casts are not`() {
        assertEquals(listOf(":since"), textOf("SELECT * FROM t WHERE a > :since", TokenRole.PARAMETER))
        assertEquals(emptyList<String>(), textOf("SELECT a::text FROM t", TokenRole.PARAMETER))
    }

    @Test
    fun `tokens come out in order and never overlap`() {
        val sql = "SELECT `id`, 'x', 12 /* c */ FROM t WHERE a = :p"
        val tokens = SqlHighlighter.tokenize(sql)

        tokens.zipWithNext().forEach { (first, second) ->
            assertTrue("tokens overlap: $first, $second", first.end <= second.start)
        }
        tokens.forEach { assertTrue(it.start in 0..sql.length && it.end <= sql.length) }
    }

    @Test
    fun `an unterminated literal does not run past the end of the text`() {
        val sql = "SELECT 'unterminated"

        val tokens = SqlHighlighter.tokenize(sql)

        assertTrue(tokens.all { it.end <= sql.length })
        assertEquals(listOf(TokenRole.KEYWORD, TokenRole.STRING), rolesOf(sql))
    }
}
