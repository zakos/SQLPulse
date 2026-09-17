package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.schema.quoteIdentifier

/** A statement and the values to bind to it, in order. */
data class PreparedSql(val sql: String, val parameters: List<String?>)

/** A row edit cannot be built without a primary key (§7.6). */
class NoPrimaryKeyException : Exception("this table has no primary key")

/**
 * Builds the row-level statements of §7.6.
 *
 * Values are always bound, never interpolated: [render] exists only to show the user what will
 * run, and its output is never executed. Identifiers are backtick-quoted, because they cannot be
 * bound.
 */
object RowSqlBuilder {

    /**
     * @param key the primary key columns and their current values; a NULL there would make the
     *   WHERE match nothing, so it is refused.
     */
    fun update(
        database: String,
        table: String,
        key: Map<String, String?>,
        column: String,
        newValue: String?,
    ): PreparedSql {
        requireKey(key)
        val sql = buildString {
            append("UPDATE ").append(qualified(database, table))
            append(" SET ").append(quoteIdentifier(column)).append(" = ?")
            append(whereClause(key))
        }
        return PreparedSql(sql, listOf(newValue) + key.values.toList())
    }

    fun delete(database: String, table: String, key: Map<String, String?>): PreparedSql {
        requireKey(key)
        val sql = "DELETE FROM ${qualified(database, table)}${whereClause(key)}"
        return PreparedSql(sql, key.values.toList())
    }

    fun insert(database: String, table: String, values: Map<String, String?>): PreparedSql {
        require(values.isNotEmpty()) { "an INSERT needs at least one column" }
        val columns = values.keys.joinToString(", ") { quoteIdentifier(it) }
        val placeholders = values.keys.joinToString(", ") { "?" }
        return PreparedSql(
            sql = "INSERT INTO ${qualified(database, table)} ($columns) VALUES ($placeholders)",
            parameters = values.values.toList(),
        )
    }

    /**
     * The statement with its values written in, for the confirmation dialog (§7.6). Display only —
     * what actually runs is the prepared statement with bound parameters.
     */
    fun render(prepared: PreparedSql): String {
        val builder = StringBuilder()
        var parameterIndex = 0
        prepared.sql.forEach { c ->
            if (c == '?' && parameterIndex < prepared.parameters.size) {
                builder.append(literal(prepared.parameters[parameterIndex]))
                parameterIndex++
            } else {
                builder.append(c)
            }
        }
        return builder.toString()
    }

    private fun literal(value: String?): String = when (value) {
        null -> "NULL"
        else -> "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"
    }

    private fun qualified(database: String, table: String) =
        "${quoteIdentifier(database)}.${quoteIdentifier(table)}"

    private fun whereClause(key: Map<String, String?>) =
        key.keys.joinToString(prefix = " WHERE ", separator = " AND ") { "${quoteIdentifier(it)} = ?" }

    private fun requireKey(key: Map<String, String?>) {
        if (key.isEmpty()) throw NoPrimaryKeyException()
        if (key.values.any { it == null }) throw NoPrimaryKeyException()
    }
}
