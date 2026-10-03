package hu.laurel.sqlpulse.ui.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    private fun history(text: String = "") = EditHistory(EditorText(text))

    /** Types [chars] one at a time, [stepMs] apart, starting at [startMs]; returns the end time. */
    private fun EditHistory.type(chars: String, startMs: Long = 0, stepMs: Long = 100): Long {
        var now = startMs
        for (c in chars) {
            val before = current
            val text = before.text.substring(0, before.selectionStart) + c + before.text.substring(before.selectionEnd)
            val caret = before.selectionStart + 1
            record(EditorText(text, caret), EditKind.TYPING, now)
            now += stepMs
        }
        return now
    }

    @Test
    fun `a fresh history has nothing to undo or redo`() {
        val h = history("abc")
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertNull(h.undo())
        assertNull(h.redo())
    }

    @Test
    fun `typing one word is one step`() {
        val h = history()
        h.type("select")
        assertEquals(1, h.undoDepth)
        assertEquals("", h.undo()?.text)
        assertFalse(h.canUndo)
    }

    @Test
    fun `a space ends the word, so the next word is a step of its own`() {
        val h = history()
        h.type("select from")
        // "select " and "from"
        assertEquals(2, h.undoDepth)
        assertEquals("select ", h.undo()?.text)
        assertEquals("", h.undo()?.text)
    }

    @Test
    fun `a pause longer than the gap starts a new step`() {
        val h = history()
        val end = h.type("ab")
        h.type("cd", startMs = end + EditHistory.TYPING_GAP_MS + 1)
        assertEquals(2, h.undoDepth)
        assertEquals("ab", h.undo()?.text)
    }

    @Test
    fun `exactly the gap still merges`() {
        val h = history()
        h.type("a", startMs = 0)
        h.type("b", startMs = EditHistory.TYPING_GAP_MS)
        assertEquals(1, h.undoDepth)
    }

    @Test
    fun `redo brings back what undo took, with the selection`() {
        val h = history()
        h.type("abc")
        val undone = h.undo()
        assertEquals(EditorText("", 0, 0), undone)
        val redone = h.redo()
        assertEquals(EditorText("abc", 3, 3), redone)
        assertFalse(h.canRedo)
        assertTrue(h.canUndo)
    }

    @Test
    fun `a new edit after an undo discards the redo future`() {
        val h = history()
        h.type("abc")
        h.undo()
        assertTrue(h.canRedo)
        h.type("x", startMs = 10_000)
        assertFalse(h.canRedo)
        assertNull(h.redo())
        assertEquals("", h.undo()?.text)
    }

    @Test
    fun `typing after an undo is not merged into the undone run`() {
        val h = history()
        h.type("abc")
        h.undo()
        h.type("x", startMs = 50)
        h.type("y", startMs = 100)
        assertEquals(1, h.undoDepth)
        assertEquals("", h.undo()?.text)
    }

    @Test
    fun `paste is a step of its own and splits typing around it`() {
        val h = history()
        h.type("ab")
        h.record(EditorText("abXYZ", 5), EditKind.PASTE, 300)
        h.type("c", startMs = 400)
        assertEquals(3, h.undoDepth)
        assertEquals("abXYZ", h.undo()?.text)
        assertEquals("ab", h.undo()?.text)
        assertEquals("", h.undo()?.text)
    }

    @Test
    fun `two pastes in a row stay two steps`() {
        val h = history()
        h.record(EditorText("one", 3), EditKind.PASTE, 0)
        h.record(EditorText("onetwo", 6), EditKind.PASTE, 10)
        assertEquals(2, h.undoDepth)
    }

    @Test
    fun `key insert, snippet, format and completion are each their own step`() {
        for (kind in listOf(EditKind.KEY_INSERT, EditKind.SNIPPET, EditKind.FORMAT, EditKind.COMPLETION, EditKind.OTHER)) {
            val h = history()
            h.record(EditorText("a", 1), kind, 0)
            h.record(EditorText("ab", 2), kind, 10)
            assertEquals("$kind", 2, h.undoDepth)
        }
    }

    @Test
    fun `undoing a format gives back the exact text and selection from before it`() {
        val h = history()
        h.record(EditorText("select 1", 8), EditKind.PASTE, 0)
        h.record(EditorText("SELECT\n  1", 3, 6), EditKind.FORMAT, 10)
        assertEquals(EditorText("select 1", 8, 8), h.undo())
        assertEquals(EditorText("SELECT\n  1", 3, 6), h.redo())
    }

    @Test
    fun `backspacing a run is one step`() {
        val h = history("hello")
        var now = 0L
        var text = "hello"
        repeat(3) {
            text = text.dropLast(1)
            h.record(EditorText(text, text.length), EditKind.DELETING, now)
            now += 100
        }
        assertEquals(1, h.undoDepth)
        assertEquals(EditorText("hello", 5, 5), h.undo())
    }

    @Test
    fun `deleting somewhere else is a new step`() {
        val h = history("hello world")
        h.record(EditorText("hello worl", 10), EditKind.DELETING, 0)
        // The cursor jumps to the start and deletes there.
        h.record(EditorText("hello worl", 1), EditKind.DELETING, 50)
        h.record(EditorText("ello worl", 0), EditKind.DELETING, 100)
        assertEquals(2, h.undoDepth)
    }

    @Test
    fun `typing and deleting do not merge with each other`() {
        val h = history()
        h.type("ab")
        h.record(EditorText("a", 1), EditKind.DELETING, 300)
        assertEquals(2, h.undoDepth)
    }

    @Test
    fun `typing over a selection is its own step`() {
        val h = history()
        h.type("abc")
        h.record(EditorText("abc", 0, 3), EditKind.OTHER, 400)
        h.record(EditorText("x", 1), EditKind.TYPING, 450)
        assertEquals(2, h.undoDepth)
        assertEquals(EditorText("abc", 0, 3), h.undo())
    }

    @Test
    fun `moving the cursor is not an edit but ends the typing run`() {
        val h = history()
        h.type("ab")
        h.record(EditorText("ab", 0), EditKind.TYPING, 250)
        assertEquals(1, h.undoDepth)
        // Typing at the new place is a different step.
        h.record(EditorText("xab", 1), EditKind.TYPING, 300)
        assertEquals(2, h.undoDepth)
        // And what undo goes back to remembers where the cursor was.
        assertEquals(EditorText("ab", 0, 0), h.undo())
    }

    @Test
    fun `recording the same text again changes nothing but the selection`() {
        val h = history("abc")
        h.record(EditorText("abc", 1, 2), EditKind.OTHER, 0)
        assertFalse(h.canUndo)
        assertEquals(EditorText("abc", 1, 2), h.current)
    }

    @Test
    fun `steps are capped, dropping the oldest`() {
        val h = EditHistory(EditorText(""), maxSteps = 3)
        for (i in 1..10) h.record(EditorText("t".repeat(i)), EditKind.PASTE, i * 10L)
        assertEquals(3, h.undoDepth)
        assertEquals("t".repeat(9), h.undo()?.text)
        assertEquals("t".repeat(8), h.undo()?.text)
        assertEquals("t".repeat(7), h.undo()?.text)
        assertNull(h.undo())
    }

    @Test
    fun `memory is capped by total characters, oldest first, but one step always stays`() {
        val h = EditHistory(EditorText(""), maxSteps = 1000, maxChars = 100)
        for (i in 1..20) h.record(EditorText("x".repeat(i * 10)), EditKind.PASTE, i * 10L)
        assertTrue(h.undoDepth in 1..19)
        // What is left still undoes cleanly, newest first.
        assertEquals("x".repeat(190), h.undo()?.text)

        val big = EditHistory(EditorText("x".repeat(500)), maxChars = 100)
        big.record(EditorText("y".repeat(500)), EditKind.PASTE, 0)
        assertEquals(1, big.undoDepth)
        assertEquals("x".repeat(500), big.undo()?.text)
    }

    @Test
    fun `thousands of typed characters in one run stay one step and one copy`() {
        val h = history()
        h.type("a".repeat(5_000), stepMs = 1)
        assertEquals(1, h.undoDepth)
        assertEquals("", h.undo()?.text)
        assertEquals(5_000, h.redo()?.text?.length)
    }

    @Test
    fun `reset forgets everything and starts from the given text`() {
        val h = history()
        h.type("abc")
        h.undo()
        h.reset(EditorText("draft", 2, 4))
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertEquals(EditorText("draft", 2, 4), h.current)
    }

    @Test
    fun `a stale selection is clamped to the text`() {
        val h = EditHistory(EditorText("ab", 9, 12))
        assertEquals(EditorText("ab", 2, 2), h.current)
        h.record(EditorText("abc", 1, 99), EditKind.PASTE, 0)
        assertEquals(EditorText("abc", 1, 3), h.current)
    }

    @Test
    fun `classify tells typing, deleting, paste and the rest apart`() {
        assertEquals(EditKind.TYPING, EditHistory.classify("ab", "aXb"))
        assertEquals(EditKind.DELETING, EditHistory.classify("aXb", "ab"))
        assertEquals(EditKind.PASTE, EditHistory.classify("ab", "aXYZb"))
        assertEquals(EditKind.OTHER, EditHistory.classify("abc", "aXc"))
        assertEquals(EditKind.OTHER, EditHistory.classify("abcd", "ad"))
        assertEquals(EditKind.OTHER, EditHistory.classify("same", "same"))
    }

    @Test
    fun `edit between finds the smallest replacement`() {
        assertEquals(EditHistory.Edit(2, 0, 3), EditHistory.Edit.between("abef", "abXYZef"))
        assertEquals(EditHistory.Edit(1, 2, 0), EditHistory.Edit.between("abcd", "ad"))
        assertEquals(EditHistory.Edit(4, 0, 0), EditHistory.Edit.between("same", "same"))
        // Repeated characters: any consistent answer is fine, as long as it is a valid edit.
        val edit = EditHistory.Edit.between("aa", "aaa")
        assertEquals(1, edit.inserted - edit.removed)
    }

    @Test
    fun `the registry gives each tab its own history and drops it with the tab`() {
        val all = EditHistories()
        val one = all.of(1, EditorText("a"))
        val two = all.of(2, EditorText("b"))
        one.record(EditorText("ab"), EditKind.PASTE, 0)
        assertTrue(all.of(1, EditorText("ab")).canUndo)
        assertFalse(all.of(2, EditorText("b")).canUndo)
        assertTrue(one !== two)
        all.drop(1)
        assertNull(all.peek(1))
        assertFalse(all.of(1, EditorText("ab")).canUndo)
    }

    @Test
    fun `text that changed behind the history's back starts a fresh one`() {
        val all = EditHistories()
        all.of(1, EditorText("")).record(EditorText("typed"), EditKind.PASTE, 0)
        // A draft restored from disk replaces the text without telling the history.
        val fresh = all.of(1, EditorText("SELECT draft"))
        assertFalse(fresh.canUndo)
        assertEquals("SELECT draft", fresh.current.text)
        all.clear()
        assertNull(all.peek(1))
    }

    @Test
    fun `each tab keeps its own history`() {
        val first = history()
        val second = history()
        first.type("one")
        second.type("two")
        first.undo()
        assertTrue(second.canUndo)
        assertFalse(second.canRedo)
        assertEquals("", first.current.text)
        assertEquals("two", second.current.text)
    }
}
