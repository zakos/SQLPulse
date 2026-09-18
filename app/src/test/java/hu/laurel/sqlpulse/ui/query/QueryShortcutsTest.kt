package hu.laurel.sqlpulse.ui.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueryShortcutsTest {

    @Test
    fun `ctrl enter runs, and with shift only the statement the cursor is in`() {
        assertEquals(QueryShortcut.RUN, QueryShortcuts.of(KeyPress("Enter", ctrl = true)))
        assertEquals(
            QueryShortcut.RUN_CURRENT,
            QueryShortcuts.of(KeyPress("Enter", ctrl = true, shift = true)),
        )
    }

    @Test
    fun `find and format share a letter, told apart by shift`() {
        assertEquals(QueryShortcut.FIND, QueryShortcuts.of(KeyPress("F", ctrl = true)))
        assertEquals(
            QueryShortcut.FORMAT,
            QueryShortcuts.of(KeyPress("F", ctrl = true, shift = true)),
        )
    }

    @Test
    fun `ctrl s saves the query as a favourite`() {
        assertEquals(QueryShortcut.SAVE_FAVOURITE, QueryShortcuts.of(KeyPress("s", ctrl = true)))
    }

    @Test
    fun `escape needs no modifier`() {
        assertEquals(QueryShortcut.DISMISS, QueryShortcuts.of(KeyPress("Escape")))
    }

    @Test
    fun `a letter on its own is text being typed, not a shortcut`() {
        assertNull(QueryShortcuts.of(KeyPress("F")))
        assertNull(QueryShortcuts.of(KeyPress("s")))
        assertNull(QueryShortcuts.of(KeyPress("Enter")))
    }

    @Test
    fun `alt is left to the system and to other layouts`() {
        // On several keyboard layouts Alt Gr arrives as Alt, and it is how a Hungarian layout
        // types characters that belong in SQL.
        assertNull(QueryShortcuts.of(KeyPress("F", ctrl = true, alt = true)))
        assertNull(QueryShortcuts.of(KeyPress("Escape", alt = true)))
    }

    @Test
    fun `an unclaimed combination stays unclaimed`() {
        assertNull(QueryShortcuts.of(KeyPress("Q", ctrl = true)))
        assertNull(QueryShortcuts.of(KeyPress("S", ctrl = true, shift = true)))
    }
}
