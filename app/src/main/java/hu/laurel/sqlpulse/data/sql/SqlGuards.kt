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
