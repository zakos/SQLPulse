package hu.laurel.sqlpulse.data.grid

import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** One number and the row (0-based, in the loaded table) it came from. */
data class Sample(val row: Int, val value: Double)

/** Which side of the data a value falls out on. */
enum class OutlierSide { LOW, HIGH }

/**
 * A value flagged by at least one rule. Both flags are kept because they disagree in useful ways:
 * the IQR fence is blind when half the column is one number, the robust z-score is touchier on
 * skewed data, and a value both rules flag is the one worth looking at first.
 */
data class Outlier(
    val row: Int,
    val value: Double,
    val side: OutlierSide,
    val byIqr: Boolean,
    val byRobustZ: Boolean,
    /** Null when the MAD is zero and the score is therefore not a finite number. */
    val robustZ: Double?,
)

/** Result of looking for outliers in one column. */
sealed interface OutlierOutcome {
    /** The column holds something that is not a number (text, dates, blobs) or nothing at all. */
    data object NotApplicable : OutlierOutcome

    /** Numbers, but too few for any quartile to mean something: no verdict either way. */
    data class NotEnoughData(val count: Int) : OutlierOutcome

    data class Report(
        val count: Int,
        val median: Double,
        val q1: Double,
        val q3: Double,
        val mean: Double,
        /** Sample standard deviation (n - 1). */
        val stdDev: Double,
        val mad: Double,
        val lowFence: Double,
        val highFence: Double,
        /** Sorted by row. */
        val outliers: List<Outlier>,
    ) : OutlierOutcome {
        val iqr: Double get() = q3 - q1
        val outlierRows: Set<Int> get() = outliers.mapTo(HashSet()) { it.row }
        val iqrOutlierCount: Int get() = outliers.count { it.byIqr }
    }
}

/**
 * Local outlier detection: no model, no network, two textbook rules.
 *
 * - Tukey fences: a value is out when it lies beyond Q1 - 1.5 IQR or Q3 + 1.5 IQR. Quartiles use
 *   linear interpolation between order statistics (R's type 7, also Excel's QUARTILE.INC).
 * - Robust z-score (Iglewicz and Hoaglin): 0.6745 (x - median) / MAD, flagged above 3.5 in
 *   magnitude. It rests on the median, so the outliers cannot drag the yardstick along.
 *
 * Zero spread is handled rather than divided by: with IQR = 0 the fences collapse onto Q1/Q3, and
 * with MAD = 0 every value different from the median is infinitely far from it and is flagged.
 * A column where every value is equal therefore has no outliers under either rule.
 */
object Outliers {
    const val MIN_VALUES = 4
    const val IQR_FACTOR = 1.5
    const val Z_THRESHOLD = 3.5
    private const val MAD_SCALE = 0.6745

    /** Outliers among [samples]; NULLs must already be left out. */
    fun analyze(samples: List<Sample>): OutlierOutcome {
        if (samples.isEmpty()) return OutlierOutcome.NotApplicable
        if (samples.size < MIN_VALUES) return OutlierOutcome.NotEnoughData(samples.size)

        val sorted = samples.map { it.value }.sorted()
        val median = quantile(sorted, 0.5)
        val q1 = quantile(sorted, 0.25)
        val q3 = quantile(sorted, 0.75)
        val iqr = q3 - q1
        val low = q1 - IQR_FACTOR * iqr
        val high = q3 + IQR_FACTOR * iqr
        val mad = quantile(sorted.map { abs(it - median) }.sorted(), 0.5)
        val mean = sorted.sum() / sorted.size
        val variance = sorted.sumOf { (it - mean) * (it - mean) } / (sorted.size - 1)

        val found = samples.mapNotNull { s ->
            val byIqr = s.value < low || s.value > high
            val z = if (mad > 0.0) MAD_SCALE * (s.value - median) / mad else null
            // MAD = 0: more than half the values are the median, so any other value is flagged.
            val byZ = if (z != null) abs(z) > Z_THRESHOLD else s.value != median
            if (!byIqr && !byZ) return@mapNotNull null
            Outlier(
                row = s.row,
                value = s.value,
                side = if (s.value < median) OutlierSide.LOW else OutlierSide.HIGH,
                byIqr = byIqr,
                byRobustZ = byZ,
                robustZ = z,
            )
        }.sortedBy { it.row }

        return OutlierOutcome.Report(
            count = samples.size,
            median = median,
            q1 = q1,
            q3 = q3,
            mean = mean,
            stdDev = sqrt(variance),
            mad = mad,
            lowFence = low,
            highFence = high,
            outliers = found,
        )
    }

    /**
     * Outliers of one result column. Any non-numeric, non-NULL cell makes the column not
     * applicable: a mixed column has no honest distribution. Booleans are flags, not magnitudes.
     */
    fun analyzeColumn(table: ResultTable, column: Int): OutlierOutcome {
        if (column !in table.columns.indices) return OutlierOutcome.NotApplicable
        val samples = ArrayList<Sample>()
        table.rows.forEachIndexed { row, cells ->
            when (val cell = cells.getOrNull(column)) {
                null, CellValue.Null -> Unit
                is CellValue.Number -> {
                    val v = cell.value.toDoubleOrNull()?.takeIf { it.isFinite() }
                        ?: return OutlierOutcome.NotApplicable
                    samples += Sample(row, v)
                }
                else -> return OutlierOutcome.NotApplicable
            }
        }
        return analyze(samples)
    }

    /** A statistic as plain text: four decimals at most, no trailing zeros, never an exponent. */
    fun format(value: Double): String =
        java.math.BigDecimal.valueOf(value).setScale(4, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()

    /** Type 7 quantile of an already sorted, non-empty list. */
    internal fun quantile(sorted: List<Double>, p: Double): Double {
        val position = (sorted.size - 1) * p
        val lower = floor(position).toInt()
        val fraction = position - lower
        val next = sorted.getOrElse(lower + 1) { sorted[lower] }
        return sorted[lower] + fraction * (next - sorted[lower])
    }
}
