package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.sql.dialect.SqlGrammar

/** What a statement will do to the database. Decides whether a read-only connection accepts it. */
enum class StatementKind {
    /** SELECT, SHOW, DESCRIBE, EXPLAIN, WITH ... SELECT. */
    READ,

    /** INSERT, UPDATE, DELETE, REPLACE. */
    WRITE,

    /** DDL and everything else: out of scope for this app (§2). */
    OTHER,
}

/**
 * Static SQL inspection, kept deliberately small and syntactic.
 *
 * This is not a parser and does not pretend to be a security boundary — the real limits are the
 * MySQL grants (§3) and the read-only flag on the JDBC connection. It exists to catch the obvious
 * mistakes before they travel: an UPDATE typed into a read-only connection, or a SELECT with no
 * LIMIT against a table with millions of rows (§7.4).
 *
 * Every scanner takes a [SqlGrammar] — which quotes and comments the engine has — defaulting to
 * MySQL's, which is what these functions knew before there were other engines. The dialects
 * (data/sql/dialect) pass their own; the MySQL behaviour is unchanged by construction.
 */
object SqlGuards {

    const val DEFAULT_ROW_LIMIT = 500

    fun classify(sql: String, grammar: SqlGrammar = SqlGrammar.MYSQL): StatementKind {
        val stripped = strip(sql, grammar)
        val first = firstKeyword(stripped) ?: return StatementKind.OTHER
        // A CTE says nothing about what the statement does: MySQL 8 lets an UPDATE or a DELETE
        // follow one, and `WITH ... UPDATE` counted as a read is exactly the accident this guard
        // is here to catch.
        if (first == "with") return classifyAfterCte(stripped, grammar)
        return when (first) {
            in grammar.readStarters -> StatementKind.READ
            in grammar.writeStarters -> StatementKind.WRITE
            else -> StatementKind.OTHER
        }
    }

    /**
     * What a `WITH` statement really is, decided by the statement its definitions lead up to.
     *
     * When the prelude cannot be walked — a shape we do not know, or unbalanced parentheses — the
     * fallback is a word scan outside every parenthesis: a CTE body always sits inside one, so a
     * write keyword found at that level belongs to the statement itself. Erring towards WRITE
     * there costs a confirmation dialog; erring towards READ would let the write past it.
     */
    private fun classifyAfterCte(strippedSql: String, grammar: SqlGrammar): StatementKind {
        val tail = cteTail(strippedSql)
            ?: return if (hasTopLevelWrite(strippedSql, grammar)) StatementKind.WRITE else StatementKind.READ
        return when (firstKeyword(tail)) {
            in grammar.writeStarters -> StatementKind.WRITE
            in grammar.readStarters -> StatementKind.READ
            else -> if (hasTopLevelWrite(tail, grammar)) StatementKind.WRITE else StatementKind.READ
        }
    }

    /**
     * The statement that follows the CTE definitions of [strippedSql], or null when the prelude is
     * not shaped the way MySQL writes one.
     *
     * Expects [strip]ped input, so every parenthesis it counts is a real one rather than a
     * character inside a string or a comment.
     */
    private fun cteTail(strippedSql: String): String? {
        var index = skipSpace(strippedSql, 0)
        if (!readWord(strippedSql, index).equals("with", ignoreCase = true)) return null
        index = skipSpace(strippedSql, index + "with".length)
        if (readWord(strippedSql, index).equals("recursive", ignoreCase = true)) {
            index = skipSpace(strippedSql, index + "recursive".length)
        }
        while (true) {
            val name = readWord(strippedSql, index)
            // A backticked CTE name is blanked out by strip, which leaves AS as the first word.
            if (!name.equals("as", ignoreCase = true)) {
                if (name.isEmpty()) return null
                index = skipSpace(strippedSql, index + name.length)
                // The column list a CTE may declare before its AS.
                if (strippedSql.getOrNull(index) == '(') {
                    index = skipSpace(strippedSql, endOfParens(strippedSql, index) ?: return null)
                }
                if (!readWord(strippedSql, index).equals("as", ignoreCase = true)) return null
            }
            index = skipSpace(strippedSql, index + "as".length)
            // MySQL 8 allows the body to be marked MATERIALIZED or NOT MATERIALIZED.
            while (true) {
                val hint = readWord(strippedSql, index)
                if (!hint.equals("not", true) && !hint.equals("materialized", true)) break
                index = skipSpace(strippedSql, index + hint.length)
            }
            if (strippedSql.getOrNull(index) != '(') return null
            index = skipSpace(strippedSql, endOfParens(strippedSql, index) ?: return null)
            if (strippedSql.getOrNull(index) != ',') return strippedSql.substring(index)
            index = skipSpace(strippedSql, index + 1)
        }
    }

    private fun skipSpace(sql: String, from: Int): Int {
        var index = from
        while (index < sql.length && sql[index].isWhitespace()) index++
        return index
    }

    /** The bare word starting at [from], empty when a word does not start there. */
    private fun readWord(sql: String, from: Int): String {
        var end = from
        while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_' || sql[end] == '$')) {
            end++
        }
        return sql.substring(from, end)
    }

    /** @return the index just past the parenthesis group opening at [start], or null if unclosed. */
    private fun endOfParens(sql: String, start: Int): Int? {
        var depth = 0
        var index = start
        while (index < sql.length) {
            when (sql[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return index + 1
            }
            index++
        }
        return null
    }

    /** True when a write keyword stands outside every parenthesis of [strippedSql]. */
    private fun hasTopLevelWrite(strippedSql: String, grammar: SqlGrammar): Boolean {
        var depth = 0
        var index = 0
        while (index < strippedSql.length) {
            val c = strippedSql[index]
            when {
                c == '(' -> { depth++; index++ }
                c == ')' -> { depth--; index++ }
                c.isLetter() || c == '_' -> {
                    val word = readWord(strippedSql, index)
                    if (depth <= 0 && word.lowercase() in grammar.writeStarters) return true
                    index += word.length
                }

                else -> index++
            }
        }
        return false
    }

    /**
     * Appends a LIMIT when a read statement has none (§7.4). Returns the SQL unchanged otherwise,
     * so an explicit LIMIT in the query always wins.
     *
     * @return the SQL to run, and whether a limit was added — the UI notes that quietly above the
     *   result.
     */
    fun applyDefaultLimit(
        sql: String,
        limit: Int = DEFAULT_ROW_LIMIT,
        grammar: SqlGrammar = SqlGrammar.MYSQL,
    ): LimitResult {
        val trimmed = sql.trim().trimEnd(';')
        if (classify(trimmed, grammar) != StatementKind.READ) return LimitResult(trimmed, false)
        val stripped = strip(trimmed, grammar)
        val starter = firstKeyword(stripped)
        // SHOW/DESCRIBE return small, fixed result sets and reject LIMIT in most forms.
        if (starter != "select" && starter != "with") return LimitResult(trimmed, false)
        if (hasLimit(stripped)) return LimitResult(trimmed, false)
        return LimitResult("$trimmed LIMIT $limit", true)
    }

    data class LimitResult(val sql: String, val limitAdded: Boolean)

    /** True when the statement already ends in a LIMIT clause outside of strings and comments. */
    fun hasLimit(strippedSql: String): Boolean =
        Regex("(?i)\\blimit\\s+(\\d+|\\?|:[a-z_][a-z0-9_]*)").containsMatchIn(strippedSql)

    /** The `:name` placeholders of a saved query (§7.4), in order of first appearance. */
    fun parameters(sql: String, grammar: SqlGrammar = SqlGrammar.MYSQL): List<String> =
        Regex(":([a-zA-Z_][a-zA-Z0-9_]*)").findAll(strip(sql, grammar))
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    /**
     * True for an UPDATE or DELETE with no WHERE clause — a statement that rewrites or empties the
     * whole table.
     *
     * A LIMIT does not count: `DELETE FROM t LIMIT 10` still picks its ten rows arbitrarily. This
     * is a typo guard, not a security boundary; the boundary is the MySQL grants (§3).
     */
    fun isUnguardedWrite(sql: String, grammar: SqlGrammar = SqlGrammar.MYSQL): Boolean {
        if (classify(sql, grammar) != StatementKind.WRITE) return false
        val stripped = strip(sql, grammar)
        // A WHERE inside a CTE body guards the CTE, not the UPDATE that follows it. A prelude we
        // cannot walk falls back to the whole statement, which errs towards letting it run — the
        // confirmation dialog still stands in front of it.
        val body = if (firstKeyword(stripped) == "with") cteTail(stripped) ?: stripped else stripped
        val starter = firstKeyword(body)
        // INSERT and REPLACE add rows rather than rewriting existing ones.
        if (starter != "update" && starter != "delete") return false
        return !Regex("(?i)\\bwhere\\b").containsMatchIn(body)
    }

    /**
     * The database named by a bare `USE somedb` statement, or null for anything else.
     *
     * `USE` cannot simply be executed: the app hands out connections from a pool, so it would
     * switch one connection and leave the others where they were. It is treated as a request to
     * change the session's current database instead, which then applies to every connection.
     */
    fun useTarget(sql: String): String? {
        // Matched on the raw statement: stripping would throw away a backticked database name.
        val trimmed = sql.trim().trimEnd(';').trim()
        // A backticked or quoted database name may contain spaces, so it is matched as one token.
        val match = Regex("(?i)^use\\s+(`[^`]*(?:``[^`]*)*`|\"[^\"]*\"|\\S+)$").find(trimmed)
            ?: return null
        return unquoteIdentifier(match.groupValues[1]).takeIf { it.isNotEmpty() }
    }

    /** Strips backticks or quotes from an identifier, undoubling any escaped quote inside. */
    fun unquoteIdentifier(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length < 2) return trimmed
        val first = trimmed.first()
        if (first != '`' && first != '"' && first != '\'') return trimmed
        if (trimmed.last() != first) return trimmed
        return trimmed.substring(1, trimmed.length - 1).replace("$first$first", first.toString())
    }

    /**
     * Rewrites `:name` placeholders into JDBC `?` markers and reports the binding order (§7.4).
     *
     * Placeholders inside string literals, quoted identifiers and comments are left alone, so a
     * query containing `'12:30'` or `time::text` is not mangled. A name may appear several times;
     * it is then bound several times, which is what a prepared statement needs.
     */
    fun bindParameters(sql: String, grammar: SqlGrammar = SqlGrammar.MYSQL): BoundStatement {
        val out = StringBuilder(sql.length)
        val order = mutableListOf<String>()
        var index = 0
        while (index < sql.length) {
            val c = sql[index]
            when {
                grammar.opensQuote(sql, index) -> {
                    val end = grammar.endOfQuoted(sql, index)
                    out.append(sql, index, end)
                    index = end
                }

                grammar.opensLineComment(sql, index) -> {
                    val end = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length
                    out.append(sql, index, end)
                    index = end
                }

                sql.startsWith("/*", index) -> {
                    val end = (sql.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2)) ?: sql.length
                    out.append(sql, index, end)
                    index = end
                }

                // "::" is a cast, not a placeholder.
                c == ':' && sql.getOrNull(index + 1) == ':' -> {
                    out.append("::")
                    index += 2
                }

                c == ':' && sql.getOrNull(index + 1)?.isValidParameterStart() == true -> {
                    var end = index + 1
                    while (end < sql.length && sql[end].isValidParameterChar()) end++
                    order += sql.substring(index + 1, end)
                    out.append('?')
                    index = end
                }

                else -> {
                    out.append(c)
                    index++
                }
            }
        }
        return BoundStatement(out.toString(), order)
    }

    data class BoundStatement(val sql: String, val parameterOrder: List<String>)

    private fun Char.isValidParameterStart() = isLetter() || this == '_'

    private fun Char.isValidParameterChar() = isLetterOrDigit() || this == '_'

    /** @return the index just past the closing quote of the literal starting at [start]. */
    internal fun endOfLiteral(sql: String, start: Int, grammar: SqlGrammar = SqlGrammar.MYSQL): Int =
        grammar.endOfQuoted(sql, start)

    private fun firstKeyword(strippedSql: String): String? =
        strippedSql.trimStart('(', ' ', '\t', '\n', '\r')
            .takeWhile { !it.isWhitespace() && it != '(' }
            .lowercase()
            .ifEmpty { null }

    /**
     * Removes comments and the contents of quoted literals and identifiers, so keyword matching
     * does not trip over a table called `limit_log` or a string containing "delete".
     */
    fun strip(sql: String, grammar: SqlGrammar = SqlGrammar.MYSQL): String {
        val out = StringBuilder(sql.length)
        var index = 0
        while (index < sql.length) {
            val c = sql[index]
            when {
                // A doubled quote inside is an escaped quote, not the end of the literal; the
                // grammar knows that, and whether a backslash escapes too.
                grammar.opensQuote(sql, index) -> {
                    out.append(' ')
                    index = grammar.endOfQuoted(sql, index)
                }

                grammar.opensLineComment(sql, index) ->
                    index = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length

                c == '/' -> if (sql.startsWith("/*", index)) {
                    val end = sql.indexOf("*/", index + 2)
                    index = if (end >= 0) end + 2 else sql.length
                    out.append(' ')
                } else {
                    out.append(c)
                    index++
                }

                else -> {
                    out.append(c)
                    index++
                }
            }
        }
        return out.toString()
    }
}
