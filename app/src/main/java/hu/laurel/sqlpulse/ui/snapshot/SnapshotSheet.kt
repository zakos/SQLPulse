package hu.laurel.sqlpulse.ui.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.snapshot.ComparisonOutcome
import hu.laurel.sqlpulse.data.snapshot.MatchStrategy
import hu.laurel.sqlpulse.data.snapshot.ResultDiff
import hu.laurel.sqlpulse.data.snapshot.RowChangeKind
import hu.laurel.sqlpulse.data.snapshot.RowDiff
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.ui.grid.asText
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

/**
 * The time machine's answer, as a sheet over the result it is about.
 *
 * A sheet rather than a screen on purpose: the comparison is read against the rows behind it, and
 * the question it answers — "what moved since I last looked" — is a glance, not a destination.
 *
 * Nothing is decided here. Which rows are new, gone or changed, and whether the two runs could be
 * lined up at all, is settled in `data/snapshot` before this composable is called; this only puts
 * words and colours on it, and is careful to repeat the two admissions the diff carries: that a
 * comparison without a primary key cannot see an edit, and that a trimmed snapshot is a
 * comparison of the first N rows rather than of the result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotSheet(
    outcome: ComparisonOutcome,
    onDismiss: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        shape = Shapes.sheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(stringResource(R.string.snapshot_title), style = MaterialTheme.typography.titleMedium)

            when (outcome) {
                is ComparisonOutcome.Compared -> DiffBody(outcome.diff)

                is ComparisonOutcome.ColumnsDiffer -> Text(
                    text = stringResource(R.string.snapshot_columns_differ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.warning,
                )

                ComparisonOutcome.NoResult -> Text(
                    text = stringResource(R.string.snapshot_no_result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.textSecondary,
                )

                is ComparisonOutcome.Refused -> Text(
                    text = stringResource(R.string.snapshot_refused),
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.warning,
                )
            }

            TextButton(onClick = onDismiss) { Text(stringResource(R.string.snapshot_close)) }
        }
    }
}

@Composable
internal fun DiffBody(diff: ResultDiff) {
    val semantic = LocalSemanticColors.current
    val times = DateFormat.getTimeInstance(DateFormat.MEDIUM)

    Text(
        text = stringResource(
            R.string.snapshot_times,
            times.format(Date(diff.takenAt)),
            times.format(Date(diff.comparedAt)),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = semantic.textSecondary,
    )

    // Three counts side by side, each with its sign and its word as well as its colour.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        DiffStat("+${diff.addedCount}", stringResource(R.string.snapshot_stat_added), semantic.success, Modifier.weight(1f))
        DiffStat("~${diff.changedCount}", stringResource(R.string.snapshot_stat_changed), semantic.warning, Modifier.weight(1f))
        DiffStat("−${diff.removedCount}", stringResource(R.string.snapshot_stat_removed), semantic.danger, Modifier.weight(1f))
    }
    Text(
        text = stringResource(R.string.snapshot_unchanged, diff.unchangedCount),
        style = MaterialTheme.typography.bodySmall,
        color = semantic.textSecondary,
    )

    // What the comparison was able to see. Both of these change what the numbers above mean, so
    // they are shown next to them rather than hidden behind anything.
    when (val strategy = diff.strategy) {
        is MatchStrategy.PrimaryKey -> Text(
            text = stringResource(
                R.string.snapshot_match_key,
                strategy.columns.joinToString(", "),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )

        MatchStrategy.WholeRow -> Text(
            text = stringResource(R.string.snapshot_match_row),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.warning,
        )
    }

    if (diff.partial) {
        Text(
            text = stringResource(
                R.string.snapshot_partial,
                diff.beforeRowCount,
                diff.afterRowCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.warning,
        )
    }

    if (diff.identical) {
        Text(
            text = stringResource(R.string.snapshot_identical),
            style = MaterialTheme.typography.bodyMedium,
            color = semantic.success,
        )
        return
    }

    DiffTable(diff)
}

/**
 * The rows that moved, as a table: the result's own columns, each row tinted by what happened to
 * it, with the old value of a changed cell struck through above the new one. Read across, a row is
 * the row as it looks now (or looked, for one that is gone); read down, a column shows what
 * changed in it. A thousand changed rows is possible, so the rows are a lazy list.
 */
@Composable
private fun DiffTable(diff: ResultDiff) {
    val semantic = LocalSemanticColors.current
    val widths = remember(diff) {
        diff.columns.indices.map { index ->
            val longest = diff.rows.maxOfOrNull { row ->
                val shown = (row.after ?: row.before)?.getOrNull(index)?.asText()?.length ?: 0
                val old = row.cells.firstOrNull { it.columnIndex == index }?.before?.asText()?.length ?: 0
                maxOf(shown, old)
            } ?: 0
            (maxOf(longest, diff.columns[index].length) * 8 + 24).coerceIn(64, 220).dp
        }
    }
    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(modifier = Modifier.width(DIFF_MARK_WIDTH + widths.fold(0.dp) { total, width -> total + width })) {
            Row(
                modifier = Modifier.fillMaxWidth().height(34.dp).background(semantic.surfaceRaised),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(DIFF_MARK_WIDTH))
                diff.columns.forEachIndexed { index, name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = semantic.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.width(widths[index]).padding(horizontal = 8.dp),
                    )
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(diff.rows) { row -> DiffTableRow(row, widths) }
            }
        }
    }
}

@Composable
private fun DiffTableRow(row: RowDiff, widths: List<Dp>) {
    val semantic = LocalSemanticColors.current
    val colour: Color = when (row.kind) {
        RowChangeKind.ADDED -> semantic.success
        RowChangeKind.REMOVED -> semantic.danger
        RowChangeKind.CHANGED -> semantic.warning
    }
    val mark = when (row.kind) {
        RowChangeKind.ADDED -> "+"
        RowChangeKind.REMOVED -> "−"
        RowChangeKind.CHANGED -> "~"
    }
    val cells = (row.after ?: row.before).orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            // Tinted, with a coloured edge and a mark in words' place: which way a row moved is
            // visible before any of it is read, and the mark says it without the colour.
            .background(if (row.kind == RowChangeKind.CHANGED) Color.Transparent else colour.copy(alpha = 0.10f))
            .drawBehind {
                drawRect(colour, size = size.copy(width = 3.dp.toPx()))
                drawRect(
                    semantic.hairline,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1.dp.toPx()),
                    size = size.copy(height = 1.dp.toPx()),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            mark,
            style = MonoStyles.cell.copy(fontWeight = FontWeight.Bold),
            color = colour,
            modifier = Modifier.width(DIFF_MARK_WIDTH),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        widths.forEachIndexed { index, width ->
            val change = row.cells.firstOrNull { it.columnIndex == index }
            val value = cells.getOrNull(index)
            Column(
                modifier = Modifier
                    .width(width)
                    .fillMaxHeight()
                    .then(if (change != null) Modifier.background(semantic.warning.copy(alpha = 0.14f)) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (change != null) {
                    DiffCellText(change.before, struck = true, muted = true)
                    DiffCellText(change.after, struck = false, muted = false)
                } else {
                    DiffCellText(value, struck = row.kind == RowChangeKind.REMOVED, muted = row.kind == RowChangeKind.REMOVED)
                }
            }
        }
    }
}

@Composable
private fun DiffCellText(value: CellValue?, struck: Boolean, muted: Boolean) {
    val semantic = LocalSemanticColors.current
    val colour = when {
        muted -> semantic.textSecondary.copy(alpha = 0.8f)
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

private val DIFF_MARK_WIDTH = 28.dp

@Composable
private fun DiffStat(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, Shapes.button)
            .border(1.dp, LocalSemanticColors.current.hairline, Shapes.button)
            .padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = color,
        )
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = LocalSemanticColors.current.textSecondary)
    }
}
