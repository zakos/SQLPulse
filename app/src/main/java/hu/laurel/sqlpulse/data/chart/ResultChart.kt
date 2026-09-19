package hu.laurel.sqlpulse.data.chart

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable

/**
 * How the points are drawn.
 *
 * Only two, and which one is not a taste: a series along a date is a line because the gap between
 * two points means something, and a series along names is bars because it does not.
 */
enum class ChartKind { BARS, LINE }

/** One drawn value. [label] is what goes under the bar or point. */
data class ChartPoint(val label: String, val value: Double)

/** One column's worth of values, in the order they are drawn. */
data class ChartSeries(val label: String, val points: List<ChartPoint>)

/**
 * A chart ready to be drawn: nothing left to decide, nothing left to look up.
 *
 * [truncatedTo] is the number of categories kept when there were more; null when everything is on
 * screen. A chart that quietly drops two thirds of its bars is a lie told with a picture, so this
 * travels with the data and the screen has to say it.
 */
data class ChartData(
    val kind: ChartKind,
    val labelColumn: String,
    val series: List<ChartSeries>,
    val truncatedTo: Int? = null,
) {
    val isEmpty: Boolean get() = series.isEmpty() || series.all { it.points.isEmpty() }

    /** The largest value drawn, and the smallest — the axis needs both, and zero is always in. */
    val maximum: Double get() = maxOf(0.0, series.flatMap { it.points }.maxOfOrNull { it.value } ?: 0.0)
    val minimum: Double get() = minOf(0.0, series.flatMap { it.points }.minOfOrNull { it.value } ?: 0.0)
}

/** Why a result cannot be drawn, in the words the screen will use. */
enum class ChartRefusal {
    /** Nothing to draw. */
    NO_ROWS,

    /** Not one column holds numbers, so there is no height to give anything. */
    NO_NUMBERS,
}

sealed interface ChartOutcome {
    data class Drawable(val data: ChartData) : ChartOutcome
    data class Refused(val reason: ChartRefusal) : ChartOutcome
}

/**
 * Which columns a chart is built from.
 *
 * Kept apart from the data so the screen can offer the choice: the guess is right often enough to
 * draw something immediately, and wrong often enough that changing it must not mean typing a
 * different query.
 */
data class ChartSpec(
    /** The column whose values label the points, or null to number the rows. */
    val labelColumn: Int?,
    val valueColumns: List<Int>,
    val kind: ChartKind,
)

/**
 * Turning a result into a chart.
 *
 * The whole point is one glance: a `GROUP BY` answered as forty rows of digits is half a minute of
 * reading, and as bars it is a second. So the reading has to be honest — the axis starts at zero,
 * the categories that do not fit are counted rather than dropped silently, and a result that
 * cannot be drawn says why instead of drawing something meaningless.
 */
object ResultCharts {

    /**
     * How many categories are drawn at most.
     *
     * Past this a phone-wide chart is a grey blur with no readable label, and the rows behind it
     * are better read as rows. The rest are not thrown away: the screen says how many were left.
     */
    const val MAX_CATEGORIES = 40

    /** The columns a chart would use, or null when the result has nothing to draw. */
    fun suggest(table: ResultTable): ChartSpec? {
        val numbers = table.columns.indices.filter { table.columns[it].type == CellType.NUMBER }
        if (numbers.isEmpty() || table.rows.isEmpty()) return null

        val date = table.columns.indices.firstOrNull { table.columns[it].type == CellType.DATE }
        // A date column is a timeline, and a timeline is a line. Text is a set of names, and the
        // distance between two names means nothing, so those get bars.
        val text = table.columns.indices.firstOrNull { table.columns[it].type == CellType.TEXT }
        val label = date ?: text
        return ChartSpec(
            labelColumn = label,
            // Every numeric column except the one labelling the points, and at most a handful:
            // more lines than that is a tangle nobody reads.
            valueColumns = numbers.filter { it != label }.take(MAX_SERIES),
            kind = if (date != null) ChartKind.LINE else ChartKind.BARS,
        )
    }

    /** The chart [spec] describes, or the reason there is none. */
    fun build(table: ResultTable, spec: ChartSpec?): ChartOutcome {
        if (table.rows.isEmpty()) return ChartOutcome.Refused(ChartRefusal.NO_ROWS)
        val chosen = spec ?: suggest(table) ?: return ChartOutcome.Refused(ChartRefusal.NO_NUMBERS)
        val values = chosen.valueColumns.filter { it in table.columns.indices }
        if (values.isEmpty()) return ChartOutcome.Refused(ChartRefusal.NO_NUMBERS)

        val rows = table.rows.indices.toList()
        val kept = rows.take(MAX_CATEGORIES)
        val series = values.map { column ->
            ChartSeries(
                label = table.columns[column].label,
                points = kept.mapNotNull { row ->
                    numberOf(table.rows[row].getOrNull(column))?.let { value ->
                        ChartPoint(label = labelOf(table, chosen.labelColumn, row), value = value)
                    }
                },
            )
        }.filter { it.points.isNotEmpty() }

        if (series.isEmpty()) return ChartOutcome.Refused(ChartRefusal.NO_NUMBERS)
        return ChartOutcome.Drawable(
            ChartData(
                kind = chosen.kind,
                labelColumn = chosen.labelColumn
                    ?.let { table.columns.getOrNull(it)?.label }
                    .orEmpty(),
                series = series,
                truncatedTo = kept.size.takeIf { rows.size > it },
            ),
        )
    }

    /** The columns that can label the points: anything that is not a number is a name. */
    fun labelCandidates(table: ResultTable): List<Int> =
        table.columns.indices.filter { table.columns[it].type != CellType.NUMBER }

    /** The columns that can be drawn: only numbers have a height. */
    fun valueCandidates(table: ResultTable): List<Int> =
        table.columns.indices.filter { table.columns[it].type == CellType.NUMBER }

    /**
     * A cell as a number, or null.
     *
     * A NULL is not zero: drawing it as one puts a bar on the floor where the honest answer is a
     * gap, and on a line it would pull the shape down to a value nothing measured.
     */
    private fun numberOf(cell: CellValue?): Double? = when (cell) {
        is CellValue.Number -> cell.value.toDoubleOrNull()
        is CellValue.Bool -> if (cell.value) 1.0 else 0.0
        else -> null
    }

    private fun labelOf(table: ResultTable, column: Int?, row: Int): String {
        val cell = column?.let { table.rows[row].getOrNull(it) }
        return when (cell) {
            null, CellValue.Null -> "#${row + 1}"
            is CellValue.Text -> cell.value.ifBlank { "#${row + 1}" }
            is CellValue.Number -> cell.value
            is CellValue.Date -> cell.value
            is CellValue.Bool -> if (cell.value) "1" else "0"
            is CellValue.Blob -> "#${row + 1}"
        }
    }

    /** More lines than this on a phone is a tangle; the rest of the columns stay in the grid. */
    private const val MAX_SERIES = 4
}
