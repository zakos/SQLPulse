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

    /** Escape: close whatever is open over the editor. */
    DISMISS,
}

/**
 * The keyboard on a tablet or a desktop-sized window (research summary, §2.0).
 *
 * Only combinations nothing else claims: Ctrl and the letter keys the same way every SQL client
 * spells them, so the shortcut a user already has in their fingers works here too. A bare letter
 * is never a shortcut — it is text being typed into the editor.
 */
object QueryShortcuts {

    fun of(press: KeyPress): QueryShortcut? {
        if (press.alt) return null
        val key = press.key.uppercase()
        if (key == "ESCAPE" && !press.ctrl) return QueryShortcut.DISMISS
        if (!press.ctrl) return null
        return when {
            key == "ENTER" && press.shift -> QueryShortcut.RUN_CURRENT
            key == "ENTER" -> QueryShortcut.RUN
            key == "F" && press.shift -> QueryShortcut.FORMAT
            key == "F" -> QueryShortcut.FIND
            key == "S" && !press.shift -> QueryShortcut.SAVE_FAVOURITE
            else -> null
        }
    }
}
