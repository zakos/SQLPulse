package hu.laurel.sqlpulse.ui.schema

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.schema.ForeignKey
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.SchemaIndex
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnFilter
import hu.laurel.sqlpulse.data.sql.EditKind
import hu.laurel.sqlpulse.ui.copyToClipboard
import hu.laurel.sqlpulse.ui.grid.CellEditDialog
import hu.laurel.sqlpulse.ui.grid.CellSelection
import hu.laurel.sqlpulse.ui.grid.CellSheet
import hu.laurel.sqlpulse.ui.grid.ConfirmStatementDialog
import hu.laurel.sqlpulse.ui.grid.ResultGrid
import hu.laurel.sqlpulse.ui.grid.RowDetailSheet
import hu.laurel.sqlpulse.ui.grid.asText
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/** Table page (§7.3): Data, Structure and DDL, with row editing and export on the Data tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableDetailScreen(
    onBack: () -> Unit,
    onOpenTable: (database: String, table: String) -> Unit,
    viewModel: TableDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val semantic = LocalSemanticColors.current
    val snackbarHost = remember { SnackbarHostState() }

    var selectedCell by remember { mutableStateOf<CellSelection?>(null) }
    var editingCell by remember { mutableStateOf<CellSelection?>(null) }
    var detailRow by remember { mutableStateOf<Int?>(null) }
    var exportMenuOpen by remember { mutableStateOf(false) }

    // The share sheet is started once per export; the file lives in the cache until the next lock.
    LaunchedEffect(state.shareIntent) {
        state.shareIntent?.let { intent ->
            context.startActivity(android.content.Intent.createChooser(intent, null))
            viewModel.shareIntentHandled()
        }
    }

    val undoLabel = stringResource(R.string.undo)
    val undoMessage = stringResource(R.string.edit_applied)
    LaunchedEffect(state.undoable) {
        if (state.undoable == null) return@LaunchedEffect
        val result = snackbarHost.showSnackbar(
            message = undoMessage,
            actionLabel = undoLabel,
            duration = androidx.compose.material3.SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undo()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.table, style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.database,
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
                    if (state.tab == TableTab.DATA && state.rows != null) {
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
            TabRow(selectedTabIndex = state.tab.ordinal) {
                TableTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == state.tab,
                        onClick = { viewModel.select(tab) },
                        text = {
                            Text(
                                stringResource(
                                    when (tab) {
                                        TableTab.DATA -> R.string.tab_data
                                        TableTab.STRUCTURE -> R.string.tab_structure
                                        TableTab.DDL -> R.string.tab_ddl
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { viewModel.dismissError() }.padding(Spacing.l),
                )
            }

            if (state.loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(Spacing.l)) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            when (state.tab) {
                TableTab.DATA -> state.rows?.let { rows ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        FilterBar(
                            columns = rows.columns.map { it.label },
                            filter = state.filter,
                            onFilter = viewModel::setFilter,
                        )
                        ResultGrid(
                            table = rows,
                            modifier = Modifier.fillMaxSize(),
                            onLoadMore = viewModel::loadMore,
                            loadingMore = state.loadingMore,
                            totalRows = state.totalRows,
                            onCellClick = { selectedCell = it },
                            onRowLongPress = { detailRow = it },
                            sort = state.sort,
                            onSort = viewModel::sortBy,
                        )
                    }
                }

                TableTab.STRUCTURE -> state.structure?.let { structure ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(structure.columns, key = { it.name }) { column ->
                            ColumnRow(column)
                            HorizontalDivider(color = semantic.hairline)
                        }
                        if (structure.indexes.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.structure_indexes)) }
                            items(structure.indexes, key = { it.name }) { IndexRow(it) }
                        }
                        if (structure.foreignKeys.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.structure_foreign_keys)) }
                            items(structure.foreignKeys, key = { it.constraintName + it.column }) { fk ->
                                // §7.3: touching a foreign key jumps to the referenced table.
                                ForeignKeyRow(fk) { onOpenTable(fk.referencedDatabase, fk.referencedTable) }
                            }
                        }
                        if (structure.primaryKey.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.structure_no_primary_key),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = semantic.warning,
                                    modifier = Modifier.padding(Spacing.l),
                                )
                            }
                        }
                    }
                }

                TableTab.DDL -> state.ddl?.let { ddl ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            IconButton(onClick = { context.copyToClipboard(ddl) }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.ddl_copy),
                                )
                            }
                        }
                        Text(
                            text = ddl,
                            style = MonoStyles.cell,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(Spacing.l),
                        )
                    }
                }
            }
        }
    }

    selectedCell?.let { selection ->
        CellSheet(
            column = selection.column,
            value = selection.value,
            canEdit = state.canEdit,
            editBlockedReason = state.editBlockedReason?.let {
                stringResource(
                    when (it) {
                        EditBlock.READ_ONLY -> R.string.error_read_only
                        EditBlock.NO_PRIMARY_KEY -> R.string.structure_no_primary_key
                    },
                )
            },
            onCopy = { context.copyToClipboard(it) },
            onEdit = {
                editingCell = selection
                selectedCell = null
            },
            onDismiss = { selectedCell = null },
        )
    }

    editingCell?.let { selection ->
        CellEditDialog(
            columnLabel = selection.column.label,
            initialValue = if (selection.value is CellValue.Null) null else selection.value.asText(),
            onConfirm = { newValue ->
                viewModel.prepareCellEdit(selection.rowIndex, selection.column.label, newValue)
                editingCell = null
            },
            onDismiss = { editingCell = null },
        )
    }

    detailRow?.let { rowIndex ->
        val rows = state.rows
        if (rows != null) {
            RowDetailSheet(
                columns = rows.columns,
                row = rows.rows.getOrElse(rowIndex) { emptyList() },
                canDelete = state.canEdit,
                onCopy = { context.copyToClipboard(it) },
                onDelete = {
                    viewModel.prepareRowDelete(rowIndex)
                    detailRow = null
                },
                onDismiss = { detailRow = null },
            )
        }
    }

    state.pendingEdit?.let { edit ->
        val destructive = edit.kind == EditKind.DELETE
        ConfirmStatementDialog(
            title = stringResource(
                if (destructive) R.string.confirm_delete_title else R.string.confirm_update_title,
            ),
            statement = edit.preview,
            destructive = destructive,
            // §7.6: on a production connection, deleting means typing the table name.
            requireTableName = state.table.takeIf { destructive && state.isProduction },
            onConfirm = viewModel::confirmEdit,
            onDismiss = viewModel::dismissEdit,
        )
    }

    state.conflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            title = { Text(stringResource(R.string.conflict_title)) },
            text = {
                Text(
                    if (conflict.rowExists) {
                        stringResource(
                            R.string.conflict_body,
                            conflict.currentValue ?: "NULL",
                        )
                    } else {
                        stringResource(R.string.conflict_row_gone)
                    },
                )
            },
            confirmButton = {
                // Only offered while there is still a row to write to.
                if (conflict.rowExists) {
                    Button(onClick = viewModel::overwriteConflict) {
                        Text(stringResource(R.string.conflict_overwrite))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConflict) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Quick search on one column (§7.1: "filter on a column" is the first thing the fast-lookup flow
 * asks for). The filter runs on the server, so it searches the whole table, not the loaded page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    columns: List<String>,
    filter: ColumnFilter?,
    onFilter: (String?, String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var text by remember(filter?.column) { mutableStateOf(filter?.contains.orEmpty()) }
    val column = filter?.column ?: columns.firstOrNull() ?: return

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box {
            OutlinedButton(onClick = { menuOpen = true }, shape = Shapes.button) {
                Text(column, style = MonoStyles.cell, maxLines = 1)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                columns.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate, style = MonoStyles.cell) },
                        onClick = {
                            menuOpen = false
                            onFilter(candidate, text)
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onFilter(column, it)
            },
            label = { Text(stringResource(R.string.filter_contains)) },
            singleLine = true,
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            text = ""
                            onFilter(null, "")
                        },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.schema_clear_filter))
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.l, bottom = Spacing.s),
    )
}

@Composable
private fun ColumnRow(column: SchemaColumn) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(column.name, style = MonoStyles.cell)
                if (column.isPrimaryKey) {
                    Text(
                        stringResource(R.string.structure_primary_key),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.success,
                    )
                }
            }
            Text(
                text = buildString {
                    append(column.typeName)
                    if (!column.nullable) append(" · NOT NULL")
                    column.defaultValue?.let { append(" · DEFAULT $it") }
                    column.extra?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
        }
    }
}

@Composable
private fun IndexRow(index: SchemaIndex) {
    val semantic = LocalSemanticColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Text(index.name, style = MonoStyles.cell)
        Text(
            text = index.columns.joinToString(", ") + if (index.unique) " · UNIQUE" else "",
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
}

@Composable
private fun ForeignKeyRow(foreignKey: ForeignKey, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        Text(foreignKey.column, style = MonoStyles.cell)
        Text(
            text = "→ ${foreignKey.referencedTable}.${foreignKey.referencedColumn}",
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
}

