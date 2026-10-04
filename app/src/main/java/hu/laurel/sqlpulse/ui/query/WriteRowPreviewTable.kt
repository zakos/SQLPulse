package hu.laurel.sqlpulse.ui.query

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.WriteImpact
import hu.laurel.sqlpulse.data.sql.WriteKind
import hu.laurel.sqlpulse.data.sql.WriteRowPreview
import hu.laurel.sqlpulse.ui.grid.asText
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/** One cell of the preview: the value now, and — for an UPDATE that changes it — the value after. */
private class PreviewCell(val before: CellValue?, val after: CellValue?) {
    val changed: Boolean get() = after != null && before?.asText() != after.asText()
}

/** The columns of the table to draw and the cells of each row, with old and new paired up. */
private class PreviewGrid(val headers: List<String>, val rows: List<List<PreviewCell>>)

private fun layoutOf(preview: WriteRowPreview): PreviewGrid {
    val columns = preview.table.columns
    val newLabels = preview.changedColumns.map { it + WriteImpact.NEW_SUFFIX }
    // The new-value columns trail the table's own; everything before them is the row as it is now.
    val oldCount = (columns.size - preview.changedColumns.size).coerceAtLeast(0)
    val newIndex = newLabels.associateWith { label ->
        (oldCount until columns.size).firstOrNull { columns[it].label == label }
    }
    val after = preview.changedColumns.associate { name ->
        val old = (0 until oldCount).firstOrNull { columns[it].label.equals(name, ignoreCase = true) }
        old to newIndex[name + WriteImpact.NEW_SUFFIX]
    }
    // The changed columns come right after the first one (usually the key): on a phone the card
    // shows two or three columns before it scrolls, and those are the ones the user must see.
    val order = (0 until oldCount).sortedBy { index ->
        when {
            index == 0 -> 0
            after[index] != null -> 1
            else -> 2
        }
    }
    return PreviewGrid(
        headers = order.map { columns[it].label },
        rows = preview.table.rows.map { row ->
            order.map { index ->
                PreviewCell(row.getOrNull(index), after[index]?.let { row.getOrNull(it) })
            }
        },
    )
}

private fun widthOf(grid: PreviewGrid, column: Int): Dp {
    val longest = maxOf(
        grid.headers[column].length,
        grid.rows.maxOfOrNull { row ->
            maxOf(row[column].before?.asText()?.length ?: 0, row[column].after?.asText()?.length ?: 0)
        } ?: 0,
    )
    return (longest * 7.5f + 20f).coerceIn(56f, 180f).dp
}

/**
 * The rows a write is about to change, small enough to sit under the statement on a phone: mono
 * text, scrolling sideways for the columns and up and down past [PREVIEW_MAX_HEIGHT]. A changed
 * cell of an UPDATE shows the old value struck through above the new one; the rows a DELETE
 * removes are tinted red with a red edge, so which of the two this is is clear before a word is
 * read.
 */
@Composable
fun WriteRowPreviewTable(preview: WriteRowPreview, statementNumber: Int?, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val grid = remember(preview) { layoutOf(preview) }
    val widths = remember(grid) { grid.headers.indices.map { widthOf(grid, it) } }
    val shown = preview.table.rowCount
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        val caption = when {
            shown == 0 -> stringResource(R.string.dml_preview_empty)
            preview.totalRows != null && preview.totalRows > shown ->
                stringResource(R.string.dml_preview_first_of, shown, preview.totalRows)

            preview.table.truncated -> stringResource(R.string.dml_preview_first, shown)
            else -> pluralStringResource(R.plurals.dml_preview_all, shown, shown)
        }
        Text(
            text = listOfNotNull(
                statementNumber?.let { stringResource(R.string.dml_preview_statement, it) },
                caption,
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = semantic.textSecondary,
        )
        if (shown == 0 || grid.headers.isEmpty()) return@Column
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background, Shapes.button)
                .border(1.dp, semantic.hairline, Shapes.button),
        ) {
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    Row(
                        modifier = Modifier.height(30.dp).background(semantic.surfaceRaised),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.width(EDGE_WIDTH))
                        grid.headers.forEachIndexed { index, name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = if (name in preview.changedColumns) semantic.warning else semantic.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(widths[index]).padding(horizontal = 8.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.heightIn(max = PREVIEW_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                        grid.rows.forEach { row -> PreviewRow(row, widths, preview.kind) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(cells: List<PreviewCell>, widths: List<Dp>, kind: WriteKind) {
    val semantic = LocalSemanticColors.current
    val delete = kind == WriteKind.DELETE
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .background(if (delete) semantic.danger.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .drawBehind {
                drawRect(
                    if (delete) semantic.danger else semantic.hairline,
                    size = Size(if (delete) 3.dp.toPx() else 1.dp.toPx(), size.height),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(EDGE_WIDTH))
        cells.forEachIndexed { index, cell ->
            Column(
                modifier = Modifier
                    .width(widths[index])
                    .fillMaxHeight()
                    .then(if (cell.changed) Modifier.background(semantic.warning.copy(alpha = 0.14f)) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (cell.changed) {
                    PreviewText(cell.before, struck = true, muted = true)
                    PreviewText(cell.after, struck = false, muted = false)
                } else {
                    PreviewText(cell.before, struck = delete, muted = delete)
                }
            }
        }
    }
}

@Composable
private fun PreviewText(value: CellValue?, struck: Boolean, muted: Boolean) {
    val semantic = LocalSemanticColors.current
    val colour = when {
        muted -> semantic.textSecondary
        value is CellValue.Number -> semantic.cellNumber
        value is CellValue.Date -> semantic.cellDate
        value is CellValue.Null -> semantic.cellNull
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = value?.asText().orEmpty(),
        style = MonoStyles.cell.copy(fontSize = 12.sp),
        color = colour,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textDecoration = if (struck) TextDecoration.LineThrough else null,
    )
}

private val EDGE_WIDTH = 8.dp

/** Tall enough for a handful of rows, short enough that the buttons stay on a phone screen. */
private val PREVIEW_MAX_HEIGHT = 200.dp
