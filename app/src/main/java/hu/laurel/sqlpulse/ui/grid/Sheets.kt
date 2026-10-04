package hu.laurel.sqlpulse.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.grid.ColumnStats
import hu.laurel.sqlpulse.data.grid.OutlierOutcome
import hu.laurel.sqlpulse.data.grid.Outliers
import hu.laurel.sqlpulse.ui.components.SectionCaption
import hu.laurel.sqlpulse.data.sql.BlobPreview
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.JsonFormatter
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * The bottom sheet a cell opens (§7.5): the whole value in monospace, the column name and type
 * above it, Copy and — where the row can be identified — Edit below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellSheet(
    column: ColumnMeta,
    value: CellValue,
    canEdit: Boolean,
    editBlockedReason: String?,
    onCopy: (String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    /** Null where the bytes cannot be fetched — a query result has no row to go back to. */
    onPreviewBlob: (() -> Unit)? = null,
    blobPreview: BlobPreview.Preview? = null,
    loadingBlob: Boolean = false,
    /** Set where this cell is a foreign key with a value; null offers nothing (§7.3). */
    linkOffer: LinkOffer? = null,
    onOpenLink: () -> Unit = {},
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
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            // Column name and type on one line, in the grid's own monospace: the sheet is a closer
            // look at a cell, and should read as the same thing.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    column.label,
                    style = MonoStyles.cell.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                )
                Text(
                    column.typeName,
                    style = MonoStyles.cell.copy(fontSize = 12.sp),
                    color = semantic.textSecondary,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }

            val raw = value.asText()
            // Only offered when the text really parses as JSON, so the toggle never promises a
            // structure the value does not have.
            val formatted = remember(raw) { JsonFormatter.pretty(raw) }
            var showFormatted by remember(raw) { mutableStateOf(formatted != null) }

            if (formatted != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = showFormatted,
                        onClick = { showFormatted = !showFormatted },
                        label = { Text(stringResource(R.string.cell_json)) },
                    )
                }
            }

            Text(
                text = when {
                    blobPreview != null -> blobPreview.content
                    showFormatted && formatted != null -> formatted
                    else -> raw
                },
                style = MonoStyles.cell.copy(lineHeight = 22.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, Shapes.button)
                    .border(1.dp, semantic.hairline, Shapes.button)
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = Spacing.m),
            )

            blobPreview?.takeIf { it.truncated }?.let {
                Text(
                    stringResource(R.string.cell_blob_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }

            linkOffer?.takeIf { it.guessed }?.let {
                Text(
                    text = "* " + stringResource(R.string.link_guessed_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.warning,
                )
            }

            editBlockedReason?.takeIf { !canEdit }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = semantic.warning)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                // Copies what is on screen: someone reading the formatted version wants that one.
                OutlinedButton(
                    onClick = {
                        onCopy(
                            blobPreview?.content
                                ?: if (showFormatted && formatted != null) formatted else raw,
                        )
                    },
                    shape = Shapes.button,
                ) {
                    Text(stringResource(R.string.cell_copy))
                }
                // Only for a BLOB, and only once: a second tap would fetch the same bytes again.
                if (value is CellValue.Blob && onPreviewBlob != null && blobPreview == null) {
                    OutlinedButton(
                        onClick = onPreviewBlob,
                        enabled = !loadingBlob,
                        shape = Shapes.button,
                    ) { Text(stringResource(R.string.cell_blob_preview)) }
                }
                // §7.3: the row this value points at, without typing a JOIN for it.
                linkOffer?.let { offer ->
                    OutlinedButton(onClick = onOpenLink, shape = Shapes.button) {
                        Text(
                            stringResource(R.string.link_open_parent, offer.targetTable) +
                                if (offer.guessed) " *" else "",
                        )
                    }
                }
                if (canEdit) {
                    Button(onClick = onEdit, shape = Shapes.button) {
                        Text(stringResource(R.string.cell_edit))
                    }
                }
            }
        }
    }
}

/**
 * Row detail (§7.5): every column under one another as label-value pairs, which is what makes a
 * wide table usable in one hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowDetailSheet(
    columns: List<ColumnMeta>,
    row: List<CellValue>,
    canDelete: Boolean,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** Null where the row has no schema behind it — a query result is not a table row (§7.3). */
    onShowChildren: (() -> Unit)? = null,
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
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            columns.forEachIndexed { index, column ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                    Text(
                        text = column.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.textSecondary,
                    )
                    Text(row.getOrNull(index)?.asText().orEmpty(), style = MonoStyles.cell)
                }
                HorizontalDivider(color = semantic.hairline)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(
                    onClick = {
                        onCopy(
                            columns.mapIndexed { index, column ->
                                "${column.label}: ${row.getOrNull(index)?.asText().orEmpty()}"
                            }.joinToString("\n"),
                        )
                    },
                    shape = Shapes.button,
                ) { Text(stringResource(R.string.cell_copy)) }

                onShowChildren?.let { show ->
                    OutlinedButton(onClick = show, shape = Shapes.button) {
                        Text(stringResource(R.string.link_children))
                    }
                }

                if (canDelete) {
                    OutlinedButton(onClick = onDelete, shape = Shapes.button) {
                        Text(stringResource(R.string.row_delete))
                    }
                }
            }
        }
    }
}

/** What following a link from one cell would open, or null where nothing is on offer. */
data class LinkOffer(val targetTable: String, val guessed: Boolean)

/** One table pointing at the row on screen, as the sheet lists it. */
data class LinkChildEntry(
    val table: String,
    val columns: String,
    val rows: Long?,
    val guessed: Boolean,
    val onOpen: () -> Unit,
)

/**
 * The walk along the relationships (§7.3): the rows one link led to, what they point at in turn,
 * and the way back.
 *
 * A step reached along a guessed link — one read off the column names, because the schema declares
 * no foreign key — is marked with an asterisk and named as a guess, the same way the map marks it.
 * Nothing here is followed silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkWalkSheet(
    title: String,
    guessed: Boolean,
    canGoBack: Boolean,
    loading: Boolean,
    error: String?,
    columns: List<ColumnMeta>,
    rows: List<List<CellValue>>,
    /** Set when a lookup that expected one row found none, or found several. */
    notice: String?,
    selectedRow: Int?,
    children: List<LinkChildEntry>,
    childrenLoading: Boolean,
    offerFor: (rowIndex: Int, column: String) -> LinkOffer?,
    onOpenParent: (rowIndex: Int, column: String) -> Unit,
    onSelectRow: (Int) -> Unit,
    onBack: () -> Unit,
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
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canGoBack) {
                    OutlinedButton(onClick = onBack, shape = Shapes.button) {
                        Text(stringResource(R.string.link_back))
                    }
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }

            if (guessed) {
                Text(
                    text = "* " + stringResource(R.string.link_guessed_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.warning,
                )
            }

            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = semantic.warning)
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (loading) {
                Text(stringResource(R.string.link_loading), style = MaterialTheme.typography.bodySmall)
            }

            rows.forEachIndexed { rowIndex, row ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                    columns.forEachIndexed { index, column ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                            Text(
                                text = column.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.textSecondary,
                            )
                            Text(row.getOrNull(index)?.asText().orEmpty(), style = MonoStyles.cell)
                            offerFor(rowIndex, column.label)?.let { offer ->
                                OutlinedButton(
                                    onClick = { onOpenParent(rowIndex, column.label) },
                                    shape = Shapes.button,
                                ) {
                                    Text(
                                        stringResource(R.string.link_open_parent, offer.targetTable) +
                                            if (offer.guessed) " *" else "",
                                    )
                                }
                            }
                        }
                    }

                    OutlinedButton(onClick = { onSelectRow(rowIndex) }, shape = Shapes.button) {
                        Text(stringResource(R.string.link_children))
                    }

                    if (selectedRow == rowIndex) {
                        if (childrenLoading) {
                            Text(
                                stringResource(R.string.link_loading),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else if (children.isEmpty()) {
                            Text(
                                stringResource(R.string.link_children_none),
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.textSecondary,
                            )
                        } else {
                            children.forEach { entry -> ChildLinkRow(entry) }
                        }
                    }

                    HorizontalDivider(color = semantic.hairline)
                }
            }
        }
    }
}

/**
 * Column statistics: count, non-null count, distinct count, sum, average, min, max.
 * Each value is copyable to clipboard. Numeric stats are shown only when the column's
 * values parse as numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnStatsSheet(
    columnLabel: String,
    stats: ColumnStats,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
    highlightOutliers: Boolean = false,
    onHighlightOutliersChange: ((Boolean) -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        shape = Shapes.sheet,
    ) {
        ColumnStatsSheetContent(columnLabel, stats, onCopy, highlightOutliers, onHighlightOutliersChange)
    }
}

@Composable
fun ColumnStatsSheetContent(
    columnLabel: String,
    stats: ColumnStats,
    onCopy: (String) -> Unit,
    highlightOutliers: Boolean = false,
    /** Null where the caller cannot mark cells, which hides the toggle. */
    onHighlightOutliersChange: ((Boolean) -> Unit)? = null,
    /** Start with the outlier list open; the screenshot test uses it. */
    outliersExpanded: Boolean = false,
) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The outlier list can be long, and a bottom sheet does not scroll its own content.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(R.string.colstats_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            columnLabel,
            style = MonoStyles.cell.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        )
        Text(
            stringResource(R.string.colstats_based_on, stats.rowCount),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            StatRow(
                label = stringResource(R.string.colstats_count),
                value = stats.rowCount.toString(),
                onCopy = onCopy,
            )
            StatRow(
                label = stringResource(R.string.colstats_nonnull),
                value = stats.nonNullCount.toString(),
                onCopy = onCopy,
            )
            StatRow(
                label = stringResource(R.string.colstats_distinct),
                value = stats.distinctCount.toString(),
                onCopy = onCopy,
            )
            stats.sum?.let {
                StatRow(
                    label = stringResource(R.string.colstats_sum),
                    value = it.toPlainString(),
                    onCopy = onCopy,
                )
            }
            stats.average?.let {
                StatRow(
                    label = stringResource(R.string.colstats_average),
                    value = it,
                    onCopy = onCopy,
                )
            }
            stats.min?.let {
                StatRow(
                    label = stringResource(R.string.colstats_minimum),
                    value = it,
                    onCopy = onCopy,
                )
            }
            stats.max?.let {
                StatRow(
                    label = stringResource(R.string.colstats_maximum),
                    value = it,
                    onCopy = onCopy,
                )
            }
        }

        DistributionSection(
            outcome = stats.distribution,
            onCopy = onCopy,
            highlight = highlightOutliers,
            onHighlightChange = onHighlightOutliersChange,
            initiallyExpanded = outliersExpanded,
        )
    }
}

/** Most outliers listed on screen; "Copy all" still takes every one. */
private const val OUTLIER_LIST_LIMIT = 50

/**
 * Median, spread and outliers of a numeric column. A column that is not numeric gets no section at
 * all; one with too few values gets a single line saying why there is no verdict.
 */
@Composable
private fun DistributionSection(
    outcome: OutlierOutcome,
    onCopy: (String) -> Unit,
    highlight: Boolean,
    onHighlightChange: ((Boolean) -> Unit)?,
    initiallyExpanded: Boolean,
) {
    val semantic = LocalSemanticColors.current
    when (outcome) {
        OutlierOutcome.NotApplicable -> Unit
        is OutlierOutcome.NotEnoughData -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SectionCaption(stringResource(R.string.outliers_title))
            Text(
                stringResource(R.string.outliers_not_enough, outcome.count),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
        }

        is OutlierOutcome.Report -> {
            var expanded by remember { mutableStateOf(initiallyExpanded) }
            val fmt = Outliers::format
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                SectionCaption(stringResource(R.string.outliers_title))
                StatRow(stringResource(R.string.outliers_median), fmt(outcome.median), onCopy)
                StatRow(
                    stringResource(R.string.outliers_quartiles),
                    "${fmt(outcome.q1)} \u2013 ${fmt(outcome.q3)}",
                    onCopy,
                )
                StatRow(stringResource(R.string.outliers_stddev), fmt(outcome.stdDev), onCopy)

                val count = outcome.outliers.size
                val warn = count > 0
                // The count line is the entry to the list, so it reads as a button when there is
                // something behind it and as a plain fact when there is not.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background, Shapes.button)
                        .border(1.dp, if (warn) semantic.warning else semantic.hairline, Shapes.button)
                        .then(if (warn) Modifier.clickable { expanded = !expanded } else Modifier)
                        .padding(horizontal = Spacing.m, vertical = Spacing.s),
                ) {
                    Text(
                        text = stringResource(R.string.outliers_fences, fmt(outcome.lowFence), fmt(outcome.highFence)),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.textSecondary,
                    )
                    Text(
                        text = if (warn) {
                            pluralStringResource(R.plurals.outliers_count, count, count) +
                                if (expanded) "  \u25B4" else "  \u25BE"
                        } else {
                            stringResource(R.string.outliers_none)
                        },
                        style = MonoStyles.cell.copy(fontSize = 14.sp),
                        color = if (warn) semantic.warning else MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (warn && expanded) {
                    outcome.outliers.take(OUTLIER_LIST_LIMIT).forEach { o ->
                        val rule = stringResource(
                            when {
                                o.byIqr && o.byRobustZ -> R.string.outliers_rule_both
                                o.byIqr -> R.string.outliers_rule_iqr
                                else -> R.string.outliers_rule_z
                            },
                        )
                        val value = fmt(o.value)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCopy(value) }
                                .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                        ) {
                            Text(
                                stringResource(R.string.outliers_row, o.row + 1),
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(value, style = MonoStyles.cell.copy(fontSize = 14.sp), modifier = Modifier.weight(1.4f))
                            Text(rule, style = MaterialTheme.typography.labelSmall, color = semantic.textSecondary)
                        }
                    }
                    if (count > OUTLIER_LIST_LIMIT) {
                        Text(
                            stringResource(R.string.outliers_more, count - OUTLIER_LIST_LIMIT),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.textSecondary,
                            modifier = Modifier.padding(horizontal = Spacing.m),
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onCopy(
                                outcome.outliers.joinToString("\n") { "${it.row + 1}\t${fmt(it.value)}" },
                            )
                        },
                        shape = Shapes.button,
                    ) { Text(stringResource(R.string.outliers_copy_all)) }
                }

                if (onHighlightChange != null && warn) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.outliers_highlight),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = highlight, onCheckedChange = onHighlightChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, onCopy: (String) -> Unit) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, Shapes.button)
            .border(1.dp, semantic.hairline, Shapes.button)
            .clickable { onCopy(value) }
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
        Text(
            value,
            style = MonoStyles.cell.copy(fontSize = 14.sp),
        )
    }
}

@Composable
private fun ChildLinkRow(entry: LinkChildEntry) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = entry.onOpen, shape = Shapes.button) {
            Text(entry.table + if (entry.guessed) " *" else "")
        }
        Column {
            Text(
                text = entry.rows?.let { stringResource(R.string.link_child_rows, it) }
                    ?: stringResource(R.string.link_child_rows_unknown),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = entry.columns,
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
        }
    }
}
