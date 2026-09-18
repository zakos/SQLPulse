package hu.laurel.sqlpulse.ui.query

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.db.SavedQueryEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.copyToClipboard
import hu.laurel.sqlpulse.ui.grid.CellSelection
import hu.laurel.sqlpulse.ui.grid.CellSheet
import hu.laurel.sqlpulse.ui.grid.ResultGrid
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

/**
 * Query editor (§7.4): monospace field with highlighting, the key row the phone keyboard lacks,
 * and the result, history and favourites underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueryEditorScreen(
    onBack: () -> Unit,
    viewModel: QueryEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current
    val context = LocalContext.current
    var favouriteDialogOpen by remember { mutableStateOf(false) }
    var exportMenuOpen by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var selectedCell by remember { mutableStateOf<CellSelection?>(null) }

    // §7.7: the export leaves through the system share sheet; the app keeps no file.
    LaunchedEffect(state.shareIntent) {
        state.shareIntent?.let { intent ->
            context.startActivity(Intent.createChooser(intent, null))
            viewModel.shareIntentHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.query_title))
                        Text(
                            text = listOfNotNull(state.connectionName, state.database).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.textSecondary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.format() },
                        enabled = state.sql.isNotBlank(),
                    ) {
                        Icon(
                            Icons.Default.FormatAlignLeft,
                            contentDescription = stringResource(R.string.query_format),
                        )
                    }
                    IconButton(onClick = { findOpen = !findOpen }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.query_find),
                        )
                    }
                    IconButton(
                        onClick = { favouriteDialogOpen = true },
                        enabled = state.sql.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Star, contentDescription = stringResource(R.string.query_favourite_add))
                    }
                    if (state.result != null) {
                        Box {
                            IconButton(onClick = { exportMenuOpen = true }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.export))
                            }
                            DropdownMenu(
                                expanded = exportMenuOpen,
                                onDismissRequest = { exportMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_csv)) },
                                    onClick = {
                                        exportMenuOpen = false
                                        viewModel.export(ExportFormat.CSV)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_json)) },
                                    onClick = {
                                        exportMenuOpen = false
                                        viewModel.export(ExportFormat.JSON)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // §7.3 lets you browse any database, so the editor has to be able to follow it.
            if (state.databases.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    items(state.databases) { database ->
                        FilterChip(
                            selected = database == state.database,
                            onClick = { viewModel.selectDatabase(database) },
                            label = { Text(database, style = MonoStyles.cell) },
                        )
                    }
                }
            }

            if (findOpen) {
                FindReplaceBar(
                    onReplaceAll = { find, replacement -> viewModel.replaceAll(find, replacement) },
                    onClose = { findOpen = false },
                )
            }

            // TextFieldValue rather than a plain String: the selection is what decides whether
            // "run" means the whole script or only the part the user marked.
            var field by remember { mutableStateOf(TextFieldValue(state.sql)) }
            LaunchedEffect(state.sql, state.selectionStart) {
                // The text can also change from outside the field: a completion, the key row, a
                // favourite. The cursor then goes where the view model put it, not to the end.
                if (field.text != state.sql) {
                    field = TextFieldValue(
                        text = state.sql,
                        selection = TextRange(state.selectionStart.coerceIn(0, state.sql.length)),
                    )
                }
            }

            if (state.editorCollapsed) {
                CollapsedEditor(sql = state.sql, onExpand = viewModel::toggleEditor)
            } else {
                OutlinedTextField(
                    value = field,
                    onValueChange = { value ->
                        field = value
                        viewModel.setSql(value.text)
                        viewModel.setSelection(value.selection.min, value.selection.max)
                        viewModel.suggest(
                            value.text.take(value.selection.min)
                                .takeLastWhile { it.isLetterOrDigit() || it == '_' },
                        )
                    },
                    textStyle = MonoStyles.editor,
                    visualTransformation = SqlVisualTransformation(plain = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 220.dp)
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }

            if (state.suggestions.isNotEmpty() && !state.editorCollapsed) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.l),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    items(state.suggestions) { suggestion ->
                        AssistChip(
                            // Completing replaces the half-typed word rather than adding to it.
                            onClick = { viewModel.complete(suggestion) },
                            label = { Text(suggestion, style = MonoStyles.cell) },
                        )
                    }
                }
            }

            // The key row: characters that are three taps deep on a phone keyboard.
            if (!state.editorCollapsed) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    items(KEY_ROW_ITEMS) { item ->
                        AssistChip(
                            onClick = { viewModel.append(item) },
                            label = { Text(item, style = MonoStyles.cell) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                if (state.running) {
                    Button(onClick = viewModel::cancel, shape = Shapes.button) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Text(
                            stringResource(R.string.query_cancel),
                            modifier = Modifier.padding(start = Spacing.s),
                        )
                    }
                } else {
                    Button(
                        onClick = { viewModel.run() },
                        enabled = state.sql.isNotBlank() && state.connectionName != null,
                        shape = Shapes.button,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(
                            // The label says what pressing it will actually do.
                            stringResource(
                                when {
                                    state.hasSelection -> R.string.query_run_selection
                                    state.isScript -> R.string.query_run_all
                                    else -> R.string.query_run
                                },
                            ),
                            modifier = Modifier.padding(start = Spacing.s),
                        )
                    }
                    if (state.isScript && !state.hasSelection) {
                        OutlinedButton(
                            onClick = { viewModel.runCurrent() },
                            enabled = state.connectionName != null,
                            shape = Shapes.button,
                        ) { Text(stringResource(R.string.query_run_current)) }
                    }
                    OutlinedButton(
                        onClick = viewModel::explain,
                        enabled = state.sql.isNotBlank() && state.connectionName != null,
                        shape = Shapes.button,
                    ) { Text(stringResource(R.string.query_explain)) }
                }
                Text(
                    text = stringResource(R.string.query_row_limit, state.rowLimit),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
                if (state.readOnly) {
                    Text(
                        text = stringResource(R.string.db_read_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.textSecondary,
                    )
                }
            }

            if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(Spacing.l),
            ) {
                QueryPanel.entries.forEachIndexed { index, panel ->
                    SegmentedButton(
                        selected = state.panel == panel,
                        onClick = { viewModel.selectPanel(panel) },
                        shape = SegmentedButtonDefaults.itemShape(index, QueryPanel.entries.size),
                        label = {
                            Text(
                                stringResource(
                                    when (panel) {
                                        QueryPanel.RESULT -> R.string.query_panel_result
                                        QueryPanel.HISTORY -> R.string.query_panel_history
                                        QueryPanel.FAVOURITES -> R.string.query_panel_favourites
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            if (state.statements.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.l),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    itemsIndexed(state.statements) { index, run ->
                        FilterChip(
                            selected = index == state.selectedStatement,
                            onClick = { viewModel.selectStatement(index) },
                            label = { Text(statementLabel(index, run)) },
                        )
                    }
                }
            }

            state.switchedTo?.let { database ->
                Text(
                    text = stringResource(R.string.query_switched_database, database),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.l),
                )
            }

            state.error?.let { message ->
                ErrorBlock(message = message, detail = state.errorDetail)
            }

            when {
                state.connectionName == null -> NoSessionState(onBack)
                state.panel == QueryPanel.HISTORY -> HistoryPanel(history, viewModel::load)
                state.panel == QueryPanel.FAVOURITES -> FavouritesPanel(
                    favourites = favourites,
                    onLoad = viewModel::load,
                    onDelete = viewModel::deleteFavourite,
                )

                else -> ResultPanel(
                    state = state,
                    onCellSelected = { selectedCell = it },
                    onSort = viewModel::sortResult,
                )
            }
        }
    }

    selectedCell?.let { selection ->
        CellSheet(
            column = selection.column,
            value = selection.value,
            // Editing needs a table and a primary key, which an arbitrary query does not have (§7.6).
            canEdit = false,
            editBlockedReason = null,
            onCopy = { context.copyToClipboard(it) },
            onEdit = {},
            onDismiss = { selectedCell = null },
        )
    }

    if (state.pendingParameters.isNotEmpty()) {
        ParameterDialog(
            names = state.pendingParameters,
            onRun = viewModel::run,
            onDismiss = viewModel::dismissParameters,
        )
    }

    if (favouriteDialogOpen) {
        FavouriteNameDialog(
            onSave = { name ->
                viewModel.saveFavourite(name)
                favouriteDialogOpen = false
            },
            onDismiss = { favouriteDialogOpen = false },
        )
    }
}

@Composable
private fun ErrorBlock(message: String, detail: String?) {
    var expanded by remember(detail) { mutableStateOf(false) }
    val semantic = LocalSemanticColors.current
    Column(modifier = Modifier.padding(horizontal = Spacing.l)) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        detail?.let {
            // §11: the raw cause stays behind "Details".
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(R.string.error_details))
            }
            if (expanded) Text(it, style = MonoStyles.cell, color = semantic.textSecondary)
        }
    }
}

@Composable
private fun Placeholder(textId: Int) {
    Text(
        text = stringResource(textId),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalSemanticColors.current.textSecondary,
        modifier = Modifier.padding(Spacing.l),
    )
}

@Composable
private fun ResultPanel(
    state: QueryEditorUiState,
    onCellSelected: (CellSelection) -> Unit,
    onSort: (String) -> Unit,
) {
    val result = state.result
    when {
        state.updateCount != null -> Placeholder(R.string.query_rows_changed)
        result == null -> Placeholder(R.string.query_no_result_yet)
        result.columns.isEmpty() -> Placeholder(R.string.query_no_result_yet)
        else -> Column {
            Text(
                text = stringResource(R.string.query_result_summary, result.rowCount, result.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = LocalSemanticColors.current.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.l),
            )
            ResultGrid(
                table = result,
                modifier = Modifier.fillMaxSize(),
                onCellClick = { onCellSelected(it) },
                sort = state.resultSort,
                onSort = onSort,
            )
        }
    }
}

@Composable
private fun HistoryPanel(history: List<QueryHistoryEntity>, onLoad: (String) -> Unit) {
    val semantic = LocalSemanticColors.current
    if (history.isEmpty()) {
        Placeholder(R.string.query_history_empty)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(history, key = { it.id }) { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLoad(entry.sql) }
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
            ) {
                Text(entry.sql, style = MonoStyles.cell, maxLines = 2)
                Text(
                    text = "${DateFormat.getDateTimeInstance().format(Date(entry.executedAt))} · " +
                        "${entry.durationMs} ms · ${entry.rowCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }
            HorizontalDivider(color = semantic.hairline)
        }
    }
}

@Composable
private fun FavouritesPanel(
    favourites: List<SavedQueryEntity>,
    onLoad: (String) -> Unit,
    onDelete: (SavedQueryEntity) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    if (favourites.isEmpty()) {
        Placeholder(R.string.query_favourites_empty)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(favourites, key = { it.id }) { favourite ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLoad(favourite.sql) }
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(favourite.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        favourite.sql,
                        style = MonoStyles.cell,
                        color = semantic.textSecondary,
                        maxLines = 2,
                    )
                }
                IconButton(onClick = { onDelete(favourite) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.connection_delete))
                }
            }
            HorizontalDivider(color = semantic.hairline)
        }
    }
}

/** §7.4: a favourite with `:parameters` asks for the values on a small form before it runs. */
@Composable
private fun ParameterDialog(
    names: List<String>,
    onRun: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember(names) { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.query_parameters_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                names.forEach { name ->
                    OutlinedTextField(
                        value = values[name].orEmpty(),
                        onValueChange = { values[name] = it },
                        label = { Text(name) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onRun(names.associateWith { values[it].orEmpty() }) }) {
                Text(stringResource(R.string.query_run))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FavouriteNameDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.query_favourite_add)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.query_favourite_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onSave(name) }) { Text(stringResource(R.string.connection_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** §8: an empty state always says why and offers one way out. */
@Composable
private fun NoSessionState(onBack: () -> Unit) {
    EmptyState(
        title = stringResource(R.string.query_no_session_title),
        body = stringResource(R.string.schema_no_session_body),
        actionLabel = stringResource(R.string.cancel),
        onAction = onBack,
        modifier = Modifier.fillMaxSize(),
    )
}


/**
 * What a statement's chip says: its number, and how it ended.
 *
 * The number comes first, because after a failure the first question is which statement it was.
 */
@Composable
private fun statementLabel(index: Int, run: StatementRun): String {
    val position = "${index + 1}"
    return when {
        run.error != null -> "$position · " + stringResource(R.string.query_statement_failed)
        run.switchedTo != null -> "$position · ${run.switchedTo}"
        run.updateCount != null ->
            "$position · " + stringResource(R.string.query_statement_changed, run.updateCount)

        run.table != null ->
            "$position · " + stringResource(R.string.query_statement_rows, run.table.rowCount)
        else -> position
    }
}

/**
 * Find and replace over the editor's text.
 *
 * Replace-all only: stepping through matches would need the editor to scroll and select, and on a
 * phone the text is short enough that replacing everything and looking at the result is quicker.
 * The match is case-insensitive, like SQL's own keywords and identifiers.
 */
@Composable
private fun FindReplaceBar(onReplaceAll: (String, String) -> Unit, onClose: () -> Unit) {
    var find by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        OutlinedTextField(
            value = find,
            onValueChange = { find = it },
            label = { Text(stringResource(R.string.query_find)) },
            singleLine = true,
            textStyle = MonoStyles.cell,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = replacement,
            onValueChange = { replacement = it },
            label = { Text(stringResource(R.string.query_replace)) },
            singleLine = true,
            textStyle = MonoStyles.cell,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onReplaceAll(find, replacement) }, enabled = find.isNotEmpty()) {
            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.query_replace_all))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
        }
    }
}

/**
 * The editor while a result is being read: one line of the query, and a way back to it.
 *
 * The whole row opens the editor, not just the arrow — after a query has run, the first thing
 * anyone does with the text is change it.
 */
@Composable
private fun CollapsedEditor(sql: String, onExpand: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = sql.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
            style = MonoStyles.cell,
            color = semantic.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = stringResource(R.string.query_edit),
            tint = semantic.textSecondary,
        )
    }
}
