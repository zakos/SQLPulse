package hu.laurel.sqlpulse.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.SqlPulseColors
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * Result grid (§7.5). Two-way scrolling with a header that stays put, monospace cells and muted
 * type colouring; no zebra striping, just a hairline between rows.
 *
 * Still to come in the grid's own phase (§12/6): a sticky first column, draggable column widths,
 * and paging as you scroll. Column widths here are derived from the content of the loaded page.
 */
@Composable
fun ResultGrid(
    table: ResultTable,
    modifier: Modifier = Modifier,
) {
    val horizontal = rememberScrollState()
    val semantic = LocalSemanticColors.current
    var selected by remember { mutableStateOf<Pair<ColumnMeta, CellValue>?>(null) }

    val widths = remember(table) { columnWidths(table) }

    Column(modifier = modifier) {
        if (table.limitAdded) {
            // §7.4: the added limit is stated quietly, not as a warning.
            Text(
                text = stringResource(R.string.grid_limit_added),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SqlPulseColors.DarkSurfaceRaised)
                .horizontalScroll(horizontal)
                .padding(vertical = Spacing.s),
        ) {
            table.columns.forEachIndexed { index, column ->
                Text(
                    text = column.label,
                    style = MonoStyles.cell,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .width(widths[index])
                        .padding(horizontal = Spacing.s),
                )
            }
        }
        HorizontalDivider(color = semantic.hairline)

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(table.rows) { _, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontal)
                        // §8: the row is a 44dp target even though a cell is smaller.
                        .heightIn(min = 44.dp)
                        .padding(vertical = Spacing.xs),
                ) {
                    row.forEachIndexed { index, cell ->
                        val column = table.columns.getOrNull(index) ?: return@forEachIndexed
                        Cell(
                            column = column,
                            value = cell,
                            modifier = Modifier
                                .width(widths[index])
                                .clickable { selected = column to cell }
                                .padding(horizontal = Spacing.s),
                        )
                    }
                }
                HorizontalDivider(color = semantic.hairline)
            }

            if (table.truncated) {
                item {
                    Text(
                        text = stringResource(R.string.grid_truncated, table.rowCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.textSecondary,
                        modifier = Modifier.padding(Spacing.m),
                    )
                }
            }
        }
    }

    selected?.let { (column, value) ->
        CellDialog(column = column, value = value, onDismiss = { selected = null })
    }
}

@Composable
private fun Cell(column: ColumnMeta, value: CellValue, modifier: Modifier = Modifier) {
    val text: String
    val color: Color
    var italic = false

    when (value) {
        is CellValue.Null -> {
            text = "NULL"
            color = SqlPulseColors.CellNull
            italic = true
        }

        is CellValue.Number -> {
            text = value.value
            color = SqlPulseColors.CellNumber
        }

        is CellValue.Date -> {
            text = value.value
            color = SqlPulseColors.CellDate
        }

        is CellValue.Bool -> {
            text = if (value.value) "1" else "0"
            color = SqlPulseColors.CellNumber
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

    Text(
        text = text,
        style = if (column.type == CellType.NUMBER) MonoStyles.cellNumber else MonoStyles.cell,
        color = color,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (column.type == CellType.NUMBER) TextAlign.End else TextAlign.Start,
        modifier = modifier,
    )
}

@Composable
private fun CellDialog(column: ColumnMeta, value: CellValue, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${column.label} · ${column.typeName}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    text = when (value) {
                        is CellValue.Null -> "NULL"
                        is CellValue.Text -> value.value
                        is CellValue.Number -> value.value
                        is CellValue.Date -> value.value
                        is CellValue.Bool -> value.value.toString()
                        is CellValue.Blob -> formatBytes(value.sizeBytes)
                    },
                    style = MonoStyles.cell,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Width from the widest value in the loaded page, clamped so one long column cannot take over. */
private fun columnWidths(table: ResultTable) = table.columns.mapIndexed { index, column ->
    val widest = table.rows.asSequence()
        .mapNotNull { it.getOrNull(index) }
        .maxOfOrNull { displayLength(it) } ?: 0
    val characters = maxOf(column.label.length, widest).coerceIn(MIN_CHARS, MAX_CHARS)
    (characters * CHAR_WIDTH_DP).dp
}

private fun displayLength(value: CellValue): Int = when (value) {
    is CellValue.Null -> 4
    is CellValue.Text -> value.value.length
    is CellValue.Number -> value.value.length
    is CellValue.Date -> value.value.length
    is CellValue.Bool -> 1
    is CellValue.Blob -> 10
}

private fun formatBytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> "${size / (1024 * 1024)} MB"
}

private const val MIN_CHARS = 6
private const val MAX_CHARS = 32

/** Rough advance width of JetBrains Mono at 13sp. */
private const val CHAR_WIDTH_DP = 8
