package hu.laurel.sqlpulse.ui.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.snapshot.ComparisonOutcome
import hu.laurel.sqlpulse.data.snapshot.MatchStrategy
import hu.laurel.sqlpulse.data.snapshot.ResultDiff
import hu.laurel.sqlpulse.data.snapshot.RowChangeKind
import hu.laurel.sqlpulse.data.snapshot.RowDiff
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
private fun DiffBody(diff: ResultDiff) {
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

    HorizontalDivider(color = semantic.hairline)

    // Bounded by the diff itself, but still a list: a thousand changed rows is possible and the
    // sheet must not try to lay all of them out at once.
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(diff.rows) { row ->
            DiffRow(row = row, columns = diff.columns)
            HorizontalDivider(color = semantic.hairline)
        }
    }
}

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

@Composable
private fun DiffRow(row: RowDiff, columns: List<String>) {
    val semantic = LocalSemanticColors.current
    val colour: Color = when (row.kind) {
        RowChangeKind.ADDED -> semantic.success
        RowChangeKind.REMOVED -> semantic.danger
        RowChangeKind.CHANGED -> semantic.warning
    }
    val label = when (row.kind) {
        RowChangeKind.ADDED -> R.string.snapshot_row_added
        RowChangeKind.REMOVED -> R.string.snapshot_row_removed
        RowChangeKind.CHANGED -> R.string.snapshot_row_changed
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A tinted row with a coloured edge, as in the time machine design: which way a row
            // moved is visible before any of it is read.
            .background(colour.copy(alpha = if (row.kind == RowChangeKind.CHANGED) 0.06f else 0.10f))
            .drawBehind { drawRect(colour, size = size.copy(width = 3.dp.toPx())) }
            .padding(start = Spacing.m, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                color = colour,
            )
            // The key where there is one; without a key the row is its own identity, so the row
            // itself is the heading.
            val heading = row.key
                ?.joinToString(", ") { it.asText() }
                ?.let { stringResource(R.string.snapshot_row_key, it) }
                ?: (row.after ?: row.before).orEmpty().joinToString(" · ") { it.asText() }
            Text(heading, style = MonoStyles.cell)
        }

        when (row.kind) {
            // A changed row is the whole point of the key: column by column, what it said and
            // what it says now.
            RowChangeKind.CHANGED -> row.cells.forEach { change ->
                Text(
                    text = stringResource(
                        R.string.snapshot_cell_change,
                        change.column,
                        change.before.asText(),
                        change.after.asText(),
                    ),
                    style = MonoStyles.cell,
                    color = semantic.textSecondary,
                )
            }

            // For a row that came or went there is no "before and after" — only the row, which
            // is worth showing in full so it can be recognised.
            else -> {
                val cells = (row.after ?: row.before).orEmpty()
                if (row.key != null) {
                    Text(
                        text = columns.indices.joinToString(" · ") { index ->
                            "${columns[index]}=${cells.getOrNull(index)?.asText().orEmpty()}"
                        },
                        style = MonoStyles.cell,
                        color = semantic.textSecondary,
                    )
                }
            }
        }
    }
}
