package hu.laurel.sqlpulse.data.sql

/** One statement inside an editor's text, with where it starts and ends. */
data class ScriptStatement(
    val sql: String,
    val start: Int,
    val end: Int,
)

/**
 * Splits editor text into statements.
 *
 * Semicolons are the separator, but only the ones that are really separators: a semicolon inside a
 * string, an identifier or a comment is part of the statement. This is the same syntactic scan
 * [SqlGuards.strip] does — it is not a parser, and it does not try to be one. What it does have to
 * get right is never cutting a statement in half, because half a DELETE is a different DELETE.
 *
 * `DELIMITER` is not supported: it is a client command, and the app refuses the DDL that needs it
 * anyway (§2), so a routine body with internal semicolons cannot be created from here.
 */
object SqlScript {

    fun split(text: String): List<ScriptStatement> {
        val statements = mutableListOf<ScriptStatement>()
        var start = 0
        var index = 0

        fun emit(end: Int) {
            val slice = text.substring(start, end)
            if (slice.isNotBlank() && SqlGuards.strip(slice).isNotBlank()) {
                statements += ScriptStatement(
                    sql = slice.trim(),
                    start = start + slice.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0),
                    end = end,
                )
            }
            start = end + 1
        }

        while (index < text.length) {
            when (val c = text[index]) {
                '\'', '"', '`' -> index = skipQuoted(text, index, c)

                '-' -> index = if (text.startsWith("--", index)) skipLineComment(text, index) else index + 1

                '#' -> index = skipLineComment(text, index)

                '/' -> index = if (text.startsWith("/*", index)) skipBlockComment(text, index) else index + 1

                ';' -> {
                    emit(index)
                    index++
                }

                else -> index++
            }
        }
        if (start < text.length) emit(text.length)
        return statements
    }

    /**
     * The statement the cursor is inside, for "run what I am looking at".
     *
     * A cursor sitting on the semicolon or just past it belongs to the statement it ends, which is
     * where the cursor is after typing one.
     */
    fun statementAt(text: String, cursor: Int): ScriptStatement? {
        val statements = split(text)
        return statements.firstOrNull { cursor in it.start..it.end }
            ?: statements.lastOrNull { it.end <= cursor }
            ?: statements.firstOrNull()
    }

    private fun skipQuoted(text: String, from: Int, quote: Char): Int {
        var index = from + 1
        while (index < text.length) {
            val c = text[index]
            // Backslash escapes apply to strings but not to backtick-quoted identifiers.
            if (c == '\\' && quote != '`') {
                index += 2
                continue
            }
            index++
            if (c == quote) {
                // A doubled quote is an escaped quote, not the end of the literal.
                if (index < text.length && text[index] == quote) index++ else return index
            }
        }
        return index
    }

    private fun skipLineComment(text: String, from: Int): Int {
        val newline = text.indexOf('\n', from)
        return if (newline < 0) text.length else newline + 1
    }

    private fun skipBlockComment(text: String, from: Int): Int {
        val close = text.indexOf("*/", from + 2)
        return if (close < 0) text.length else close + 2
    }
}
