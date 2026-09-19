package hu.laurel.sqlpulse.ui.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cursor is written as `|` in every case below; [at] splits the text at it. Nothing here
 * touches Android, a schema or a network — the question is only ever "what belongs here".
 */
class SqlCompletionTest {

    private fun at(marked: String): CompletionContext {
        val cursor = marked.indexOf('|')
        require(cursor >= 0) { "mark the cursor with |" }
        return SqlCompletion.contextAt(marked.replace("|", ""), cursor)
    }

    // --- Which clause the cursor is in ------------------------------------------------------

    @Test
    fun `start of a statement offers the statement keywords`() {
        val context = at("|")
        assertEquals(SqlClause.NONE, context.clause)
        assertTrue("SELECT" in context.keywords)
        assertFalse(context.wantTables)
    }

    @Test
    fun `after FROM a table is expected`() {
        val context = at("SELECT * FROM |")
        assertEquals(SqlClause.FROM, context.clause)
        assertTrue(context.wantTables)
        assertTrue(context.columnTables.isEmpty())
    }

    @Test
    fun `after JOIN a table is expected`() {
        val context = at("SELECT * FROM hivasok h LEFT JOIN |")
        assertEquals(SqlClause.JOIN, context.clause)
        assertTrue(context.wantTables)
    }

    @Test
    fun `after a comma in FROM a table is still expected`() {
        val context = at("SELECT * FROM hivasok, |")
        assertTrue(context.wantTables)
    }

    @Test
    fun `after the table name itself only keywords are offered`() {
        val context = at("SELECT * FROM hivasok |")
        assertEquals(SqlClause.FROM, context.clause)
        assertFalse(context.wantTables)
        assertTrue("WHERE" in context.keywords)
        assertTrue("LEFT JOIN" in context.keywords)
    }

    @Test
    fun `SELECT list offers the columns of the table named later`() {
        val context = at("SELECT | FROM hivasok")
        assertEquals(SqlClause.SELECT, context.clause)
        assertEquals(listOf("hivasok"), context.columnTables)
        assertFalse(context.wantTables)
    }

    @Test
    fun `WHERE offers columns`() {
        val context = at("SELECT * FROM hivasok WHERE |")
        assertEquals(SqlClause.WHERE, context.clause)
        assertEquals(listOf("hivasok"), context.columnTables)
        assertTrue("ORDER BY" in context.keywords)
    }

    @Test
    fun `ORDER BY offers columns`() {
        val context = at("SELECT * FROM hivasok ORDER BY |")
        assertEquals(SqlClause.ORDER_BY, context.clause)
        assertEquals(listOf("hivasok"), context.columnTables)
        assertTrue("DESC" in context.keywords)
    }

    @Test
    fun `GROUP BY offers columns`() {
        val context = at("SELECT * FROM hivasok GROUP BY |")
        assertEquals(SqlClause.GROUP_BY, context.clause)
        assertEquals(listOf("hivasok"), context.columnTables)
        assertTrue("HAVING" in context.keywords)
    }

    @Test
    fun `a half-typed clause keyword is not read as the clause itself`() {
        // The word under the cursor is what is being typed, not a word of the statement.
        val context = at("SELECT * FROM hivasok WHE|")
        assertEquals(SqlClause.FROM, context.clause)
        assertEquals("WHE", context.prefix)
    }

    @Test
    fun `UPDATE expects a table and its SET expects columns`() {
        assertTrue(at("UPDATE |").wantTables)
        val set = at("UPDATE hivasok SET |")
        assertEquals(SqlClause.SET, set.clause)
        assertEquals(listOf("hivasok"), set.columnTables)
    }

    @Test
    fun `INSERT INTO expects a table`() {
        val context = at("INSERT INTO |")
        assertEquals(SqlClause.INTO, context.clause)
        assertTrue(context.wantTables)
    }

    // --- Prefixes and qualifiers ------------------------------------------------------------

    @Test
    fun `the half-typed word is the prefix and knows where it starts`() {
        val context = at("SELECT * FROM hiv|")
        assertEquals("hiv", context.prefix)
        assertEquals(14, context.prefixStart)
        assertTrue(context.wantTables)
    }

    @Test
    fun `a dot after an alias asks for that table's columns only`() {
        val context = at("SELECT h.| FROM hivasok h")
        assertEquals("h", context.qualifier)
        assertEquals(listOf("hivasok"), context.columnTables)
        assertTrue(context.keywords.isEmpty())
    }

    @Test
    fun `an alias introduced with AS resolves the same way`() {
        val context = at("SELECT * FROM hivasok AS h WHERE h.|")
        assertEquals("h", context.qualifier)
        assertEquals(listOf("hivasok"), context.columnTables)
    }

    @Test
    fun `a qualified prefix replaces only the part after the dot`() {
        val text = "SELECT h.ne FROM hivasok h"
        val context = SqlCompletion.contextAt(text, "SELECT h.ne".length)
        assertEquals("ne", context.prefix)
        assertEquals("SELECT h.".length, context.prefixStart)
    }

    @Test
    fun `the table's own name works as a qualifier`() {
        val context = at("SELECT * FROM hivasok WHERE hivasok.|")
        assertEquals(listOf("hivasok"), context.columnTables)
    }

    @Test
    fun `an unknown qualifier is taken to be a table name`() {
        val context = at("SELECT munkalapok.| FROM hivasok")
        assertEquals(listOf("munkalapok"), context.columnTables)
    }

    @Test
    fun `a backtick-quoted table arrives unquoted`() {
        val context = at("SELECT o.| FROM `order` o")
        assertEquals(listOf("order"), context.columnTables)
    }

    @Test
    fun `a database-qualified table keeps only the table part`() {
        val context = at("SELECT * FROM luna.hivasok h WHERE |")
        assertEquals(listOf("hivasok"), context.columnTables)
        assertEquals(TableRef("hivasok", "h"), context.tables.single())
    }

    @Test
    fun `every joined table is in scope`() {
        val context = at("SELECT * FROM hivasok h JOIN munkalapok m ON h.id = m.hivas_id WHERE |")
        assertEquals(listOf("hivasok", "munkalapok"), context.columnTables)
    }

    // --- Strings and comments ---------------------------------------------------------------

    @Test
    fun `inside a string nothing is offered`() {
        val context = at("SELECT * FROM hivasok WHERE nev = 'Kov|acs'")
        assertTrue(context.suppressed)
        assertTrue(context.keywords.isEmpty())
        assertTrue(context.columnTables.isEmpty())
        assertFalse(context.wantTables)
    }

    @Test
    fun `after the string has closed suggestions come back`() {
        val context = at("SELECT * FROM hivasok WHERE nev = 'Kovacs' |")
        assertFalse(context.suppressed)
        assertEquals(SqlClause.WHERE, context.clause)
    }

    @Test
    fun `an escaped quote does not end the string early`() {
        val context = at("SELECT * FROM t WHERE a = 'it\\'s |'")
        assertTrue(context.suppressed)
    }

    @Test
    fun `inside a line comment nothing is offered`() {
        val context = at("SELECT * FROM hivasok -- a mai |")
        assertTrue(context.suppressed)
    }

    @Test
    fun `inside a hash comment nothing is offered`() {
        val context = at("SELECT * FROM hivasok # a mai |")
        assertTrue(context.suppressed)
    }

    @Test
    fun `inside a block comment nothing is offered`() {
        val context = at("SELECT /* nem ez kell |*/ FROM hivasok")
        assertTrue(context.suppressed)
    }

    @Test
    fun `after a block comment has closed suggestions come back`() {
        val context = at("SELECT /* jegyzet */ | FROM hivasok")
        assertFalse(context.suppressed)
        assertEquals(SqlClause.SELECT, context.clause)
    }

    @Test
    fun `a line comment ends at the newline`() {
        val context = at("SELECT * -- jegyzet\nFROM |")
        assertFalse(context.suppressed)
        assertTrue(context.wantTables)
    }

    // --- Scripts ----------------------------------------------------------------------------

    @Test
    fun `in a script the statement the cursor is in decides`() {
        val context = at("SELECT * FROM hivasok;\nSELECT * FROM munkalapok WHERE |")
        assertEquals(SqlClause.WHERE, context.clause)
        assertEquals(listOf("munkalapok"), context.columnTables)
    }

    @Test
    fun `the earlier statement is read for a cursor in the earlier statement`() {
        val context = at("SELECT * FROM hivasok WHERE |;\nSELECT * FROM munkalapok")
        assertEquals(listOf("hivasok"), context.columnTables)
    }

    @Test
    fun `a semicolon inside a string does not split the script`() {
        val context = at("SELECT * FROM hivasok WHERE nev = 'a;b' AND |")
        assertEquals(SqlClause.WHERE, context.clause)
        assertEquals(listOf("hivasok"), context.columnTables)
    }

    @Test
    fun `just past a semicolon a new statement begins`() {
        val context = at("SELECT * FROM hivasok;|")
        assertEquals(SqlClause.NONE, context.clause)
        assertTrue(context.tables.isEmpty())
        assertTrue("SELECT" in context.keywords)
    }

    @Test
    fun `an unfinished statement is read as far as it goes`() {
        val context = at("SELECT nev, ido FROM |")
        assertEquals(SqlClause.FROM, context.clause)
        assertTrue(context.wantTables)
    }

    @Test
    fun `an unfinished join still has its first table in scope`() {
        val context = at("SELECT * FROM hivasok h JOIN munkalapok m ON |")
        assertEquals(SqlClause.ON, context.clause)
        assertEquals(listOf("hivasok", "munkalapok"), context.columnTables)
    }

    @Test
    fun `a cursor past the end of the text is clamped`() {
        val context = SqlCompletion.contextAt("SELECT * FROM t", 9_999)
        assertEquals(SqlClause.FROM, context.clause)
    }

    @Test
    fun `empty text is not a crash`() {
        val context = SqlCompletion.contextAt("", 0)
        assertEquals(SqlClause.NONE, context.clause)
        assertEquals("", context.prefix)
    }

    @Test
    fun `a quoted word that spells a keyword is a name, not a clause`() {
        val context = at("SELECT * FROM `order` WHERE |")
        assertEquals(SqlClause.WHERE, context.clause)
        assertEquals(listOf("order"), context.columnTables)
    }

    @Test
    fun `UNION starts the reading again`() {
        val context = at("SELECT a FROM t1 UNION SELECT | FROM t2")
        assertEquals(SqlClause.SELECT, context.clause)
    }
}
