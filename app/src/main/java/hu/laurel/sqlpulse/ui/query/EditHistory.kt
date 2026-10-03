package hu.laurel.sqlpulse.ui.query

/** The editor's text with its selection: everything an undo has to put back. */
data class EditorText(
    val text: String,
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
) {
    /** Selection clamped to the text, so a stale range can never index out of it. */
    fun clamped(): EditorText {
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        return if (start == selectionStart && end == selectionEnd) {
            this
        } else {
            copy(selectionStart = start, selectionEnd = end)
        }
    }
}

/**
 * Where a change came from. Only [TYPING] and [DELETING] ever merge into the step before them;
 * every other source is one step of its own, so undoing a paste, a snippet or a format gives
 * back exactly the text that was there before it — not "a bit of it".
 */
enum class EditKind { TYPING, DELETING, PASTE, KEY_INSERT, SNIPPET, FORMAT, COMPLETION, OTHER }

/**
 * The histories of all open tabs, by tab id.
 *
 * Kept apart from the view model so the two rules that matter can be tested: a history is dropped
 * with its tab, and a history never outlives a change of text it did not see — a draft restored
 * from disk, or SQL handed over from another screen, starts a fresh one.
 */
class EditHistories {
    private val histories = mutableMapOf<Long, EditHistory>()

    /** The tab's history, started from [state] if there is none or the tab's text moved without it. */
    fun of(tabId: Long, state: EditorText): EditHistory {
        val existing = histories[tabId] ?: return EditHistory(state).also { histories[tabId] = it }
        if (existing.current.text != state.text) existing.reset(state)
        return existing
    }

    fun peek(tabId: Long): EditHistory? = histories[tabId]

    fun drop(tabId: Long) {
        histories.remove(tabId)
    }

    fun clear() = histories.clear()
}

/**
 * Undo and redo for one editor tab.
 *
 * Snapshot based on purpose: a step is the whole text as it stood before the step. For SQL scripts
 * (kilobytes, not megabytes) that is simpler and harder to get wrong than diffs, and the memory
 * cap below keeps it honest. Pure Kotlin with the clock passed in, so every rule is a test.
 *
 * It lives only in memory. Drafts are persisted elsewhere; restoring one starts a fresh history,
 * because "undo" back past what was restored from disk would resurrect text the user never saw
 * in this session.
 *
 * @param maxSteps oldest steps are dropped beyond this many undo steps.
 * @param maxChars ... and beyond this many characters in all (undo and redo stacks together).
 */
class EditHistory(
    initial: EditorText,
    private val maxSteps: Int = MAX_STEPS,
    private val maxChars: Int = MAX_CHARS,
    private val gapMs: Long = TYPING_GAP_MS,
) {
    private val undoStack = ArrayDeque<EditorText>()
    private val redoStack = ArrayDeque<EditorText>()
    private var chars = 0

    /** The state the history believes the editor is in. */
    var current: EditorText = initial.clamped()
        private set

    private var lastKind: EditKind? = null
    private var lastTimeMs = 0L

    /** True right after typing a space or newline: the next typed character starts a new step. */
    private var boundary = false

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size
    val redoDepth: Int get() = redoStack.size

    /** Throws everything away and starts from [state]. */
    fun reset(state: EditorText) {
        undoStack.clear()
        redoStack.clear()
        chars = 0
        current = state.clamped()
        lastKind = null
        boundary = false
    }

    /**
     * Notes that the editor is now in [next].
     *
     * Same text with a different selection only moves the remembered selection: moving the cursor
     * is not an edit, but it does decide where the cursor goes back to on the next undo.
     */
    fun record(next: EditorText, kind: EditKind, nowMs: Long) {
        val state = next.clamped()
        if (state.text == current.text) {
            current = state
            // A cursor jump ends a run of typing: what is typed elsewhere is another step.
            lastKind = null
            return
        }
        val inserted = insertedText(current.text, state.text)
        if (!canMerge(current, state, kind, nowMs)) {
            push(current)
            // A new edit makes the undone future unreachable.
            redoStack.forEach { chars -= it.text.length }
            redoStack.clear()
        }
        boundary = kind == EditKind.TYPING && inserted.isNotEmpty() && inserted.last().isWhitespace()
        current = state
        lastKind = kind
        lastTimeMs = nowMs
    }

    /** Steps back; null when there is nothing to undo. */
    fun undo(): EditorText? {
        val previous = undoStack.removeLastOrNull() ?: return null
        chars -= previous.text.length
        redoStack.addLast(current)
        chars += current.text.length
        current = previous
        lastKind = null
        return previous
    }

    /** Steps forward again; null when nothing was undone since the last edit. */
    fun redo(): EditorText? {
        val next = redoStack.removeLastOrNull() ?: return null
        chars -= next.text.length
        undoStack.addLast(current)
        chars += current.text.length
        current = next
        lastKind = null
        return next
    }

    private fun canMerge(before: EditorText, after: EditorText, kind: EditKind, nowMs: Long): Boolean {
        if (undoStack.isEmpty() || lastKind != kind) return false
        if (kind != EditKind.TYPING && kind != EditKind.DELETING) return false
        if (nowMs - lastTimeMs > gapMs || nowMs < lastTimeMs) return false
        // Only a plain caret continues a run; typing over a selection is its own step.
        if (before.selectionStart != before.selectionEnd) return false
        val edit = Edit.between(before.text, after.text)
        return if (kind == EditKind.TYPING) {
            edit.removed == 0 && edit.start == before.selectionEnd && !boundary
        } else {
            // Backspace ends at the caret, forward delete starts at it.
            edit.inserted == 0 &&
                (edit.start + edit.removed == before.selectionEnd || edit.start == before.selectionEnd)
        }
    }

    private fun push(state: EditorText) {
        undoStack.addLast(state)
        chars += state.text.length
        while (undoStack.size > maxSteps || (chars > maxChars && undoStack.size > 1)) {
            chars -= undoStack.removeFirst().text.length
        }
    }

    private fun insertedText(old: String, new: String): String {
        val edit = Edit.between(old, new)
        return new.substring(edit.start, edit.start + edit.inserted)
    }

    /** The smallest single replacement that turns one text into the other. */
    data class Edit(val start: Int, val removed: Int, val inserted: Int) {
        companion object {
            fun between(old: String, new: String): Edit {
                var prefix = 0
                val limit = minOf(old.length, new.length)
                while (prefix < limit && old[prefix] == new[prefix]) prefix++
                var suffix = 0
                while (suffix < limit - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
                return Edit(prefix, old.length - prefix - suffix, new.length - prefix - suffix)
            }
        }
    }

    companion object {
        /** "Unlimited" in practice: far more than anybody undoes through, small enough for a phone. */
        const val MAX_STEPS = 500
        const val MAX_CHARS = 1_000_000

        /** Typing slower than this starts a new step, the way a pause does in every editor. */
        const val TYPING_GAP_MS = 1_000L

        /**
         * What a change the text field reported is, judged from the text alone: one character in is
         * typing, one character out is deleting, anything bigger (paste, cut, autocomplete, a
         * selection replaced) is a step of its own.
         */
        fun classify(old: String, new: String): EditKind {
            val edit = Edit.between(old, new)
            return when {
                edit.removed == 0 && edit.inserted == 1 -> EditKind.TYPING
                edit.inserted == 0 && edit.removed == 1 -> EditKind.DELETING
                edit.removed == 0 && edit.inserted > 1 -> EditKind.PASTE
                else -> EditKind.OTHER
            }
        }
    }
}
