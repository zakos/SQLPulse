package hu.laurel.sqlpulse.data.schema

/**
 * How a generated column is materialised.
 *
 * A virtual column is computed on every read and takes no space; a stored one is written on
 * insert and can be indexed on any server. The distinction changes what the column costs, so it
 * is worth showing rather than flattening both into "generated".
 */
enum class GeneratedKind { VIRTUAL, STORED }

/**
 * The reading and the wording of the extra schema facts (CHECK constraints, referential rules,
 * generated columns, collation, partitioning).
 *
 * Everything here is pure: a string in, a string out. It sits apart from [SchemaRepository]
 * because each of these facts arrives from the server in a shape nobody would choose to read —
 * `CHECK_CLAUSE` comes back wrapped in its own parentheses with the quotes escaped, `EXTRA`
 * announces a generated column in three different spellings depending on the server, and
 * `DELETE_RULE` is never empty even when the foreign key has no rule worth mentioning. Doing that
 * work in one place, with no database and no Android in reach, is what makes it testable.
 */
object SchemaExtras {

    /**
     * Which kind of generated column [extra] describes, or null for an ordinary column.
     *
     * `information_schema.COLUMNS.EXTRA` is where MySQL hides this, and the three servers we care
     * about do not agree on the spelling: MySQL writes "VIRTUAL GENERATED" or "STORED GENERATED",
     * MariaDB writes "VIRTUAL" / "PERSISTENT" (its own word for stored), and both may prefix
     * other words such as "DEFAULT_GENERATED" — which is *not* a generated column at all, only a
     * column whose default is an expression. Hence the match on whole words: "DEFAULT_GENERATED"
     * contains neither "VIRTUAL" nor "STORED" nor "PERSISTENT", and stays an ordinary column.
     */
    fun generatedKind(extra: String?): GeneratedKind? {
        val words = extra?.uppercase()?.split(' ', ',', '\t')?.filter { it.isNotBlank() }.orEmpty()
        return when {
            words.contains("VIRTUAL") -> GeneratedKind.VIRTUAL
            words.contains("STORED") || words.contains("PERSISTENT") -> GeneratedKind.STORED
            else -> null
        }
    }

    /**
     * The expression a generated column is computed from, as it would be typed.
     *
     * The server stores it with the quotes escaped for its own `SHOW CREATE TABLE` output, and
     * MariaDB additionally wraps the whole thing in parentheses. Both are undone here: what the
     * reader wants is the expression, not the server's way of quoting it.
     */
    fun generationExpression(raw: String?): String? = unwrapExpression(raw)

    /**
     * The `ON DELETE` / `ON UPDATE` line for a foreign key, or null when there is nothing to say.
     *
     * `REFERENTIAL_CONSTRAINTS` always has a rule for both, so printing them unconditionally would
     * put "ON DELETE RESTRICT · ON UPDATE RESTRICT" under nearly every key in the schema and teach
     * the reader to stop looking. RESTRICT and NO ACTION are what a key does when nothing was
     * declared — MySQL treats the two as the same thing — so only the rules that actually change
     * what a delete or an update does are worth a line.
     */
    fun foreignKeyRules(onDelete: String?, onUpdate: String?): String? {
        val parts = buildList {
            notableRule(onDelete)?.let { add("ON DELETE $it") }
            notableRule(onUpdate)?.let { add("ON UPDATE $it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** A referential rule worth showing, normalised; null for RESTRICT, NO ACTION and blanks. */
    fun notableRule(raw: String?): String? {
        val rule = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        return rule.takeUnless { it == "RESTRICT" || it == "NO ACTION" }
    }

    /**
     * A CHECK constraint's condition, as a person would write it.
     *
     * MySQL hands back `CHECK_CLAUSE` already wrapped in the parentheses it will print in
     * `SHOW CREATE TABLE` and with every quote escaped — `(\`price\` > 0)` or
     * `(\`code\` <> _utf8mb4\'\')`. The outer pair of parentheses is removed only when it wraps
     * the whole expression, so `(a > 0) AND (b > 0)` keeps both of its pairs, and the line breaks
     * the server inserts are collapsed so a long condition stays one readable line.
     */
    fun checkExpression(raw: String?): String? = unwrapExpression(raw)

    /**
     * The collation worth showing next to a column, or null.
     *
     * Almost every column in a schema inherits the table's collation, so repeating it on each row
     * would bury the one column that does not. Only a column that differs from [tableCollation] —
     * the case that silently breaks comparisons and index use in a join — gets a line of its own.
     * A numeric or date column has no collation at all and also gets nothing.
     */
    fun columnCollation(tableCollation: String?, columnCollation: String?): String? {
        val column = columnCollation?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return column.takeUnless { it.equals(tableCollation?.trim(), ignoreCase = true) }
    }

    /**
     * The character set a collation belongs to: everything before the first underscore.
     *
     * MySQL names a collation after its character set (`utf8mb4_0900_ai_ci`), and the character
     * set is the half that decides which characters can be stored at all.
     */
    fun charsetOf(collation: String?): String? =
        collation?.trim()?.takeIf { it.isNotEmpty() }?.substringBefore('_')?.takeIf { it.isNotEmpty() }

    /**
     * How the table is cut up: the method and the expression it is cut on, "RANGE (YEAR(sold_at))".
     *
     * The expression is empty for `KEY` partitioning on the primary key, where the method alone is
     * the whole truth, so it is left off rather than shown as an empty pair of brackets.
     */
    fun partitionSummary(partitions: List<TablePartition>): String? {
        val first = partitions.firstOrNull() ?: return null
        val method = first.method?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val expression = first.expression?.trim()?.takeIf { it.isNotEmpty() }
        return if (expression == null) method else "$method ($expression)"
    }

    /**
     * The rows the server believes are in the partitions together, or null when it will not say.
     *
     * Every partition of an InnoDB table reports an estimate, not a count, and a partition that
     * reports nothing at all is left out rather than counted as empty — a sum with a hole in it is
     * still closer to the truth than a zero that looks measured.
     */
    fun partitionRowTotal(partitions: List<TablePartition>): Long? {
        val known = partitions.mapNotNull { it.approximateRows }
        return known.takeIf { it.isNotEmpty() }?.sum()
    }

    /**
     * Cuts a long expression down for a list row, ending it with an ellipsis.
     *
     * A CHECK clause or a generated column's expression has no length limit worth relying on, and
     * one that runs to a paragraph would push everything else off the screen. The full text is
     * still in the DDL tab, which is where someone reading the whole expression should be.
     */
    fun shorten(text: String, max: Int = 120): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    /**
     * Undoes the server's own quoting of a stored expression.
     *
     * Two things are undone: the backslash escaping MySQL applies to the quotes inside the
     * expression, and a single pair of parentheses around the whole of it. Whitespace — including
     * the newlines a multi-line condition was written with — becomes single spaces, because these
     * are shown on one line.
     */
    private fun unwrapExpression(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val unescaped = text.replace("\\'", "'").replace("\\\"", "\"")
        val collapsed = unescaped.split(' ', '\n', '\r', '\t')
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return stripOuterParentheses(collapsed).takeIf { it.isNotEmpty() }
    }

    /**
     * Removes one pair of parentheses, and only when it really does wrap the whole expression.
     *
     * `(a > 0)` loses its pair; `(a > 0) AND (b > 0)` keeps both, because its first opening
     * parenthesis is closed again before the end. Getting this wrong would turn a valid condition
     * into `a > 0) AND (b > 0`, which is worse than leaving the brackets alone, so the depth is
     * counted rather than guessed from the first and last character.
     */
    private fun stripOuterParentheses(text: String): String {
        if (!text.startsWith("(") || !text.endsWith(")")) return text
        var depth = 0
        text.forEachIndexed { index, character ->
            when (character) {
                '(' -> depth++
                ')' -> {
                    depth--
                    // Closed before the last character: the pair wraps only part of the text.
                    if (depth == 0 && index != text.lastIndex) return text
                }
            }
        }
        return if (depth == 0) stripOuterParentheses(text.substring(1, text.length - 1).trim()) else text
    }
}
