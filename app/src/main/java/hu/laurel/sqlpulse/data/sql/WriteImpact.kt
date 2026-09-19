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
    fun countQuery(sql: String): String? {
        // Comments go first so the derived query cannot carry half of one, and so the table
        // reference is read without a comment wedged into it.
        val clean = blankComments(sql.trim().trimEnd(';'))
        val masked = blankLiterals(clean)
        // Two statements in one string: whichever of them is the write, one count cannot speak for
        // both of them.
        if (masked.contains(';')) return null

        val start = skipSpace(masked, 0)
        val keyword = readWord(masked, start).lowercase()
        val after = skipSpace(masked, start + keyword.length)
        return when (keyword) {
            "update" -> updateCount(clean, masked, after)
            "delete" -> deleteCount(clean, masked, after)
            else -> null
        }
    }

    /** `UPDATE [modifiers] table [alias] SET ... [WHERE ...]` and nothing more adventurous. */
    private fun updateCount(clean: String, masked: String, from: Int): String? {
        val start = skipModifiers(masked, from, UPDATE_MODIFIERS)
        val keywords = topLevelKeywords(masked, start)
        val setAt = keywords.firstOrNull { it.second == "set" }?.first ?: return null
        val table = clean.substring(start, setAt).trim()
        if (!TABLE_REFERENCE.matches(table)) return null
        return "SELECT COUNT(*) FROM $table" + (tail(clean, masked, setAt) ?: return null)
    }

    /** `DELETE [modifiers] FROM table [alias] [WHERE ...]`, the single-table form only. */
    private fun deleteCount(clean: String, masked: String, from: Int): String? {
        val start = skipModifiers(masked, from, DELETE_MODIFIERS)
        // `DELETE t1 FROM a JOIN b` and `DELETE FROM t USING ...` name more than one table, and the
        // rows they remove are not the rows a count over any one of them returns.
        if (!readWord(masked, start).equals("from", ignoreCase = true)) return null
        val tableStart = skipSpace(masked, start + "from".length)
        val keywords = topLevelKeywords(masked, tableStart)
        if (keywords.any { it.second == "using" }) return null
        val stop = keywords.firstOrNull { it.second in TAIL_STARTERS }?.first ?: clean.length
        val table = clean.substring(tableStart, stop).trim()
        if (!TABLE_REFERENCE.matches(table)) return null
        return "SELECT COUNT(*) FROM $table" + (tail(clean, masked, stop) ?: return null)
    }

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
