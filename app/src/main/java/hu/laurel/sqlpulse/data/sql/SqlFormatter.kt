package hu.laurel.sqlpulse.data.sql

/**
 * Lays a statement out over several lines so it can be read on a phone.
 *
 * This is a formatter, not a parser: it walks the text token by token, keeping strings,
 * identifiers and comments exactly as they are, and only decides where the line breaks go. That
 * limit is deliberate — a formatter that understood SQL well enough to rewrite it would also be
 * able to change what it means, and no layout is worth that.
 *
 * What it does:
 *  - each major clause starts a new line,
 *  - a comma in a top-level list starts a new line, so a long SELECT list reads downwards,
 *  - a subquery is indented, while an ordinary bracket — a function call, an IN list — is left
 *    inline, because breaking `count(*)` over three lines helps nobody,
 *  - keyword case is left alone: it is the author's, and MySQL does not care.
 */
object SqlFormatter {

    private const val INDENT = "    "

    /** Keywords that begin a line of their own. */
    private val CLAUSES = setOf(
        "select", "from", "where", "group", "having", "order", "limit", "offset",
        "union", "insert", "update", "delete", "set", "values", "returning",
    )

    /** Join keywords, which also begin a line but can be preceded by their qualifiers. */
    private val JOINS = setOf("join", "straight_join")

    private val JOIN_QUALIFIERS = setOf("inner", "left", "right", "full", "cross", "outer", "natural")

    /** Second words of a two-word clause, which must not start a line of their own. */
    private val CONTINUATIONS = setOf("by")

    fun format(sql: String): String {
        val tokens = tokenize(sql)
        if (tokens.isEmpty()) return sql

        val out = StringBuilder()
        var depth = 0
        var lineIsEmpty = true
        var previousWord: String? = null
        // One entry per open bracket: true when it was indented, so the closer knows what to undo.
        val brackets = ArrayDeque<Boolean>()

        fun newLine() {
            if (!lineIsEmpty) {
                out.append('\n')
                lineIsEmpty = true
            }
            if (lineIsEmpty) out.append(INDENT.repeat(depth))
        }

        fun appendToken(text: String) {
            if (!lineIsEmpty && needsSpaceBefore(out, text)) out.append(' ')
            out.append(text)
            lineIsEmpty = false
        }

        for ((position, token) in tokens.withIndex()) {
            when {
                token.kind == TokenKind.WHITESPACE -> Unit

                token.kind == TokenKind.COMMENT -> {
                    // A comment keeps its own line: a trailing "-- ..." would otherwise swallow
                    // whatever the next line break was supposed to put after it.
                    newLine()
                    appendToken(token.text)
                    out.append('\n')
                    lineIsEmpty = true
                    out.append(INDENT.repeat(depth))
                }

                token.text == "(" -> {
                    val subquery = startsSubquery(tokens, position)
                    appendToken(token.text)
                    brackets.addLast(subquery)
                    if (subquery) depth++
                }

                token.text == ")" -> {
                    if (brackets.removeLastOrNull() == true) {
                        depth = (depth - 1).coerceAtLeast(0)
                        newLine()
                    }
                    appendToken(token.text)
                }

                token.text == "," && brackets.isEmpty() -> {
                    // The comma stays at the end of the line it belongs to, as in written prose,
                    // and what follows is indented under the clause it belongs to.
                    appendToken(token.text)
                    out.append('\n').append(INDENT.repeat(depth + 1))
                    lineIsEmpty = false
                }

                token.kind == TokenKind.WORD && startsLine(token.text, previousWord) -> {
                    newLine()
                    appendToken(token.text)
                }

                else -> appendToken(token.text)
            }
            if (token.kind == TokenKind.WORD) previousWord = token.text.lowercase()
            if (token.kind != TokenKind.WHITESPACE && token.kind != TokenKind.COMMENT) {
                if (token.text == "(") previousWord = null
            }
        }
        return out.toString().trim().lines().joinToString("\n") { it.trimEnd() }
    }

    /** True when the bracket opens a subquery rather than a function call or a value list. */
    private fun startsSubquery(tokens: List<Token>, openIndex: Int): Boolean {
        val next = tokens.drop(openIndex + 1)
            .firstOrNull { it.kind != TokenKind.WHITESPACE && it.kind != TokenKind.COMMENT }
            ?: return false
        return next.kind == TokenKind.WORD && next.text.lowercase() in CLAUSES
    }

    private fun startsLine(word: String, previousWord: String?): Boolean {
        val lower = word.lowercase()
        if (lower in CONTINUATIONS) return false
        if (lower in CLAUSES) return true
        if (lower in JOINS) return previousWord !in JOIN_QUALIFIERS
        // "LEFT JOIN" breaks before LEFT, not between the two words.
        return lower in JOIN_QUALIFIERS
    }

    private fun needsSpaceBefore(out: StringBuilder, text: String): Boolean {
        val last = out.lastOrNull() ?: return false
        // Nothing to separate from: a fresh line, its indent, or an opening bracket.
        if (last == '(' || last == '\n' || last == ' ') return false
        if (text == "," || text == ")" || text == ";") return false
        // A bracket right after a name is a call or a list belonging to that name: count(*).
        if (text == "(") return !(last.isLetterOrDigit() || last == '_' || last == '`')
        return true
    }

    private enum class TokenKind { WORD, STRING, COMMENT, SYMBOL, WHITESPACE }

    private data class Token(val text: String, val kind: TokenKind)

    private fun tokenize(sql: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < sql.length) {
            val c = sql[index]
            when {
                c.isWhitespace() -> {
                    val start = index
                    while (index < sql.length && sql[index].isWhitespace()) index++
                    tokens += Token(sql.substring(start, index), TokenKind.WHITESPACE)
                }

                c == '\'' || c == '"' || c == '`' -> {
                    val start = index
                    index = skipQuoted(sql, index, c)
                    tokens += Token(sql.substring(start, index), TokenKind.STRING)
                }

                sql.startsWith("--", index) || c == '#' -> {
                    val start = index
                    val newline = sql.indexOf('\n', index)
                    index = if (newline < 0) sql.length else newline
                    tokens += Token(sql.substring(start, index).trimEnd(), TokenKind.COMMENT)
                }

                sql.startsWith("/*", index) -> {
                    val start = index
                    val close = sql.indexOf("*/", index + 2)
                    index = if (close < 0) sql.length else close + 2
                    tokens += Token(sql.substring(start, index), TokenKind.COMMENT)
                }

                c.isLetterOrDigit() || c == '_' || c == '.' || c == '@' || c == '$' || c == ':' -> {
                    val start = index
                    while (index < sql.length &&
                        (sql[index].isLetterOrDigit() || sql[index] in "_.@$:")
                    ) {
                        index++
                    }
                    tokens += Token(sql.substring(start, index), TokenKind.WORD)
                }

                else -> {
                    tokens += Token(c.toString(), TokenKind.SYMBOL)
                    index++
                }
            }
        }
        return tokens
    }

    private fun skipQuoted(text: String, from: Int, quote: Char): Int {
        var index = from + 1
        while (index < text.length) {
            val c = text[index]
            if (c == '\\' && quote != '`') {
                index += 2
                continue
            }
            index++
            if (c == quote) {
                if (index < text.length && text[index] == quote) index++ else return index
            }
        }
        return index
    }
}
