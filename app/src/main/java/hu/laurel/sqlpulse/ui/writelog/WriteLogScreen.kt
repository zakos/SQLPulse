package hu.laurel.sqlpulse.ui.writelog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.WriteLogEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.writelog.WriteLogRetention
import hu.laurel.sqlpulse.data.writelog.WriteOutcome
import hu.laurel.sqlpulse.data.writelog.WriteSource
import hu.laurel.sqlpulse.ui.components.ColorRail
import hu.laurel.sqlpulse.ui.components.DialogButtons
import hu.laurel.sqlpulse.ui.components.DialogCard
import hu.laurel.sqlpulse.ui.components.DialogHeading
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.components.InfoBadge
import hu.laurel.sqlpulse.ui.components.SectionCaption
import hu.laurel.sqlpulse.ui.components.SegmentedChoice
import hu.laurel.sqlpulse.ui.connections.shortLabel
import hu.laurel.sqlpulse.ui.query.SqlVisualTransformation
import hu.laurel.sqlpulse.ui.theme.ConnectionColor
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The write log: what this app wrote, where, when and with what result. */
@Composable
fun WriteLogScreen(
    onBack: () -> Unit,
    viewModel: WriteLogViewModel = hiltViewModel(),
) {
    WriteLogScreenContent(onBack = onBack, viewModel = viewModel)
}

/** The screen itself, drawn from whatever [WriteLogController] it is handed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteLogScreenContent(
    onBack: () -> Unit,
    viewModel: WriteLogController,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val semantic = LocalSemanticColors.current
    var menuOpen by remember { mutableStateOf(false) }

    // The share sheet is started once per export, and only because the user asked for one.
    LaunchedEffect(state.shareIntent) {
        state.shareIntent?.let { intent ->
            context.startActivity(android.content.Intent.createChooser(intent, null))
            viewModel.shareIntentHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = { Text(stringResource(R.string.writelog_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.writelog_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.writelog_export_csv)) },
                            enabled = state.entries.isNotEmpty(),
                            onClick = { menuOpen = false; viewModel.export(ExportFormat.CSV) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.writelog_export_json)) },
                            enabled = state.entries.isNotEmpty(),
                            onClick = { menuOpen = false; viewModel.export(ExportFormat.JSON) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.writelog_clear), color = semantic.danger) },
                            enabled = state.total > 0,
                            onClick = { menuOpen = false; viewModel.requestClear() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xl),
        ) {
            item { Filters(state, viewModel) }
            if (state.exportFailed) {
                item {
                    Text(
                        stringResource(R.string.writelog_export_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    )
                }
            }
            when {
                state.total == 0 -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        Text(
                            stringResource(R.string.writelog_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.writelog_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = semantic.textSecondary,
                        )
                    }
                }
                state.entries.isEmpty() -> item {
                    EmptyState(
                        title = stringResource(R.string.writelog_no_match_title),
                        body = stringResource(R.string.writelog_no_match_body),
                        actionLabel = stringResource(R.string.writelog_no_match_action),
                        onAction = viewModel::clearFilters,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> items(state.entries, key = { it.id }) { entry ->
                    EntryRow(
                        entry = entry,
                        expanded = entry.id in state.expanded,
                        onToggle = { viewModel.toggleExpanded(entry.id) },
                    )
                }
            }
            item { Retention(state.retentionDays, viewModel::setRetentionDays) }
        }
    }

    if (state.confirmClear) {
        BasicAlertDialog(onDismissRequest = viewModel::dismissClear) {
            ClearLogCard(count = state.total, onConfirm = viewModel::confirmClear, onDismiss = viewModel::dismissClear)
        }
    }
}

@Composable
private fun Filters(state: WriteLogUiState, viewModel: WriteLogController) {
    val semantic = LocalSemanticColors.current
    Column {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text(stringResource(R.string.writelog_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (state.query.isNotEmpty()) {
                {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.writelog_clear_search))
                    }
                }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item {
                FilterChip(
                    selected = state.environment == null,
                    onClick = { viewModel.setEnvironment(null) },
                    label = { Text(stringResource(R.string.writelog_filter_all)) },
                )
            }
            items(ConnectionEnvironment.ORDER) { environment ->
                val production = environment.isProduction
                FilterChip(
                    selected = state.environment == environment,
                    onClick = { viewModel.setEnvironment(environment) },
                    label = { Text(stringResource(environment.shortLabel())) },
                    // Production is drawn in its own colour even unselected: it is the part of the
                    // log somebody will actually be looking for.
                    colors = if (production) {
                        androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            labelColor = semantic.production,
                            selectedContainerColor = semantic.production.copy(alpha = 0.2f),
                            selectedLabelColor = semantic.production,
                        )
                    } else {
                        androidx.compose.material3.FilterChipDefaults.filterChipColors()
                    },
                    border = if (production) {
                        androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = state.environment == environment,
                            borderColor = semantic.production.copy(alpha = 0.5f),
                            selectedBorderColor = semantic.production,
                        )
                    } else {
                        androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = state.environment == environment,
                        )
                    },
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item {
                FilterChip(
                    selected = state.source == null,
                    onClick = { viewModel.setSource(null) },
                    label = { Text(stringResource(R.string.writelog_filter_all)) },
                )
            }
            items(WriteSource.entries) { source ->
                FilterChip(
                    selected = state.source == source,
                    onClick = { viewModel.setSource(source) },
                    label = { Text(stringResource(source.label())) },
                )
            }
        }
        if (state.total > 0) {
            Text(
                stringResource(R.string.writelog_count, state.entries.size, state.total),
                style = MonoStyles.cell.copy(fontSize = 12.sp),
                color = semantic.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
        }
        HorizontalDivider(color = semantic.hairline)
    }
}

private val TimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * One write. The rail carries the connection's colour (production keeps its red tint over the
 * whole row), the head says when and where, the SQL is what was sent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryRow(entry: WriteLogEntity, expanded: Boolean, onToggle: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val environment = ConnectionEnvironment.fromName(entry.environment)
    val failed = entry.outcome == WriteOutcome.FAILED.name
    val source = WriteSource.entries.firstOrNull { it.name == entry.source }
    val highlight = SqlVisualTransformation(
        plain = MaterialTheme.colorScheme.onSurface,
        keyword = MaterialTheme.colorScheme.primary,
        string = semantic.success,
        number = semantic.cellNumber,
        comment = semantic.cellNull,
        identifier = semantic.cellDate,
        parameter = semantic.warning,
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .then(
                    if (environment.isProduction) {
                        Modifier.background(semantic.production.copy(alpha = 0.07f))
                    } else {
                        Modifier
                    },
                )
                .clickable(onClickLabel = stringResource(if (expanded) R.string.writelog_collapse else R.string.writelog_expand), onClick = onToggle),
        ) {
            ColorRail(
                if (environment.isProduction) semantic.production else ConnectionColor.fromName(entry.connectionColor).value,
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.l, vertical = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(
                        TimeFormat.format(Instant.ofEpochMilli(entry.time)),
                        style = MonoStyles.cell.copy(fontSize = 12.sp),
                        color = semantic.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (environment != ConnectionEnvironment.UNSET) {
                        InfoBadge(
                            stringResource(environment.shortLabel()),
                            if (environment.isProduction) semantic.production else semantic.textSecondary,
                        )
                    }
                    InfoBadge(
                        stringResource(if (failed) R.string.writelog_outcome_failed else R.string.writelog_outcome_ok),
                        if (failed) semantic.danger else semantic.success,
                    )
                }
                Text(
                    buildString {
                        append(entry.connectionName.ifBlank { "?" })
                        append(" · ")
                        append(entry.database ?: stringResource(R.string.writelog_database_none))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    InfoBadge(
                        source?.let { stringResource(it.label()) } ?: entry.source,
                        MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        rowsLabel(entry.affectedRows) + " · " + stringResource(R.string.writelog_duration_ms, entry.durationMs),
                        style = MonoStyles.cell.copy(fontSize = 12.sp),
                        color = semantic.textSecondary,
                    )
                    if (entry.inTransaction) {
                        InfoBadge(stringResource(R.string.writelog_in_transaction), semantic.warning)
                    }
                }
                Text(
                    text = highlight.filter(AnnotatedString(entry.statement)).text,
                    style = MonoStyles.cell.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background, Shapes.button)
                        .padding(horizontal = Spacing.m, vertical = Spacing.s),
                )
                if (failed && entry.error != null) {
                    Text(entry.error, style = MaterialTheme.typography.bodySmall, color = semantic.danger)
                }
                if (expanded && entry.inTransaction) {
                    Text(
                        stringResource(R.string.writelog_in_transaction_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.textSecondary,
                    )
                }
            }
        }
        HorizontalDivider(color = semantic.hairline)
    }
}

@Composable
private fun rowsLabel(rows: Int?): String = when (rows) {
    null -> stringResource(R.string.writelog_rows_unknown)
    1 -> stringResource(R.string.writelog_rows_one)
    else -> stringResource(R.string.writelog_rows, rows)
}

private fun WriteSource.label(): Int = when (this) {
    WriteSource.SQL_EDITOR -> R.string.writelog_source_sql_editor
    WriteSource.ROW_EDIT -> R.string.writelog_source_row_edit
    WriteSource.RESULT_EDIT -> R.string.writelog_source_result_edit
    WriteSource.CSV_IMPORT -> R.string.writelog_source_csv_import
    WriteSource.UNDO -> R.string.writelog_source_undo
}

@Composable
private fun Retention(days: Int, onSelect: (Int) -> Unit) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier.padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        SectionCaption(stringResource(R.string.writelog_keep_title))
        // A stored value that is not one of the choices (set by hand or by a later version) is
        // shown as no selection rather than being silently rewritten.
        SegmentedChoice(
            options = WriteLogRetention.DAY_CHOICES,
            selected = days,
            label = { stringResource(R.string.writelog_keep_days, it) },
            onSelect = onSelect,
        )
        Text(
            stringResource(R.string.writelog_keep_note),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
        Text(
            stringResource(R.string.writelog_values_note),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
}

/** The confirmation before the log is emptied, drawn on its own so a screenshot can show it. */
@Composable
fun ClearLogCard(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DialogCard(danger = true) {
        DialogHeading(
            title = stringResource(R.string.writelog_clear_title),
            subtitle = stringResource(R.string.writelog_clear_subtitle, count).uppercase(),
            danger = true,
        )
        Text(
            stringResource(R.string.writelog_clear_body),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalSemanticColors.current.textSecondary,
        )
        DialogButtons(
            cancelLabel = stringResource(R.string.cancel),
            onCancel = onDismiss,
            actionLabel = stringResource(R.string.writelog_clear_confirm),
            onAction = onConfirm,
            enabled = true,
            danger = true,
        )
    }
}
