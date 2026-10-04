package hu.laurel.sqlpulse.data.export

import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlSyntax

enum class ExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    /** Tabs instead of commas: what spreadsheets paste cleanly and shells cut easily. */
    TSV("tsv", "text/tab-separated-values"),
    JSON("json", "application/json"),
    /** Markdown table format. */
    MARKDOWN("md", "text/markdown"),
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

    fun serialize(
        table: ResultTable,
        format: ExportFormat,
        tableName: String? = null,
        syntax: SqlSyntax = MySqlDialect,
    ): String =
        when (format) {
            ExportFormat.CSV -> toCsv(table)
            ExportFormat.TSV -> toTsv(table)
            ExportFormat.JSON -> toJson(table)
            ExportFormat.MARKDOWN -> toMarkdown(table)
            ExportFormat.SQL -> toSqlInserts(table, tableName, syntax)
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
     * everything is quoted and escaped for [syntax]'s engine (MySQL by default), and a BLOB is refused rather than exported as its size,
     * which would insert nonsense.
     */
    fun toSqlInserts(table: ResultTable, tableName: String?, syntax: SqlSyntax = MySqlDialect): String {
        val target = tableName?.takeIf { it.isNotBlank() }
            ?: table.columns.firstNotNullOfOrNull { it.table }
            ?: "table_name"
        val columns = table.columns.joinToString(", ") { syntax.quoteIdentifier(it.label) }
        return table.rows.joinToString("\n") { row ->
            val values = table.columns.indices.joinToString(", ") { index ->
                sqlLiteral(row.getOrNull(index), syntax)
            }
            "INSERT INTO ${syntax.quoteIdentifier(target)} ($columns) VALUES ($values);"
        }
    }

    /**
     * The same formats one row at a time, for a result too big to hold: [header], then [row] for
     * each row as it arrives, then [footer]. Concatenated they equal [serialize] of the whole
     * table, except for Markdown, whose padded columns need every row before the first can be
     * written; the streamed table is unpadded, which renders the same.
     */
    class RowWriter(
        private val format: ExportFormat,
        private val columns: List<hu.laurel.sqlpulse.data.sql.ColumnMeta>,
        tableName: String? = null,
        private val syntax: SqlSyntax = MySqlDialect,
    ) {
        private var written = 0L
        private val target = tableName?.takeIf { it.isNotBlank() }
            ?: columns.firstNotNullOfOrNull { it.table }
            ?: "table_name"
        private val quotedColumns = columns.joinToString(", ") { syntax.quoteIdentifier(it.label) }

        fun header(): String = when (format) {
            ExportFormat.CSV -> columns.joinToString(",") { csvField(it.label) } + "\r\n"
            ExportFormat.TSV -> columns.joinToString("\t") { tsvField(it.label) } + "\n"
            ExportFormat.JSON -> "["
            ExportFormat.MARKDOWN -> if (columns.isEmpty()) {
                ""
            } else {
                "| " + columns.joinToString(" | ") { markdownCell(it.label) } + " |\n" +
                    "| " + columns.joinToString(" | ") { "--" } + " |\n"
            }
            ExportFormat.SQL -> ""
        }

        fun row(cells: List<CellValue>): String {
            val first = written++ == 0L
            return when (format) {
                ExportFormat.CSV -> cells.joinToString(",") { csvField(plainText(it)) } + "\r\n"
                ExportFormat.TSV -> cells.joinToString("\t") { tsvField(plainText(it)) } + "\n"
                ExportFormat.JSON -> buildString {
                    if (!first) append(",")
                    append("{")
                    columns.forEachIndexed { index, column ->
                        if (index > 0) append(",")
                        append(jsonString(column.label)).append(":")
                        append(jsonValue(cells.getOrNull(index)))
                    }
                    append("}")
                }
                ExportFormat.MARKDOWN ->
                    "| " + cells.joinToString(" | ") { markdownCell(plainText(it) ?: "NULL") } + " |\n"
                ExportFormat.SQL -> {
                    val values = columns.indices.joinToString(", ") { sqlLiteral(cells.getOrNull(it), syntax) }
                    (if (first) "" else "\n") + "INSERT INTO ${syntax.quoteIdentifier(target)} ($quotedColumns) VALUES ($values);"
                }
            }
        }

        fun footer(): String = when (format) {
            ExportFormat.JSON -> "]"
            else -> ""
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
     * Markdown table format with pipe-delimited cells.
     *
     * Inspired by dbx (crates/dbx-formats/src/text_export.rs): escapes pipes and newlines,
     * pads columns for readability. NULL is shown as "NULL".
     */
    fun toMarkdown(table: ResultTable): String {
        if (table.columns.isEmpty()) return ""

        // Escape pipes and newlines in all column headers and cell values
        val headers = table.columns.map { markdownCell(it.label) }
        val rows = table.rows.map { row ->
            row.map { markdownCell(plainText(it) ?: "NULL") }
        }

        // Calculate column widths for padding
        val widths = headers.indices.map { colIndex ->
            val headerWidth = headers.getOrNull(colIndex)?.length ?: 0
            val maxRowWidth = rows.maxOfOrNull { it.getOrNull(colIndex)?.length ?: 0 } ?: 0
            maxOf(headerWidth, maxRowWidth)
        }

        return buildString {
            // Header row
            append("| ")
            append(headers.mapIndexed { index, header ->
                padRight(header, widths[index])
            }.joinToString(" | "))
            append(" |\n")

            // Separator row (minimum 2 dashes per column)
            append("| ")
            append(widths.mapIndexed { index, width ->
                "-".repeat(maxOf(width, 2))
            }.joinToString(" | "))
            append(" |\n")

            // Data rows
            rows.forEach { row ->
                append("| ")
                append(row.mapIndexed { index, cell ->
                    padRight(cell, widths[index])
                }.joinToString(" | "))
                append(" |\n")
            }
        }
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

    /** A literal in [syntax]'s dialect. A BLOB has no contents here, so it becomes NULL rather than a lie. */
    private fun sqlLiteral(value: CellValue?, syntax: SqlSyntax): String = when (value) {
        null, is CellValue.Null, is CellValue.Blob -> "NULL"
        is CellValue.Number -> value.value.takeIf { it.isFiniteNumber() } ?: quote(value.value, syntax)
        // PostgreSQL's boolean does not take 1 and 0; everywhere else a bit or an integer does.
        is CellValue.Bool ->
            if (syntax.engine == DatabaseEngine.POSTGRESQL) {
                if (value.value) "TRUE" else "FALSE"
            } else {
                if (value.value) "1" else "0"
            }
        else -> quote(plainText(value).orEmpty(), syntax)
    }

    /**
     * MySQL escapes with backslashes; the other engines read a backslash literally and escape
     * only the quote itself (so doubling backslashes there would corrupt the value). A string
     * with non-ASCII text gets T-SQL's `N` prefix, or SQL Server would store it in a code page.
     */
    private fun quote(value: String, syntax: SqlSyntax): String {
        if (syntax.grammar.backslashEscapes) {
            return "'" + value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\u0000", "\\0") + "'"
        }
        val literal = "'" + value.replace("'", "''") + "'"
        return if (syntax.engine == DatabaseEngine.SQLSERVER && value.any { it.code > 127 }) "N$literal" else literal
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

    /** Escape pipes and newlines for Markdown cells. */
    private fun markdownCell(value: String): String =
        value.replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\r\n", "<br>")
            .replace("\n", "<br>")
            .replace("\r", "<br>")

    /** Pad value to width by appending spaces. */
    private fun padRight(value: String, width: Int): String =
        if (value.length >= width) value else value + " ".repeat(width - value.length)
}
