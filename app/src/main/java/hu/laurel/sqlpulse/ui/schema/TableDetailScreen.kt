package hu.laurel.sqlpulse.ui.schema

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Upload
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
import hu.laurel.sqlpulse.data.csv.ImportPlan
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.schema.CheckConstraint
import hu.laurel.sqlpulse.data.schema.ForeignKey
import hu.laurel.sqlpulse.data.schema.GeneratedKind
import hu.laurel.sqlpulse.data.schema.LookupOutcome
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.SchemaExtras
import hu.laurel.sqlpulse.data.schema.SchemaIndex
import hu.laurel.sqlpulse.data.schema.TablePartition
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnEditors
import hu.laurel.sqlpulse.data.sql.ColumnFilter
import hu.laurel.sqlpulse.data.sql.EditKind
import hu.laurel.sqlpulse.ui.copyToClipboard
import hu.laurel.sqlpulse.ui.grid.CellEditDialog
import hu.laurel.sqlpulse.ui.grid.CellSelection
import hu.laurel.sqlpulse.ui.grid.CellSheet
import hu.laurel.sqlpulse.ui.grid.ConfirmStatementDialog
import hu.laurel.sqlpulse.ui.grid.LinkChildEntry
import hu.laurel.sqlpulse.ui.grid.LinkOffer
import hu.laurel.sqlpulse.ui.grid.LinkWalkSheet
import hu.laurel.sqlpulse.ui.grid.ResultGrid
import hu.laurel.sqlpulse.ui.grid.RowDetailSheet
import hu.laurel.sqlpulse.ui.grid.asText
import hu.laurel.sqlpulse.ui.labelRes
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
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::prepareImport)
    }

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
                    // Importing is a write, so it follows the same rule as editing a row.
                    if (state.tab == TableTab.DATA && state.canEdit) {
                        IconButton(
                            onClick = { importPicker.launch(arrayOf("text/*", "text/csv", "*/*")) },
                            enabled = !state.importing,
                        ) {
                            Icon(
                                Icons.Default.Upload,
                                contentDescription = stringResource(R.string.import_csv),
                            )
                        }
                    }
                    if (state.tab == TableTab.DATA && state.rows != null) {
                        Box {
                            IconButton(onClick = { exportMenuOpen = true }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.export))
                            }
                            DropdownMenu(
                                expanded = exportMenuOpen,
                                onDismissRequest = { exportMenuOpen = false },
                            ) {
                                // One entry per format, in the order they are reached for:
                                // a spreadsheet, a shell, a program, another database.
                                ExportFormat.entries.forEach { format ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(format.labelRes())) },
                                        onClick = {
                                            exportMenuOpen = false
                                            viewModel.export(format)
                                        },
                                    )
                                }
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
                        // The table's own collation sits above the columns, because it is what a
                        // column's collation is read against: only the columns that differ from
                        // it carry a collation of their own.
                        structure.collation?.let { collation ->
                            item { TableCollationRow(collation) }
                        }
                        items(structure.columns, key = { "column:" + it.name }) { column ->
                            ColumnRow(column, structure.collation)
                            HorizontalDivider(color = semantic.hairline)
                        }
                        if (structure.indexes.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.structure_indexes)) }
                            items(structure.indexes, key = { "index:" + it.name }) { IndexRow(it) }
                        }
                        if (structure.foreignKeys.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.structure_foreign_keys)) }
                            items(structure.foreignKeys, key = { "fk:" + it.constraintName + it.column }) { fk ->
                                // §7.3: touching a foreign key jumps to the referenced table.
                                ForeignKeyRow(fk) { onOpenTable(fk.referencedDatabase, fk.referencedTable) }
                            }
                        }
                        // A server without CHECK constraints reports none, which looks exactly
                        // like a table that declares none — so the section simply does not
                        // appear, rather than claiming anything either way.
                        if (structure.checks.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.structure_checks)) }
                            items(structure.checks, key = { "check:" + it.name }) { CheckRow(it) }
                        }
                        if (structure.partitioned) {
                            item { SectionHeader(stringResource(R.string.structure_partitions)) }
                            item { PartitionSummaryRow(structure.partitions) }
                            items(
                                structure.partitions,
                                key = { "partition:" + it.name + "/" + it.subName.orEmpty() },
                            ) { PartitionRow(it) }
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
            // §7.3: a foreign key cell offers the row it points at; a NULL one offers nothing.
            linkOffer = viewModel.parentLinkFor(selection.rowIndex, selection.column.label)
                ?.let { LinkOffer(it.parentTable, it.guessed) },
            onOpenLink = {
                viewModel.openParent(selection.rowIndex, selection.column.label)
                selectedCell = null
            },
        )
    }

    editingCell?.let { selection ->
        CellEditDialog(
            columnLabel = selection.column.label,
            initialValue = if (selection.value is CellValue.Null) null else selection.value.asText(),
            // The type comes from the table's own structure, which knows an enum's values; the
            // result set only reports that the column is a string.
            editor = ColumnEditors.of(
                state.structure?.columns
                    ?.firstOrNull { it.name == selection.column.label }
                    ?.typeName
                    ?: selection.column.typeName,
            ),
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
                onShowChildren = {
                    viewModel.showChildrenOf(rowIndex)
                    detailRow = null
                },
            )
        }
    }

    state.walk?.let { walk ->
        val step = walk.step
        LinkWalkSheet(
            title = step?.label.orEmpty(),
            guessed = walk.trail.hasGuessedStep,
            canGoBack = walk.trail.canGoBack,
            loading = walk.loading,
            error = walk.error,
            columns = walk.rows?.columns.orEmpty(),
            rows = walk.rows?.rows.orEmpty(),
            notice = when (walk.outcome) {
                LookupOutcome.MISSING -> stringResource(R.string.link_missing)
                LookupOutcome.SEVERAL -> stringResource(
                    R.string.link_several,
                    walk.rows?.rowCount ?: 0,
                )
                else -> null
            },
            selectedRow = walk.selectedRow,
            children = walk.children.map { child ->
                LinkChildEntry(
                    table = child.link.childTable,
                    columns = child.link.childColumns.joinToString(", "),
                    rows = child.rows,
                    guessed = child.link.guessed,
                    onOpen = { viewModel.openChildren(child.link) },
                )
            },
            childrenLoading = walk.childrenLoading,
            offerFor = { rowIndex, column ->
                viewModel.walkParentLinkFor(rowIndex, column)
                    ?.let { LinkOffer(it.parentTable, it.guessed) }
            },
            onOpenParent = viewModel::openParentFromWalk,
            onSelectRow = viewModel::selectWalkRow,
            onBack = viewModel::walkBack,
            onDismiss = viewModel::closeWalk,
        )
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

    state.importPlan?.let { plan ->
        ImportDialog(
            plan = plan,
            table = state.table,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::dismissImport,
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

/**
 * One column, and everything about it that fits on two lines.
 *
 * A generated column is marked beside its name rather than inside the type line, because it is
 * the one property here that changes what writing to the column means: it cannot be written to at
 * all. Its expression follows on a line of its own, shortened — the whole of it is in the DDL tab.
 */
@Composable
private fun ColumnRow(column: SchemaColumn, tableCollation: String?) {
    val semantic = LocalSemanticColors.current
    val generated = column.generatedKind
    val collation = SchemaExtras.columnCollation(tableCollation, column.collation)
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
                if (generated != null) {
                    Text(
                        text = stringResource(
                            when (generated) {
                                GeneratedKind.VIRTUAL -> R.string.structure_generated_virtual
                                GeneratedKind.STORED -> R.string.structure_generated_stored
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.textSecondary,
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
            column.generationExpression?.let { expression ->
                Text(
                    text = "= ${SchemaExtras.shorten(expression)}",
                    style = MonoStyles.cell,
                    color = semantic.textSecondary,
                )
            }
            // Only where it differs from the table's: a column collating differently is what
            // silently breaks a join, and it is invisible unless it is said out loud.
            collation?.let {
                Text(
                    text = stringResource(R.string.structure_collation_column, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.warning,
                )
            }
        }
    }
}

/** The table's default collation, shown once above the columns. */
@Composable
private fun TableCollationRow(collation: String) {
    val semantic = LocalSemanticColors.current
    Text(
        text = stringResource(R.string.structure_collation_table, collation),
        style = MaterialTheme.typography.bodySmall,
        color = semantic.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
    )
}

/**
 * One CHECK constraint: its name, its condition, and whether the server actually applies it.
 *
 * A `NOT ENFORCED` constraint is called out in the warning colour, because it reads as protection
 * in the DDL and is none: the rows it forbids go in anyway.
 */
@Composable
private fun CheckRow(check: CheckConstraint) {
    val semantic = LocalSemanticColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(check.name, style = MonoStyles.cell)
            if (!check.enforced) {
                Text(
                    text = stringResource(R.string.structure_check_not_enforced),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.warning,
                )
            }
        }
        check.expression?.let {
            Text(
                text = SchemaExtras.shorten(it),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
        }
    }
}

/** How the table is cut up, and how many rows the server thinks are in the pieces together. */
@Composable
private fun PartitionSummaryRow(partitions: List<TablePartition>) {
    val semantic = LocalSemanticColors.current
    val total = SchemaExtras.partitionRowTotal(partitions)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        SchemaExtras.partitionSummary(partitions)?.let {
            Text(text = it, style = MonoStyles.cell)
        }
        Text(
            text = buildList {
                add(stringResource(R.string.structure_partition_count, partitions.size))
                total?.let { add(stringResource(R.string.structure_partition_total_rows, it)) }
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
}

/**
 * One partition, with the rows the server estimates are in it.
 *
 * The per-partition estimate is the point of the list: it is how an unbalanced partitioning —
 * every row in one piece, the rest empty — becomes visible.
 */
@Composable
private fun PartitionRow(partition: TablePartition) {
    val semantic = LocalSemanticColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Text(
            text = partition.subName?.let { "${partition.name} / $it" } ?: partition.name,
            style = MonoStyles.cell,
        )
        partition.approximateRows?.let {
            Text(
                text = stringResource(R.string.schema_rows_approx, it),
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
        // Only the rules that do something: RESTRICT and NO ACTION are what every key without a
        // declared rule does, and printing them under each one would hide the CASCADE.
        foreignKey.ruleSummary?.let { rules ->
            Text(
                text = rules,
                style = MaterialTheme.typography.bodySmall,
                color = semantic.warning,
            )
        }
    }
}


/**
 * What importing this file would do, before it does it.
 *
 * Every number here is the answer to a question someone would otherwise have to ask afterwards:
 * how many rows, which columns are filled, which of the file's columns are ignored, and which
 * lines did not parse. An import that cannot work says why instead of offering a button.
 */
@Composable
private fun ImportDialog(
    plan: ImportPlan,
    table: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_title, table)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(R.string.import_rows, plan.rowCount))
                Text(
                    text = stringResource(
                        R.string.import_columns,
                        plan.match.matched.values.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
                if (plan.match.unmatched.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.import_ignored,
                            plan.match.unmatched.joinToString(", "),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.warning,
                    )
                }
                if (plan.table.malformedRows > 0) {
                    Text(
                        text = stringResource(R.string.import_malformed, plan.table.malformedRows),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.warning,
                    )
                }
                if (!plan.match.canImport) {
                    Text(
                        text = if (plan.match.blocking.isEmpty()) {
                            stringResource(R.string.import_no_columns)
                        } else {
                            stringResource(
                                R.string.import_blocking,
                                plan.match.blocking.joinToString(", "),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (plan.match.canImport && plan.rowCount > 0) {
                Button(onClick = onConfirm) { Text(stringResource(R.string.import_run)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
