package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.schema.TableStructure
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlSyntax

/**
 * Why a query result cannot be edited in place.
 *
 * The order of the checks in [ResultEditabilities.analyse] decides which of several true reasons
 * is the one reported, so it is the most "structural" one first: a JOIN is a bigger fact than the
 * fact that its columns happen to be expressions.
 */
enum class NotEditableReason {
    NOT_SELECT,
    MULTIPLE_STATEMENTS,
    CTE,
    UNION,
    JOIN,
    SUBQUERY_IN_FROM,
    DISTINCT,
    GROUP_BY,
    HAVING,
    AGGREGATE,
    WINDOW,

    /** A selected item is not a plain column: arithmetic, a function, a literal, a subquery. */
    EXPRESSION,
    NO_TABLE,

    /** Valid SQL that this analysis does not model (a PARTITION clause, a table function, ...). */
    UNSUPPORTED,
    NO_DATABASE,
    UNKNOWN_TABLE,
    UNKNOWN_COLUMN,

    /** The columns the server returned are not the columns the statement text promises. */
    COLUMN_MISMATCH,
    NO_KEY,
    KEY_NOT_SELECTED,

    /** Not a property of the SQL: a production connection whose writes have not been unlocked. */
    WRITES_LOCKED,
}

/** One item of the select list, as written. */
sealed interface SelectItem {
    /** `col`, `t.col AS x` — [name] is the column, [alias] what the result calls it. */
    data class Column(val name: String, val alias: String?) : SelectItem

    /** `*` or `t.*`, which stands for every column of the table, in table order. */
    data object Star : SelectItem
}

/**
 * Which base table (if any) a query result maps onto, so its rows can be edited where they are.
 *
 * Follows dbx `sql_editability.rs`: one table in FROM, no JOIN / GROUP BY / HAVING / DISTINCT /
 * UNION / aggregate / window / CTE / derived table, and every selected item a plain column or `*`.
 * The statement text decides the first stage ([Editable]); the schema decides the second
 * ([Confirmed]) — the key must actually be in the result.
 */
sealed interface ResultEditability {
    /** Passed the text analysis: the result maps onto [table]. Not yet checked against the schema. */
    data class Editable(
        /** From a qualified `db.table`; null means "the session's current database". */
        val database: String?,
        val table: String,
        val alias: String?,
        val columnMapping: List<SelectItem>,
    ) : ResultEditability

    /** Passed the schema check as well: [target] is everything an edit needs. */
    data class Confirmed(val target: ResultEditTarget) : ResultEditability

    data class NotEditable(val reason: NotEditableReason) : ResultEditability
}

/**
 * A result confirmed to map onto one table, with a usable key.
 *
 * @param columns the base column behind each result column, by position; null where the result
 *   column is not writable (a generated column).
 * @param types parallel to [columns].
 * @param key key column name to its position in the result. Primary key if complete in the result,
 *   else a NOT NULL unique index that is.
 */
data class ResultEditTarget(
    val database: String,
    val table: String,
    val columns: List<String?>,
    val key: Map<String, Int>,
    /** The table's own type of each result column (`enum('a','b')`), which the result set does not know. */
    val types: List<String> = emptyList(),
)

object ResultEditabilities {

    /**
     * Reads [sql] (one statement) and says whether its result maps onto a single table. [syntax]
     * says how the engine quotes and comments: a `"name"` is a column in PostgreSQL and a string
     * in MySQL, and `#` starts a comment only in MySQL.
     */
    fun analyse(sql: String, syntax: SqlSyntax = MySqlDialect): ResultEditability {
        val tokens = tokenize(sql, syntax).dropLastWhile { it.isSymbol(';') }
        if (tokens.isEmpty()) return no(NotEditableReason.NOT_SELECT)
        if (tokens.any { it.isSymbol(';') }) return no(NotEditableReason.MULTIPLE_STATEMENTS)
        val first = tokens[0]
        if (first.isWord("with")) return no(NotEditableReason.CTE)
        if (!first.isWord("select")) return no(NotEditableReason.NOT_SELECT)

        val depth = depthOf(tokens)

        // Clause keywords at depth zero; a subquery's own FROM or GROUP BY is none of our business.
        // `FORCE INDEX FOR ORDER BY (i)` is a hint's wording, not an ORDER BY clause.
        val hintWords = setOf("order", "group", "join")
        val topWords = tokens.indices.filter {
            depth[it] == 0 && tokens[it].kind == Kind.WORD &&
                !(it > 0 && tokens[it - 1].isWord("for") && tokens[it].lower in hintWords) &&
                !(tokens[it].isWord("for") && tokens.getOrNull(it + 1)?.lower in hintWords)
        }
        fun firstTop(vararg words: String): Int? =
            topWords.firstOrNull { tokens[it].lower in words }

        if (firstTop("union", "intersect", "except") != null) return no(NotEditableReason.UNION)
        if (firstTop("into") != null) return no(NotEditableReason.NOT_SELECT)
        val fromAt = firstTop("from") ?: return no(NotEditableReason.NO_TABLE)

        // The select list starts after the modifiers, which say things about the result.
        var listStart = 1
        var distinct = false
        while (listStart < fromAt && tokens[listStart].kind == Kind.WORD) {
            val word = tokens[listStart].lower
            when {
                word in HARMLESS_MODIFIERS -> Unit
                word == "distinct" || word == "distinctrow" -> distinct = true
                word == "straight_join" -> return no(NotEditableReason.JOIN)
                else -> break
            }
            listStart++
        }

        val clauseEnd = topWords.firstOrNull { it > fromAt && tokens[it].lower in CLAUSE_STARTERS }
            ?: tokens.size
        val from = when (
            val parsed = parseFrom(
                tokens.subList(fromAt + 1, clauseEnd),
                depth.slice(fromAt + 1 until clauseEnd),
            )
        ) {
            is FromParse.Failed -> return no(parsed.reason)
            is FromParse.Table -> parsed
        }

        if (distinct) return no(NotEditableReason.DISTINCT)
        if (firstTop("group") != null) return no(NotEditableReason.GROUP_BY)
        if (firstTop("having") != null) return no(NotEditableReason.HAVING)
        if (firstTop("window") != null) return no(NotEditableReason.WINDOW)
        // PROCEDURE ANALYSE replaces the result with something that is not the table's rows.
        if (firstTop("procedure") != null) return no(NotEditableReason.UNSUPPORTED)

        val list = tokens.subList(listStart, fromAt)
        for (i in list.indices) {
            val token = list[i]
            if (token.isWord("over")) return no(NotEditableReason.WINDOW)
            if (token.kind == Kind.WORD && token.lower in AGGREGATES && list.getOrNull(i + 1)?.isSymbol('(') == true) {
                return no(NotEditableReason.AGGREGATE)
            }
        }

        val items = mutableListOf<SelectItem>()
        for (itemTokens in splitTopLevel(list)) {
            items += parseItem(itemTokens, from) ?: return no(NotEditableReason.EXPRESSION)
        }
        if (items.isEmpty()) return no(NotEditableReason.EXPRESSION)
        return ResultEditability.Editable(from.database, from.table, from.alias, items)
    }

    /**
     * Holds an [ResultEditability.Editable] up against the table's real structure and the columns
     * the server actually returned.
     *
     * Text analysis can be fooled — `SELECT NOT x` reads like a column called `not` — so this
     * checks every name, and that the labels line up position by position with what came back. An
     * edit built on a wrong guess would change the wrong column of the wrong row.
     */
    fun confirm(
        editable: ResultEditability.Editable,
        defaultDatabase: String?,
        resultLabels: List<String>,
        structure: TableStructure,
    ): ResultEditability {
        val database = editable.database ?: defaultDatabase
        if (database.isNullOrBlank()) return no(NotEditableReason.NO_DATABASE)
        if (structure.columns.isEmpty()) return no(NotEditableReason.UNKNOWN_TABLE)

        // Per result position: the base column, and the label the server will have given it.
        val expanded = mutableListOf<Pair<String, String>>()
        for (item in editable.columnMapping) {
            when (item) {
                SelectItem.Star -> structure.columns.forEach { expanded += it.name to it.name }
                is SelectItem.Column -> {
                    val column = structure.columns.firstOrNull { it.name.equals(item.name, ignoreCase = true) }
                        ?: return no(NotEditableReason.UNKNOWN_COLUMN)
                    expanded += column.name to (item.alias ?: column.name)
                }
            }
        }
        if (expanded.size != resultLabels.size) return no(NotEditableReason.COLUMN_MISMATCH)
        if (expanded.indices.any { !expanded[it].second.equals(resultLabels[it], ignoreCase = true) }) {
            return no(NotEditableReason.COLUMN_MISMATCH)
        }

        val positions = HashMap<String, Int>()
        expanded.forEachIndexed { index, (base, _) -> positions.putIfAbsent(base.lowercase(), index) }

        val nullable = structure.columns.associate { it.name.lowercase() to it.nullable }
        val candidates = buildList {
            if (structure.primaryKey.isNotEmpty()) add(structure.primaryKey)
            structure.indexes
                .filter { index ->
                    index.unique && index.columns.isNotEmpty() &&
                        index.columns.all { nullable[it.lowercase()] == false }
                }
                .sortedBy { it.columns.size }
                .forEach { add(it.columns) }
        }
        if (candidates.isEmpty()) return no(NotEditableReason.NO_KEY)
        val key = candidates.firstOrNull { columns -> columns.all { positions.containsKey(it.lowercase()) } }
            ?: return no(NotEditableReason.KEY_NOT_SELECTED)

        val generated = structure.columns.filter { it.generatedKind != null }.map { it.name.lowercase() }.toSet()
        return ResultEditability.Confirmed(
            ResultEditTarget(
                database = database,
                table = editable.table,
                columns = expanded.map { (base, _) -> base.takeUnless { it.lowercase() in generated } },
                key = key.associateWith { positions.getValue(it.lowercase()) },
                types = expanded.map { (base, _) ->
                    structure.columns.first { it.name == base }.typeName
                },
            ),
        )
    }

    private fun no(reason: NotEditableReason) = ResultEditability.NotEditable(reason)

    // --- FROM ----------------------------------------------------------------------------------

    private sealed interface FromParse {
        data class Table(val database: String?, val table: String, val alias: String?) : FromParse
        data class Failed(val reason: NotEditableReason) : FromParse
    }

    private fun parseFrom(clause: List<Token>, depth: List<Int>): FromParse {
        if (clause.isEmpty()) return FromParse.Failed(NotEditableReason.NO_TABLE)
        if (clause[0].isSymbol('(')) return FromParse.Failed(NotEditableReason.SUBQUERY_IN_FROM)
        for (i in clause.indices) {
            if (depth[i] != 0) continue
            val token = clause[i]
            // A comma is the old spelling of a cross join.
            if (token.isSymbol(',') || (token.kind == Kind.WORD && token.lower in JOIN_WORDS)) {
                return FromParse.Failed(NotEditableReason.JOIN)
            }
        }
        var index = 0
        val parts = mutableListOf<String>()
        while (true) {
            val token = clause.getOrNull(index) ?: return FromParse.Failed(NotEditableReason.UNSUPPORTED)
            if (!token.isIdentifier()) return FromParse.Failed(NotEditableReason.UNSUPPORTED)
            parts += token.text
            index++
            if (clause.getOrNull(index)?.isSymbol('.') == true && parts.size < 2) index++ else break
        }
        var alias: String? = null
        if (clause.getOrNull(index)?.isWord("as") == true) {
            val named = clause.getOrNull(index + 1)
            if (named == null || !named.isAliasToken()) return FromParse.Failed(NotEditableReason.UNSUPPORTED)
            alias = named.text
            index += 2
        } else {
            val next = clause.getOrNull(index)
            if (next != null && next.isAliasToken() && next.lower !in HINT_WORDS && !next.isWord("partition")) {
                alias = next.text
                index++
            }
        }
        // Index hints are tuning, not shape: USE INDEX (a), FORCE KEY (b), IGNORE INDEX FOR ORDER BY (c).
        while (index < clause.size) {
            val word = clause[index]
            if (word.kind != Kind.WORD || word.lower !in HINT_WORDS) return FromParse.Failed(NotEditableReason.UNSUPPORTED)
            val open = (index until clause.size).firstOrNull { clause[it].isSymbol('(') }
                ?: return FromParse.Failed(NotEditableReason.UNSUPPORTED)
            val close = (open until clause.size).firstOrNull { clause[it].isSymbol(')') }
                ?: return FromParse.Failed(NotEditableReason.UNSUPPORTED)
            index = close + 1
        }
        return FromParse.Table(
            database = if (parts.size == 2) parts[0] else null,
            table = parts.last(),
            alias = alias,
        )
    }

    // --- Select list ---------------------------------------------------------------------------

    /** A plain column or a star, or null for anything else. */
    private fun parseItem(tokens: List<Token>, from: FromParse.Table): SelectItem? {
        if (tokens.isEmpty()) return null
        if (tokens.size == 1 && tokens[0].isSymbol('*')) return SelectItem.Star

        // Qualifiers a column may carry: the alias hides the table's own name.
        fun qualifierOk(parts: List<String>): Boolean {
            if (parts.size == 1) return true
            val tableName = from.alias ?: from.table
            if (!parts[parts.size - 2].equals(tableName, ignoreCase = true)) return false
            if (parts.size == 3) {
                val db = from.database ?: return true
                return parts[0].equals(db, ignoreCase = true)
            }
            return true
        }

        // t.* and db.t.*
        if (tokens.size >= 3 && tokens.last().isSymbol('*') && tokens[tokens.size - 2].isSymbol('.')) {
            val parts = identifierParts(tokens.subList(0, tokens.size - 2)) ?: return null
            // The star stands in the column position, so the qualifier check sees it as one more part.
            return if (parts.size <= 2 && qualifierOk(parts + "*")) SelectItem.Star else null
        }

        var index = 0
        val parts = mutableListOf<String>()
        while (true) {
            val token = tokens.getOrNull(index) ?: return null
            if (!token.isIdentifier()) return null
            // NULL, TRUE and friends read like a bare column; they are not.
            if (token.kind == Kind.WORD && token.lower in NOT_A_COLUMN) return null
            parts += token.text
            index++
            if (tokens.getOrNull(index)?.isSymbol('.') == true && parts.size < 3) index++ else break
        }
        if (!qualifierOk(parts)) return null

        val rest = tokens.subList(index, tokens.size)
        val alias = when {
            rest.isEmpty() -> null
            rest.size == 2 && rest[0].isWord("as") && rest[1].isAliasToken() -> rest[1].text
            rest.size == 1 && rest[0].isAliasToken() -> rest[0].text
            else -> return null
        }
        return SelectItem.Column(parts.last(), alias)
    }

    private fun identifierParts(tokens: List<Token>): List<String>? {
        if (tokens.size % 2 == 0) return null
        val parts = mutableListOf<String>()
        for ((i, token) in tokens.withIndex()) {
            if (i % 2 == 0) {
                if (!token.isIdentifier()) return null
                parts += token.text
            } else if (!token.isSymbol('.')) {
                return null
            }
        }
        return parts
    }

    private fun splitTopLevel(tokens: List<Token>): List<List<Token>> {
        val items = mutableListOf<List<Token>>()
        var depth = 0
        var start = 0
        for ((i, token) in tokens.withIndex()) {
            when {
                token.isSymbol('(') -> depth++
                token.isSymbol(')') -> depth--
                token.isSymbol(',') && depth == 0 -> {
                    items += tokens.subList(start, i)
                    start = i + 1
                }
            }
        }
        items += tokens.subList(start, tokens.size)
        return items
    }

    // --- Tokens --------------------------------------------------------------------------------

    private enum class Kind { WORD, QUOTED, STRING, SYMBOL }

    /** [text] is the word as written, the unquoted name, the raw string, or the single symbol. */
    private class Token(val kind: Kind, val text: String) {
        val lower: String = text.lowercase()

        fun isSymbol(c: Char) = kind == Kind.SYMBOL && text[0] == c
        fun isWord(word: String) = kind == Kind.WORD && lower == word

        /** A name: a backquoted one, or a bare word that is neither a number nor an operator. */
        fun isIdentifier() =
            kind == Kind.QUOTED || (kind == Kind.WORD && !text.all { it.isDigit() } && lower !in OPERATOR_WORDS)

        fun isAliasToken() = kind == Kind.STRING || isIdentifier()
    }

    private fun depthOf(tokens: List<Token>): List<Int> {
        var current = 0
        return tokens.map { token ->
            if (token.isSymbol(')')) current--
            val here = current.coerceAtLeast(0)
            if (token.isSymbol('(')) current++
            here
        }
    }

    /**
     * Splits SQL into words, quoted names, strings and single symbols, dropping comments.
     *
     * Own tokenizer rather than WriteImpact's blanking helpers: those blank backquoted names along
     * with string contents, and the names are exactly what has to be read here.
     */
    private fun tokenize(sql: String, syntax: SqlSyntax): List<Token> {
        val grammar = syntax.grammar
        // The character that quotes a name: a backtick in MySQL, a double quote in PostgreSQL.
        val idQuote = syntax.quoteIdentifier("x").first()
        val out = mutableListOf<Token>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                // MySQL wants a blank after `--`; the standard (PostgreSQL) does not.
                grammar.opensLineComment(sql, i) &&
                    (grammar.hashComments.not() || c == '#' || i + 2 >= sql.length || sql[i + 2].isWhitespace()) ->
                    i = sql.indexOf('\n', i).takeIf { it >= 0 } ?: sql.length

                sql.startsWith("/*", i) -> i = sql.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: sql.length
                c == idQuote -> {
                    val end = minOf(grammar.endOfQuoted(sql, i), sql.length)
                    out += Token(
                        Kind.QUOTED,
                        sql.substring(i + 1, maxOf(i + 1, end - 1)).replace("$idQuote$idQuote", idQuote.toString()),
                    )
                    i = end
                }

                grammar.opensQuote(sql, i) -> {
                    val end = minOf(grammar.endOfQuoted(sql, i), sql.length)
                    out += Token(Kind.STRING, sql.substring(i, end))
                    i = end
                }

                c.isLetterOrDigit() || c == '_' || c == '$' -> {
                    var end = i
                    while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_' || sql[end] == '$')) end++
                    out += Token(Kind.WORD, sql.substring(i, end))
                    i = end
                }

                else -> {
                    out += Token(Kind.SYMBOL, c.toString())
                    i++
                }
            }
        }
        return out
    }

    private val HARMLESS_MODIFIERS = setOf(
        "all", "high_priority", "sql_small_result", "sql_big_result", "sql_buffer_result",
        "sql_no_cache", "sql_cache", "sql_calc_found_rows",
    )

    /** What ends the FROM clause. */
    private val CLAUSE_STARTERS = setOf(
        "where", "group", "having", "order", "limit", "union", "intersect", "except", "window",
        "for", "lock", "into", "procedure", "offset",
    )

    private val JOIN_WORDS = setOf("join", "inner", "cross", "left", "right", "natural", "outer", "straight_join", "lateral")
    private val HINT_WORDS = setOf("use", "ignore", "force")

    private val AGGREGATES = setOf(
        "count", "sum", "avg", "min", "max", "group_concat", "bit_and", "bit_or", "bit_xor", "std", "stddev",
        "stddev_pop", "stddev_samp", "var_pop", "var_samp", "variance", "json_arrayagg", "json_objectagg",
    )

    /** Words that read as a bare column in a select list and are values. */
    private val NOT_A_COLUMN = setOf(
        "null", "true", "false", "current_date", "current_time", "current_timestamp", "current_user",
        "localtime", "localtimestamp", "default",
    )

    /** Operators spelled as words: they can neither be a column nor an alias. */
    private val OPERATOR_WORDS = setOf(
        "not", "binary", "interval", "case", "and", "or", "xor", "like", "is", "in", "between", "div",
        "mod", "regexp", "rlike", "collate", "escape", "exists", "as", "from", "select", "distinct",
    )
}
