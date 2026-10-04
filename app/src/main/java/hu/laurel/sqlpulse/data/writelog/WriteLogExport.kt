package hu.laurel.sqlpulse.data.writelog

import hu.laurel.sqlpulse.data.db.WriteLogEntity
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import java.time.Instant

/**
 * The log as a [ResultTable], so the export reuses the CSV/JSON serializer (and its quoting)
 * instead of growing a second one. Times are ISO-8601 UTC: unambiguous in a file that leaves the
 * phone.
 */
object WriteLogExport {

    private val COLUMNS = listOf(
        "time" to CellType.DATE,
        "connection" to CellType.TEXT,
        "environment" to CellType.TEXT,
        "database" to CellType.TEXT,
        "source" to CellType.TEXT,
        "affected_rows" to CellType.NUMBER,
        "outcome" to CellType.TEXT,
        "error" to CellType.TEXT,
        "duration_ms" to CellType.NUMBER,
        "in_transaction" to CellType.BOOLEAN,
        "statement" to CellType.TEXT,
    )

    fun toTable(entries: List<WriteLogEntity>): ResultTable = ResultTable(
        columns = COLUMNS.map { (label, type) -> ColumnMeta(label, type, type.name, "write_log") },
        rows = entries.map { e ->
            listOf(
                CellValue.Date(Instant.ofEpochMilli(e.time).toString()),
                CellValue.Text(e.connectionName),
                CellValue.Text(e.environment),
                text(e.database),
                CellValue.Text(e.source),
                e.affectedRows?.let { CellValue.Number(it.toString()) } ?: CellValue.Null,
                CellValue.Text(e.outcome),
                text(e.error),
                CellValue.Number(e.durationMs.toString()),
                CellValue.Bool(e.inTransaction),
                CellValue.Text(e.statement),
            )
        },
    )

    private fun text(value: String?): CellValue = value?.let { CellValue.Text(it) } ?: CellValue.Null
}
