package hu.laurel.sqlpulse.ui.query

import hu.laurel.sqlpulse.data.sql.ParameterValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryTabsTest {

    private fun tabs(count: Int): List<QueryTab> = (1..count).map { QueryTab(id = it.toLong()) }

    // --- Opening ----------------------------------------------------------------------------

    @Test
    fun `opening adds a tab at the end`() {
        val opened = QueryTabs.open(tabs(2), id = 9, sql = "SELECT 1")
        assertNotNull(opened)
        assertEquals(listOf(1L, 2L, 9L), opened!!.map { it.id })
        assertEquals("SELECT 1", opened.last().sql)
        // The cursor lands after the text it was given, not at the start of it.
        assertEquals("SELECT 1".length, opened.last().selectionStart)
    }

    @Test
    fun `opening past the limit is refused rather than silently dropped`() {
        val full = tabs(QueryTabs.MAX_TABS)
        assertNull(QueryTabs.open(full, id = 99))
        // One below the limit still works, so the limit is the limit and not one less.
        assertNotNull(QueryTabs.open(tabs(QueryTabs.MAX_TABS - 1), id = 99))
    }

    // --- Duplicating ------------------------------------------------------------------------

    @Test
    fun `duplicating copies the text next to the original`() {
        val source = listOf(
            QueryTab(id = 1, sql = "SELECT 1", database = "luna", title = "elso"),
            QueryTab(id = 2),
        )
        val copied = QueryTabs.duplicate(source, id = 1, newId = 7)!!
        assertEquals(listOf(1L, 7L, 2L), copied.map { it.id })
        val copy = copied[1]
        assertEquals("SELECT 1", copy.sql)
        assertEquals("luna", copy.database)
        assertEquals("elso", copy.title)
    }

    @Test
    fun `a duplicate carries the parameters but not the result`() {
        val source = listOf(
            QueryTab(
                id = 1,
                sql = "SELECT :nev",
                parameters = mapOf("nev" to ParameterValue("Kovacs")),
                statements = listOf(StatementRun(sql = "SELECT 1", updateCount = 3)),
                updateCount = 3,
            ),
        )
        val copy = QueryTabs.duplicate(source, id = 1, newId = 2)!![1]
        assertEquals(ParameterValue("Kovacs"), copy.parameters["nev"])
        assertTrue(copy.statements.isEmpty())
        assertNull(copy.updateCount)
        assertNull(copy.result)
    }

    @Test
    fun `duplicating past the limit or an unknown tab is refused`() {
        assertNull(QueryTabs.duplicate(tabs(QueryTabs.MAX_TABS), id = 1, newId = 99))
        assertNull(QueryTabs.duplicate(tabs(2), id = 404, newId = 99))
    }

    // --- Closing ----------------------------------------------------------------------------

    @Test
    fun `closing removes the tab`() {
        assertEquals(listOf(1L, 3L), QueryTabs.close(tabs(3), id = 2).map { it.id })
    }

    @Test
    fun `closing an unknown tab changes nothing`() {
        val before = tabs(3)
        assertEquals(before, QueryTabs.close(before, id = 404))
    }

    @Test
    fun `closing the only tab empties it rather than leaving no editor`() {
        val only = listOf(QueryTab(id = 5, sql = "SELECT 1", title = "elso"))
        val closed = QueryTabs.close(only, id = 5)
        assertEquals(1, closed.size)
        assertEquals(5L, closed.single().id)
        assertEquals("", closed.single().sql)
        assertNull(closed.single().title)
    }

    @Test
    fun `after closing the active tab the one to its right takes over`() {
        assertEquals(3L, QueryTabs.activeAfterClose(tabs(3), closed = 2, active = 2))
    }

    @Test
    fun `closing the last tab falls back to the one on its left`() {
        assertEquals(2L, QueryTabs.activeAfterClose(tabs(3), closed = 3, active = 3))
    }

    @Test
    fun `closing a tab you are not in leaves you where you are`() {
        assertEquals(1L, QueryTabs.activeAfterClose(tabs(3), closed = 3, active = 1))
    }

    // --- Renaming ---------------------------------------------------------------------------

    @Test
    fun `renaming sets the name and a blank name clears it`() {
        val named = QueryTabs.rename(tabs(2), id = 2, title = "  havi riport  ")
        assertEquals("havi riport", named[1].title)
        assertNull(QueryTabs.rename(named, id = 2, title = "   ")[1].title)
        // Only the named tab moves.
        assertNull(named[0].title)
    }

    // --- Labels -----------------------------------------------------------------------------

    @Test
    fun `the label is the name when there is one`() {
        assertEquals("riport", QueryTabs.label(QueryTab(id = 1, title = "riport", sql = "SELECT 1")))
    }

    @Test
    fun `without a name the label is the first line of SQL`() {
        assertEquals(
            "SELECT * FROM hivasok",
            QueryTabs.label(QueryTab(id = 1, sql = "\n\n  SELECT *   FROM hivasok\nWHERE a = 1")),
        )
    }

    @Test
    fun `an empty tab has no label of its own`() {
        assertNull(QueryTabs.label(QueryTab(id = 1, sql = "   \n  ")))
    }

    @Test
    fun `a long first line is elided for the chip only`() {
        val sql = "SELECT nev, ido, hossz, megjegyzes FROM hivasok"
        val label = QueryTabs.label(QueryTab(id = 1, sql = sql))!!
        assertEquals(QueryTabs.LABEL_LENGTH, label.length)
        assertTrue(label.endsWith("…"))
        // The text itself is untouched: only what is drawn on the chip was shortened.
        assertEquals(sql, QueryTab(id = 1, sql = sql).sql)
    }

    // --- The unsaved mark -------------------------------------------------------------------

    @Test
    fun `text that was never saved is marked`() {
        assertTrue(QueryTab(id = 1, sql = "SELECT 1").unsaved)
    }

    @Test
    fun `an empty tab is not marked`() {
        assertFalse(QueryTab(id = 1, sql = "   ").unsaved)
    }

    @Test
    fun `saved text is not marked until it is changed again`() {
        val saved = QueryTab(id = 1, sql = "SELECT 1", savedSql = "SELECT 1")
        assertFalse(saved.unsaved)
        assertFalse(saved.copy(sql = "  SELECT 1  ").unsaved)
        assertTrue(saved.copy(sql = "SELECT 2").unsaved)
    }

    // --- Replacing --------------------------------------------------------------------------

    @Test
    fun `replace touches one tab and leaves the others alone`() {
        val changed = QueryTabs.replace(tabs(3), id = 2) { it.copy(sql = "SELECT 2") }
        assertEquals(listOf("", "SELECT 2", ""), changed.map { it.sql })
    }
}
