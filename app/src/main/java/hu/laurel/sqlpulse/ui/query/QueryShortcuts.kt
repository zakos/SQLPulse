package hu.laurel.sqlpulse.ui.query

/**
 * One key press, as much of it as a shortcut cares about.
 *
 * [key] is a name rather than a key code so this stays testable without Android: "ENTER", "F",
 * "ESCAPE".
 */
data class KeyPress(
    val key: String,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

/** What a shortcut asks the query screen to do. */
enum class QueryShortcut {
    /** Run what the run button would run: the selection, or the whole script. */
    RUN,

    /** Run only the statement the cursor is in. */
    RUN_CURRENT,

    FORMAT,
    FIND,
    SAVE_FAVOURITE,

    /** Take back the last edit; Ctrl+Z. */
    UNDO,

    /** Put back what was taken back; Ctrl+Shift+Z or Ctrl+Y. */
    REDO,

    /** Escape: close whatever is open over the editor. */
    DISMISS,

    /** Down arrow while the completion list is open. */
    COMPLETION_NEXT,

    /** Up arrow while the completion list is open. */
    COMPLETION_PREVIOUS,

    /** Enter or Tab while the completion list is open: take the highlighted row. */
    COMPLETION_ACCEPT,

    /** Escape while the completion list is open: close it, nothing else. */
    COMPLETION_CLOSE,
}

/**
 * The keyboard on a tablet or a desktop-sized window (research summary, §2.0).
 *
 * Only combinations nothing else claims: Ctrl and the letter keys the same way every SQL client
 * spells them, so the shortcut a user already has in their fingers works here too. A bare letter
 * is never a shortcut — it is text being typed into the editor.
 */
object QueryShortcuts {

    /**
     * [completionOpen] hands the arrow keys, Enter, Tab and Escape to the completion list. Without
     * it they stay with the text field, where Enter is a newline and the arrows move the cursor.
     */
    fun of(press: KeyPress, completionOpen: Boolean = false): QueryShortcut? {
        if (press.alt) return null
        val key = press.key.uppercase()
        if (completionOpen && !press.ctrl) {
            when {
                key == "ARROWDOWN" -> return QueryShortcut.COMPLETION_NEXT
                key == "ARROWUP" -> return QueryShortcut.COMPLETION_PREVIOUS
                key == "ENTER" && !press.shift -> return QueryShortcut.COMPLETION_ACCEPT
                key == "TAB" && !press.shift -> return QueryShortcut.COMPLETION_ACCEPT
                key == "ESCAPE" -> return QueryShortcut.COMPLETION_CLOSE
            }
        }
        if (key == "ESCAPE" && !press.ctrl) return QueryShortcut.DISMISS
        if (!press.ctrl) return null
        return when {
            key == "ENTER" && press.shift -> QueryShortcut.RUN_CURRENT
            key == "ENTER" -> QueryShortcut.RUN
            key == "F" && press.shift -> QueryShortcut.FORMAT
            key == "F" -> QueryShortcut.FIND
            key == "S" && !press.shift -> QueryShortcut.SAVE_FAVOURITE
            key == "Z" && press.shift -> QueryShortcut.REDO
            key == "Z" -> QueryShortcut.UNDO
            key == "Y" && !press.shift -> QueryShortcut.REDO
            else -> null
        }
    }
}
