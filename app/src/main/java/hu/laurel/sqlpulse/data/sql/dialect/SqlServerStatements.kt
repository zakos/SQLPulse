package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.sql.NotEditableReason
import hu.laurel.sqlpulse.data.sql.ResultEditabilities
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.WriteImpact
import hu.laurel.sqlpulse.data.sql.WriteKind
import hu.laurel.sqlpulse.data.sql.WritePreviewQuery

/**
 * T-SQL statement inspection: what a statement does, how to cap its rows, how many rows a write
 * touches, and whether a result maps back onto one table.
 *
 * It lives apart from [SqlServerDialect] because it is pure text work and carries the part that
 * matters most for this engine: **the driver ignores `setReadOnly`**, so the classification below
 * is the only thing between a read-only connection and a write (besides the server's grants).
 * That is why it is stricter than the MySQL guard. T-SQL needs no statement terminator, so
 * `SELECT 1 DELETE FROM t` is two statements with no `;` between them, and the driver sends a
 * multi-statement batch as one — a guard that only reads the first word would wave it through.
 * Here every bare word of the statement is looked at, quotes, brackets and comments excluded.
 */
internal object TSql {

    /** `'string'`, `"identifier"` and `[identifier]` (a `]]` inside is a literal `]`). */
    val GRAMMAR = SqlGrammar(
        quotes = mapOf('\'' to '\'', '"' to '"', '[' to ']'),
        backslashEscapes = false,
        readStarters = setOf("select", "with"),
        writeStarters = setOf("insert", "update", "delete", "merge"),
    )

    /** One bare word of a statement, lower-cased, with where it sits and how deep in parentheses. */
    class Word(val text: String, val start: Int, val end: Int, val depth: Int)

    /**
     * [sql] with every quoted section and comment blanked to spaces of the same length — so the
     * indices of what is left still point into the original — and its bare words.
     */
    class Scanned(val sql: String) {
        val blank: String
        val words: List<Word>

        init {
            val out = sql.toCharArray()
            var index = 0
            while (index < sql.length) {
                val end = when {
                    GRAMMAR.opensQuote(sql, index) -> GRAMMAR.endOfQuoted(sql, index)
                    GRAMMAR.opensLineComment(sql, index) -> sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length
                    sql.startsWith("/*", index) -> sql.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: sql.length
                    else -> -1
                }
                if (end < 0) {
                    index++
                } else {
                    for (i in index until end) out[i] = ' '
                    index = end
                }
            }
            blank = String(out)

            val found = mutableListOf<Word>()
            var depth = 0
            var i = 0
            while (i < blank.length) {
                val c = blank[i]
                when {
                    c == '(' -> { depth++; i++ }
                    c == ')' -> { depth--; i++ }
                    isWordChar(c) -> {
                        var end = i
                        while (end < blank.length && isWordChar(blank[end])) end++
                        found += Word(blank.substring(i, end).lowercase(), i, end, depth.coerceAtLeast(0))
                        i = end
                    }

                    else -> i++
                }
            }
            words = found
        }

        val topLevel: List<Word> get() = words.filter { it.depth == 0 }

        /** The statements of the text, split at `;` outside quotes, parentheses and comments. */
        fun pieces(): List<String> = blank.split(';').filter { it.isNotBlank() }

        private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '#' || c == '@' || c == '$'
    }

    // ---------------------------------------------------------------- classify

    /**
     * Words that make a statement something this app does not run (§2: no DDL, no administration),
     * wherever in the text they stand. All are reserved words, so none of them can be a bare
     * column or table name; a bracketed name is blanked before the scan.
     */
    private val UNSUPPORTED_WORDS = setOf(
        "create", "alter", "drop", "truncate", "grant", "revoke", "deny", "exec", "execute",
        "shutdown", "kill", "backup", "restore", "dbcc", "reconfigure", "checkpoint", "bulk",
        "openrowset", "opendatasource", "openquery", "setuser", "readtext", "writetext", "updatetext",
        "waitfor", "raiserror", "xp_cmdshell",
    )

    /** `SELECT … INTO new_table` is a CREATE TABLE in a SELECT's clothes. */
    private fun intoMakesATable(words: List<Word>): Boolean = words.any { it.text == "into" }

    fun classify(sql: String): StatementKind {
        val scanned = Scanned(sql)
        val first = SqlGuards.classify(sql, GRAMMAR)
        if (first == StatementKind.OTHER) return first
        val words = scanned.words.map { it.text }
        if (words.any { it in UNSUPPORTED_WORDS }) return StatementKind.OTHER
        // A statement that is not an INSERT but has an INTO is a SELECT INTO; for an INSERT the
        // INTO is the ordinary one.
        if (words.firstOrNull() != "insert" && intoMakesATable(scanned.words) &&
            words.none { it == "insert" || it == "merge" }
        ) {
            return StatementKind.OTHER
        }
        // Whatever hides behind the first word, a write word anywhere makes it a write.
        if (words.any { it in GRAMMAR.writeStarters }) return StatementKind.WRITE
        return first
    }

    /**
     * An UPDATE or DELETE that would touch every row. Looked at statement by statement, and at the
     * top level only: a WHERE inside a subquery or a CTE guards that, not the write around it.
     */
    fun isUnguardedWrite(sql: String): Boolean {
        if (classify(sql) != StatementKind.WRITE) return false
        val scanned = Scanned(sql)
        // `;` splits at the top level of the blanked text; each piece is scanned on its own.
        return scanned.pieces().any { piece ->
            val top = Scanned(piece).topLevel.map { it.text }
            ("update" in top || "delete" in top) && "where" !in top
        }
    }

    // ---------------------------------------------------------------- default row limit

    private val SET_OPERATORS = setOf("union", "intersect", "except")

    /**
     * Adds the row cap, the T-SQL way: `SELECT TOP (n) …`.
     *
     * TOP goes after SELECT, or after `DISTINCT`/`ALL` (`SELECT DISTINCT TOP (n)`). A statement
     * that already pages itself (TOP, OFFSET … FETCH) is left alone, so what the user wrote wins.
     * A UNION cannot take a TOP on its first branch without limiting only that branch, so it is
     * capped with `OFFSET 0 ROWS FETCH NEXT n ROWS ONLY` when it has an ORDER BY (T-SQL requires
     * one) and left alone when it has none — the grid's own row cap still applies on the client.
     */
    fun applyDefaultLimit(sql: String, limit: Int): SqlGuards.LimitResult {
        val trimmed = sql.trim().trimEnd(';').trim()
        if (classify(trimmed) != StatementKind.READ) return SqlGuards.LimitResult(trimmed, false)
        val scanned = Scanned(trimmed)
        val unchanged = SqlGuards.LimitResult(trimmed, false)
        // More than one statement: which one the cap belongs to is not ours to guess.
        if (scanned.blank.contains(';')) return unchanged
        val top = scanned.topLevel
        val select = top.firstOrNull { it.text == "select" } ?: return unchanged
        if (top.any { it.text == "offset" || it.text == "fetch" }) return unchanged

        if (top.any { it.start > select.start && it.text in SET_OPERATORS }) {
            if (top.none { it.text == "order" } || top.any { it.text == "option" || it.text == "for" }) {
                return unchanged
            }
            return SqlGuards.LimitResult("$trimmed OFFSET 0 ROWS FETCH NEXT $limit ROWS ONLY", true)
        }

        val after = top.indexOf(select) + 1
        var insertAfter = select
        var next = top.getOrNull(after)
        if (next != null && (next.text == "distinct" || next.text == "all")) {
            insertAfter = next
            next = top.getOrNull(after + 1)
        }
        if (next != null && next.text == "top") return unchanged
        val at = insertAfter.end
        return SqlGuards.LimitResult(
            trimmed.substring(0, at) + " TOP ($limit)" + trimmed.substring(at),
            true,
        )
    }

    // ---------------------------------------------------------------- write impact

    private class Parsed(val table: String, val tail: String, val set: String?)

    private val TABLE_REFERENCE = Regex(
        "^(\\[[^\\]]+]|\"[^\"]+\"|[A-Za-z_#@][\\w$#@]*)(\\.(\\[[^\\]]+]|\"[^\"]+\"|[A-Za-z_#@][\\w$#@]*)){0,3}$",
    )

    private val COLUMN_TARGET = Regex(
        "^(?:(?:\\[[^\\]]+]|\"[^\"]+\"|[A-Za-z_][\\w$#]*)\\.){0,2}(\\[(?:[^\\]]|]])+]|\"[^\"]+\"|[A-Za-z_][\\w$#]*)$",
    )

    /** Anything that, run inside a SELECT, would do more than read. */
    private val SIDE_EFFECT_WORDS = setOf(
        "exec", "execute", "into", "waitfor", "openrowset", "opendatasource", "openquery", "xp_cmdshell",
        "output", "option",
    )

    /**
     * `UPDATE t SET … [WHERE …]` and `DELETE [FROM] t [WHERE …]`, the single-table forms only.
     * Everything else — TOP, OUTPUT, a FROM/JOIN that adds tables, a table hint, a CTE, several
     * statements — is refused rather than approximated: the count is read as a fact just before
     * someone presses the button.
     */
    private fun parse(sql: String): Parsed? {
        val clean = sql.trim().trimEnd(';').trim()
        val scanned = Scanned(clean)
        if (scanned.blank.contains(';')) return null
        val top = scanned.topLevel
        val first = top.firstOrNull() ?: return null
        if (first.start != clean.indexOfFirst { !it.isWhitespace() }) return null
        if (scanned.words.any { it.text in SIDE_EFFECT_WORDS || it.text.startsWith("xp_") }) return null
        // `NEXT VALUE FOR seq` advances a sequence every time it is evaluated, a SELECT's included.
        if (scanned.words.zipWithNext().any { (a, b) -> a.text == "next" && b.text == "value" }) return null
        if (top.any { it.text == "join" || it.text == "top" }) return null
        val whereAt = top.firstOrNull { it.text == "where" }
        val tail = whereAt?.let { " " + clean.substring(it.start).trim() } ?: ""
        return when (first.text) {
            "update" -> {
                val set = top.firstOrNull { it.text == "set" } ?: return null
                // `UPDATE t SET … FROM …` joins other tables in; the count would not be the same.
                if (top.any { it.text == "from" }) return null
                val table = clean.substring(first.end, set.start).trim()
                if (!TABLE_REFERENCE.matches(table)) return null
                Parsed(table, tail, clean.substring(set.end, whereAt?.start ?: clean.length).trim())
            }

            "delete" -> {
                val from = top.getOrNull(1)?.takeIf { it.text == "from" } ?: return null
                if (top.count { it.text == "from" } != 1) return null
                val table = clean.substring(from.end, whereAt?.start ?: clean.length).trim()
                if (!TABLE_REFERENCE.matches(table)) return null
                Parsed(table, tail, null)
            }

            else -> null
        }
    }

    fun writeCountQuery(sql: String): String? =
        parse(sql)?.let { "SELECT COUNT(*) FROM ${it.table}${it.tail}" }

    /**
     * The SELECT that shows the rows [sql] would change. All right-hand sides of an UPDATE's SET
     * see the *old* row in T-SQL (unlike MySQL's left-to-right), so no ordering check is needed;
     * what is refused is a variable assignment (`SET @v = col`), a repeated column, and anything
     * [parse] refuses.
     */
    fun writePreviewQuery(sql: String, limit: Int): WritePreviewQuery? {
        if (limit <= 0) return null
        val parsed = parse(sql) ?: return null
        val from = " FROM ${parsed.table}${parsed.tail}"
        val set = parsed.set ?: return WritePreviewQuery("SELECT TOP ($limit) *$from", WriteKind.DELETE, emptyList())
        val assignments = splitAssignments(set) ?: return null
        val columns = assignments.map { it.first }
        if (columns.map { it.lowercase() }.toSet().size != columns.size) return null
        val news = assignments.joinToString(", ") { (column, expression) ->
            "($expression) AS ${bracket(column + WriteImpact.NEW_SUFFIX)}"
        }
        return WritePreviewQuery("SELECT TOP ($limit) *, $news$from", WriteKind.UPDATE, columns)
    }

    private fun splitAssignments(set: String): List<Pair<String, String>>? {
        val scanned = Scanned(set)
        val parts = mutableListOf<IntRange>()
        var depth = 0
        var from = 0
        scanned.blank.forEachIndexed { i, c ->
            when {
                c == '(' -> depth++
                c == ')' -> depth--
                c == ',' && depth == 0 -> { parts += from until i; from = i + 1 }
            }
        }
        parts += from until set.length
        val result = parts.map { range ->
            val part = set.substring(range.first, range.last + 1)
            val eq = scanned.blank.substring(range.first, range.last + 1).indexOf('=')
            if (eq <= 0) return null
            val target = part.substring(0, eq).trim()
            val column = COLUMN_TARGET.matchEntire(target)?.groupValues?.get(1) ?: return null
            val name = when {
                column.startsWith("[") -> column.substring(1, column.length - 1).replace("]]", "]")
                column.startsWith("\"") -> column.substring(1, column.length - 1).replace("\"\"", "\"")
                else -> column
            }
            val expression = part.substring(eq + 1).trim()
            if (expression.isBlank()) return null
            name to expression
        }
        return result.takeIf { it.isNotEmpty() }
    }

    fun bracket(name: String): String = "[" + name.replace("]", "]]") + "]"

    // ---------------------------------------------------------------- result editability

    /**
     * Whether a typed SELECT maps onto one table. The analysis itself is the shared one
     * (ResultEditabilities, written for MySQL's backticks); T-SQL is translated into the shape it
     * reads — `[a]` and `"a"` become `` `a` ``, a leading `TOP (n)` is dropped, since the cap says
     * nothing about which table the rows come from — and anything the translation cannot be sure
     * of (a `#temp` table, which the MySQL reader would take for a comment; `FOR XML`) is refused.
     */
    fun resultEditability(sql: String): ResultEditability {
        val scanned = Scanned(sql)
        if (scanned.blank.contains('#')) return ResultEditability.NotEditable(NotEditableReason.UNSUPPORTED)
        if (scanned.topLevel.any { it.text == "for" || it.text == "option" }) {
            return ResultEditability.NotEditable(NotEditableReason.UNSUPPORTED)
        }
        return ResultEditabilities.analyse(toBacktickForm(withoutTop(sql, scanned)))
    }

    private val TOP_CLAUSE = Regex("(?i)^(\\s*select\\s+)top\\s*(\\(\\s*\\d+\\s*\\)|\\d+)\\s+")

    private fun withoutTop(sql: String, scanned: Scanned): String {
        // Only a TOP that is the statement's own, right after its first SELECT.
        val select = scanned.topLevel.firstOrNull() ?: return sql
        if (select.text != "select") return sql
        return TOP_CLAUSE.replace(sql, "$1")
    }

    /** `[a]b]` → `` `ab` ``, `"x"` → `` `x` ``: names only, strings and comments are copied. */
    private fun toBacktickForm(sql: String): String {
        val out = StringBuilder(sql.length)
        var index = 0
        while (index < sql.length) {
            val c = sql[index]
            when {
                c == '[' || c == '"' -> {
                    val end = GRAMMAR.endOfQuoted(sql, index)
                    val closing = if (c == '[') ']' else '"'
                    val inner = sql.substring(index + 1, (end - 1).coerceAtLeast(index + 1))
                        .replace("$closing$closing", closing.toString())
                    out.append('`').append(inner.replace("`", "``")).append('`')
                    index = end
                }

                GRAMMAR.opensQuote(sql, index) -> {
                    val end = GRAMMAR.endOfQuoted(sql, index)
                    out.append(sql, index, end)
                    index = end
                }

                GRAMMAR.opensLineComment(sql, index) -> {
                    val end = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length
                    out.append(sql, index, end)
                    index = end
                }

                sql.startsWith("/*", index) -> {
                    val end = sql.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: sql.length
                    out.append(sql, index, end)
                    index = end
                }

                else -> {
                    out.append(c)
                    index++
                }
            }
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- scripts

    /**
     * `GO` is not T-SQL but a batch separator of the Microsoft client tools: a line holding only
     * `GO` (optionally with a repeat count). The app's script runner splits on `;`, so a script
     * with `GO` lines would send the word to the server and fail on it. This reports whether the
     * text has such a line, so the editor can say "GO is not supported" instead.
     */
    fun hasBatchSeparator(text: String): Boolean =
        Regex("(?im)^\\s*go(\\s+\\d+)?\\s*;?\\s*$").containsMatchIn(Scanned(text).blank)
}
