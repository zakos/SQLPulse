package hu.laurel.sqlpulse.data.export

import hu.laurel.sqlpulse.data.schema.quoteIdentifier
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable

enum class ExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    /** Tabs instead of commas: what spreadsheets paste cleanly and shells cut easily. */
    TSV("tsv", "text/tab-separated-values"),
    JSON("json", "application/json"),
    /** INSERT statements, to carry a handful of rows to another database. */
    SQL("sql", "application/sql"),
}

/**
 * Turns a result page into CSV or JSON (§7.7).
 *
 * Deliberately free of Android types so it can be tested on the JVM; the screen only takes the
 * string and hands it to the share sheet.
 */
object ResultSerializer {

    fun serialize(table: ResultTable, format: ExportFormat, tableName: String? = null): String =
        when (format) {
            ExportFormat.CSV -> toCsv(table)
            ExportFormat.TSV -> toTsv(table)
            ExportFormat.JSON -> toJson(table)
            ExportFormat.SQL -> toSqlInserts(table, tableName)
        }

    /** RFC 4180: comma separated, quotes doubled, CRLF line endings. */
    fun toCsv(table: ResultTable): String = buildString {
        append(table.columns.joinToString(",") { csvField(it.label) })
        append("\r\n")
        table.rows.forEach { row ->
            append(row.joinToString(",") { csvField(plainText(it)) })
            append("\r\n")
        }
    }

    /**
     * Tab separated, one row per line.
     *
     * A tab, a newline or a backslash inside a value is escaped the way MySQL's own
     * `SELECT ... INTO OUTFILE` does it, so a value containing a tab cannot silently become two
     * columns. NULL is `\N`, again as MySQL writes it.
     */
    fun toTsv(table: ResultTable): String = buildString {
        append(table.columns.joinToString("\t") { tsvField(it.label) })
        append("\n")
        table.rows.forEach { row ->
            append(row.joinToString("\t") { tsvField(plainText(it)) })
            append("\n")
        }
    }

    /**
     * One INSERT per row, for moving a few rows somewhere else.
     *
     * The table name comes from the caller — a query result can come from several tables or none,
     * and guessing would produce statements that look right and are not. Values are written as
     * literals, because a file of prepared statements would need the parameters beside it;
     * everything is escaped for MySQL, and a BLOB is refused rather than exported as its size,
     * which would insert nonsense.
     */
    fun toSqlInserts(table: ResultTable, tableName: String?): String {
        val target = tableName?.takeIf { it.isNotBlank() }
            ?: table.columns.firstNotNullOfOrNull { it.table }
            ?: "table_name"
        val columns = table.columns.joinToString(", ") { quoteIdentifier(it.label) }
        return table.rows.joinToString("\n") { row ->
            val values = table.columns.indices.joinToString(", ") { index ->
                sqlLiteral(row.getOrNull(index))
            }
            "INSERT INTO ${quoteIdentifier(target)} ($columns) VALUES ($values);"
        }
    }

    fun toJson(table: ResultTable): String = buildString {
        append("[")
        table.rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) append(",")
            append("{")
            table.columns.forEachIndexed { columnIndex, column ->
                if (columnIndex > 0) append(",")
                append(jsonString(column.label)).append(":")
                append(jsonValue(row.getOrNull(columnIndex)))
            }
            append("}")
        }
        append("]")
    }

    /**
     * A NULL is an empty, unquoted field, which is how MySQL's own CSV output distinguishes it
     * from an empty string. A BLOB exports its size, matching what the grid showed — the contents
     * were never read (§7.5).
     */
    private fun plainText(value: CellValue): String? = when (value) {
        is CellValue.Null -> null
        is CellValue.Text -> value.value
        is CellValue.Number -> value.value
        is CellValue.Date -> value.value
        is CellValue.Bool -> if (value.value) "1" else "0"
        is CellValue.Blob -> "[BLOB ${value.sizeBytes} B]"
    }

    private fun tsvField(value: String?): String {
        if (value == null) return "\\N"
        return value
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    /** MySQL string literal. A BLOB has no contents here, so it becomes NULL rather than a lie. */
    private fun sqlLiteral(value: CellValue?): String = when (value) {
        null, is CellValue.Null, is CellValue.Blob -> "NULL"
        is CellValue.Number -> value.value.takeIf { it.isFiniteNumber() } ?: quote(value.value)
        is CellValue.Bool -> if (value.value) "1" else "0"
        else -> quote(plainText(value).orEmpty())
    }

    private fun quote(value: String): String = "'" + value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u0000", "\\0") + "'"

    private fun csvField(value: String?): String {
        if (value == null) return ""
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    private fun jsonValue(value: CellValue?): String = when (value) {
        null, is CellValue.Null -> "null"
        is CellValue.Number -> value.value.takeIf { it.isFiniteNumber() } ?: jsonString(value.value)
        is CellValue.Bool -> value.value.toString()
        else -> jsonString(plainText(value).orEmpty())
    }

    private fun String.isFiniteNumber(): Boolean = toDoubleOrNull()?.isFinite() == true

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }
}
