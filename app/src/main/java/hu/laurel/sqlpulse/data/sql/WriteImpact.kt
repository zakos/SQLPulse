package hu.laurel.sqlpulse.data.sql

/**
 * Turns an UPDATE or a DELETE into the `SELECT COUNT(*)` that says how many rows it would touch.
 *
 * The rewrite is deliberately literal: the same table, the same WHERE, nothing invented. Anything
 * whose row count is not simply "the rows this WHERE matches" is refused instead of approximated,
 * because the number is read as a fact in the second before someone presses the button, and a
 * number that is wrong there is worse than no number at all. Refusing means the dialog says the
 * count is unknown, which is honest and still lets the statement run.
 */
object WriteImpact {

    /**
     * The count query for [sql], or null when it cannot be derived with confidence.
     *
     * Null covers: INSERT and REPLACE (they add rows rather than matching them), anything behind a
     * CTE, a multi-table UPDATE or DELETE, a join, a subquery in the FROM, `DELETE ... USING`, a
     * LIMIT (it picks an arbitrary subset of what the WHERE matches) and more than one statement.
     */
    fun countQuery(sql: String): String? =
        parse(sql)?.let { "SELECT COUNT(*) FROM ${it.table}${it.tail}" }

    /**
     * The read-only SELECT that shows up to [limit] of the rows [sql] would change, or null when
     * it cannot be derived with the same confidence as the count (it refuses exactly what
     * [countQuery] refuses). Follows dbx's `dml_preview_sql.rs`: an UPDATE becomes
     * `SELECT *, <expr> AS "<col> (new)" ... FROM t WHERE ...`, a DELETE `SELECT * FROM t WHERE ...`.
     *
     * An UPDATE is refused on top of that when its SET list cannot be evaluated on the old row
     * alone: a column assigned twice, a later expression that reads an earlier-assigned column
     * (MySQL evaluates the list left to right, so it would see the new value, the preview the old
     * one), or an expression with a side effect (`NEXTVAL`, `GET_LOCK`, `@v := ...`) that running
     * it as a SELECT would trigger for real.
     */
    fun previewQuery(sql: String, limit: Int = PREVIEW_ROWS): WritePreviewQuery? {
        if (limit <= 0) return null
        val parsed = parse(sql) ?: return null
        val suffix = " FROM ${parsed.table}${parsed.tail} LIMIT $limit"
        val set = parsed.set ?: return WritePreviewQuery("SELECT *$suffix", WriteKind.DELETE, emptyList())
        val assignments = parseAssignments(set) ?: return null
        val columns = assignments.map { it.column }
        if (columns.map { it.lowercase() }.toSet().size != columns.size) return null
        assignments.forEachIndexed { index, assignment ->
            val earlier = columns.take(index).map { it.lowercase() }.toSet()
            if (assignment.expression.isBlank()) return null
            if (hasSideEffect(assignment.expression)) return null
            if (earlier.isNotEmpty() && referencedNames(assignment.expression).any { it in earlier }) return null
        }
        val news = assignments.joinToString(", ") {
            "(${it.expression}) AS ${quoteIdentifier(it.column + NEW_SUFFIX)}"
        }
        return WritePreviewQuery("SELECT *, $news$suffix", WriteKind.UPDATE, columns)
    }

    private class Parsed(val table: String, val tail: String, val set: String?)

    private class Assignment(val column: String, val expression: String)

    private fun parse(sql: String): Parsed? {
        // Comments go first so the derived query cannot carry half of one, and so the table
        // reference is read without a comment wedged into it.
        val clean = blankComments(sql.trim().trimEnd(';'))
        val masked = blankLiterals(clean)
        // Two statements in one string: whichever of them is the write, one count cannot speak for
        // both of them.
        if (masked.contains(';')) return null

        val start = skipSpace(masked, 0)
        val keyword = readWord(masked, start).lowercase()
        val after = skipSpace(clean, start + keyword.length)
        return when (keyword) {
            "update" -> parseUpdate(clean, masked, after)
            "delete" -> parseDelete(clean, masked, after)
            else -> null
        }
    }

    /** `UPDATE [modifiers] table [alias] SET ... [WHERE ...]` and nothing more adventurous. */
    private fun parseUpdate(clean: String, masked: String, from: Int): Parsed? {
        val start = skipModifiers(clean, from, UPDATE_MODIFIERS)
        val keywords = topLevelKeywords(masked, start)
        val setAt = keywords.firstOrNull { it.second == "set" }?.first ?: return null
        val table = clean.substring(start, setAt).trim()
        if (!TABLE_REFERENCE.matches(table)) return null
        val tail = tail(clean, masked, setAt) ?: return null
        // The SET list ends where the WHERE (or ORDER BY) begins; LIMIT is already refused.
        val setEnd = keywords.firstOrNull { it.first > setAt && it.second in TAIL_STARTERS }?.first ?: clean.length
        return Parsed(table, tail, clean.substring(setAt + "set".length, setEnd).trim())
    }

    /** `DELETE [modifiers] FROM table [alias] [WHERE ...]`, the single-table form only. */
    private fun parseDelete(clean: String, masked: String, from: Int): Parsed? {
        val start = skipModifiers(clean, from, DELETE_MODIFIERS)
        // `DELETE t1 FROM a JOIN b` and `DELETE FROM t USING ...` name more than one table, and the
        // rows they remove are not the rows a count over any one of them returns.
        if (!readWord(masked, start).equals("from", ignoreCase = true)) return null
        val tableStart = skipSpace(clean, start + "from".length)
        val keywords = topLevelKeywords(masked, tableStart)
        if (keywords.any { it.second == "using" }) return null
        val stop = keywords.firstOrNull { it.second in TAIL_STARTERS }?.first ?: clean.length
        val table = clean.substring(tableStart, stop).trim()
        if (!TABLE_REFERENCE.matches(table)) return null
        return Parsed(table, tail(clean, masked, stop) ?: return null, null)
    }

    /**
     * `col = expr, col2 = expr2` split at the top level, or null when an item is not that shape.
     * Commas inside parentheses (`CONCAT(a, b)`) or literals (`'a, b'`) do not split.
     */
    private fun parseAssignments(set: String): List<Assignment>? {
        val masked = blankLiterals(set)
        val parts = mutableListOf<String>()
        var depth = 0
        var from = 0
        masked.forEachIndexed { i, c ->
            when {
                c == '(' -> depth++
                c == ')' -> depth--
                c == ',' && depth == 0 -> { parts += set.substring(from, i); from = i + 1 }
            }
        }
        parts += set.substring(from)
        val result = parts.map { part ->
            val eq = blankLiterals(part).indexOf('=')
            if (eq <= 0) return null
            val target = part.substring(0, eq).trim()
            // The last group, not a split on '.': a backquoted name may contain one.
            val last = TARGET_COLUMN.matchEntire(target)?.groupValues?.get(3) ?: return null
            val column = if (last.startsWith("`")) last.substring(1, last.length - 1).replace("``", "`") else last
            Assignment(column, part.substring(eq + 1).trim())
        }
        return result.takeIf { it.isNotEmpty() }
    }

    /** Lowercased names an expression mentions: bare words and backquoted identifiers. */
    private fun referencedNames(expression: String): Set<String> {
        val names = mutableSetOf<String>()
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                c == '\'' || c == '"' -> i = SqlGuards.endOfLiteral(expression, i)
                c == '`' -> {
                    val end = SqlGuards.endOfLiteral(expression, i)
                    names += expression.substring(i + 1, (end - 1).coerceAtLeast(i + 1)).replace("``", "`").lowercase()
                    i = end
                }

                c.isLetter() || c == '_' || c == '$' -> {
                    val word = readWord(expression, i)
                    names += word.lowercase()
                    i += word.length
                }

                else -> i++
            }
        }
        return names
    }

    private fun hasSideEffect(expression: String): Boolean {
        val masked = blankLiterals(expression)
        return masked.contains(":=") || referencedNames(expression).any { it in SIDE_EFFECT_WORDS }
    }

    private fun quoteIdentifier(name: String) = "`" + name.replace("`", "``") + "`"

    /**
     * The `WHERE ...` to copy over, prefixed with a space; "" when the statement has none — that is
     * the whole table, which is a real answer — or null when what follows refuses a rewrite.
     */
    private fun tail(clean: String, masked: String, from: Int): String? {
        val keywords = topLevelKeywords(masked, from)
        // A LIMIT takes an arbitrary slice of the matching rows, so the count is not the number of
        // rows that would change.
        if (keywords.any { it.second == "limit" }) return null
        val where = keywords.firstOrNull { it.second == "where" } ?: return ""
        // ORDER BY only matters together with a LIMIT, which is already refused; dropping it keeps
        // the count query valid.
        val order = keywords.firstOrNull { it.first > where.first && it.second == "order" }
        return " " + clean.substring(where.first, order?.first ?: clean.length).trim()
    }

    /**
     * Every bare word of [masked] from [from] that stands outside all parentheses, lowercased and
     * paired with where it starts.
     *
     * Depth is what keeps a subquery's own WHERE or LIMIT from being mistaken for the statement's.
     */
    private fun topLevelKeywords(masked: String, from: Int): List<Pair<Int, String>> {
        val found = mutableListOf<Pair<Int, String>>()
        var depth = 0
        var index = from
        while (index < masked.length) {
            val c = masked[index]
            when {
                c == '(' -> { depth++; index++ }
                c == ')' -> { depth--; index++ }
                c.isLetter() || c == '_' -> {
                    val word = readWord(masked, index)
                    if (depth <= 0) found += index to word.lowercase()
                    index += word.length
                }

                else -> index++
            }
        }
        return found
    }

    private fun skipModifiers(masked: String, from: Int, modifiers: Set<String>): Int {
        var index = skipSpace(masked, from)
        while (true) {
            val word = readWord(masked, index)
            if (word.lowercase() !in modifiers) return index
            index = skipSpace(masked, index + word.length)
        }
    }

    private fun skipSpace(sql: String, from: Int): Int {
        var index = from
        while (index < sql.length && sql[index].isWhitespace()) index++
        return index
    }

    private fun readWord(sql: String, from: Int): String {
        var end = from
        while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_' || sql[end] == '$')) {
            end++
        }
        return sql.substring(from, end)
    }

    /**
     * A copy of [sql] with every comment replaced by spaces, the same length as the original.
     *
     * SqlGuards.strip collapses what it removes, which moves everything after it; here the indices
     * have to keep pointing at the same characters, because the table and the WHERE are copied out
     * of this text word for word.
     */
    private fun blankComments(sql: String): String = blank(sql, literals = false)

    /** The same, for the contents of strings and quoted identifiers. Comments are already gone. */
    private fun blankLiterals(sql: String): String = blank(sql, literals = true)

    private fun blank(sql: String, literals: Boolean): String {
        val out = sql.toCharArray()
        var index = 0
        while (index < sql.length) {
            val c = sql[index]
            val end = when {
                literals && (c == '\'' || c == '"' || c == '`') -> SqlGuards.endOfLiteral(sql, index)
                !literals && (sql.startsWith("--", index) || c == '#') ->
                    sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length

                !literals && sql.startsWith("/*", index) ->
                    sql.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: sql.length

                else -> null
            }
            if (end == null) {
                index++
            } else {
                for (i in index until end) out[i] = ' '
                index = end
            }
        }
        return String(out)
    }

    /** Suffix of the alias that carries an UPDATE's new value, after the column's own name. */
    const val NEW_SUFFIX = " (new)"

    /** How many rows the confirmation shows; the count says how many there are in all. */
    const val PREVIEW_ROWS = 20

    private val SIDE_EFFECT_WORDS = setOf(
        "nextval", "setval", "lastval", "get_lock", "release_lock", "release_all_locks", "sleep", "benchmark",
        "load_file", "master_pos_wait", "source_pos_wait",
    )

    /** `col`, `t.col` or `db.t.col`, each part bare or backquoted. */
    private val TARGET_COLUMN = Regex(
        "(?i)^((`(?:[^`]|``)+`|[a-z_\$][a-z0-9_\$]*)\\.){0,2}(`(?:[^`]|``)+`|[a-z_\$][a-z0-9_\$]*)$",
    )

    private val UPDATE_MODIFIERS = setOf("low_priority", "ignore")
    private val DELETE_MODIFIERS = setOf("low_priority", "quick", "ignore")
    private val TAIL_STARTERS = setOf("where", "order", "limit")

    /**
     * One table, optionally qualified and optionally aliased. A comma, a parenthesis or a join
     * keyword all fail to match here, which is how the complicated statements are refused: they do
     * not fit into a single `FROM`.
     */
    private val TABLE_REFERENCE = Regex(
        "(?i)^(`[^`]+`|\"[^\"]+\"|[a-z_\$][a-z0-9_\$]*)" +
            "(\\.(`[^`]+`|\"[^\"]+\"|[a-z_\$][a-z0-9_\$]*))?" +
            "(\\s+(as\\s+)?(`[^`]+`|[a-z_\$][a-z0-9_\$]*))?$",
    )
}

enum class WriteKind { UPDATE, DELETE }

/**
 * The SELECT behind a write's preview. For an UPDATE the new value of each of [changedColumns]
 * comes back as an extra column named `<column>` + [WriteImpact.NEW_SUFFIX], after all the
 * table's own (old-value) columns.
 */
data class WritePreviewQuery(val sql: String, val kind: WriteKind, val changedColumns: List<String>)

/**
 * The rows a write would change, as read just before it runs. [table] holds the old rows; for an
 * UPDATE each of [changedColumns] has its new value in the column named `<column> (new)`.
 * [totalRows] is the full count when it is known, so the card can say "first 20 of N".
 */
data class WriteRowPreview(
    val kind: WriteKind,
    val changedColumns: List<String>,
    val table: ResultTable,
    val totalRows: Long? = null,
    /** Which statement of the run this is, so a gap (one that could not be previewed) is not misnumbered. */
    val statementIndex: Int = 0,
)

/**
 * The ceiling on how many rows one hand-typed statement may change (§7.7).
 *
 * The number is a guess made before the statement runs, so it can only ever refuse the obvious
 * accident: a WHERE that matches the whole table instead of one row. An estimate we could not
 * derive never counts as exceeding the ceiling — refusing on a number nobody has would only teach
 * people to raise the limit to get past it.
 */
object AffectedRowLimit {

    const val DEFAULT_MAX_AFFECTED_ROWS = 1_000

    /** 0 means no ceiling, which is how the setting is turned off. */
    fun exceeds(estimate: Long?, limit: Int): Boolean =
        limit > 0 && estimate != null && estimate > limit
}
