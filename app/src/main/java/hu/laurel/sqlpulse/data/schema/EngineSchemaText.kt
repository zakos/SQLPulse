package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine

/**
 * Column types, defaults and constraint names as one engine spells them, reduced to what two
 * servers of that engine can agree on. The comparison ([SchemaDiff]) calls these for the
 * non-MySQL engines; MySQL's own rules stay in SchemaDiff where they always were.
 *
 * A comparison only ever puts two databases of the *same* engine side by side (different engines
 * spell everything differently and would produce nothing but noise), so none of this converts
 * between engines: it removes spelling differences inside one.
 */
object EngineSchemaText {

    private val WHITESPACE = Regex("\\s+")

    // ------------------------------------------------------------------ PostgreSQL

    /**
     * `format_type` writes `character varying(255)` where DDL, ORMs and psql habits say
     * `varchar(255)`, and the system catalog says `int4` for `integer`; both sides of a
     * comparison come from `format_type`, but a hand-written or imported capture may not. The
     * pairs are whole-word replacements applied to the part before any `(n)` or `[]`.
     */
    private val POSTGRES_SYNONYMS = listOf(
        "character varying" to "varchar",
        "character" to "char",
        "bpchar" to "char",
        "int4" to "integer",
        "int" to "integer",
        "serial" to "integer",
        "serial4" to "integer",
        "int2" to "smallint",
        "smallserial" to "smallint",
        "serial2" to "smallint",
        "int8" to "bigint",
        "bigserial" to "bigint",
        "serial8" to "bigint",
        "float4" to "real",
        "float8" to "double precision",
        "float" to "double precision",
        "bool" to "boolean",
        "decimal" to "numeric",
        "timestamp without time zone" to "timestamp",
        "timestamp with time zone" to "timestamptz",
        "time without time zone" to "time",
        "time with time zone" to "timetz",
    )

    // `varchar(255)`, `numeric(10,2)[]`, `timestamp(3) with time zone`: base, optional (args), rest.
    private val POSTGRES_TYPE = Regex("^([a-z_][a-z0-9_ ]*?)\\s*(\\([^)]*\\))?\\s*((?:with(?:out)? time zone)?\\s*(?:\\[\\])*)$")

    fun normalizePostgresType(type: String): String {
        val lower = type.trim().lowercase().replace(WHITESPACE, " ")
        val match = POSTGRES_TYPE.matchEntire(lower) ?: return lower
        var base = match.groupValues[1].trim()
        val args = match.groupValues[2].replace(" ", "")
        var rest = match.groupValues[3].trim()
        // `timestamp(3) with time zone`: the zone words trail the arguments.
        if (rest.startsWith("with")) {
            base = "$base $rest".replace(WHITESPACE, " ")
            rest = ""
        }
        val canonical = POSTGRES_SYNONYMS.firstOrNull { it.first == base }?.second ?: base
        val arrays = rest.replace(" ", "")
        return canonical + args + arrays
    }

    private val NEXTVAL = Regex("^nextval\\(.*\\)$", RegexOption.IGNORE_CASE)
    private val POSTGRES_CAST = Regex("::\\s*[a-z_][a-z0-9_ ]*(?:\\([^)]*\\))?(?:\\[\\])*", RegexOption.IGNORE_CASE)

    /**
     * A serial column's `nextval('public.orders_id_seq'::regclass)` names the sequence, with a
     * schema that is `shop_dev` on one server and `shop` on the other and depends on `search_path`
     * for the printing; the sequence is the same idea either way. Casts the server adds to
     * literals (`'x'::character varying`) are not part of what anybody wrote.
     */
    fun normalizePostgresDefault(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (NEXTVAL.matches(trimmed)) return "nextval(sequence)"
        val withoutCasts = POSTGRES_CAST.replace(trimmed, "").trim()
        return stripOuterParentheses(withoutCasts)
    }

    // ------------------------------------------------------------------ SQL Server

    /**
     * `nvarchar(max)` is `nvarchar(-1)` in the catalog and either way the same type; `numeric` is
     * the ANSI name for `decimal`, and `timestamp` is the old name of `rowversion`.
     */
    fun normalizeSqlServerType(type: String): String {
        var lower = type.trim().lowercase().replace(WHITESPACE, " ")
        lower = lower.replace(Regex("\\s*\\(\\s*"), "(").replace(Regex("\\s*,\\s*"), ",").replace(Regex("\\s*\\)"), ")")
        lower = lower.replace("(-1)", "(max)")
        val base = lower.substringBefore('(')
        val args = lower.substring(base.length)
        val canonical = when (base) {
            "numeric" -> "decimal"
            "timestamp" -> "rowversion"
            "national character varying", "nchar varying" -> "nvarchar"
            "character varying", "char varying" -> "varchar"
            "double precision" -> "float"
            "integer" -> "int"
            else -> base
        }
        return canonical + args
    }

    private val NATIONAL_LITERAL = Regex("^[nN](?='.*'$)")
    private val BARE_CALL = Regex("^[a-zA-Z_][a-zA-Z0-9_]*\\(\\)$")

    /**
     * T-SQL stores a default with the brackets it was written in: `((0))`, `(getdate())`,
     * `(N'abc')`. Two databases created by different scripts differ in the brackets and in the
     * `N` prefix of a Unicode literal, not in the default.
     */
    fun normalizeSqlServerDefault(value: String?): String? {
        var text = value?.trim() ?: return null
        text = stripOuterParentheses(text)
        text = NATIONAL_LITERAL.replace(text, "")
        // `getdate()` and `GETDATE()` are one function; text inside quotes is left alone.
        if (BARE_CALL.matches(text)) text = text.lowercase()
        if (text.equals("current_timestamp", ignoreCase = true)) text = "getdate()"
        return text
    }

    // SQL Server names an unnamed constraint `PK__orders__3213E83F5C8A2E1B`: the tail is random,
    // so the same constraint has a different name on every server it was created on.
    private val GENERATED_SQLSERVER_NAME = Regex("^(PK|FK|UQ|CK|DF)__.+__[0-9A-Fa-f]{8,}$")

    fun isGeneratedName(engine: DatabaseEngine, name: String): Boolean =
        engine == DatabaseEngine.SQLSERVER && GENERATED_SQLSERVER_NAME.matches(name)

    // ------------------------------------------------------------------ SQLite

    /**
     * SQLite does not enforce a declared type: it files it under one of five affinities by looking
     * for substrings (section 3.1 of its datatype page). `VARCHAR(255)` and `TEXT` are the same
     * column; `INT`, `INTEGER` and `BIGINT` are the same column. A name that lands in NUMERIC
     * (`DATE`, `BOOLEAN`, `DECIMAL(10,2)`) is kept as written, because there the declared name
     * is all the schema says about what the column is for.
     */
    fun normalizeSqliteType(type: String): String {
        val lower = type.trim().lowercase().replace(WHITESPACE, " ")
        return when {
            lower.isEmpty() -> "blob"
            "int" in lower -> "integer"
            "char" in lower || "clob" in lower || "text" in lower -> "text"
            "blob" in lower -> "blob"
            "real" in lower || "floa" in lower || "doub" in lower -> "real"
            else -> lower.replace(Regex("\\s*,\\s*"), ",")
        }
    }

    fun normalizeSqliteDefault(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        return stripOuterParentheses(trimmed)
    }

    // ------------------------------------------------------------------ shared

    /** `((0))` becomes `0`; `(a) + (b)` stays, because its brackets do not wrap the whole text. */
    fun stripOuterParentheses(text: String): String {
        var current = text.trim()
        while (current.length >= 2 && current.first() == '(' && current.last() == ')' && wrapsWhole(current)) {
            current = current.substring(1, current.length - 1).trim()
        }
        return current
    }

    private fun wrapsWhole(text: String): Boolean {
        var depth = 0
        var quote: Char? = null
        for ((index, c) in text.withIndex()) {
            if (quote != null) {
                if (c == quote) quote = null
                continue
            }
            when (c) {
                '\'', '"' -> quote = c
                '(' -> depth++
                ')' -> {
                    depth--
                    // Closed before the end: the first bracket belongs to something shorter.
                    if (depth == 0 && index < text.length - 1) return false
                }
            }
        }
        return depth == 0
    }
}
