package hu.laurel.sqlpulse.ui.schema

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.csv.CsvMapping
import hu.laurel.sqlpulse.data.csv.ImportPlan
import hu.laurel.sqlpulse.data.csv.MappingIssue
import hu.laurel.sqlpulse.data.csv.MappingIssueKind
import hu.laurel.sqlpulse.data.format.LocaleFormat
import hu.laurel.sqlpulse.ui.appLocale
import hu.laurel.sqlpulse.ui.components.DialogButtons
import hu.laurel.sqlpulse.ui.components.DialogCard
import hu.laurel.sqlpulse.ui.components.DialogHeading
import hu.laurel.sqlpulse.ui.components.DialogNote
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * What a CSV import is about to do, before it does it: how many rows, which column of the file
 * lands in which column of the table (each one a dropdown the person can change), what looks
 * wrong about that, and the first rows as they would be written.
 *
 * Nothing is matched by position, so a column that has no partner is shown as left out rather
 * than quietly shifting its neighbours; the by-name match is only where the dropdowns start.
 */
@Composable
fun ImportPlanCard(
    plan: ImportPlan,
    table: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onMap: (fileIndex: Int, tableColumn: String?) -> Unit = { _, _ -> },
) {
    val semantic = LocalSemanticColors.current
    val fileColumns = plan.table.header.size
    val issues = remember(plan) { plan.issues }
    val preview = remember(plan) { CsvMapping.preview(plan) }
    DialogCard {
        DialogHeading(
            title = stringResource(R.string.import_title, table),
            subtitle = stringResource(R.string.import_file_summary, fileColumns).uppercase(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            ImportStat(LocaleFormat.integer(plan.rowCount.toLong(), appLocale()), stringResource(R.string.import_stat_rows), Modifier.weight(1f))
            ImportStat("${plan.mapping.count { it != null }} / $fileColumns", stringResource(R.string.import_stat_columns), Modifier.weight(1f))
        }
        Column(
            modifier = Modifier
                .heightIn(max = 232.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background, Shapes.button)
                .border(1.dp, semantic.hairline, Shapes.button),
        ) {
            plan.table.header.forEachIndexed { index, fromFile ->
                val target = plan.mapping.getOrNull(index)
                val flagged = target != null && issues.any {
                    it.tableColumn == target && it.kind != MappingIssueKind.REQUIRED_UNMAPPED
                }
                ImportMappingRow(
                    fromFile = fromFile,
                    target = target,
                    options = plan.columns.map { it.name to it.typeName },
                    flagged = flagged,
                    onChange = { onMap(index, it) },
                )
            }
        }
        if (preview.isNotEmpty()) {
            ImportPreview(header = CsvMapping.previewHeader(plan), rows = preview)
        }
        if (plan.table.malformedRows > 0) {
            DialogNote(stringResource(R.string.import_malformed, plan.table.malformedRows), Icons.Default.Warning, semantic.warning)
        }
        // A handful is enough to act on; the rest shows as the first ones are fixed.
        issues.take(MAX_ISSUES).forEach { ImportIssueLine(it) }
        if (issues.size > MAX_ISSUES) {
            Text(
                stringResource(R.string.import_issue_more, issues.size - MAX_ISSUES),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
        }
        if (plan.canImport) {
            DialogNote(stringResource(R.string.import_transaction_note), Icons.Default.Shield, semantic.textSecondary)
        }
        DialogButtons(
            cancelLabel = stringResource(R.string.cancel),
            onCancel = onDismiss,
            actionLabel = stringResource(R.string.import_run_rows, LocaleFormat.integer(plan.rowCount.toLong(), appLocale())),
            onAction = onConfirm,
            enabled = plan.canImport && plan.rowCount > 0,
            actionIcon = Icons.Default.Upload,
        )
    }
}

private const val MAX_ISSUES = 3

@Composable
private fun ImportStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, Shapes.button)
            .border(1.dp, LocalSemanticColors.current.hairline, Shapes.button)
            .padding(Spacing.m),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = LocalSemanticColors.current.textSecondary)
    }
}

/** One file column, an arrow, and the dropdown that says where it goes. */
@Composable
private fun ImportMappingRow(
    fromFile: String,
    target: String?,
    options: List<Pair<String, String>>,
    flagged: Boolean,
    onChange: (String?) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    val ok = target != null
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            fromFile,
            style = MonoStyles.cell,
            color = if (ok) MaterialTheme.colorScheme.onSurface else semantic.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = semantic.textSecondary.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        ColumnPicker(
            selected = target,
            options = options,
            description = stringResource(R.string.import_map_for, fromFile),
            modifier = Modifier.weight(1.15f),
            onChange = onChange,
        )
        Icon(
            when {
                !ok -> Icons.Default.Close
                flagged -> Icons.Default.Warning
                else -> Icons.Default.Check
            },
            contentDescription = null,
            tint = when {
                !ok -> semantic.textSecondary
                flagged -> semantic.warning
                else -> semantic.success
            },
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The table-column dropdown: a mono field that opens a menu of columns with their types, and "skip". */
@Composable
private fun ColumnPicker(
    selected: String?,
    options: List<Pair<String, String>>,
    description: String,
    modifier: Modifier = Modifier,
    onChange: (String?) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, Shapes.button)
                .border(1.dp, semantic.hairline, Shapes.button)
                .clickable { open = true }
                .semantics { contentDescription = description }
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected ?: stringResource(R.string.import_left_out),
                style = MonoStyles.cell,
                color = if (selected != null) MaterialTheme.colorScheme.onSurface else semantic.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = semantic.textSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_left_out), style = MonoStyles.cell, color = semantic.textSecondary) },
                onClick = {
                    open = false
                    onChange(null)
                },
            )
            options.forEach { (name, type) ->
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MonoStyles.cell, color = MaterialTheme.colorScheme.onSurface)
                            Text(type, style = MonoStyles.cell.copy(fontSize = 11.sp), color = semantic.textSecondary)
                        }
                    },
                    onClick = {
                        open = false
                        onChange(name)
                    },
                )
            }
        }
    }
}

/** The first rows as they would be written: the mapped columns only, headed by the table's names. */
@Composable
private fun ImportPreview(header: List<String>, rows: List<List<String?>>) {
    val semantic = LocalSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(R.string.import_preview_title).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = semantic.textSecondary,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background, Shapes.button)
                .border(1.dp, semantic.hairline, Shapes.button)
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            PreviewRow(header, header = true)
            rows.forEach { PreviewRow(it, header = false) }
        }
    }
}

@Composable
private fun PreviewRow(cells: List<String?>, header: Boolean) {
    val semantic = LocalSemanticColors.current
    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
        cells.forEach { cell ->
            Text(
                text = cell ?: "NULL",
                style = MonoStyles.cell.copy(fontSize = 12.sp, fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal),
                color = when {
                    header -> semantic.textSecondary
                    cell == null -> semantic.cellNull
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(92.dp).padding(end = Spacing.s),
            )
        }
    }
}

/** One problem, in the colour of how serious it is: red stops the import, amber only warns. */
@Composable
private fun ImportIssueLine(issue: MappingIssue) {
    val semantic = LocalSemanticColors.current
    val tint: Color = if (issue.kind.blocking) MaterialTheme.colorScheme.error else semantic.warning
    val text = when (issue.kind) {
        MappingIssueKind.NOTHING_MAPPED -> stringResource(R.string.import_no_columns)
        MappingIssueKind.DUPLICATE_TARGET ->
            stringResource(R.string.import_issue_duplicate, issue.fileColumn.orEmpty(), issue.tableColumn.orEmpty())
        MappingIssueKind.REQUIRED_UNMAPPED -> stringResource(R.string.import_issue_required, issue.tableColumn.orEmpty())
        MappingIssueKind.TYPE_MISMATCH -> stringResource(
            R.string.import_issue_type,
            issue.fileColumn.orEmpty(),
            issue.tableColumn.orEmpty(),
            issue.typeName.orEmpty(),
            issue.count,
            issue.example.orEmpty(),
        )
        MappingIssueKind.NULL_IN_REQUIRED ->
            stringResource(R.string.import_issue_null, issue.fileColumn.orEmpty(), issue.tableColumn.orEmpty(), issue.count)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = tint, modifier = Modifier.fillMaxWidth())
    }
}
