package hu.laurel.sqlpulse.data.sql

/**
 * Turns the `?` of a statement digest into the `:name` parameters the editor already understands.
 *
 * The statement digest keeps the shape of a query and throws its values away, leaving a `?` where
 * each was. Opened in the editor as it is, the text would not run: the editor's own placeholders
 * are named (`:name`, §7.4) and its parameter dialog is what asks for values, so a bare `?` would
 * go to the server unbound. Naming them `p1`, `p2`, … lets that dialog ask, with nothing new to
 * learn.
 */
object DigestPlaceholders {

    /** [sql] with the placeholders renamed, and how many there were. */
    data class Converted(val sql: String, val count: Int)

    /**
     * Replaces every `?` outside string literals, quoted identifiers and comments.
     *
     * A name that the text already uses (`:p1` written by hand) is skipped over, so a converted
     * statement never ends up with one name meaning two different values.
     */
    fun toNamed(sql: String): Converted {
        val taken = SqlGuards.parameters(sql).toSet()
        val out = StringBuilder(sql.length + 8)
        var count = 0
        var counter = 0
        fun nextName(): String {
            do counter++ while ("p$counter" in taken)
            return "p$counter"
        }

        var index = 0
        while (index < sql.length) {
            val c = sql[index]
            val end = when {
                c == '\'' || c == '"' || c == '`' -> SqlGuards.endOfLiteral(sql, index)
                sql.startsWith("--", index) || c == '#' -> sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length
                sql.startsWith("/*", index) -> sql.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: sql.length
                else -> null
            }
            if (end != null) {
                out.append(sql, index, end)
                index = end
            } else if (c == '?') {
                out.append(':').append(nextName())
                count++
                index++
            } else {
                out.append(c)
                index++
            }
        }
        return Converted(out.toString(), count)
    }
}
