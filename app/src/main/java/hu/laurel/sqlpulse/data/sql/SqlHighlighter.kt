package hu.laurel.sqlpulse.data.sql

/** What a stretch of SQL text is, for colouring purposes (§7.4). */
enum class TokenRole { KEYWORD, STRING, NUMBER, COMMENT, QUOTED_IDENTIFIER, PARAMETER }

/** Half-open range [start, end) of [sql] that should be drawn as [role]. */
data class SqlToken(val start: Int, val end: Int, val role: TokenRole)

/**
 * Tokenises SQL far enough to colour it. Deliberately UI-free so it can be tested on the JVM: the
 * Compose layer only maps a [TokenRole] to a colour.
 *
 * Anything not returned here is plain text; the ranges never overlap and come out in order.
 */
object SqlHighlighter {

    private val KEYWORDS = setOf(
        "select", "from", "where", "and", "or", "not", "null", "is", "in", "like", "between",
        "join", "inner", "left", "right", "outer", "full", "cross", "on", "using",
        "group", "by", "having", "order", "asc", "desc", "limit", "offset",
        "insert", "into", "values", "update", "set", "delete", "replace",
        "create", "alter", "drop", "table", "index", "view", "database", "schema",
        "as", "distinct", "union", "all", "case", "when", "then", "else", "end",
        "with", "show", "describe", "explain", "count", "sum", "avg", "min", "max",
        "true", "false", "primary", "key", "foreign", "references", "default", "exists",
    )

    fun tokenize(sql: String): List<SqlToken> {
        val tokens = mutableListOf<SqlToken>()
        var index = 0

        while (index < sql.length) {
            val c = sql[index]
            when {
                c == '\'' || c == '"' -> {
                    val end = skipLiteral(sql, index)
                    tokens += SqlToken(index, end, TokenRole.STRING)
                    index = end
                }

                c == '`' -> {
                    val end = skipLiteral(sql, index)
                    tokens += SqlToken(index, end, TokenRole.QUOTED_IDENTIFIER)
                    index = end
                }

                sql.startsWith("--", index) || c == '#' -> {
                    val end = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length
                    tokens += SqlToken(index, end, TokenRole.COMMENT)
                    index = end
                }

                sql.startsWith("/*", index) -> {
                    val end = (sql.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2)) ?: sql.length
                    tokens += SqlToken(index, end, TokenRole.COMMENT)
                    index = end
                }

                // Consume "::" whole, so the cast operator cannot leave a stray ":" behind that
                // then looks like the start of a parameter.
                c == ':' && sql.getOrNull(index + 1) == ':' -> index += 2

                c == ':' && sql.getOrNull(index + 1)?.let { it.isLetter() || it == '_' } == true -> {
                    var end = index + 1
                    while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_')) end++
                    tokens += SqlToken(index, end, TokenRole.PARAMETER)
                    index = end
                }

                c.isDigit() && !isWordCharacter(sql.getOrNull(index - 1)) -> {
                    var end = index
                    while (end < sql.length && (sql[end].isDigit() || sql[end] == '.')) end++
                    tokens += SqlToken(index, end, TokenRole.NUMBER)
                    index = end
                }

                c.isLetter() || c == '_' -> {
                    var end = index
                    while (end < sql.length && isWordCharacter(sql[end])) end++
                    val word = sql.substring(index, end)
                    if (word.lowercase() in KEYWORDS) {
                        tokens += SqlToken(index, end, TokenRole.KEYWORD)
                    }
                    index = end
                }

                else -> index++
            }
        }
        return tokens
    }

    private fun isWordCharacter(c: Char?): Boolean =
        c != null && (c.isLetterOrDigit() || c == '_')

    /** @return the index just past the closing quote, or the end of the text if it never closes. */
    private fun skipLiteral(sql: String, start: Int): Int {
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
}
