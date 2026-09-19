package hu.laurel.sqlpulse.ui.query

import hu.laurel.sqlpulse.data.sql.SqlHighlighter
import hu.laurel.sqlpulse.data.sql.SqlScript
import hu.laurel.sqlpulse.data.sql.SqlToken
import hu.laurel.sqlpulse.data.sql.TokenRole

/** The part of a statement the cursor is standing in. */
enum class SqlClause {
    /** Before anything that names a clause: the start of a statement. */
    NONE,
    SELECT,
    FROM,
    JOIN,
    ON,
    WHERE,
    GROUP_BY,
    ORDER_BY,
    HAVING,
    SET,
    INTO,
    VALUES,
    LIMIT,
}

/** A table named by the statement, with the alias it was given (`t`, or `AS t`). */
data class TableRef(val name: String, val alias: String?)

/**
 * Everything the completion needs to know about where the cursor is.
 *
 * It says what to offer, not what the answers are: the table and column names live behind a
 * network call, and this object is what decides whether that call is worth making at all.
 */
data class CompletionContext(
    /** The half-typed word the cursor is at the end of; empty at a word boundary. */
    val prefix: String,
    /** Where [prefix] starts in the whole editor text, so a completion can replace it. */
    val prefixStart: Int,
    val clause: SqlClause,
    /** True inside a string or a comment, where nothing at all should be offered. */
    val suppressed: Boolean,
    /** The `a` of `a.`, if the cursor sits just after a qualified name's dot. */
    val qualifier: String?,
    /** True where a table name belongs: right after FROM, JOIN, UPDATE, INTO or a comma. */
    val wantTables: Boolean,
    /** The tables whose columns belong here. Empty means: do not fetch any columns. */
    val columnTables: List<String>,
    /** The keywords that may follow this position, unfiltered. */
    val keywords: List<String>,
    /** Every table the statement names, with aliases — what [columnTables] was resolved from. */
    val tables: List<TableRef>,
)

/**
 * Where the cursor is in a statement, and what may be written there.
 *
 * The lexical layer is not re-implemented here. [SqlScript] already decides where one statement
 * ends and the next begins, and [SqlHighlighter] already knows a string from a comment from an
 * identifier; both are used as they are. What this adds is the part neither has: which clause the
 * cursor is in, which tables the statement has in scope, and what an alias points at.
 *
 * It is a reader, not a parser. It never has to decide whether a statement is valid — only what a
 * person half-way through typing one probably wants next — so an unfinished statement, which is
 * the normal case while typing, is simply the statement it has seen so far.
 */
object SqlCompletion {

    fun contextAt(text: String, cursor: Int): CompletionContext {
        val at = cursor.coerceIn(0, text.length)
        val statement = SqlScript.statementAt(text, at)

        // Everything of the current statement up to the cursor. Looking only backwards is what
        // makes an unterminated string or comment detect itself: the text before the cursor holds
        // the opening quote and no closing one, which is exactly what "inside" means.
        val start = when {
            statement == null -> 0
            // Past the statement's semicolon: the cursor is in the next, still empty, statement.
            at > statement.end -> (statement.end + 1).coerceAtMost(at)
            else -> statement.start.coerceAtMost(at)
        }
        val before = text.substring(start, at)

        if (insideStringOrComment(before)) {
            return CompletionContext(
                prefix = "",
                prefixStart = at,
                clause = SqlClause.NONE,
                suppressed = true,
                qualifier = null,
                wantTables = false,
                columnTables = emptyList(),
                keywords = emptyList(),
                tables = emptyList(),
            )
        }

        // The whole statement, for the tables in scope: `SELECT | FROM hivasok` has its FROM after
        // the cursor, and the columns on offer there are still that table's.
        val whole = when {
            statement == null || at > statement.end -> before
            else -> text.substring(statement.start, statement.end.coerceAtMost(text.length))
        }

        val words = words(before)
        // The word the cursor is touching is the one being typed, not a word of the statement:
        // "WHERE" half-typed must not be read as "we are already in the WHERE clause".
        val typing = words.lastOrNull()?.takeIf { it.end == before.length && it.identifier }
        val settled = if (typing == null) words else words.dropLast(1)

        val prefix = typing?.text ?: ""
        val prefixStart = start + (typing?.start ?: before.length)

        val dotAt = settled.lastOrNull()?.takeIf {
            it.text == "." && it.end == (typing?.start ?: before.length)
        }
        val qualifier = dotAt?.let { dot ->
            settled.getOrNull(settled.lastIndex - 1)?.takeIf { it.identifier }?.text
        }

        val clause = clauseOf(settled)
        val tables = tablesIn(words(whole))

        // A table name belongs right after the keyword that introduces one, or after a comma that
        // separates two of them. After `FROM hivasok` the next thing is a keyword or an alias, and
        // offering the table list again there is noise.
        val previous = settled.lastOrNull()
        val atNamePosition = previous == null || previous.text == "," ||
            (!previous.quoted && previous.text.lowercase() in TABLE_INTRODUCERS)
        val wantTables = qualifier == null && clause in TABLE_CLAUSES && atNamePosition

        val columnTables = when {
            qualifier != null -> listOfNotNull(resolve(tables, qualifier))
            clause in COLUMN_CLAUSES -> tables.map { it.name }
            else -> emptyList()
        }

        return CompletionContext(
            prefix = prefix,
            prefixStart = prefixStart,
            clause = clause,
            suppressed = false,
            qualifier = qualifier,
            wantTables = wantTables,
            columnTables = columnTables.distinct(),
            keywords = if (qualifier != null) emptyList() else KEYWORDS_AFTER[clause].orEmpty(),
            tables = tables,
        )
    }

    /** What `a` in `a.column` refers to: an alias if one matches, else a table named directly. */
    fun resolve(tables: List<TableRef>, qualifier: String): String? =
        tables.firstOrNull { it.alias.equals(qualifier, ignoreCase = true) }?.name
            ?: tables.firstOrNull { it.name.equals(qualifier, ignoreCase = true) }?.name
            // An unknown qualifier is still probably a table name — the FROM may not be typed yet.
            ?: qualifier.takeIf { it.isNotBlank() }

    /**
     * True when the text ends inside a string or a comment.
     *
     * [SqlHighlighter] marks both; what it cannot say is whether the one at the end ever closed,
     * which is the whole question here. A token that reaches the end of the text without its
     * closing quote, comment terminator or newline is one the cursor is standing in.
     */
    private fun insideStringOrComment(before: String): Boolean {
        val last = SqlHighlighter.tokenize(before).lastOrNull() ?: return false
        if (last.end != before.length) return false
        return when (last.role) {
            TokenRole.STRING, TokenRole.QUOTED_IDENTIFIER -> !closed(before, last)
            TokenRole.COMMENT -> !closed(before, last)
            else -> false
        }
    }

    private fun closed(text: String, token: SqlToken): Boolean = when {
        text.startsWith("/*", token.start) ->
            token.end - token.start >= 4 && text.regionMatches(token.end - 2, "*/", 0, 2)
        // A line comment's token stops at the newline, so one that runs to the end never closed.
        text.startsWith("--", token.start) || text[token.start] == '#' -> false
        else -> token.end - token.start >= 2 && text[token.end - 1] == text[token.start]
    }

    /** One word of a statement: an identifier, a keyword, or one of the punctuation marks that matter. */
    private data class Word(
        val text: String,
        val start: Int,
        val end: Int,
        val identifier: Boolean,
        /** Backtick-quoted, so it is a name even when it spells a keyword: `` `order` `` is a table. */
        val quoted: Boolean = false,
    ) {
        /** True when this word can be a table name or an alias. */
        val nameable: Boolean get() = identifier && (quoted || text.lowercase() !in RESERVED)
    }

    /**
     * The words of a statement, with strings, comments, numbers and parameters left out.
     *
     * The ranges come from [SqlHighlighter], so a semicolon in a string or a comma in a comment is
     * as invisible here as it is there. A backtick-quoted identifier arrives unquoted, because
     * `` `order` `` is a table called order.
     */
    private fun words(sql: String): List<Word> {
        val marked = SqlHighlighter.tokenize(sql).associateBy { it.start }
        val out = mutableListOf<Word>()
        var index = 0
        while (index < sql.length) {
            val token = marked[index]
            when (token?.role) {
                TokenRole.STRING, TokenRole.COMMENT, TokenRole.NUMBER, TokenRole.PARAMETER -> {
                    index = token.end.coerceAtLeast(index + 1)
                    continue
                }

                TokenRole.QUOTED_IDENTIFIER -> {
                    val inner = sql.substring(
                        (token.start + 1).coerceAtMost(sql.length),
                        (token.end - 1).coerceIn(token.start + 1, sql.length),
                    )
                    out += Word(inner, token.start, token.end, identifier = true, quoted = true)
                    index = token.end.coerceAtLeast(index + 1)
                    continue
                }

                else -> Unit
            }
            val c = sql[index]
            when {
                c.isLetter() || c == '_' -> {
                    var end = index
                    while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_')) end++
                    out += Word(sql.substring(index, end), index, end, identifier = true)
                    index = end
                }

                c == ',' || c == '.' || c == '(' || c == ')' || c == '*' || c == '=' -> {
                    out += Word(c.toString(), index, index + 1, identifier = false)
                    index++
                }

                else -> index++
            }
        }
        return out
    }

    /**
     * The clause the last word before the cursor belongs to.
     *
     * Read forwards rather than backwards, so the later of two clause words wins: in
     * `SELECT x FROM t WHERE`, WHERE is where we are, even though SELECT came first.
     */
    private fun clauseOf(words: List<Word>): SqlClause {
        var clause = SqlClause.NONE
        var index = 0
        while (index < words.size) {
            // A quoted word is a name, never a clause: `SELECT * FROM `where`` is a table.
            if (words[index].quoted) {
                index++
                continue
            }
            val word = words[index].text.lowercase()
            val next = words.getOrNull(index + 1)?.takeIf { !it.quoted }?.text?.lowercase()
            when (word) {
                "select" -> clause = SqlClause.SELECT
                "from" -> clause = SqlClause.FROM
                "join" -> clause = SqlClause.JOIN
                "on", "using" -> clause = SqlClause.ON
                "where" -> clause = SqlClause.WHERE
                "having" -> clause = SqlClause.HAVING
                "set" -> clause = SqlClause.SET
                "into" -> clause = SqlClause.INTO
                "values" -> clause = SqlClause.VALUES
                "limit" -> clause = SqlClause.LIMIT
                // `UPDATE t SET ...`: a table name comes next, same as after FROM.
                "update" -> clause = SqlClause.FROM
                // A new SELECT after UNION starts the reading again.
                "union" -> clause = SqlClause.NONE
                "group" -> if (next == "by") {
                    clause = SqlClause.GROUP_BY
                    index++
                }

                "order" -> if (next == "by") {
                    clause = SqlClause.ORDER_BY
                    index++
                }
            }
            index++
        }
        return clause
    }

    /**
     * The tables the statement names, in order, with their aliases.
     *
     * A qualified `db.table` keeps only the table part: the completion looks columns up in the
     * session's current database, and a cross-database query is rare enough that offering the
     * wrong database's columns is better handled by offering none.
     */
    private fun tablesIn(words: List<Word>): List<TableRef> {
        val refs = mutableListOf<TableRef>()
        var index = 0
        while (index < words.size) {
            if (words[index].quoted || words[index].text.lowercase() !in TABLE_INTRODUCERS) {
                index++
                continue
            }
            index++
            while (index < words.size) {
                val name = readName(words, index) ?: break
                index = name.second
                var alias: String? = null
                val word = words.getOrNull(index)
                if (word != null && word.text.equals("as", ignoreCase = true)) {
                    val aliased = words.getOrNull(index + 1)
                    if (aliased != null && aliased.identifier) {
                        alias = aliased.text
                        index += 2
                    } else {
                        index++
                    }
                } else if (word != null && word.nameable) {
                    alias = word.text
                    index++
                }
                refs += TableRef(name.first, alias)
                if (words.getOrNull(index)?.text == ",") index++ else break
            }
        }
        return refs
    }

    /** Reads `name`, `db.name` or `` `db`.`name` ``, returning the table part and where it ended. */
    private fun readName(words: List<Word>, from: Int): Pair<String, Int>? {
        val first = words.getOrNull(from) ?: return null
        if (!first.nameable) return null
        var name = first.text
        var index = from + 1
        while (words.getOrNull(index)?.text == "." && words.getOrNull(index + 1)?.identifier == true) {
            name = words[index + 1].text
            index += 2
        }
        return name to index
    }

    /** Words that introduce a table name. */
    private val TABLE_INTRODUCERS = setOf("from", "join", "update", "into")

    private val TABLE_CLAUSES = setOf(SqlClause.FROM, SqlClause.JOIN, SqlClause.INTO)

    private val COLUMN_CLAUSES = setOf(
        SqlClause.SELECT,
        SqlClause.WHERE,
        SqlClause.ON,
        SqlClause.GROUP_BY,
        SqlClause.ORDER_BY,
        SqlClause.HAVING,
        SqlClause.SET,
    )

    /** Words that can never be a table name or an alias, so seeing one ends the table list. */
    private val RESERVED = setOf(
        "select", "from", "where", "join", "inner", "left", "right", "outer", "full", "cross",
        "natural", "straight_join", "on", "using", "group", "order", "by", "having", "limit",
        "offset", "set", "values", "as", "and", "or", "not", "union", "into", "update", "insert",
        "delete", "replace", "for", "asc", "desc", "when", "then", "else", "end", "case", "is",
        "null", "in", "like", "between", "distinct", "with", "force", "use", "ignore", "index",
    )

    /**
     * What may follow each clause.
     *
     * Written out rather than derived, because the useful answer is not "every keyword that is
     * grammatical here" but "the handful that are usually meant" — `ORDER BY` after a WHERE, not
     * `PROCEDURE ANALYSE`.
     */
    private val KEYWORDS_AFTER: Map<SqlClause, List<String>> = mapOf(
        SqlClause.NONE to listOf(
            "SELECT", "INSERT INTO", "UPDATE", "DELETE FROM", "REPLACE INTO", "WITH",
            "SHOW", "EXPLAIN", "DESCRIBE", "USE",
        ),
        SqlClause.SELECT to listOf(
            "DISTINCT", "AS", "FROM", "COUNT(", "SUM(", "AVG(", "MIN(", "MAX(", "CASE", "NULL",
        ),
        SqlClause.FROM to listOf(
            "AS", "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "WHERE", "GROUP BY",
            "ORDER BY", "LIMIT",
        ),
        SqlClause.JOIN to listOf("AS", "ON", "USING"),
        SqlClause.ON to listOf("AND", "OR", "JOIN", "LEFT JOIN", "WHERE", "GROUP BY", "ORDER BY"),
        SqlClause.WHERE to listOf(
            "AND", "OR", "NOT", "IN", "LIKE", "BETWEEN", "IS NULL", "IS NOT NULL", "EXISTS",
            "GROUP BY", "ORDER BY", "LIMIT",
        ),
        SqlClause.GROUP_BY to listOf("HAVING", "ORDER BY", "LIMIT"),
        SqlClause.ORDER_BY to listOf("ASC", "DESC", "LIMIT"),
        SqlClause.HAVING to listOf("AND", "OR", "ORDER BY", "LIMIT"),
        SqlClause.SET to listOf("WHERE", "NULL"),
        SqlClause.INTO to listOf("VALUES", "SET", "SELECT"),
        SqlClause.VALUES to listOf("NULL", "DEFAULT"),
        SqlClause.LIMIT to listOf("OFFSET"),
    )
}
