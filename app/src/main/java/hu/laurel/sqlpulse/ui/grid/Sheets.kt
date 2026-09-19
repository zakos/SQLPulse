package hu.laurel.sqlpulse.ui.grid

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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.BlobPreview
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
            Text(column.label, style = MaterialTheme.typography.titleMedium)
            Text(column.typeName, style = MaterialTheme.typography.bodySmall, color = semantic.textSecondary)

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
                style = MonoStyles.cell,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
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
