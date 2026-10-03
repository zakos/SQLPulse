package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.StatementKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lexical rules the shared scanners read SQL with. MySQL's are pinned against the behaviour
 * the scanners had when they were hard-coded; the others show the grammar is what decides, so a
 * phase-2 engine can describe itself instead of copying a scanner.
 */
class SqlGrammarTest {

    private val mysql = SqlGrammar.MYSQL
    private val ansi = SqlGrammar.ANSI

    /** PostgreSQL's shape, as its dialect will describe it. */
    private val dollar = ansi.copy(dollarQuotes = true)

    /** T-SQL's bracketed names. */
    private val brackets = ansi.copy(quotes = ansi.quotes + ('[' to ']'))

    @Test
    fun `MySQL quotes end where they always ended`() {
        val sql = "'it''s' x"
        assertEquals(7, mysql.endOfQuoted(sql, 0))
        // A backslash escapes inside a string…
        assertEquals(6, mysql.endOfQuoted("'a\\'b' x", 0))
        // …but not inside a backticked name.
        assertEquals(4, mysql.endOfQuoted("`a\\` x", 0))
        assertEquals(6, mysql.endOfQuoted("`a``b` x", 0))
        // Unclosed runs to the end.
        assertEquals(4, mysql.endOfQuoted("'abc", 0))
    }

    @Test
    fun `a backslash is an ordinary character in standard SQL`() {
        // 'a\' is a whole literal there; the next quote opens a new one.
        assertEquals(4, ansi.endOfQuoted("'a\\' 'b'", 0))
        assertEquals(StatementKind.READ, SqlGuards.classify("SELECT 'C:\\' FROM t", ansi))
    }

    @Test
    fun `hash starts a comment only in MySQL`() {
        assertTrue(mysql.opensLineComment("# x", 0))
        assertFalse(ansi.opensLineComment("# x", 0))
        assertTrue(ansi.opensLineComment("-- x", 0))
        assertEquals("SELECT a  FROM t", SqlGuards.strip("SELECT a # c\nFROM t", mysql).replace("\n", " "))
        assertEquals("SELECT a # c FROM t", SqlGuards.strip("SELECT a # c\nFROM t", ansi).replace("\n", " "))
    }

    @Test
    fun `dollar quotes hide their contents and positional parameters are not quotes`() {
        val body = "SELECT \$\$ DELETE FROM t; ' \$\$, \$fn\$ x \$fn\$ FROM t WHERE id = \$1"
        assertEquals("SELECT  ,   FROM t WHERE id = \$1", SqlGuards.strip(body, dollar))
        assertFalse(dollar.opensQuote("\$1", 0))
        assertTrue(dollar.opensQuote("\$\$", 0))
        assertFalse("MySQL has no dollar quotes", mysql.opensQuote("\$\$", 0))
        assertEquals(StatementKind.READ, SqlGuards.classify(body, dollar))
    }

    @Test
    fun `bracketed names are skipped like any other quote`() {
        val sql = "SELECT [delete], [a]]b] FROM [t] WHERE x = :x"
        assertEquals(StatementKind.READ, SqlGuards.classify(sql, brackets))
        assertEquals(listOf("x"), SqlGuards.bindParameters(sql, brackets).parameterOrder)
        assertEquals("SELECT [delete], [a]]b] FROM [t] WHERE x = ?", SqlGuards.bindParameters(sql, brackets).sql)
        assertEquals(6, brackets.endOfQuoted("[a]]b] x", 0))
    }

    @Test
    fun `placeholders inside another engine's quotes are left alone`() {
        val sql = "SELECT \$\$:not\$\$, ':no', \"x:y\" FROM t WHERE a = :yes"
        assertEquals(listOf("yes"), SqlGuards.bindParameters(sql, dollar).parameterOrder)
    }

    @Test
    fun `statement starters come from the grammar`() {
        assertEquals(StatementKind.WRITE, SqlGuards.classify("MERGE INTO t USING s ON 1=1", ansi))
        assertEquals(StatementKind.OTHER, SqlGuards.classify("MERGE INTO t USING s ON 1=1", mysql))
        assertEquals(StatementKind.READ, SqlGuards.classify("SHOW TABLES", mysql))
        assertEquals(StatementKind.OTHER, SqlGuards.classify("SHOW TABLES", ansi))
        assertTrue(SqlGuards.isUnguardedWrite("DELETE FROM t", ansi))
    }
}
