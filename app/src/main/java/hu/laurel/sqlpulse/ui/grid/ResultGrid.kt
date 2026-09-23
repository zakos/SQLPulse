package hu.laurel.sqlpulse.ui.grid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ColumnSort
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.theme.LocalGridFontScale
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing

/** What the grid hands back when the user picks a cell or a row. */
data class CellSelection(val rowIndex: Int, val column: ColumnMeta, val value: CellValue)

/**
 * Result grid (§7.5): scrolls both ways, with a header and a first column that stay put, column
 * widths that can be dragged, and automatic loading as the list nears its end.
 *
 * Cells are monospace, numbers are right-aligned with tabular figures, NULL is faint italics and a
 * BLOB shows a size rather than its contents.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResultGrid(
    table: ResultTable,
    modifier: Modifier = Modifier,
    /** Called when the list nears its end; null means there is nothing more to load. */
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    totalRows: Int? = null,
    onCellClick: (CellSelection) -> Unit = {},
    onRowLongPress: (Int) -> Unit = {},
    /** Current sort, if any; null leaves the header icons in their neutral state. */
    sort: ColumnSort? = null,
    /** Called with a column label when its sort icon is tapped. Null hides the icons. */
    onSort: ((String) -> Unit)? = null,
    /** False where the caller already says so in its own summary line. */
    showLimitNote: Boolean = true,
) {
    val horizontal = rememberScrollState()
    val listState = rememberLazyListState()
    val semantic = LocalSemanticColors.current
    val density = LocalDensity.current
    // The setting is a percentage; everything here works in multiples of the default size.
    val scale = LocalGridFontScale.current / 100f

    // Widths start from the loaded page's content and are overridden once a user drags one.
    val measured = remember(table.columns, table.rowCount, scale) { columnWidths(table, scale) }
    val overrides = remember(table.columns) { mutableStateMapOf<Int, Dp>() }
    fun widthOf(index: Int): Dp = overrides[index] ?: measured.getOrElse(index) { DEFAULT_WIDTH.dp }

    if (onLoadMore != null) {
        val nearEnd by remember {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= table.rowCount - LOAD_MORE_THRESHOLD
            }
        }
        LaunchedEffect(listState, table.rowCount) {
            snapshotFlow { nearEnd }.collect { if (it && !loadingMore) onLoadMore() }
        }
    }

    Column(modifier = modifier) {
        if (table.limitAdded && showLimitNote) {
            Text(
                text = stringResource(R.string.grid_limit_added),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
            )
        }

        HeaderRow(
            table = table,
            horizontal = horizontal,
            scale = scale,
            widthOf = ::widthOf,
            onResize = { index, deltaPx ->
                val delta = with(density) { deltaPx.toDp() }
                overrides[index] = (widthOf(index) + delta).coerceIn(MIN_WIDTH.dp, MAX_WIDTH.dp)
            },
            sort = sort,
            onSort = onSort,
        )
        HorizontalDivider(color = semantic.hairline)

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(table.rows) { rowIndex, row ->
                Row(
                    // Centred like the header: with cells of different heights a top-aligned row
                    // reads as a second misalignment under the first one.
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Intrinsic height, so the pinned first cell can run the row's full height.
                        .height(IntrinsicSize.Min)
                        // The row grows with the text but never shrinks below a touch target.
                        .heightIn(min = maxOf(ROW_HEIGHT.dp, (ROW_HEIGHT * scale).dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { onRowLongPress(rowIndex) },
                        ),
                ) {
                    // The first column is outside the scrolling area, so it stays visible.
                    table.columns.firstOrNull()?.let { column ->
                        StickyCell(
                            column = column,
                            value = row.firstOrNull() ?: CellValue.Null,
                            width = widthOf(0),
                            onClick = {
                                onCellClick(CellSelection(rowIndex, column, row.first()))
                            },
                        )
                    }
                    Row(modifier = Modifier.horizontalScroll(horizontal)) {
                        row.drop(1).forEachIndexed { offset, cell ->
                            val index = offset + 1
                            val column = table.columns.getOrNull(index) ?: return@forEachIndexed
                            Cell(
                                column = column,
                                value = cell,
                                modifier = Modifier
                                    .width(widthOf(index))
                                    .clickable { onCellClick(CellSelection(rowIndex, column, cell)) }
                                    .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                            )
                        }
                    }
                }
                HorizontalDivider(color = semantic.hairline)
            }

            item {
                Text(
                    text = when {
                        loadingMore -> stringResource(R.string.grid_loading_more)
                        totalRows != null -> stringResource(R.string.grid_loaded_of, table.rowCount, totalRows)
                        table.truncated -> stringResource(R.string.grid_truncated, table.rowCount)
                        else -> stringResource(R.string.grid_loaded, table.rowCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                    modifier = Modifier.padding(Spacing.m),
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(
    table: ResultTable,
    horizontal: androidx.compose.foundation.ScrollState,
    scale: Float,
    widthOf: (Int) -> Dp,
    onResize: (Int, Float) -> Unit,
    sort: ColumnSort?,
    onSort: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Follows the theme: on the light theme a dark header was a black band (§8).
            .background(LocalSemanticColors.current.surfaceRaised)
            .height(maxOf(HEADER_HEIGHT.dp, (HEADER_HEIGHT * scale).dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        table.columns.firstOrNull()?.let { column ->
            HeaderCell(
                label = column.label,
                width = widthOf(0),
                sort = sort,
                onSort = onSort,
                onResize = { onResize(0, it) },
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(horizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            table.columns.drop(1).forEachIndexed { offset, column ->
                HeaderCell(
                    label = column.label,
                    width = widthOf(offset + 1),
                    sort = sort,
                    onSort = onSort,
                    onResize = { onResize(offset + 1, it) },
                )
            }
        }
    }
}

@Composable
private fun HeaderCell(
    label: String,
    width: Dp,
    sort: ColumnSort?,
    onSort: ((String) -> Unit)?,
    onResize: (Float) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    val sorted = sort?.takeIf { it.column == label }

    // The whole header cell is exactly one column wide, and the label takes what the sort icon
    // and the drag handle leave. Giving the label the full width and hanging the other two off
    // its end made every header 48dp wider than the cells under it, so the columns drifted
    // further left of their titles with each one — a grid whose fourth column sat under the
    // third one's name.
    val sortLabel = stringResource(
        when {
            sorted == null -> R.string.grid_sort_none
            sorted.descending -> R.string.grid_sort_desc
            else -> R.string.grid_sort_asc
        },
        label,
    )
    Row(
        modifier = Modifier.width(width),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The whole title is the sort control, cycling ascending -> descending -> the table's own
        // order; only the sorted column shows an arrow, so the header reads as names, not icons.
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(
                    if (onSort != null) {
                        Modifier.clickable(onClickLabel = sortLabel, role = Role.Button) { onSort(label) }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                // The title scales with the cells under it: a header that stayed put while the
                // rows grew would be the one row of the grid the setting did not reach.
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp * (LocalGridFontScale.current / 100f),
                ),
                color = semantic.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (sorted != null) {
                Icon(
                    imageVector = if (sorted.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Spacing.xs).size(SORT_ICON.dp),
                )
            }
        }
        // The drag handle between two headers; 12dp wide so a thumb can find it.
        Box(
            modifier = Modifier
                .width(RESIZE_HANDLE.dp)
                .height(HEADER_HEIGHT.dp)
                // Wide enough for a thumb, drawn as a hairline so the header stays quiet.
                .drawBehind {
                    drawRect(
                        semantic.hairline,
                        topLeft = Offset(size.width - 1.dp.toPx(), size.height * 0.25f),
                        size = Size(1.dp.toPx(), size.height * 0.5f),
                    )
                }
                .pointerInput(label) {
                    detectHorizontalDragGestures { _, dragAmount -> onResize(dragAmount) }
                },
        )
    }
}

@Composable
private fun StickyCell(column: ColumnMeta, value: CellValue, width: Dp, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            // The pinned column is the screen's own ground with a hairline and a soft shade on its
            // right edge (§8): it reads as the same table, only held still while the rest scrolls.
            .background(MaterialTheme.colorScheme.background)
            .drawWithContent {
                drawContent()
                val edge = 1.dp.toPx()
                drawRect(semantic.hairline, topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height))
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent),
                        startX = size.width,
                        endX = size.width + 6.dp.toPx(),
                    ),
                    topLeft = Offset(size.width, 0f),
                    size = Size(6.dp.toPx(), size.height),
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
        contentAlignment = Alignment.CenterStart,
    ) {
        Cell(column = column, value = value)
    }
}

@Composable
internal fun Cell(column: ColumnMeta, value: CellValue, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val text: String
    val color: Color
    var italic = false

    when (value) {
        is CellValue.Null -> {
            text = "NULL"
            color = semantic.cellNull
            italic = true
        }

        is CellValue.Number -> {
            text = value.value
            color = semantic.cellNumber
        }

        is CellValue.Date -> {
            text = value.value
            color = semantic.cellDate
        }

        is CellValue.Bool -> {
            text = if (value.value) "1" else "0"
            color = semantic.cellNumber
        }

        // §7.5, §11: a BLOB shows its size in the grid, never its contents.
        is CellValue.Blob -> {
            text = formatBytes(value.sizeBytes)
            color = LocalSemanticColors.current.textSecondary
        }

        is CellValue.Text -> {
            text = value.value.replace('\n', ' ')
            color = MaterialTheme.colorScheme.onSurface
        }
    }

    val base = if (column.type == CellType.NUMBER) MonoStyles.cellNumber else MonoStyles.cell
    Text(
        text = text,
        style = base.copy(fontSize = base.fontSize * (LocalGridFontScale.current / 100f)),
        color = color,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (column.type == CellType.NUMBER) TextAlign.End else TextAlign.Start,
        modifier = modifier,
    )
}

/** The plain text of a cell, for the detail sheet and the clipboard. */
fun CellValue.asText(): String = when (this) {
    is CellValue.Null -> "NULL"
    is CellValue.Text -> value
    is CellValue.Number -> value
    is CellValue.Date -> value
    is CellValue.Bool -> if (value) "1" else "0"
    is CellValue.Blob -> formatBytes(sizeBytes)
}

/** Width from the widest value in the loaded page, clamped so one long column cannot take over. */
private fun columnWidths(table: ResultTable, fontScale: Float): List<Dp> =
    table.columns.mapIndexed { index, column ->
        val widest = table.rows.asSequence()
            .mapNotNull { it.getOrNull(index) }
            .maxOfOrNull { displayLength(it) } ?: 0
        GridWidths.columnWidthDp(column.label.length, widest, fontScale).dp
    }

private fun displayLength(value: CellValue): Int = when (value) {
    is CellValue.Null -> 4
    is CellValue.Text -> value.value.length
    is CellValue.Number -> value.value.length
    is CellValue.Date -> value.value.length
    is CellValue.Bool -> 1
    is CellValue.Blob -> 10
}

internal fun formatBytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> "${size / (1024 * 1024)} MB"
}

private const val DEFAULT_WIDTH = 120
private const val MIN_WIDTH = GridWidths.MIN_WIDTH_DP
private const val MAX_WIDTH = GridWidths.MAX_WIDTH_DP
private const val HEADER_HEIGHT = 44
private const val SORT_ICON = 16
private const val ROW_HEIGHT = 44

/** §7.5: the next page starts loading this many rows before the end. */
private const val LOAD_MORE_THRESHOLD = 20
