package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.sql.dialect.SqlGrammar

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
 * What a quote, a comment or a dollar-quoted body looks like is the engine's [SqlGrammar]; MySQL's
 * is the default, so a caller that does not know about engines splits as it always did.
 *
 * `DELIMITER` is not supported: it is a client command, and the app refuses the DDL that needs it
 * anyway (§2), so a routine body with internal semicolons cannot be created from here. Nor is
 * SQL Server's `GO`, another client command: a script with a `GO` line is not split on it (see
 * SqlServerDialect for what the editor says instead).
 */
object SqlScript {

    fun split(text: String, grammar: SqlGrammar = SqlGrammar.MYSQL): List<ScriptStatement> {
        val statements = mutableListOf<ScriptStatement>()
        var start = 0
        var index = 0

        fun emit(end: Int) {
            val slice = text.substring(start, end)
            if (slice.isNotBlank() && SqlGuards.strip(slice, grammar).isNotBlank()) {
                statements += ScriptStatement(
                    sql = slice.trim(),
                    start = start + slice.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0),
                    end = end,
                )
            }
            start = end + 1
        }

        while (index < text.length) {
            // The grammar says what quotes a name or a string here and what starts a comment:
            // MySQL's by default, T-SQL's `[a;b]` and `#temp` for SQL Server.
            when {
                grammar.opensQuote(text, index) -> index = grammar.endOfQuoted(text, index)

                grammar.opensLineComment(text, index) -> index = skipLineComment(text, index)

                text.startsWith("/*", index) -> index = skipBlockComment(text, index)

                text[index] == ';' -> {
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
    fun statementAt(text: String, cursor: Int, grammar: SqlGrammar = SqlGrammar.MYSQL): ScriptStatement? {
        val statements = split(text, grammar)
        return statements.firstOrNull { cursor in it.start..it.end }
            ?: statements.lastOrNull { it.end <= cursor }
            ?: statements.firstOrNull()
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
