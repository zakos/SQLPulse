package hu.laurel.sqlpulse.data.query

import hu.laurel.sqlpulse.data.backup.JsonException
import hu.laurel.sqlpulse.data.backup.JsonValue
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine

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
 *
 * Each template is tagged with the engines it is valid on and spells its row limit the way the
 * engine does (`LIMIT 100`, or `TOP (100)` in T-SQL); the sheet shows [forEngine] of the live
 * session. [ALL] is the MySQL set, which is what every install had before engines existed.
 */
object BuiltInSnippets {

    private class Template(
        val id: String,
        val name: String,
        /** Null: valid everywhere. */
        val engines: Set<DatabaseEngine>?,
        val body: (DatabaseEngine) -> String,
    )

    private val MYSQL_ONLY = setOf(DatabaseEngine.MYSQL)
    private val NOT_SQLSERVER = DatabaseEngine.entries.toSet() - DatabaseEngine.SQLSERVER

    /** `SELECT <columns> FROM …` with the first 100 rows asked for in the engine's own words. */
    private fun limited(engine: DatabaseEngine, columns: String, rest: String): String =
        if (engine == DatabaseEngine.SQLSERVER) {
            "SELECT TOP (100) $columns\n$rest"
        } else {
            "SELECT $columns\n$rest\nLIMIT 100"
        }

    private val TEMPLATES: List<Template> = listOf(
        Template("select_where", "SELECT … WHERE", null) {
            limited(it, "*", "FROM {{table}}\nWHERE {{condition}}")
        },
        Template("select_join", "SELECT … JOIN", null) {
            limited(
                it,
                "a.*, b.*",
                "FROM {{table}} a\nJOIN {{other_table}} b ON b.{{column}} = a.{{column}}\nWHERE {{condition}}",
            )
        },
        Template("select_group", "SELECT … GROUP BY", null) {
            limited(
                it,
                "{{column}}, COUNT(*) AS n",
                "FROM {{table}}\nGROUP BY {{column}}\nHAVING COUNT(*) > 1\nORDER BY n DESC",
            )
        },
        Template("select_count", "SELECT COUNT(*)", null) {
            "SELECT COUNT(*)\nFROM {{table}}\nWHERE {{condition}}"
        },
        Template("update_where", "UPDATE … WHERE", null) {
            "UPDATE {{table}}\nSET {{column}} = {{value}}\nWHERE {{condition}}"
        },
        Template("insert_values", "INSERT … VALUES", null) {
            "INSERT INTO {{table}} ({{columns}})\nVALUES ({{values}})"
        },
        Template("delete_where", "DELETE … WHERE", null) {
            "DELETE FROM {{table}}\nWHERE {{condition}}"
        },
        // T-SQL has no EXPLAIN; its plan comes from SET SHOWPLAN, which is a session setting.
        Template("explain", "EXPLAIN SELECT", NOT_SQLSERVER) {
            val prefix = if (it == DatabaseEngine.SQLITE) "EXPLAIN QUERY PLAN" else "EXPLAIN"
            "$prefix\nSELECT *\nFROM {{table}}\nWHERE {{condition}}"
        },
        // The structure peeks: MySQL's two statements have no equivalent by name elsewhere, so
        // each engine gets what it actually answers the question with.
        Template("describe", "DESCRIBE", MYSQL_ONLY) { "DESCRIBE {{table}}" },
        Template("show_index", "SHOW INDEX", MYSQL_ONLY) { "SHOW INDEX FROM {{table}}" },
        Template(
            "columns_catalog",
            "Table columns",
            setOf(DatabaseEngine.POSTGRESQL, DatabaseEngine.SQLSERVER),
        ) {
            "SELECT column_name, data_type, is_nullable\nFROM information_schema.columns\n" +
                "WHERE table_name = '{{table}}'\nORDER BY ordinal_position"
        },
        Template("indexes_pg", "Table indexes", setOf(DatabaseEngine.POSTGRESQL)) {
            "SELECT indexname, indexdef\nFROM pg_indexes\nWHERE tablename = '{{table}}'"
        },
        Template("indexes_mssql", "Table indexes", setOf(DatabaseEngine.SQLSERVER)) {
            "EXEC sp_helpindex '{{table}}'"
        },
        Template("pragma_table_info", "PRAGMA table_info", setOf(DatabaseEngine.SQLITE)) {
            "PRAGMA table_info({{table}})"
        },
        Template("pragma_index_list", "PRAGMA index_list", setOf(DatabaseEngine.SQLITE)) {
            "PRAGMA index_list({{table}})"
        },
    )

    /** The MySQL set, as it has always been. */
    val ALL: List<Snippet> = forEngine(DatabaseEngine.MYSQL)

    /** The templates valid on [engine], bodies spelled for it. */
    fun forEngine(engine: DatabaseEngine): List<Snippet> =
        TEMPLATES.filter { it.engines == null || engine in it.engines }
            .map { Snippet(id = "builtin:${it.id}", name = it.name, body = it.body(engine), builtIn = true) }
}

object SnippetEngine {

    private val PLACEHOLDER = Regex("""\{\{([^{}\n]{1,40})}}""")

    /** Words that begin a statement: a snippet starting with one goes on a line of its own. */
    private val STATEMENT_STARTERS = setOf(
        "SELECT", "UPDATE", "INSERT", "DELETE", "REPLACE", "EXPLAIN", "DESCRIBE", "SHOW", "WITH",
        "PRAGMA", "EXEC",
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
