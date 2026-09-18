package hu.laurel.sqlpulse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.data.schema.Series

/**
 * The last minute of a metric as a line.
 *
 * Deliberately unlabelled: the number above it says how much, and the line only has to answer
 * "steady, climbing, or spiking". It scales to its own peak, so a quiet metric fills the box just
 * as a busy one does — the shape is the information, not the height.
 */
@Composable
fun Sparkline(
    series: Series,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val points = series.points
        if (points.size < 2) return@Canvas
        val peak = series.peak
        val stepX = size.width / (points.size - 1)
        val line = Path()
        val area = Path()
        points.forEachIndexed { index, value ->
            val x = index * stepX
            // The drawing origin is the top left, so a high value is a small y.
            val y = size.height - (value / peak).toFloat().coerceIn(0f, 1f) * size.height
            if (index == 0) {
                line.moveTo(x, y)
                area.moveTo(x, size.height)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(size.width, size.height)
        area.close()
        drawPath(area, color = color.copy(alpha = 0.12f))
        drawPath(line, color = color, style = Stroke(width = 2.dp.toPx()))
        // The newest reading gets a dot: on a slow interval it is otherwise hard to see which
        // end of the line is now.
        val lastX = (points.size - 1) * stepX
        val lastY = size.height -
            (points.last() / peak).toFloat().coerceIn(0f, 1f) * size.height
        drawCircle(color = color, radius = 2.5.dp.toPx(), center = Offset(lastX, lastY))
    }
}
