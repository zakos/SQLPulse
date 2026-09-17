package hu.laurel.sqlpulse.data.export

import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable

enum class ExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
}

/**
 * Turns a result page into CSV or JSON (§7.7).
 *
 * Deliberately free of Android types so it can be tested on the JVM; the screen only takes the
 * string and hands it to the share sheet.
 */
object ResultSerializer {

    fun serialize(table: ResultTable, format: ExportFormat): String = when (format) {
        ExportFormat.CSV -> toCsv(table)
        ExportFormat.JSON -> toJson(table)
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
