package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlGrammar
import hu.laurel.sqlpulse.data.sql.dialect.SqlSyntax

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
    fun countQuery(sql: String, syntax: SqlSyntax = MySqlDialect): String? =
        parse(sql, syntax)?.let { "SELECT COUNT(*) FROM ${it.table}${it.tail}" }

    /**
     * The read-only SELECT that shows up to [limit] of the rows [sql] would change, or null when
     * it cannot be derived with the same confidence as the count (it refuses exactly what
     * [countQuery] refuses). Follows dbx's `dml_preview_sql.rs`: an UPDATE becomes
     * `SELECT *, <expr> AS "<col> (new)" ... FROM t WHERE ...`, a DELETE `SELECT * FROM t WHERE ...`.
     *
     * An UPDATE is refused on top of that when its SET list cannot be evaluated on the old row
     * alone: a column assigned twice, a later expression that reads an earlier-assigned column
     * (MySQL evaluates the list left to right, so it would see the new value, the preview the old
     * one), or an expression with a side effect (`NEXTVAL`, `GET_LOCK`, `@v := ...`, `SELECT ... INTO`,
     * `FOR UPDATE`) that running it as a SELECT would trigger for real, anywhere in the SET list or
     * the WHERE, subqueries included.
     */
    fun previewQuery(sql: String, limit: Int = PREVIEW_ROWS, syntax: SqlSyntax = MySqlDialect): WritePreviewQuery? {
        if (limit <= 0) return null
        val parsed = parse(sql, syntax) ?: return null
        val suffix = " FROM ${parsed.table}${parsed.tail} LIMIT $limit"
        val set = parsed.set ?: return WritePreviewQuery("SELECT *$suffix", WriteKind.DELETE, emptyList())
        val assignments = parseAssignments(set, syntax) ?: return null
        val columns = assignments.map { it.column }
        if (columns.map { it.lowercase() }.toSet().size != columns.size) return null
        assignments.forEachIndexed { index, assignment ->
            val earlier = columns.take(index).map { it.lowercase() }.toSet()
            if (assignment.expression.isBlank()) return null
            if (hasSideEffect(assignment.expression, syntax)) return null
            if (earlier.isNotEmpty() && referencedNames(assignment.expression, syntax).any { it in earlier }) return null
        }
        val news = assignments.joinToString(", ") {
            "(${it.expression}) AS ${syntax.quoteIdentifier(it.column + NEW_SUFFIX)}"
        }
        return WritePreviewQuery("SELECT *, $news$suffix", WriteKind.UPDATE, columns)
    }

    private class Parsed(val table: String, val tail: String, val set: String?)

    private class Assignment(val column: String, val expression: String)

    private fun parse(sql: String, syntax: SqlSyntax): Parsed? {
        // Comments go first so the derived query cannot carry half of one, and so the table
        // reference is read without a comment wedged into it.
        val clean = blankComments(sql.trim().trimEnd(';'), syntax.grammar)
        val masked = blankLiterals(clean, syntax.grammar)
        // Two statements in one string: whichever of them is the write, one count cannot speak for
        // both of them.
        if (masked.contains(';')) return null

        val start = skipSpace(masked, 0)
        val keyword = readWord(masked, start).lowercase()
        val after = skipSpace(clean, start + keyword.length)
        val parsed = when (keyword) {
            "update" -> parseUpdate(clean, masked, after, syntax)
            "delete" -> parseDelete(clean, masked, after, syntax)
            else -> null
        } ?: return null
        // The WHERE is copied into a SELECT that runs before the write does, so anything in it that
        // acts on the server (a lock, a sleep, a variable assignment, a locking read in a
        // subquery) would happen once more than the user agreed to.
        if (hasSideEffect(parsed.tail, syntax)) return null
        return parsed
    }

    /** `UPDATE [modifiers] table [alias] SET ... [WHERE ...]` and nothing more adventurous. */
    private fun parseUpdate(clean: String, masked: String, from: Int, syntax: SqlSyntax): Parsed? {
        val start = skipModifiers(clean, from, UPDATE_MODIFIERS)
        val keywords = topLevelKeywords(masked, start)
        val setAt = keywords.firstOrNull { it.second == "set" }?.first ?: return null
        val table = clean.substring(start, setAt).trim()
        if (!TABLE_REFERENCE.matches(table) || onlyTable(table, syntax)) return null
        // PostgreSQL's `UPDATE t SET ... FROM other WHERE ...` joins a second table: the rows it
        // changes are not the rows the WHERE matches in `t` alone.
        if (postgres(syntax) && keywords.any { it.first > setAt && it.second == "from" }) return null
        val tail = tail(clean, masked, setAt, syntax) ?: return null
        // The SET list ends where the WHERE (or ORDER BY) begins; LIMIT is already refused.
        val setEnd = keywords.firstOrNull { it.first > setAt && it.second in tailStarters(syntax) }?.first ?: clean.length
        return Parsed(table, tail, clean.substring(setAt + "set".length, setEnd).trim())
    }

    /** `DELETE [modifiers] FROM table [alias] [WHERE ...]`, the single-table form only. */
    private fun parseDelete(clean: String, masked: String, from: Int, syntax: SqlSyntax): Parsed? {
        val start = skipModifiers(clean, from, DELETE_MODIFIERS)
        // `DELETE t1 FROM a JOIN b` and `DELETE FROM t USING ...` name more than one table, and the
        // rows they remove are not the rows a count over any one of them returns.
        if (!readWord(masked, start).equals("from", ignoreCase = true)) return null
        val tableStart = skipSpace(clean, start + "from".length)
        val keywords = topLevelKeywords(masked, tableStart)
        if (keywords.any { it.second == "using" }) return null
        val stop = keywords.firstOrNull { it.second in tailStarters(syntax) }?.first ?: clean.length
        val table = clean.substring(tableStart, stop).trim()
        if (!TABLE_REFERENCE.matches(table) || onlyTable(table, syntax)) return null
        return Parsed(table, tail(clean, masked, stop, syntax) ?: return null, null)
    }

    /**
     * `col = expr, col2 = expr2` split at the top level, or null when an item is not that shape.
     * Commas inside parentheses (`CONCAT(a, b)`) or literals (`'a, b'`) do not split.
     */
    private fun parseAssignments(set: String, syntax: SqlSyntax): List<Assignment>? {
        val masked = blankLiterals(set, syntax.grammar)
        val idQuote = identifierQuote(syntax)
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
            val eq = blankLiterals(part, syntax.grammar).indexOf('=')
            if (eq <= 0) return null
            val target = part.substring(0, eq).trim()
            // The last group, not a split on '.': a quoted name may contain one.
            val last = targetColumn(idQuote).matchEntire(target)?.groupValues?.get(3) ?: return null
            val column = when {
                last.startsWith(idQuote) ->
                    last.substring(1, last.length - 1).replace("$idQuote$idQuote", idQuote.toString())
                // PostgreSQL folds a bare name to lower case, and the column is looked up by name.
                postgres(syntax) -> last.lowercase()
                else -> last
            }
            Assignment(column, part.substring(eq + 1).trim())
        }
        return result.takeIf { it.isNotEmpty() }
    }

    /** Lowercased names an expression mentions: bare words and backquoted identifiers. */
    private fun referencedNames(expression: String, syntax: SqlSyntax): Set<String> {
        val names = mutableSetOf<String>()
        val grammar = syntax.grammar
        val idQuote = identifierQuote(syntax)
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                // Strings are skipped; the engine's quoted identifiers (backticks in MySQL, double
                // quotes in PostgreSQL) are names.
                grammar.opensQuote(expression, i) && c == idQuote -> {
                    val end = grammar.endOfQuoted(expression, i)
                    names += expression.substring(i + 1, (end - 1).coerceAtLeast(i + 1))
                        .replace("$idQuote$idQuote", idQuote.toString()).lowercase()
                    i = end
                }

                grammar.opensQuote(expression, i) -> i = grammar.endOfQuoted(expression, i)

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

    /**
     * True when running [text] as part of a SELECT would do something besides read.
     *
     * The whole text is scanned, parentheses and all: a subquery is where `(SELECT SLEEP(5))` or
     * `(SELECT ... FOR UPDATE)` hides from a check that only looks at the top level. Literals and
     * comments are masked first, so a string that merely mentions SLEEP does not refuse anything.
     */
    private fun hasSideEffect(text: String, syntax: SqlSyntax): Boolean {
        val masked = blankLiterals(blankComments(text, syntax.grammar), syntax.grammar)
        val locking = if (postgres(syntax)) POSTGRES_LOCKING_OR_INTO else LOCKING_OR_INTO
        return masked.contains(":=") ||
            locking.containsMatchIn(masked) ||
            referencedNames(blankComments(text, syntax.grammar), syntax).any { name ->
                name in SIDE_EFFECT_WORDS || (
                    postgres(syntax) &&
                        (name in POSTGRES_SIDE_EFFECT_WORDS || POSTGRES_SIDE_EFFECT_PREFIXES.any(name::startsWith))
                    )
            }
    }

    /** PostgreSQL's `ONLY t` leaves out the table's children: not the rows a plain count would give. */
    private fun onlyTable(table: String, syntax: SqlSyntax) =
        postgres(syntax) && table.startsWith("only", ignoreCase = true) && table.getOrNull(4)?.isWhitespace() == true

    private fun postgres(syntax: SqlSyntax) = syntax.engine == DatabaseEngine.POSTGRESQL

    /** The character that opens this engine's quoted identifiers: a backtick, or a double quote. */
    private fun identifierQuote(syntax: SqlSyntax): Char = syntax.quoteIdentifier("x").first()

    /** What ends the SET list or the table reference; PostgreSQL's RETURNING comes after the WHERE. */
    private fun tailStarters(syntax: SqlSyntax): Set<String> =
        if (postgres(syntax)) TAIL_STARTERS + "returning" else TAIL_STARTERS

    /**
     * The `WHERE ...` to copy over, prefixed with a space; "" when the statement has none — that is
     * the whole table, which is a real answer — or null when what follows refuses a rewrite.
     */
    private fun tail(clean: String, masked: String, from: Int, syntax: SqlSyntax): String? {
        val keywords = topLevelKeywords(masked, from)
        // A LIMIT takes an arbitrary slice of the matching rows, so the count is not the number of
        // rows that would change.
        if (keywords.any { it.second == "limit" }) return null
        val where = keywords.firstOrNull { it.second == "where" } ?: return ""
        // ORDER BY only matters together with a LIMIT, which is already refused; dropping it keeps
        // the count query valid.
        val order = keywords.firstOrNull { it.first > where.first && it.second == "order" }
        // RETURNING is output, not selection: the count and the preview must not carry it along.
        val returning = keywords.firstOrNull {
            postgres(syntax) && it.first > where.first && it.second == "returning"
        }
        val end = listOfNotNull(order?.first, returning?.first).minOrNull() ?: clean.length
        return " " + clean.substring(where.first, end).trim()
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
    private fun blankComments(sql: String, grammar: SqlGrammar): String = blank(sql, grammar, literals = false)

    /** The same, for the contents of strings and quoted identifiers. Comments are already gone. */
    private fun blankLiterals(sql: String, grammar: SqlGrammar): String = blank(sql, grammar, literals = true)

    private fun blank(sql: String, grammar: SqlGrammar, literals: Boolean): String {
        val out = sql.toCharArray()
        var index = 0
        while (index < sql.length) {
            val c = sql[index]
            val end = when {
                literals && grammar.opensQuote(sql, index) -> grammar.endOfQuoted(sql, index)
                !literals && grammar.opensLineComment(sql, index) ->
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

    /** `SELECT ... INTO @v`, and the locking reads, which take row locks just by being run. */
    private val LOCKING_OR_INTO = Regex(
        "(?i)\\binto\\b|\\bfor\\s+(update|share)\\b|\\block\\s+in\\s+share\\s+mode\\b",
    )

    /** `col`, `t.col` or `db.t.col`, each part bare or quoted the way the engine quotes ([q]). */
    private fun targetColumn(q: Char): Regex {
        val quoted = "$q(?:[^$q]|$q$q)+$q"
        return Regex("(?i)^(($quoted|[a-z_\\$][a-z0-9_\\$]*)\\.){0,2}($quoted|[a-z_\\$][a-z0-9_\\$]*)$")
    }

    /** PostgreSQL functions that act on the server or the session when a SELECT merely calls them. */
    private val POSTGRES_SIDE_EFFECT_WORDS = setOf(
        "pg_sleep", "pg_sleep_for", "pg_sleep_until", "set_config", "pg_terminate_backend",
        "pg_cancel_backend", "pg_reload_conf", "lo_import", "lo_export", "lo_unlink", "lo_create",
        "pg_notify", "pg_rotate_logfile", "pg_switch_wal", "pg_create_restore_point",
    )
    private val POSTGRES_SIDE_EFFECT_PREFIXES = listOf("pg_advisory_", "pg_try_advisory_", "dblink")
    private val POSTGRES_LOCKING_OR_INTO = Regex(
        "(?i)\\binto\\b|\\bfor\\s+(no\\s+key\\s+update|key\\s+share|update|share)\\b",
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
