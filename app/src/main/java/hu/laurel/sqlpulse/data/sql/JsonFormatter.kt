package hu.laurel.sqlpulse.data.sql

/**
 * Lays JSON out over lines, or says that the text is not JSON.
 *
 * MySQL stores JSON as one line, and one line of JSON on a phone screen is unreadable. There is no
 * JSON library in the app — Android's `org.json` is lenient enough to accept things that are not
 * JSON at all, and would then present them as if they were — so this parses strictly and returns
 * null for anything it does not fully understand. A cell it cannot parse is shown as it is stored,
 * which is the honest answer.
 */
object JsonFormatter {

    private const val INDENT = "  "

    fun pretty(text: String): String? {
        val trimmed = text.trim()
        // Only a document, not a bare number or word: "12" is a number, not JSON worth folding.
        if (trimmed.firstOrNull() !in listOf('{', '[')) return null
        return runCatching {
            val parser = Parser(trimmed)
            val out = StringBuilder()
            parser.value(out, 0)
            parser.skipWhitespace()
            if (!parser.atEnd) throw IllegalArgumentException("trailing characters")
            out.toString()
        }.getOrNull()
    }

    private class Parser(private val text: String) {
        private var index = 0

        val atEnd: Boolean get() = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun value(out: StringBuilder, depth: Int) {
            skipWhitespace()
            when (peek()) {
                '{' -> obj(out, depth)
                '[' -> array(out, depth)
                '"' -> out.append(string())
                't' -> out.append(literal("true"))
                'f' -> out.append(literal("false"))
                'n' -> out.append(literal("null"))
                else -> out.append(number())
            }
        }

        private fun obj(out: StringBuilder, depth: Int) {
            expect('{')
            out.append('{')
            skipWhitespace()
            if (peek() == '}') {
                index++
                out.append('}')
                return
            }
            while (true) {
                out.append('\n').append(INDENT.repeat(depth + 1))
                skipWhitespace()
                out.append(string())
                skipWhitespace()
                expect(':')
                out.append(": ")
                value(out, depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        out.append(',')
                    }

                    '}' -> {
                        index++
                        out.append('\n').append(INDENT.repeat(depth)).append('}')
                        return
                    }

                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
        }

        private fun array(out: StringBuilder, depth: Int) {
            expect('[')
            out.append('[')
            skipWhitespace()
            if (peek() == ']') {
                index++
                out.append(']')
                return
            }
            while (true) {
                out.append('\n').append(INDENT.repeat(depth + 1))
                value(out, depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        out.append(',')
                    }

                    ']' -> {
                        index++
                        out.append('\n').append(INDENT.repeat(depth)).append(']')
                        return
                    }

                    else -> throw IllegalArgumentException("expected , or ]")
                }
            }
        }

        /** Copied through character for character: escapes stay exactly as they were stored. */
        private fun string(): String {
            expect('"')
            val start = index - 1
            while (index < text.length) {
                when (text[index]) {
                    '\\' -> index += 2
                    '"' -> {
                        index++
                        return text.substring(start, index)
                    }

                    else -> index++
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun number(): String {
            val start = index
            while (index < text.length && text[index] in "-+.eE0123456789") index++
            val slice = text.substring(start, index)
            require(slice.isNotEmpty() && slice.toBigDecimalOrNull() != null) { "not a number" }
            return slice
        }

        private fun literal(word: String): String {
            require(text.startsWith(word, index)) { "expected $word" }
            index += word.length
            return word
        }

        private fun peek(): Char {
            require(index < text.length) { "unexpected end" }
            return text[index]
        }

        private fun expect(c: Char) {
            skipWhitespace()
            require(index < text.length && text[index] == c) { "expected $c" }
            index++
        }
    }
}
