package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine

/**
 * The text of a view, a trigger or a CHECK expression, reduced to what it does, so two servers'
 * spellings of the same object compare equal.
 *
 * The idea is dbx's `normalize_mysql_view_ddl` / `strip_mysql_view_definer`
 * (`crates/dbx-sql-schema/src/schema_diff.rs`, Apache-2.0), widened from MySQL views to every
 * engine's views, triggers and CHECK expressions:
 *
 *  - MySQL's `DEFINER=`, `ALGORITHM=` and `SQL SECURITY` in a view header say who created the
 *    view and how the server may run it, not what it selects;
 *  - the object's own schema qualifier (`` `shop_dev`.`orders` ``, `dbo.orders`, `public.orders`)
 *    is dropped, because dev and production almost never share a schema name, and because one
 *    server prints the qualifier where another leaves it out;
 *  - comments and whitespace are formatting;
 *  - quoting is formatting: `` `id` ``, `"id"`, `[id]` and `id` are one name, and keywords and
 *    unquoted names are case-folded (SQL does not care, and MySQL prints `select` where SQL
 *    Server keeps what was typed). A name that needs its quotes keeps its spelling.
 *
 * String literals are copied untouched: a space or a capital inside one is part of the object.
 * Nothing here parses SQL — it is a tokeniser and a few rules — so a construct it does not know
 * simply compares by its text, which can only err towards reporting a difference.
 */
object ObjectText {

    /** A view or trigger as two servers of [engine] can agree on it. */
    fun normalize(text: String, ownNamespace: String, engine: DatabaseEngine): String {
        val source = if (engine == DatabaseEngine.MYSQL) stripMySqlViewHeader(text) else text
        return render(tokenize(source, engine), ownNamespace)
    }

    /**
     * A CHECK expression: the same reduction, and the brackets that wrap the whole condition go
     * too (`((age >= 0))` from PostgreSQL, `(age >= 0)` from MySQL, `age >= 0` from a script).
     */
    fun normalizeExpression(expression: String?, ownNamespace: String, engine: DatabaseEngine): String {
        val text = expression ?: return ""
        return EngineSchemaText.stripOuterParentheses(normalize(text, ownNamespace, engine))
    }

    /**
     * The short stretches of two different texts around the place they first part, so a reader
     * can see what changed without two whole view bodies on a phone screen. Equal texts give
     * their own start twice.
     */
    fun excerpts(a: String, b: String, before: Int = 30, after: Int = 70): Pair<String, String> {
        var d = 0
        while (d < a.length && d < b.length && a[d] == b[d]) d++
        // Start on a word boundary, so the excerpt does not open mid-word.
        var start = (d - before).coerceAtLeast(0)
        while (start > 0 && start < d && !a[start - 1].isWhitespace() && a[start - 1] !in "(),") start++
        fun cut(text: String): String {
            val from = start.coerceAtMost(text.length)
            val to = (d + after).coerceAtMost(text.length)
            return (if (from > 0) "…" else "") + text.substring(from, to) + (if (to < text.length) "…" else "")
        }
        return cut(a) to cut(b)
    }

    /**
     * `DEFINER=…`, `ALGORITHM=…` and `SQL SECURITY …` out of a view's header, i.e. before the
     * word VIEW. After it they are part of the query and are left alone.
     */
    fun stripMySqlViewHeader(ddl: String): String {
        val withoutDefiner = SchemaDiff.stripDefiner(ddl)
        val view = VIEW_KEYWORD.find(withoutDefiner) ?: return withoutDefiner
        val header = withoutDefiner.substring(0, view.range.first)
            .let { ALGORITHM.replace(it, "") }
            .let { SQL_SECURITY.replace(it, "") }
        return header + withoutDefiner.substring(view.range.first)
    }

    // ------------------------------------------------------------------ tokens

    private enum class Kind { WORD, STRING, QUOTED, SYMBOL }

    private class Token(val kind: Kind, val text: String) {
        val atom: Boolean get() = kind != Kind.SYMBOL

        /** The name this token spells when it is an identifier, folded for comparison. */
        val identifier: String? get() = if (kind == Kind.WORD || kind == Kind.QUOTED) text.lowercase().trim('"') else null
    }

    private val SIMPLE_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_$]*")

    private fun tokenize(text: String, engine: DatabaseEngine): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && text.startsWith("--", i) -> i = lineEnd(text, i)
                c == '#' && engine == DatabaseEngine.MYSQL -> i = lineEnd(text, i)
                c == '/' && text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                c == '\'' -> {
                    val end = quotedEnd(text, i, backslash = engine == DatabaseEngine.MYSQL)
                    tokens += Token(Kind.STRING, text.substring(i, end))
                    i = end
                }
                // MySQL reads "…" as a string unless ANSI_QUOTES is on; everyone else as a name.
                c == '"' && engine == DatabaseEngine.MYSQL -> {
                    val end = quotedEnd(text, i, backslash = true)
                    tokens += Token(Kind.STRING, text.substring(i, end))
                    i = end
                }
                c == '"' || c == '`' -> {
                    val end = quotedEnd(text, i, backslash = false)
                    tokens += identifier(text.substring(i + 1, (end - 1).coerceAtLeast(i + 1)).replace("$c$c", "$c"))
                    i = end
                }
                c == '[' && (engine == DatabaseEngine.SQLSERVER || engine == DatabaseEngine.SQLITE) -> {
                    val close = text.indexOf(']', i)
                    val end = if (close < 0) text.length else close + 1
                    tokens += identifier(text.substring(i + 1, (end - 1).coerceAtLeast(i + 1)))
                    i = end
                }
                c.isLetterOrDigit() || c == '_' || c == '$' || c == '@' -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j++
                    val word = text.substring(i, j)
                    // A character-set introducer (`_utf8mb4'x'`) is the server's spelling of a literal.
                    val introducer = engine == DatabaseEngine.MYSQL && word.startsWith("_") && text.getOrNull(j) == '\''
                    if (!introducer) tokens += Token(Kind.WORD, word.lowercase())
                    i = j
                }
                else -> {
                    tokens += Token(Kind.SYMBOL, c.toString())
                    i++
                }
            }
        }
        return tokens
    }

    /** A quoted name that is a plain word is that word; one that needs its quotes keeps them. */
    private fun identifier(name: String): Token =
        if (SIMPLE_IDENTIFIER.matches(name)) Token(Kind.WORD, name.lowercase())
        else Token(Kind.QUOTED, "\"$name\"")

    private fun lineEnd(text: String, from: Int): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it }

    private fun quotedEnd(text: String, start: Int, backslash: Boolean): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            if (backslash && c == '\\') {
                i += 2
                continue
            }
            if (c == quote) {
                // A doubled quote is an escaped quote, not the end.
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

    private fun render(tokens: List<Token>, ownNamespace: String): String {
        val own = ownNamespace.lowercase()
        val kept = mutableListOf<Token>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val qualifies = token.identifier == own && tokens.getOrNull(i + 1)?.text == "." &&
                tokens.getOrNull(i + 1)?.kind == Kind.SYMBOL
            // The schema and its dot go; a name after another dot (`db.schema.t`) is not the schema.
            if (qualifies && kept.lastOrNull()?.text != ".") {
                i += 2
                continue
            }
            kept += token
            i++
        }
        val out = StringBuilder()
        var previous: Token? = null
        for (token in kept) {
            // A space only survives between two words, where removing it would merge them.
            if (previous?.atom == true && token.atom) out.append(' ')
            out.append(token.text)
            previous = token
        }
        return out.toString()
    }

    private val VIEW_KEYWORD = Regex("\\bVIEW\\b", RegexOption.IGNORE_CASE)
    private val ALGORITHM = Regex("\\bALGORITHM\\s*=\\s*\\w+\\s*", RegexOption.IGNORE_CASE)
    private val SQL_SECURITY = Regex("\\bSQL\\s+SECURITY\\s+\\w+\\s*", RegexOption.IGNORE_CASE)
}
