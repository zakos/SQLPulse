package hu.laurel.sqlpulse.data.query

import hu.laurel.sqlpulse.data.backup.JsonException
import hu.laurel.sqlpulse.data.backup.JsonValue

/**
 * A piece of SQL to drop into the editor.
 *
 * The body may mark words to fill in as `{{table}}`. The braces never reach the editor: the word
 * stays, selected, so typing replaces it (a cursor marker is all a phone needs; real tab stops
 * would want a keyboard).
 */
data class Snippet(
    val id: String,
    val name: String,
    val body: String,
    val builtIn: Boolean = false,
)

/** The text to put in the editor, and what to select in it once it is there. */
data class SnippetInsertion(val text: String, val selectionStart: Int, val selectionEnd: Int)

/**
 * The templates every install has.
 *
 * Read-only statements, and the three writes that carry a WHERE of their own. No DDL: the spec
 * (§2) keeps ALTER, CREATE and DROP out of the app, and a template is the easiest way to put one
 * back in. A snippet only fills the editor; nothing here ever runs by itself.
 */
object BuiltInSnippets {
    val ALL: List<Snippet> = listOf(
        snippet("select_where", "SELECT … WHERE", "SELECT *\nFROM {{table}}\nWHERE {{condition}}\nLIMIT 100"),
        snippet(
            "select_join",
            "SELECT … JOIN",
            "SELECT a.*, b.*\nFROM {{table}} a\nJOIN {{other_table}} b ON b.{{column}} = a.{{column}}\nWHERE {{condition}}\nLIMIT 100",
        ),
        snippet(
            "select_group",
            "SELECT … GROUP BY",
            "SELECT {{column}}, COUNT(*) AS n\nFROM {{table}}\nGROUP BY {{column}}\nHAVING COUNT(*) > 1\nORDER BY n DESC\nLIMIT 100",
        ),
        snippet("select_count", "SELECT COUNT(*)", "SELECT COUNT(*)\nFROM {{table}}\nWHERE {{condition}}"),
        snippet("update_where", "UPDATE … WHERE", "UPDATE {{table}}\nSET {{column}} = {{value}}\nWHERE {{condition}}"),
        snippet("insert_values", "INSERT … VALUES", "INSERT INTO {{table}} ({{columns}})\nVALUES ({{values}})"),
        snippet("delete_where", "DELETE … WHERE", "DELETE FROM {{table}}\nWHERE {{condition}}"),
        snippet("explain", "EXPLAIN SELECT", "EXPLAIN\nSELECT *\nFROM {{table}}\nWHERE {{condition}}"),
        snippet("describe", "DESCRIBE", "DESCRIBE {{table}}"),
        snippet("show_index", "SHOW INDEX", "SHOW INDEX FROM {{table}}"),
    )

    private fun snippet(id: String, name: String, body: String) =
        Snippet(id = "builtin:$id", name = name, body = body, builtIn = true)
}

object SnippetEngine {

    private val PLACEHOLDER = Regex("""\{\{([^{}\n]{1,40})}}""")

    /** Words that begin a statement: a snippet starting with one goes on a line of its own. */
    private val STATEMENT_STARTERS = setOf(
        "SELECT", "UPDATE", "INSERT", "DELETE", "REPLACE", "EXPLAIN", "DESCRIBE", "SHOW", "WITH",
    )

    /** The body without the `{{ }}` marks, and where the first marked word sits in it. */
    fun expand(body: String): SnippetInsertion {
        val out = StringBuilder()
        var last = 0
        var first: IntRange? = null
        for (match in PLACEHOLDER.findAll(body)) {
            out.append(body, last, match.range.first)
            val start = out.length
            out.append(match.groupValues[1])
            if (first == null) first = start until out.length
            last = match.range.last + 1
        }
        out.append(body, last, body.length)
        val range = first
        return if (range != null) {
            SnippetInsertion(out.toString(), range.first, range.last + 1)
        } else {
            SnippetInsertion(out.toString(), out.length, out.length)
        }
    }

    /**
     * Puts [body] into [text] in place of the selection `[selStart, selEnd)`.
     *
     * A statement-shaped snippet after other text starts on a new line, separated by a blank line;
     * a fragment (`WHERE x = 1`, a column list) goes in line, a space after what precedes it. The
     * returned selection is in the new text's coordinates.
     */
    fun insert(text: String, selStart: Int, selEnd: Int, body: String): SnippetInsertion {
        val start = minOf(selStart, selEnd).coerceIn(0, text.length)
        val end = maxOf(selStart, selEnd).coerceIn(start, text.length)
        val before = text.substring(0, start)
        val expanded = expand(body)
        val separator = when {
            before.isBlank() -> ""
            startsStatement(expanded.text) -> when {
                before.endsWith("\n\n") -> ""
                before.endsWith("\n") -> "\n"
                else -> "\n\n"
            }
            before.last().isWhitespace() || before.last() == '(' -> ""
            else -> " "
        }
        val offset = start + separator.length
        return SnippetInsertion(
            text = before + separator + expanded.text + text.substring(end),
            selectionStart = offset + expanded.selectionStart,
            selectionEnd = offset + expanded.selectionEnd,
        )
    }

    fun startsStatement(sql: String): Boolean {
        val word = sql.trimStart().takeWhile { it.isLetter() }.uppercase()
        return word in STATEMENT_STARTERS
    }

    /** Case-insensitive match on the name or the body; a blank query matches everything. */
    fun search(snippets: List<Snippet>, query: String): List<Snippet> {
        val q = query.trim()
        if (q.isEmpty()) return snippets
        return snippets.filter { it.name.contains(q, ignoreCase = true) || it.body.contains(q, ignoreCase = true) }
    }
}

/** The user's own snippets: the rules for changing the list, and its stored form. */
object UserSnippets {
    const val MAX_SNIPPETS = 200
    const val MAX_NAME = 60
    const val MAX_BODY = 20_000

    /**
     * Adds a snippet, or replaces the one with [id]. Null when the name or body is blank, or the
     * list is full; the caller leaves the list as it was.
     */
    fun upsert(list: List<Snippet>, id: String, name: String, body: String): List<Snippet>? {
        val cleanName = name.trim().take(MAX_NAME)
        if (cleanName.isEmpty() || body.isBlank()) return null
        val cleanBody = body.take(MAX_BODY)
        val index = list.indexOfFirst { it.id == id }
        return if (index >= 0) {
            list.toMutableList().apply { this[index] = Snippet(id, cleanName, cleanBody) }
        } else {
            if (list.size >= MAX_SNIPPETS) return null
            list + Snippet(id, cleanName, cleanBody)
        }
    }

    fun delete(list: List<Snippet>, id: String): List<Snippet> = list.filterNot { it.id == id }

    fun encode(list: List<Snippet>): String = JsonValue.Arr(
        list.map {
            JsonValue.Obj(
                linkedMapOf(
                    "id" to JsonValue.Str(it.id),
                    "name" to JsonValue.Str(it.name),
                    "body" to JsonValue.Str(it.body),
                ),
            )
        },
    ).write()

    /** Reads what [encode] wrote; anything unreadable gives fewer snippets, never a crash. */
    fun decode(text: String?): List<Snippet> {
        if (text.isNullOrBlank()) return emptyList()
        val root = try {
            JsonValue.parse(text)
        } catch (_: JsonException) {
            return emptyList()
        }
        val items = (root as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { item ->
            val fields = (item as? JsonValue.Obj)?.fields ?: return@mapNotNull null
            val id = (fields["id"] as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = (fields["name"] as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val body = (fields["body"] as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Snippet(id, name.take(MAX_NAME), body.take(MAX_BODY))
        }.distinctBy { it.id }.take(MAX_SNIPPETS)
    }
}
