package hu.laurel.sqlpulse.data.sql.dialect

/**
 * The few lexical facts the app's SQL scanners need about one engine, and the words a statement
 * of each kind starts with.
 *
 * The scanners themselves (SqlGuards.strip, bindParameters, the CTE walk) are shared: what differs
 * between engines is which characters open a quoted name or string, whether a backslash escapes
 * inside one, whether `#` starts a comment, and PostgreSQL's `$tag$ … $tag$` strings. Keeping those
 * as data means a new engine describes itself here instead of copying a scanner — and a scanner
 * that misreads a quote is how a `DELETE` inside a string ends up classified as the statement.
 *
 * [MYSQL] is exactly what the scanners hard-coded before engines existed; its tests pin that.
 */
data class SqlGrammar(
    /** Characters that open a quoted string or identifier, each mapped to the one that closes it. */
    val quotes: Map<Char, Char>,
    /** Whether `\` escapes the next character inside a quote (MySQL's default, nobody else's). */
    val backslashEscapes: Boolean,
    /** Quotes inside which a backslash is an ordinary character even when [backslashEscapes]. */
    val backslashFreeQuotes: Set<Char> = emptySet(),
    /** MySQL's `# comment`. Everywhere else `#` is an operator or part of a name (`#temp`). */
    val hashComments: Boolean = false,
    /** PostgreSQL's dollar-quoted strings: `$$ … $$`, `$fn$ … $fn$`. */
    val dollarQuotes: Boolean = false,
    /** First words of a statement that only reads. `with` is resolved by what follows the CTEs. */
    val readStarters: Set<String>,
    /** First words of a row-level write (§2: no DDL, so CREATE/ALTER/DROP are never here). */
    val writeStarters: Set<String>,
) {

    /** True when a quoted section (string, quoted identifier or dollar quote) starts at [index]. */
    fun opensQuote(sql: String, index: Int): Boolean {
        val c = sql[index]
        return c in quotes || (dollarQuotes && c == '$' && dollarTag(sql, index) != null)
    }

    /** True when a line comment starts at [index]. */
    fun opensLineComment(sql: String, index: Int): Boolean =
        sql.startsWith("--", index) || (hashComments && sql[index] == '#')

    /**
     * The index just past the quoted section starting at [start] (which [opensQuote] accepted).
     * A doubled closing quote is an escaped quote, not the end; an unclosed one runs to the end.
     */
    fun endOfQuoted(sql: String, start: Int): Int {
        if (dollarQuotes && sql[start] == '$') {
            val tag = dollarTag(sql, start)
            if (tag != null) {
                val close = sql.indexOf(tag, start + tag.length)
                return if (close >= 0) close + tag.length else sql.length
            }
        }
        val open = sql[start]
        val closing = quotes[open] ?: open
        val backslash = backslashEscapes && open !in backslashFreeQuotes
        var index = start + 1
        while (index < sql.length) {
            val current = sql[index]
            if (backslash && current == '\\') {
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

    /**
     * `$tag$` or `$$` starting at [index], or null. A `$` followed by a digit is a positional
     * parameter (`$1`), not a quote.
     */
    private fun dollarTag(sql: String, index: Int): String? {
        var end = index + 1
        if (end < sql.length && sql[end].isDigit()) return null
        while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_')) end++
        if (end >= sql.length || sql[end] != '$') return null
        return sql.substring(index, end + 1)
    }

    companion object {
        /** What the app has always assumed: MySQL and MariaDB with their default SQL mode. */
        val MYSQL = SqlGrammar(
            quotes = mapOf('\'' to '\'', '"' to '"', '`' to '`'),
            backslashEscapes = true,
            backslashFreeQuotes = setOf('`'),
            hashComments = true,
            readStarters = setOf("select", "show", "describe", "desc", "explain", "with", "analyze"),
            writeStarters = setOf("insert", "update", "delete", "replace"),
        )

        /**
         * Standard SQL: `'string'`, `"identifier"`, `--` and block comments, no backslash escapes.
         * The starting point of [UnsupportedDialect]; each engine adds what it has on top
         * (PostgreSQL dollar quotes, T-SQL `[brackets]`, SQLite's backticks and brackets).
         */
        val ANSI = SqlGrammar(
            quotes = mapOf('\'' to '\'', '"' to '"'),
            backslashEscapes = false,
            readStarters = setOf("select", "with", "values", "explain"),
            writeStarters = setOf("insert", "update", "delete", "merge"),
        )
    }
}
