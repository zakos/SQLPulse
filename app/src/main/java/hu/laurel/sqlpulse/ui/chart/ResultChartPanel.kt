package hu.laurel.sqlpulse.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.chart.ChartData
import hu.laurel.sqlpulse.data.chart.ChartKind
import hu.laurel.sqlpulse.data.chart.ChartOutcome
import hu.laurel.sqlpulse.data.chart.ChartRefusal
import hu.laurel.sqlpulse.data.chart.ChartSpec
import hu.laurel.sqlpulse.data.chart.ResultCharts
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * The result as a picture, with the three choices that decide what the picture is.
 *
 * Forty rows of `GROUP BY` output is half a minute of reading and one bar chart is a second, which
 * is the whole reason this exists. What it must not become is a prettier way of being wrong: the
 * axis always includes zero, a missing value leaves a gap rather than touching the floor, and what
 * did not fit is counted on screen instead of disappearing.
 */
@Composable
fun ResultChartPanel(
    table: ResultTable,
    spec: ChartSpec?,
    onSpecChange: (ChartSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val chosen = spec ?: ResultCharts.suggest(table)
    val outcome = remember(table, chosen) { ResultCharts.build(table, chosen) }

    Column(modifier = modifier.fillMaxWidth()) {
        when (outcome) {
            is ChartOutcome.Refused -> Text(
                text = stringResource(outcome.reason.textRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.textSecondary,
                modifier = Modifier.padding(Spacing.l),
            )

            is ChartOutcome.Drawable -> {
                chosen?.let { ChartControls(table, it, onSpecChange) }
                ChartCanvas(
                    data = outcome.data,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHART_HEIGHT.dp)
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
                Legend(outcome.data)
                outcome.data.truncatedTo?.let { kept ->
                    Text(
                        text = stringResource(R.string.chart_truncated, kept, table.rowCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.warning,
                        modifier = Modifier.padding(horizontal = Spacing.l),
                    )
                }
            }
        }
    }
}

/** Which column names the points, which one is drawn, and bars or a line. */
@Composable
private fun ChartControls(table: ResultTable, spec: ChartSpec, onChange: (ChartSpec) -> Unit) {
    val labels = ResultCharts.labelCandidates(table)
    val values = ResultCharts.valueCandidates(table)

    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l),
    ) {
        Menu(
            label = table.columns.getOrNull(spec.labelColumn ?: -1)?.label
                ?: stringResource(R.string.chart_label_rows),
            options = labels,
            optionLabel = { table.columns[it].label },
            onSelect = { onChange(spec.copy(labelColumn = it)) },
        )
        Menu(
            label = spec.valueColumns.mapNotNull { table.columns.getOrNull(it)?.label }
                .joinToString(", ")
                .ifBlank { stringResource(R.string.chart_value) },
            options = values,
            optionLabel = { table.columns[it].label },
            // One column at a time from the menu: several series come from the guess, and a
            // menu that toggles them one by one would need a state nobody asked for.
            onSelect = { onChange(spec.copy(valueColumns = listOf(it))) },
        )
        ChartKind.entries.forEach { kind ->
            FilterChip(
                selected = kind == spec.kind,
                onClick = { onChange(spec.copy(kind = kind)) },
                label = { Text(stringResource(kind.textRes())) },
            )
        }
    }
}

@Composable
private fun <T> Menu(
    label: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, shape = Shapes.button) {
            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun Legend(data: ChartData) {
    if (data.series.size < 2) return
    val palette = seriesPalette()
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l),
    ) {
        data.series.forEachIndexed { index, series ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(modifier = Modifier.size(LEGEND_DOT.dp)) {
                    drawRect(color = palette[index % palette.size], size = size)
                }
                Text(text = series.label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The drawing itself.
 *
 * Text is measured rather than guessed at, because a label drawn over the axis is worse than no
 * label; where the labels cannot all fit, every nth is drawn and the rest are left out rather than
 * overlapping into a smear.
 */
@Composable
private fun ChartCanvas(data: ChartData, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val palette = seriesPalette()
    val axis = LocalSemanticColors.current.hairline
    val text = LocalSemanticColors.current.textSecondary
    val style = TextStyle(fontSize = AXIS_TEXT_SP.sp, color = text)

    Canvas(modifier = modifier) {
        if (data.isEmpty) return@Canvas
        val top = data.maximum
        val bottom = data.minimum
        val span = (top - bottom).takeIf { it > 0 } ?: 1.0

        val topLabel = measurer.measure(format(top), style)
        val bottomLabel = measurer.measure(format(bottom), style)
        val gutter = maxOf(topLabel.size.width, bottomLabel.size.width).toFloat() + AXIS_GAP
        val footer = topLabel.size.height.toFloat() + AXIS_GAP
        val plot = Size(size.width - gutter, size.height - footer)
        if (plot.width <= 0f || plot.height <= 0f) return@Canvas

        fun y(value: Double): Float =
            plot.height - ((value - bottom) / span * plot.height).toFloat()

        // The zero line, where zero is inside the range. Without it a chart of negative numbers
        // reads as if everything were positive and merely small.
        val zero = y(0.0)
        drawLine(
            color = axis,
            start = Offset(gutter, zero),
            end = Offset(size.width, zero),
            strokeWidth = 1.dp.toPx(),
        )

        drawText(textMeasurer = measurer, text = format(top), topLeft = Offset(0f, 0f), style = style)
        drawText(
            textMeasurer = measurer,
            text = format(bottom),
            topLeft = Offset(0f, plot.height - bottomLabel.size.height),
            style = style,
        )

        val points = data.series.maxOf { it.points.size }
        val slot = plot.width / points
        when (data.kind) {
            ChartKind.BARS -> {
                val barWidth = (slot / data.series.size) * BAR_FILL
                data.series.forEachIndexed { seriesIndex, series ->
                    series.points.forEachIndexed { index, point ->
                        val left = gutter + index * slot + seriesIndex * (slot / data.series.size)
                        val valueY = y(point.value)
                        drawRect(
                            color = palette[seriesIndex % palette.size],
                            topLeft = Offset(left, minOf(valueY, zero)),
                            // Never zero height: a value that rounds to the baseline still
                            // happened, and an invisible bar reads as a missing row.
                            size = Size(barWidth, maxOf(1.dp.toPx(), kotlin.math.abs(zero - valueY))),
                        )
                    }
                }
            }

            ChartKind.LINE -> {
                data.series.forEachIndexed { seriesIndex, series ->
                    val path = Path()
                    series.points.forEachIndexed { index, point ->
                        val x = gutter + index * slot + slot / 2
                        val valueY = y(point.value)
                        if (index == 0) path.moveTo(x, valueY) else path.lineTo(x, valueY)
                    }
                    drawPath(
                        path = path,
                        color = palette[seriesIndex % palette.size],
                        style = Stroke(width = LINE_WIDTH.dp.toPx()),
                    )
                }
            }
        }

        // Category labels along the bottom, thinned out until they fit.
        val first = data.series.firstOrNull() ?: return@Canvas
        val widest = first.points.maxOfOrNull { measurer.measure(it.label, style).size.width } ?: 0
        val step = maxOf(1, ((widest + AXIS_GAP * 2) / slot).toInt() + 1)
        first.points.forEachIndexed { index, point ->
            if (index % step != 0) return@forEachIndexed
            val label = measurer.measure(point.label, style)
            drawText(
                textMeasurer = measurer,
                text = point.label,
                topLeft = Offset(
                    x = gutter + index * slot + slot / 2 - label.size.width / 2,
                    y = plot.height + AXIS_GAP,
                ),
                style = style,
            )
        }
    }
}

/**
 * A number short enough for an axis.
 *
 * Thousands become "12,3k" and millions "1,2M": the exact figure is in the grid one tap away, and
 * an axis wide enough for every digit leaves no width for the chart.
 */
private fun format(value: Double): String {
    val magnitude = kotlin.math.abs(value)
    return when {
        magnitude >= 1_000_000 -> String.format("%.1fM", value / 1_000_000)
        magnitude >= 1_000 -> String.format("%.1fk", value / 1_000)
        magnitude >= 10 || value == value.toLong().toDouble() -> value.toLong().toString()
        else -> String.format("%.2f", value)
    }
}

@Composable
private fun seriesPalette(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    LocalSemanticColors.current.success,
    LocalSemanticColors.current.warning,
    MaterialTheme.colorScheme.tertiary,
)

private fun ChartKind.textRes(): Int = when (this) {
    ChartKind.BARS -> R.string.chart_kind_bars
    ChartKind.LINE -> R.string.chart_kind_line
}

private fun ChartRefusal.textRes(): Int = when (this) {
    ChartRefusal.NO_ROWS -> R.string.chart_no_rows
    ChartRefusal.NO_NUMBERS -> R.string.chart_no_numbers
}

private const val CHART_HEIGHT = 240
private const val LEGEND_DOT = 10
private const val AXIS_TEXT_SP = 11
private const val AXIS_GAP = 6f
private const val BAR_FILL = 0.8f
private const val LINE_WIDTH = 2
