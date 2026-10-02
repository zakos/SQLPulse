package hu.laurel.sqlpulse.data.grid

import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Statistics for a single column computed over loaded rows.
 * Numeric stats (sum, avg, min, max) are only computed when values parse as numbers.
 */
data class ColumnStats(
    val rowCount: Int,
    val nullCount: Int,
    val distinctCount: Int,
    val sum: BigDecimal?,
    val average: String?,
    val min: String?,
    val max: String?,
) {
    val nonNullCount: Int get() = rowCount - nullCount
}

object ColumnStatsComputer {
    /**
     * Computes statistics for a column at the given index in a result table.
     * Works on the loaded rows (no re-query). Numeric stats require all non-null
     * values to parse as BigDecimal; min/max text/date use lexicographic comparison.
     */
    fun compute(table: ResultTable, columnIndex: Int): ColumnStats? {
        if (columnIndex !in table.columns.indices || table.rows.isEmpty()) {
            return null
        }

        val values = table.rows.mapNotNull { it.getOrNull(columnIndex) }
        var nullCount = 0
        var distinctCount = 0
        var sum: BigDecimal? = BigDecimal.ZERO
        var allNumeric = true
        var minText: String? = null
        var maxText: String? = null

        val seen = mutableSetOf<String>()

        for (value in table.rows.mapNotNull { it.getOrNull(columnIndex) }) {
            when (value) {
                is CellValue.Null -> nullCount++
                is CellValue.Number -> {
                    val decimal = value.value.toBigDecimalOrNull()
                    if (decimal != null && sum != null) {
                        sum += decimal
                    } else {
                        sum = null
                        allNumeric = false
                    }
                    val text = value.value
                    seen.add(text)
                    if (minText == null) {
                        minText = text
                        maxText = text
                    } else if (minText != null && maxText != null) {
                        if (text < minText) minText = text
                        if (text > maxText) maxText = text
                    }
                }
                is CellValue.Bool -> {
                    val num = if (value.value) BigDecimal.ONE else BigDecimal.ZERO
                    sum = sum?.plus(num)
                    val text = if (value.value) "1" else "0"
                    seen.add(text)
                    if (minText == null) {
                        minText = text
                        maxText = text
                    } else if (minText != null && maxText != null) {
                        if (text < minText) minText = text
                        if (text > maxText) maxText = text
                    }
                }
                is CellValue.Date -> {
                    val text = value.value
                    seen.add(text)
                    if (minText == null) {
                        minText = text
                        maxText = text
                    } else if (minText != null && maxText != null) {
                        if (text < minText) minText = text
                        if (text > maxText) maxText = text
                    }
                    allNumeric = false
                }
                is CellValue.Text -> {
                    val text = value.value
                    seen.add(text)
                    if (minText == null) {
                        minText = text
                        maxText = text
                    } else if (minText != null && maxText != null) {
                        if (text < minText) minText = text
                        if (text > maxText) maxText = text
                    }
                    allNumeric = false
                }
                is CellValue.Blob -> {
                    val text = value.sizeBytes.toString()
                    seen.add(text)
                    if (minText == null) {
                        minText = text
                        maxText = text
                    } else if (minText != null && maxText != null) {
                        if (text < minText) minText = text
                        if (text > maxText) maxText = text
                    }
                }
            }
        }

        // Distinct count includes only non-null values; NULL is not counted
        distinctCount = seen.size

        val avgStr = if (allNumeric && sum != null && (table.rowCount - nullCount) > 0) {
            val avg = sum.divide(
                BigDecimal(table.rowCount - nullCount),
                6,
                RoundingMode.HALF_UP,
            )
            // Strip trailing zeros after decimal point
            avg.stripTrailingZeros().toPlainString()
        } else {
            null
        }

        return ColumnStats(
            rowCount = table.rowCount,
            nullCount = nullCount,
            distinctCount = distinctCount,
            sum = if (allNumeric && sum != null && sum != BigDecimal.ZERO) sum else null,
            average = avgStr,
            min = minText,
            max = maxText,
        )
    }
}
