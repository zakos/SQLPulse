package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.CheckConstraint

/**
 * CHECK constraints out of a SQLite `CREATE TABLE` text.
 *
 * SQLite stores no catalog for them (`pragma_table_info` knows nothing of CHECK), so the schema
 * comparison has to read the one place they are written down. The scan is deliberately lenient
 * rather than a SQL parser: it walks the text token by token, skips string literals, quoted
 * identifiers and comments (a `'check ('` inside a DEFAULT must not count), and takes every
 * word `CHECK` that is followed by `(` as a constraint, with the balanced parenthesis as its
 * expression. A preceding `CONSTRAINT <name>` names it; otherwise it gets a positional name,
 * which is what the comparison then pairs by expression instead.
 */
internal object SqliteChecks {

    fun parse(createTable: String): List<CheckConstraint> {
        val tokens = tokenize(createTable)
        val found = mutableListOf<CheckConstraint>()
        var unnamed = 0
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.kind == Kind.WORD && token.text.equals("check", ignoreCase = true) &&
                tokens.getOrNull(i + 1)?.let { it.kind == Kind.SYMBOL && it.text == "(" } == true
            ) {
                val close = matching(tokens, i + 1)
                val end = if (close > i + 1 && tokens[close].text == ")") tokens[close].start else createTable.length
                val expression = createTable.substring(tokens[i + 1].end, end).trim()
                val named = i >= 2 && tokens[i - 2].kind == Kind.WORD &&
                    tokens[i - 2].text.equals("constraint", ignoreCase = true)
                val name = if (named) unquote(tokens[i - 1]) else "check_${++unnamed}"
                found += CheckConstraint(name = name, expression = expression.ifEmpty { null })
                i = close + 1
            } else {
                i++
            }
        }
        return found
    }

    private enum class Kind { WORD, QUOTED, STRING, SYMBOL }

    private class Token(val kind: Kind, val text: String, val start: Int, val end: Int)

    private fun unquote(token: Token): String {
        if (token.kind != Kind.QUOTED || token.text.length < 2) return token.text
        val inner = token.text.substring(1, token.text.length - 1)
        return when (token.text.first()) {
            '[' -> inner
            else -> inner.replace("${token.text.first()}${token.text.first()}", token.text.first().toString())
        }
    }

    /** Index of the `)` that closes the `(` at [open]; the last token when the text is cut short. */
    private fun matching(tokens: List<Token>, open: Int): Int {
        var depth = 0
        for (j in open until tokens.size) {
            val t = tokens[j]
            if (t.kind != Kind.SYMBOL) continue
            if (t.text == "(") depth++
            if (t.text == ")") {
                depth--
                if (depth == 0) return j
            }
        }
        return tokens.lastIndex
    }

    private fun tokenize(text: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && text.startsWith("--", i) ->
                    i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                c == '/' && text.startsWith("/*", i) ->
                    i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                c == '\'' || c == '"' || c == '`' -> {
                    val end = quotedEnd(text, i, c)
                    out += Token(if (c == '\'') Kind.STRING else Kind.QUOTED, text.substring(i, end), i, end)
                    i = end
                }
                c == '[' -> {
                    val end = text.indexOf(']', i).let { if (it < 0) text.length else it + 1 }
                    out += Token(Kind.QUOTED, text.substring(i, end), i, end)
                    i = end
                }
                c.isLetterOrDigit() || c == '_' -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                    out += Token(Kind.WORD, text.substring(i, j), i, j)
                    i = j
                }
                else -> {
                    out += Token(Kind.SYMBOL, c.toString(), i, i + 1)
                    i++
                }
            }
        }
        return out
    }

    private fun quotedEnd(text: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < text.length) {
            if (text[i] == quote) {
                if (i + 1 < text.length && text[i + 1] == quote) {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return text.length
    }
}
