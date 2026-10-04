package hu.laurel.sqlpulse.ui.query

/** What a completion row stands for; decides its badge and where it sorts. */
enum class CompletionKind { TABLE, COLUMN, KEYWORD, FUNCTION, SNIPPET }

/**
 * One row of the completion list.
 *
 * [text] is what replaces the typed word; [detail] is the quiet second column (a column's type
 * and table, a snippet's name). [snippetBody] is set only for [CompletionKind.SNIPPET], whose
 * insertion goes through the snippet engine so its first placeholder ends up selected.
 */
data class CompletionItem(
    val text: String,
    val kind: CompletionKind,
    val detail: String? = null,
    val snippetBody: String? = null,
)

/** Ranking and prefix helpers for the completion list; pure so they can be tested without Compose. */
object CompletionRanking {

    /** Rows the list shows at once; more would need scrolling inside a floating list. */
    const val MAX_ROWS = 6

    /** `COUNT(` is a function in the list even though the keyword tables spell it with the bracket. */
    fun kindOfKeyword(word: String): CompletionKind =
        if (word.endsWith("(")) CompletionKind.FUNCTION else CompletionKind.KEYWORD

    /**
     * The candidates that fit what has been typed, best first.
     *
     * A match in the typed case wins over one that only matches ignoring case (typing `Cu` points
     * at `Customers` before `customer_id`), names the statement is about (columns, tables) come
     * before the words of the language, and with a prefix typed the shorter name is the likelier
     * one. Without a prefix the order the caller built is kept inside each kind, because that
     * order is the clause's own idea of what comes first.
     */
    fun rank(candidates: List<CompletionItem>, prefix: String, limit: Int = MAX_ROWS): List<CompletionItem> {
        val matching = candidates
            .asSequence()
            .filter { prefix.isEmpty() || it.text.startsWith(prefix, ignoreCase = true) }
            // A suggestion identical to what is already typed completes nothing.
            .filterNot { it.text.equals(prefix, ignoreCase = true) && it.kind != CompletionKind.SNIPPET }
            .distinctBy { it.kind to it.text }
            .toList()
        if (prefix.isEmpty()) {
            return matching.sortedBy { kindRank(it.kind) }.take(limit)
        }
        return matching
            .sortedWith(
                compareBy<CompletionItem>(
                    { if (it.text.startsWith(prefix)) 0 else 1 },
                    { kindRank(it.kind) },
                    { it.text.length },
                ),
            )
            .take(limit)
    }

    private fun kindRank(kind: CompletionKind): Int = when (kind) {
        CompletionKind.COLUMN, CompletionKind.TABLE -> 0
        CompletionKind.SNIPPET -> 1
        CompletionKind.FUNCTION -> 2
        CompletionKind.KEYWORD -> 3
    }

    /**
     * Splits a suggestion into the part the user already typed and the rest, so the first can be
     * drawn bold. Nothing is bold when the suggestion does not start with the prefix.
     */
    fun split(text: String, prefix: String): Pair<String, String> =
        if (prefix.isNotEmpty() && text.startsWith(prefix, ignoreCase = true)) {
            text.substring(0, prefix.length) to text.substring(prefix.length)
        } else {
            "" to text
        }

    /** The selection after a up or down press: wraps at both ends, so a held key cycles. */
    fun move(selected: Int, delta: Int, count: Int): Int =
        if (count <= 0) 0 else ((selected + delta) % count + count) % count
}

/** Where the floating list goes, in window pixels. */
data class PopupSpot(val x: Int, val y: Int, val height: Int)

/**
 * Places the completion list under the cursor line without covering the key bar below the editor.
 *
 * The key bar starts where the editor ends, so "stay above the editor's bottom edge" is the whole
 * rule: below the cursor if the list fits there, else above the cursor (which may climb over the
 * tab bar, never over the key bar), else whichever side has more room with the list shortened to
 * fit, and nothing at all when even that is too little.
 */
object CompletionPlacement {

    fun place(
        cursorX: Int,
        cursorTop: Int,
        cursorBottom: Int,
        popupWidth: Int,
        popupHeight: Int,
        editorBottom: Int,
        windowWidth: Int,
        margin: Int,
        minHeight: Int,
    ): PopupSpot? {
        val x = cursorX.coerceIn(margin, (windowWidth - popupWidth - margin).coerceAtLeast(margin))
        val below = editorBottom - cursorBottom
        val above = cursorTop
        return when {
            below >= popupHeight -> PopupSpot(x, cursorBottom, popupHeight)
            above >= popupHeight -> PopupSpot(x, cursorTop - popupHeight, popupHeight)
            maxOf(below, above) < minHeight -> null
            below >= above -> PopupSpot(x, cursorBottom, below)
            else -> PopupSpot(x, 0, above)
        }
    }
}
