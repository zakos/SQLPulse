package hu.laurel.sqlpulse.data.query

import hu.laurel.sqlpulse.data.backup.JsonException
import hu.laurel.sqlpulse.data.backup.JsonValue

/** What a key on the editor's key bar does when tapped. */
sealed interface KeyAction {
    /** Puts [text] into the editor at the cursor, with the spacing [KeyInsertion] decides. */
    data class Insert(val text: String) : KeyAction

    object Undo : KeyAction
    object Redo : KeyAction
    object Snippets : KeyAction
}

/** One key the bar can show. [id] is what is stored, so it never changes once shipped. */
data class KeyBarItem(val id: String, val action: KeyAction) {
    /** The text on a text key; actions are drawn as icons and have none. */
    val label: String? get() = (action as? KeyAction.Insert)?.text
}

/**
 * Which keys the bar shows, in which order.
 *
 * Stored as the order of all ids the user has seen and the ones hidden, rather than as "the list
 * of visible keys": a key added by a later version is then recognised as new (appended, visible)
 * instead of being mistaken for one the user removed.
 */
data class KeyBarConfig(
    val order: List<String> = KeyBar.DEFAULT_ORDER,
    val hidden: Set<String> = emptySet(),
) {
    companion object {
        val DEFAULT = KeyBarConfig()
    }
}

object KeyBar {
    const val UNDO = "@undo"
    const val REDO = "@redo"
    const val SNIPPETS = "@snippets"

    /**
     * The characters the phone keyboard hides, in the order the spec lists them (§7.4), then the
     * ones a query is built from. Actions come first so they stay put when the row scrolls back.
     */
    private val TEXT_KEYS = listOf(
        "SELECT", "FROM", "WHERE", "*", "=", "<", ">", ",", "'", "%", "(", ")",
        ";", "<>", ">=", "<=", "!=", "`", "_", "[", "]", ".", ":",
        "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE", "JOIN", "ON", "GROUP BY", "ORDER BY", "LIMIT",
    )

    val CATALOGUE: List<KeyBarItem> =
        listOf(
            KeyBarItem(UNDO, KeyAction.Undo),
            KeyBarItem(REDO, KeyAction.Redo),
            KeyBarItem(SNIPPETS, KeyAction.Snippets),
        ) + TEXT_KEYS.map { KeyBarItem(it, KeyAction.Insert(it)) }

    val DEFAULT_ORDER: List<String> = CATALOGUE.map { it.id }

    private val BY_ID = CATALOGUE.associateBy { it.id }

    /** Every key in the order the user sees them in settings, hidden ones included. */
    fun entries(config: KeyBarConfig): List<Pair<KeyBarItem, Boolean>> {
        val known = config.order.filter { it in BY_ID }.distinct()
        val missing = DEFAULT_ORDER.filter { it !in known }
        return (known + missing).map { BY_ID.getValue(it) to (it !in config.hidden) }
    }

    /** The keys the bar draws. */
    fun visible(config: KeyBarConfig): List<KeyBarItem> =
        entries(config).filter { it.second }.map { it.first }

    /** Moves a key [delta] places (negative is towards the start); out of range stops at the end. */
    fun move(config: KeyBarConfig, id: String, delta: Int): KeyBarConfig {
        val ids = entries(config).map { it.first.id }.toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return config
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (to == from) return config
        ids.add(to, ids.removeAt(from))
        return config.copy(order = ids)
    }

    fun setShown(config: KeyBarConfig, id: String, shown: Boolean): KeyBarConfig {
        if (id !in BY_ID) return config
        val ids = entries(config).map { it.first.id }
        return config.copy(order = ids, hidden = if (shown) config.hidden - id else config.hidden + id)
    }

    fun encode(config: KeyBarConfig): String = JsonValue.Obj(
        linkedMapOf(
            "order" to JsonValue.Arr(config.order.map { JsonValue.Str(it) }),
            "hidden" to JsonValue.Arr(config.hidden.sorted().map { JsonValue.Str(it) }),
        ),
    ).write()

    /** Anything unreadable gives the default bar. */
    fun decode(text: String?): KeyBarConfig {
        if (text.isNullOrBlank()) return KeyBarConfig.DEFAULT
        val fields = try {
            (JsonValue.parse(text) as? JsonValue.Obj)?.fields
        } catch (_: JsonException) {
            null
        } ?: return KeyBarConfig.DEFAULT

        fun strings(name: String): List<String> =
            (fields[name] as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value }.orEmpty()
        val order = strings("order").filter { it in BY_ID }.distinct()
        if (order.isEmpty()) return KeyBarConfig.DEFAULT
        return KeyBarConfig(order = order, hidden = strings("hidden").filter { it in BY_ID }.toSet())
    }
}

/**
 * Where a key's text goes, and with what spacing around it.
 *
 * Keywords get a space before (unless one is already there, or the cursor follows an opening
 * bracket) and one after, so `SELECT`, `FROM`, `t` types as `SELECT FROM t` without a space bar.
 * Comparison operators are spaced like keywords. Punctuation goes in exactly as it is: `COUNT(`
 * and `a.` and `x,` must not grow a space.
 */
object KeyInsertion {

    private val OPERATORS = setOf("=", "<", ">", "<>", ">=", "<=", "!=")

    /** `*` is spaced before (after SELECT) but not after: `COUNT(*)` follows. */
    private val SPACED_BEFORE_ONLY = setOf("*", ":")

    /** What to insert for [key] at a cursor with [before] to its left and [after] to its right. */
    fun textFor(key: String, before: String, after: String): String {
        val lastChar = before.lastOrNull()
        val needsGapBefore = lastChar != null && !lastChar.isWhitespace() && lastChar != '('
        val isKeyword = key.first().isLetter()
        val leading: String
        val trailing: String
        when {
            isKeyword || key in OPERATORS -> {
                leading = if (needsGapBefore) " " else ""
                val next = after.firstOrNull()
                trailing = if (next != null && (next.isWhitespace() || next == ')' || next == ',' || next == ';')) "" else " "
            }
            key in SPACED_BEFORE_ONLY -> {
                // A parameter marker or a wildcard right after a word or bracket needs the gap;
                // after an operator or a comma the previous key already left one.
                leading = if (needsGapBefore && (lastChar!!.isLetterOrDigit() || lastChar == ')')) " " else ""
                trailing = ""
            }
            else -> {
                leading = ""
                trailing = ""
            }
        }
        return leading + key + trailing
    }
}
