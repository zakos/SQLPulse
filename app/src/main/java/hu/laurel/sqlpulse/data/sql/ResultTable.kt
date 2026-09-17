package hu.laurel.sqlpulse.data.sql

import java.sql.ResultSet
import java.sql.Types

/** Display type of a column, which is all the grid needs to know (§7.5, §8). */
enum class CellType { NUMBER, TEXT, DATE, BOOLEAN, BLOB }

data class ColumnMeta(
    val label: String,
    val type: CellType,
    /** MySQL type name as reported by the driver, e.g. VARCHAR, BIGINT, DATETIME. */
    val typeName: String,
    val table: String?,
)

/**
 * One cell. BLOBs never carry their contents into the grid — only a size, because a megabyte of
 * binary would have to be held in memory to show a string it is not (§7.5, §11).
 */
sealed interface CellValue {
    data object Null : CellValue
    data class Text(val value: String) : CellValue
    data class Number(val value: String) : CellValue
    data class Date(val value: String) : CellValue
    data class Bool(val value: Boolean) : CellValue
    data class Blob(val sizeBytes: Long) : CellValue
}

/** A page of results. Rows live in memory only for the session; nothing is written to disk (§9). */
data class ResultTable(
    val columns: List<ColumnMeta>,
    val rows: List<List<CellValue>>,
    /** True when the driver had more rows than [rows] holds. */
    val truncated: Boolean = false,
    val limitAdded: Boolean = false,
    val durationMs: Long = 0,
) {
    val rowCount: Int get() = rows.size

    companion object {
        val EMPTY = ResultTable(emptyList(), emptyList())

        /**
         * Reads at most [maxRows] rows out of [resultSet]. The driver stays in charge of fetching;
         * we stop early rather than letting a runaway query fill the heap (§10).
         */
        fun from(resultSet: ResultSet, maxRows: Int): ResultTable {
            val meta = resultSet.metaData
            val columns = (1..meta.columnCount).map { index ->
                ColumnMeta(
                    label = meta.getColumnLabel(index),
                    type = cellTypeOf(meta.getColumnType(index)),
                    typeName = meta.getColumnTypeName(index) ?: "",
                    table = meta.getTableName(index)?.takeIf { it.isNotBlank() },
                )
            }

            val rows = ArrayList<List<CellValue>>()
            var truncated = false
            while (resultSet.next()) {
                if (rows.size >= maxRows) {
                    truncated = true
                    break
                }
                rows += columns.indices.map { column ->
                    readCell(resultSet, column + 1, columns[column].type)
                }
            }
            return ResultTable(columns, rows, truncated)
        }

        private fun readCell(resultSet: ResultSet, index: Int, type: CellType): CellValue =
            when (type) {
                CellType.BLOB -> {
                    val bytes = resultSet.getBytes(index)
                    if (resultSet.wasNull() || bytes == null) {
                        CellValue.Null
                    } else {
                        CellValue.Blob(bytes.size.toLong())
                    }
                }

                CellType.BOOLEAN -> {
                    val value = resultSet.getBoolean(index)
                    if (resultSet.wasNull()) CellValue.Null else CellValue.Bool(value)
                }

                else -> {
                    val value = resultSet.getString(index)
                    when {
                        resultSet.wasNull() || value == null -> CellValue.Null
                        type == CellType.NUMBER -> CellValue.Number(value)
                        type == CellType.DATE -> CellValue.Date(value)
                        else -> CellValue.Text(value)
                    }
                }
            }

        fun cellTypeOf(jdbcType: Int): CellType = when (jdbcType) {
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
            Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL,
            -> CellType.NUMBER

            // Android's java.sql.Types stops at JDBC 4.1, so the two zoned constants are
            // spelled out: 2013 is TIME_WITH_TIMEZONE and 2014 is TIMESTAMP_WITH_TIMEZONE.
            Types.DATE, Types.TIME, Types.TIMESTAMP, 2013, 2014 -> CellType.DATE

            Types.BIT, Types.BOOLEAN -> CellType.BOOLEAN

            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> CellType.BLOB

            else -> CellType.TEXT
        }
    }
}
