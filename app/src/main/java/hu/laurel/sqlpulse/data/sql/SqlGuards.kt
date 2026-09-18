package hu.laurel.sqlpulse.data.sql

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
 */
object SqlGuards {

    const val DEFAULT_ROW_LIMIT = 500

    private val READ_STARTERS = setOf("select", "show", "describe", "desc", "explain", "with", "analyze")
    private val WRITE_STARTERS = setOf("insert", "update", "delete", "replace")

    fun classify(sql: String): StatementKind {
        val first = firstKeyword(strip(sql)) ?: return StatementKind.OTHER
        return when (first) {
            in READ_STARTERS -> StatementKind.READ
            in WRITE_STARTERS -> StatementKind.WRITE
            else -> StatementKind.OTHER
        }
    }

    /**
     * Appends a LIMIT when a read statement has none (§7.4). Returns the SQL unchanged otherwise,
     * so an explicit LIMIT in the query always wins.
     *
     * @return the SQL to run, and whether a limit was added — the UI notes that quietly above the
     *   result.
     */
    fun applyDefaultLimit(sql: String, limit: Int = DEFAULT_ROW_LIMIT): LimitResult {
        val trimmed = sql.trim().trimEnd(';')
        if (classify(trimmed) != StatementKind.READ) return LimitResult(trimmed, false)
        val stripped = strip(trimmed)
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
    fun parameters(sql: String): List<String> =
        Regex(":([a-zA-Z_][a-zA-Z0-9_]*)").findAll(strip(sql))
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
    fun isUnguardedWrite(sql: String): Boolean {
        if (classify(sql) != StatementKind.WRITE) return false
        val stripped = strip(sql)
        val starter = firstKeyword(stripped)
        // INSERT and REPLACE add rows rather than rewriting existing ones.
        if (starter != "update" && starter != "delete") return false
        return !Regex("(?i)\\bwhere\\b").containsMatchIn(stripped)
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
    fun bindParameters(sql: String): BoundStatement {
        val out = StringBuilder(sql.length)
        val order = mutableListOf<String>()
        var index = 0
        while (index < sql.length) {
            val c = sql[index]
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    val end = endOfLiteral(sql, index)
                    out.append(sql, index, end)
                    index = end
                }

                sql.startsWith("--", index) || c == '#' -> {
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
    private fun endOfLiteral(sql: String, start: Int): Int {
        val closing = sql[start]
        var index = start + 1
        while (index < sql.length) {
            val current = sql[index]
            if (current == '\\' && closing != '`') {
                index += 2
                continue
            }
            index++
            if (current == closing) {
                if (index < sql.length && sql[index] == closing) index++ else return index
            }
        }
        return index
    }

    private fun firstKeyword(strippedSql: String): String? =
        strippedSql.trimStart('(', ' ', '\t', '\n', '\r')
            .takeWhile { !it.isWhitespace() && it != '(' }
            .lowercase()
            .ifEmpty { null }

    /**
     * Removes comments and the contents of quoted literals and identifiers, so keyword matching
     * does not trip over a table called `limit_log` or a string containing "delete".
     */
    fun strip(sql: String): String {
        val out = StringBuilder(sql.length)
        var index = 0
        while (index < sql.length) {
            when (val c = sql[index]) {
                '\'', '"', '`' -> {
                    val closing = c
                    out.append(' ')
                    index++
                    while (index < sql.length) {
                        val current = sql[index]
                        if (current == '\\' && closing != '`') {
                            index += 2
                            continue
                        }
                        index++
                        if (current == closing) {
                            // A doubled quote is an escaped quote, not the end of the literal.
                            if (index < sql.length && sql[index] == closing) index++ else break
                        }
                    }
                }

                '-' -> if (sql.startsWith("--", index)) {
                    index = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length
                } else {
                    out.append(c)
                    index++
                }

                '#' -> index = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length

                '/' -> if (sql.startsWith("/*", index)) {
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
