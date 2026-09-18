package hu.laurel.sqlpulse.data.csv

import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.sql.PreparedSql
import hu.laurel.sqlpulse.data.sql.RowSqlBuilder

/**
 * How a file's columns line up with a table's.
 *
 * @param matched file column name to table column name, in file order.
 * @param unmatched file columns with no column of that name in the table; their values are not
 *   imported, and the screen says so before anything runs.
 * @param missing table columns the file does not fill. Only a problem when the column demands a
 *   value, which is what [blocking] is about.
 */
data class ColumnMatch(
    val matched: Map<String, String>,
    val unmatched: List<String>,
    val missing: List<String>,
    /** Columns the file does not fill and the table cannot fill by itself. */
    val blocking: List<String>,
) {
    val canImport: Boolean get() = matched.isNotEmpty() && blocking.isEmpty()
}

/**
 * Builds the INSERTs for a CSV import (research summary, §1.2).
 *
 * Columns are matched by name, ignoring case and surrounding spaces, because that is how a file
 * exported from the same table comes back. Nothing is matched by position: a file whose columns
 * are in another order would then be written into the wrong columns, and every row would look
 * plausible.
 */
object CsvImport {

    fun match(header: List<String>, columns: List<SchemaColumn>): ColumnMatch {
        val byName = columns.associateBy { it.name.lowercase() }
        val matched = LinkedHashMap<String, String>()
        val unmatched = mutableListOf<String>()

        header.forEach { fileColumn ->
            val column = byName[fileColumn.trim().lowercase()]
            if (column == null) unmatched += fileColumn else matched[fileColumn] = column.name
        }

        val filled = matched.values.toSet()
        val missing = columns.map { it.name }.filterNot { it in filled }
        return ColumnMatch(
            matched = matched,
            unmatched = unmatched,
            missing = missing,
            blocking = columns.filter { it.name in missing && it.demandsValue() }.map { it.name },
        )
    }

    /**
     * One INSERT per row, values bound.
     *
     * A row whose fields are all NULL is skipped: that is what a stray line in the file looks
     * like, and inserting it would add a row of nothing.
     */
    fun statements(
        database: String,
        table: String,
        match: ColumnMatch,
        header: List<String>,
        rows: List<List<String?>>,
    ): List<PreparedSql> {
        val indexes = match.matched.keys.map { header.indexOf(it) }
        val targets = match.matched.values.toList()
        return rows.mapNotNull { row ->
            val values = indexes.map { index -> row.getOrNull(index) }
            if (values.all { it == null }) return@mapNotNull null
            RowSqlBuilder.insert(database, table, targets.zip(values).toMap())
        }
    }

    /**
     * True when the column has no value of its own to fall back on: NOT NULL, no default, and not
     * filled in by the server. An AUTO_INCREMENT key or a TIMESTAMP DEFAULT CURRENT_TIMESTAMP is
     * fine to leave out; a plain NOT NULL column is not.
     */
    private fun SchemaColumn.demandsValue(): Boolean {
        if (nullable) return false
        if (defaultValue != null) return false
        return extra.orEmpty().lowercase().let { extra ->
            !extra.contains("auto_increment") && !extra.contains("default_generated")
        }
    }
}
