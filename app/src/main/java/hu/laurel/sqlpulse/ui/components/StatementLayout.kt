package hu.laurel.sqlpulse.ui.components

/**
 * Breaks a one-line write statement into the lines the design shows it on, with the keywords
 * lined up on their right edge:
 *
 * ```
 * UPDATE invoices
 *    SET status = 'paid'
 *  WHERE id = 20416
 *    AND status = 'overdue'
 * ```
 *
 * A keyword inside a string literal or a quoted identifier is not a keyword, so it is left where
 * it is. Only the uppercase forms the row editor generates are recognised; a statement someone
 * typed with its own line breaks never comes through here.
 */
internal object StatementLayout {

    private val clauses = mapOf(
        "SET" to "   SET ",
        "WHERE" to " WHERE ",
        "AND" to "   AND ",
        "LIMIT" to " LIMIT ",
        "VALUES" to " VALUES ",
    )

    fun lines(sql: String): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (quote != null) {
                current.append(c)
                // A doubled quote is a quote inside the literal, not its end.
                if (c == quote) {
                    if (sql.getOrNull(i + 1) == quote) {
                        current.append(quote)
                        i++
                    } else {
                        quote = null
                    }
                }
                i++
                continue
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c
                current.append(c)
                i++
                continue
            }
            val clause = if (c == ' ') clauses.keys.firstOrNull { sql.startsWith(" $it ", i) } else null
            if (clause != null && current.isNotBlank()) {
                lines += current.toString().trimEnd()
                current.clear()
                current.append(clauses.getValue(clause))
                i += clause.length + 2
                continue
            }
            current.append(c)
            i++
        }
        if (current.isNotBlank()) lines += current.toString().trimEnd()
        return lines
    }

    /** The WHERE clause and the conditions joined to it: what decides which rows are touched. */
    fun isCondition(line: String): Boolean {
        val text = line.trimStart()
        return text.startsWith("WHERE ") || text.startsWith("AND ")
    }
}
