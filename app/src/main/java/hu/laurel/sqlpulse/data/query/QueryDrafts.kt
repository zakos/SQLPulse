package hu.laurel.sqlpulse.data.query

/** One tab's unfinished text, as it is kept between two runs of the app. */
data class QueryDraft(
    val id: Long,
    /** The name the user gave the tab, or null when the label is derived from the SQL. */
    val title: String?,
    val sql: String,
    val database: String?,
)

/** Every tab's draft, and which one was in front. */
data class DraftBook(
    val drafts: List<QueryDraft> = emptyList(),
    val activeId: Long? = null,
)

/**
 * How drafts are written down.
 *
 * A draft is text the user wrote, so it comes back exactly as it went in: no trimming, no
 * collapsing of blank lines, no length cap, no normalising of line endings. That rules out every
 * line-oriented format, because a draft holding a newline would then be several records, and it
 * rules out escaping schemes, because an escaping bug corrupts the very thing being protected.
 *
 * So each field is length-prefixed: the header line says how many characters the title, the
 * database and the SQL occupy, and those characters are then copied out verbatim. A quote, a
 * newline, a backslash and a semicolon are all just characters to this.
 *
 * A file that does not parse yields whatever was read before the damage, rather than an exception
 * or a half-decoded draft: losing the tail of the tab list is recoverable, showing somebody a
 * mangled version of their own query is not.
 */
object QueryDrafts {

    private const val MAGIC = "SQLPULSE-DRAFTS 1"

    fun encode(book: DraftBook): String {
        val out = StringBuilder()
        out.append(MAGIC).append('\n')
        out.append("active ").append(book.activeId?.toString() ?: "-").append('\n')
        book.drafts.forEach { draft ->
            out.append("tab ")
                .append(draft.id).append(' ')
                .append(draft.title?.length ?: -1).append(' ')
                .append(draft.database?.length ?: -1).append(' ')
                .append(draft.sql.length).append('\n')
            out.append(draft.title.orEmpty())
            out.append(draft.database.orEmpty())
            out.append(draft.sql)
        }
        return out.toString()
    }

    fun decode(text: String?): DraftBook {
        if (text.isNullOrEmpty()) return DraftBook()
        var index = 0

        fun line(): String? {
            if (index >= text.length) return null
            val newline = text.indexOf('\n', index)
            val end = if (newline < 0) text.length else newline
            val value = text.substring(index, end)
            index = if (newline < 0) text.length else newline + 1
            return value
        }

        if (line() != MAGIC) return DraftBook()
        val activeId = line()
            ?.removePrefix("active ")
            ?.takeIf { it != "-" }
            ?.toLongOrNull()

        val drafts = mutableListOf<QueryDraft>()
        while (index < text.length) {
            val header = line() ?: break
            if (!header.startsWith("tab ")) break
            val fields = header.removePrefix("tab ").split(' ')
            if (fields.size != 4) break
            val id = fields[0].toLongOrNull() ?: break
            val titleLength = fields[1].toIntOrNull() ?: break
            val databaseLength = fields[2].toIntOrNull() ?: break
            val sqlLength = fields[3].toIntOrNull() ?: break
            val total = maxOf(titleLength, 0) + maxOf(databaseLength, 0) + sqlLength
            if (sqlLength < 0 || titleLength < -1 || databaseLength < -1) break
            if (index + total > text.length) break

            var cursor = index
            val title = if (titleLength < 0) {
                null
            } else {
                text.substring(cursor, cursor + titleLength).also { cursor += titleLength }
            }
            val database = if (databaseLength < 0) {
                null
            } else {
                text.substring(cursor, cursor + databaseLength).also { cursor += databaseLength }
            }
            val sql = text.substring(cursor, cursor + sqlLength)
            index += total
            drafts += QueryDraft(id = id, title = title, sql = sql, database = database)
        }
        // An id pointing at a tab that did not survive would leave nothing selected.
        val active = activeId?.takeIf { id -> drafts.any { it.id == id } } ?: drafts.firstOrNull()?.id
        return DraftBook(drafts = drafts, activeId = active)
    }
}
