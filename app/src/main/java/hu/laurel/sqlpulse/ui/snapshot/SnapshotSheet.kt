package hu.laurel.sqlpulse.ui.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import hu.laurel.sqlpulse.data.snapshot.ComparedSide
import hu.laurel.sqlpulse.data.snapshot.KeyProblem
import hu.laurel.sqlpulse.data.snapshot.MatchStrategy
import hu.laurel.sqlpulse.data.snapshot.ResultDiff
import hu.laurel.sqlpulse.data.snapshot.RowChangeKind
import hu.laurel.sqlpulse.data.snapshot.RowDiff
import hu.laurel.sqlpulse.data.snapshot.SnapshotOrigin
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.ui.components.ColorRail
import hu.laurel.sqlpulse.ui.components.InfoBadge
import hu.laurel.sqlpulse.ui.connections.shortLabel
import hu.laurel.sqlpulse.ui.grid.asText
import hu.laurel.sqlpulse.ui.theme.ConnectionColor
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
    /** Re-runs the comparison on key columns the user picked; null hides the key picker. */
    onPickKey: ((List<String>) -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        shape = Shapes.sheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(stringResource(R.string.snapshot_title), style = MaterialTheme.typography.titleMedium)

            OutcomeBody(outcome, onPickKey)

            TextButton(onClick = onDismiss) { Text(stringResource(R.string.snapshot_close)) }
        }
    }
}

@Composable
internal fun OutcomeBody(outcome: ComparisonOutcome, onPickKey: ((List<String>) -> Unit)? = null) {
    val semantic = LocalSemanticColors.current
    when (outcome) {
        is ComparisonOutcome.Compared -> DiffBody(outcome.diff, onPickKey)

        is ComparisonOutcome.ColumnsDiffer -> ColumnsDifferNotice(outcome)

        is ComparisonOutcome.KeyUnusable -> KeyUnusableNotice(outcome, onPickKey)

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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiffBody(diff: ResultDiff, onPickKey: ((List<String>) -> Unit)? = null) {
    val semantic = LocalSemanticColors.current
    val times = DateFormat.getTimeInstance(DateFormat.MEDIUM)
    val before = diff.beforeOrigin
    val after = diff.afterOrigin
    val cross = diff.crossConnection && before != null && after != null

    if (cross && before != null && after != null) {
        // Two sides, each with its own connection colour: which database a row came from is the
        // one thing that must never be a guess when one of them is production.
        SideCard("A", before, diff.beforeRowCount, times.format(Date(diff.takenAt)))
        SideCard("B", after, diff.afterRowCount, times.format(Date(diff.comparedAt)))
        if (diff.queryDiffers) {
            Text(
                text = stringResource(R.string.datacompare_query_differs),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.warning,
            )
        }
    } else {
        Text(
            text = stringResource(
                R.string.snapshot_times,
                times.format(Date(diff.takenAt)),
                times.format(Date(diff.comparedAt)),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }

    // The four counts, each with its sign and its word as well as its colour. In a comparison of
    // two connections the sides are named after them; against one's own earlier run they keep the
    // time-machine words.
    val sideA = sideColor(before, semantic.danger)
    val sideB = sideColor(after, semantic.success)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        DiffStat(
            "${diff.removedCount}",
            stringResource(if (cross) R.string.datacompare_stat_only_a else R.string.snapshot_stat_removed),
            if (cross) sideA else semantic.danger,
            Modifier.weight(1f),
        )
        DiffStat(
            "${diff.addedCount}",
            stringResource(if (cross) R.string.datacompare_stat_only_b else R.string.snapshot_stat_added),
            if (cross) sideB else semantic.success,
            Modifier.weight(1f),
        )
        DiffStat("${diff.changedCount}", stringResource(R.string.snapshot_stat_changed), semantic.warning, Modifier.weight(1f))
        DiffStat("${diff.unchangedCount}", stringResource(R.string.datacompare_stat_identical), semantic.textSecondary, Modifier.weight(1f))
    }

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

    if (onPickKey != null) {
        KeyPicker(
            columns = diff.columns,
            initial = (diff.strategy as? MatchStrategy.PrimaryKey)?.columns.orEmpty(),
            expandedByDefault = diff.strategy is MatchStrategy.WholeRow,
            onPickKey = onPickKey,
        )
    }

    if (diff.partial) {
        Text(
            text = stringResource(
                if (cross) R.string.datacompare_partial else R.string.snapshot_partial,
                diff.beforeRowCount,
                diff.afterRowCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.warning,
        )
    }

    if (diff.identical) {
        Text(
            text = stringResource(if (cross) R.string.datacompare_identical else R.string.snapshot_identical),
            style = MaterialTheme.typography.bodyMedium,
            color = semantic.success,
        )
        return
    }

    DiffTable(diff, sideA, sideB, cross)
}

/** The colour of a connection, or [fallback] when the side's origin is unknown. */
private fun sideColor(origin: SnapshotOrigin?, fallback: Color): Color =
    origin?.colorName?.let { ConnectionColor.fromName(it).value } ?: fallback

@Composable
private fun environmentColor(environment: ConnectionEnvironment): Color? = when (environment) {
    ConnectionEnvironment.DEVELOPMENT -> MaterialTheme.colorScheme.primary
    ConnectionEnvironment.TEST -> LocalSemanticColors.current.warning
    ConnectionEnvironment.PRODUCTION -> LocalSemanticColors.current.production
    ConnectionEnvironment.UNSET -> null
}

/** One side of a cross-connection comparison: letter, connection, environment, database, rows, time. */
@Composable
private fun SideCard(letter: String, origin: SnapshotOrigin, rows: Int, time: String) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.surface, Shapes.button)
            .border(1.dp, semantic.hairline, Shapes.button),
    ) {
        ColorRail(sideColor(origin, semantic.textSecondary))
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(letter, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = semantic.textSecondary)
                Text(
                    origin.connectionName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                environmentColor(origin.environment)?.let {
                    InfoBadge(stringResource(origin.environment.shortLabel()), it)
                }
            }
            Text(
                text = listOfNotNull(
                    origin.database?.takeIf { it.isNotBlank() },
                    stringResource(R.string.datacompare_rows, rows),
                    time,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Lets the user say which columns identify a row. Offered open when nothing could be detected,
 * and as a small link otherwise, because a wrong automatic key is also something to be able to fix.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyPicker(
    columns: List<String>,
    initial: List<String>,
    expandedByDefault: Boolean,
    onPickKey: (List<String>) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    var open by remember { mutableStateOf(expandedByDefault) }
    var picked by remember { mutableStateOf(initial.toSet()) }
    if (!open) {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.datacompare_key_choose)) }
        return
    }
    Text(
        text = stringResource(R.string.datacompare_key_hint),
        style = MaterialTheme.typography.bodySmall,
        color = semantic.textSecondary,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        columns.forEach { column ->
            FilterChip(
                selected = column in picked,
                onClick = { picked = if (column in picked) picked - column else picked + column },
                label = { Text(column, maxLines = 1) },
            )
        }
    }
    // Keep the result's own column order in the key, so the label shown matches the table.
    Button(
        onClick = { onPickKey(columns.filter { it in picked }) },
        enabled = picked.isNotEmpty(),
    ) { Text(stringResource(R.string.datacompare_key_apply)) }
}

/** Two results with different columns: say which ones, since the query text alone does not. */
@Composable
private fun ColumnsDifferNotice(outcome: ComparisonOutcome.ColumnsDiffer) {
    val semantic = LocalSemanticColors.current
    val onlyA = outcome.before.filter { c -> outcome.after.none { it.equals(c, ignoreCase = true) } }
    val onlyB = outcome.after.filter { c -> outcome.before.none { it.equals(c, ignoreCase = true) } }
    Text(
        text = stringResource(R.string.snapshot_columns_differ),
        style = MaterialTheme.typography.bodyMedium,
        color = semantic.warning,
    )
    if (onlyA.isNotEmpty()) {
        Text(
            stringResource(R.string.datacompare_columns_only_a, onlyA.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
    if (onlyB.isNotEmpty()) {
        Text(
            stringResource(R.string.datacompare_columns_only_b, onlyB.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
    if (onlyA.isEmpty() && onlyB.isEmpty()) {
        // Same names, different order or count of repeats: still not alignable.
        Text(
            stringResource(R.string.datacompare_columns_order),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
}

/** The chosen key cannot identify rows, so nothing was compared: say why instead of guessing. */
@Composable
private fun KeyUnusableNotice(
    outcome: ComparisonOutcome.KeyUnusable,
    onPickKey: ((List<String>) -> Unit)?,
) {
    val semantic = LocalSemanticColors.current
    val key = outcome.keyColumns.joinToString(", ")
    val side = stringResource(
        if (outcome.side == ComparedSide.BEFORE) R.string.datacompare_side_a else R.string.datacompare_side_b,
    )
    val text = when (outcome.problem) {
        KeyProblem.DUPLICATE -> stringResource(
            R.string.datacompare_unusable_duplicate,
            key,
            side,
            outcome.rowCount,
            outcome.example.joinToString(", ") { it.asText() },
        )

        KeyProblem.NULL_VALUE -> stringResource(R.string.datacompare_unusable_null, key, side, outcome.rowCount)
        KeyProblem.MISSING_COLUMN -> stringResource(R.string.datacompare_unusable_missing, key)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = semantic.warning)
    Text(
        stringResource(R.string.datacompare_unusable_hint),
        style = MaterialTheme.typography.bodySmall,
        color = semantic.textSecondary,
    )
    if (onPickKey != null && outcome.columns.isNotEmpty()) {
        KeyPicker(outcome.columns, outcome.keyColumns, expandedByDefault = true, onPickKey = onPickKey)
    }
}

/**
 * The rows that moved, as a table: the result's own columns, each row tinted by what happened to
 * it, with the old value of a changed cell struck through above the new one. Read across, a row is
 * the row as it looks now (or looked, for one that is gone); read down, a column shows what
 * changed in it. A thousand changed rows is possible, so the rows are a lazy list.
 */
@Composable
private fun DiffTable(diff: ResultDiff, sideA: Color, sideB: Color, cross: Boolean) {
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
                items(diff.rows) { row -> DiffTableRow(row, widths, sideA, sideB, cross) }
            }
        }
    }
}

@Composable
private fun DiffTableRow(row: RowDiff, widths: List<Dp>, sideA: Color, sideB: Color, cross: Boolean) {
    val semantic = LocalSemanticColors.current
    val colour: Color = when (row.kind) {
        RowChangeKind.ADDED -> if (cross) sideB else semantic.success
        RowChangeKind.REMOVED -> if (cross) sideA else semantic.danger
        RowChangeKind.CHANGED -> semantic.warning
    }
    // Across connections a row is "only in A" or "only in B", and is marked by the letter so the
    // mark says it without the colour, as the sign does for a single connection.
    val mark = when (row.kind) {
        RowChangeKind.ADDED -> if (cross) "B" else "+"
        RowChangeKind.REMOVED -> if (cross) "A" else "−"
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
            .padding(horizontal = Spacing.s, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = color,
        )
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = LocalSemanticColors.current.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
