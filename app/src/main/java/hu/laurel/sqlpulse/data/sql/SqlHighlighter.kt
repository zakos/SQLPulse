package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.sql.dialect.SqlGrammar
import hu.laurel.sqlpulse.data.sql.dialect.keywords.MySqlKeywords

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

    /**
     * @param grammar which characters open a quote and a comment: MySQL's by default, which is what
     *   the editor assumed before engines existed. `[x]` and `"x"` are names, `$$…$$` a string.
     * @param keywords the lower-case words to colour; see SqlKeywords for the per-engine sets.
     */
    fun tokenize(
        sql: String,
        grammar: SqlGrammar = SqlGrammar.MYSQL,
        keywords: Set<String> = MySqlKeywords.ALL,
    ): List<SqlToken> {
        val tokens = mutableListOf<SqlToken>()
        var index = 0

        while (index < sql.length) {
            val c = sql[index]
            when {
                grammar.opensQuote(sql, index) -> {
                    val end = grammar.endOfQuoted(sql, index)
                    val role = if (grammar.isIdentifierQuote(c)) TokenRole.QUOTED_IDENTIFIER else TokenRole.STRING
                    tokens += SqlToken(index, end, role)
                    index = end
                }

                grammar.opensLineComment(sql, index) -> {
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
                    if (word.lowercase() in keywords) {
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
}
