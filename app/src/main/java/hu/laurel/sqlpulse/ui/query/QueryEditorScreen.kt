package hu.laurel.sqlpulse.ui.query

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.chart.ChartSpec
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.db.SavedQueryEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.grid.ResultFilter
import hu.laurel.sqlpulse.data.grid.ResultFilters
import hu.laurel.sqlpulse.data.sql.ParameterType
import hu.laurel.sqlpulse.data.sql.ParameterValue
import hu.laurel.sqlpulse.ui.chart.ResultChartPanel
import hu.laurel.sqlpulse.ui.components.ConnectionLostBanner
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.components.isWideWindow
import hu.laurel.sqlpulse.ui.connections.shortLabel
import hu.laurel.sqlpulse.ui.copyToClipboard
import hu.laurel.sqlpulse.ui.explain.ExplainPlanSection
import hu.laurel.sqlpulse.ui.grid.CellSelection
import hu.laurel.sqlpulse.ui.grid.CellSheet
import hu.laurel.sqlpulse.ui.grid.ResultFilterBar
import hu.laurel.sqlpulse.ui.grid.ResultGrid
import hu.laurel.sqlpulse.ui.labelRes
import hu.laurel.sqlpulse.ui.snapshot.SnapshotSheet
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors
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
    var renameDialogOpen by remember { mutableStateOf(false) }
    var selectedCell by remember { mutableStateOf<CellSelection?>(null) }
    var snapshotMenuOpen by remember { mutableStateOf(false) }

    // §7.7: the export leaves through the system share sheet; the app keeps no file.
    LaunchedEffect(state.shareIntent) {
        state.shareIntent?.let { intent ->
            context.startActivity(Intent.createChooser(intent, null))
            viewModel.shareIntentHandled()
        }
    }

    // An external keyboard is the reason this screen exists on a tablet at all, so the things
    // done most often have the shortcuts they have everywhere else. The handler sits above the
    // text field and sees the key first, which is why Ctrl+Enter runs instead of typing a newline.
    val onKey: (KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown) return@handler false
        val shortcut = QueryShortcuts.of(
            KeyPress(
                key = event.key.shortcutName(),
                ctrl = event.isCtrlPressed,
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
            ),
        )
        when (shortcut) {
            QueryShortcut.RUN -> {
                if (state.sql.isNotBlank() && !state.running) viewModel.run()
                true
            }

            QueryShortcut.RUN_CURRENT -> {
                if (state.sql.isNotBlank() && !state.running) viewModel.runCurrent()
                true
            }

            QueryShortcut.FORMAT -> {
                if (state.sql.isNotBlank()) viewModel.format()
                true
            }

            QueryShortcut.FIND -> {
                findOpen = !findOpen
                true
            }

            QueryShortcut.SAVE_FAVOURITE -> {
                if (state.sql.isNotBlank()) favouriteDialogOpen = true
                true
            }

            // Escape only takes back what it opened; with nothing open it belongs to the system.
            QueryShortcut.DISMISS -> if (findOpen) {
                findOpen = false
                true
            } else {
                false
            }

            null -> false
        }
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent(onKey),
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
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
                    // The time machine (roadmap): freeze this result, and later hold the same
                    // query's answer up against it. A menu rather than a button because taking a
                    // snapshot and comparing with one are two different moments, and the second
                    // one only exists once the first has happened.
                    if (state.result != null || state.snapshot != null) {
                        Box {
                            IconButton(onClick = { snapshotMenuOpen = true }) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = stringResource(R.string.snapshot_menu),
                                )
                            }
                            DropdownMenu(
                                expanded = snapshotMenuOpen,
                                onDismissRequest = { snapshotMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (state.snapshot == null) {
                                                    R.string.snapshot_take
                                                } else {
                                                    R.string.snapshot_retake
                                                },
                                            ),
                                        )
                                    },
                                    enabled = state.result != null,
                                    onClick = {
                                        snapshotMenuOpen = false
                                        viewModel.takeSnapshot()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.snapshot_compare)) },
                                    enabled = state.snapshot != null,
                                    onClick = {
                                        snapshotMenuOpen = false
                                        viewModel.compareWithSnapshot()
                                    },
                                )
                                if (state.snapshot != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.snapshot_discard)) },
                                        onClick = {
                                            snapshotMenuOpen = false
                                            viewModel.discardSnapshot()
                                        },
                                    )
                                }
                            }
                        }
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
        val wide = isWideWindow()
        val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()

        // Two columns where there is room for two: on a tablet the result no longer has to share
        // the height with the editor, and neither has to be scrolled to reach the other.
        val editorPane: @Composable ColumnScope.() -> Unit = {
                QueryTabBar(
                    tabs = state.tabs,
                    activeId = state.activeTabId,
                    wide = wide,
                    onSelect = viewModel::selectTab,
                    onNew = viewModel::newTab,
                    onRename = { renameDialogOpen = true },
                    onDuplicate = { viewModel.duplicateTab(it) },
                    onClose = viewModel::requestCloseTab,
                )

                // §7.3 lets you browse any database, so the editor has to be able to follow it. The
                // chips belong to writing a query, so they fold away with the editor.
                if (state.databases.isNotEmpty() && !state.editorCollapsed) {
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
                // The tab id is part of the key: two tabs can hold the same text, and switching
                // between them still has to move the cursor to where that tab left it.
                LaunchedEffect(state.activeTabId, state.sql, state.selectionStart) {
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
                    CollapsedEditor(
                        sql = state.sql,
                        running = state.running,
                        onRun = { viewModel.run() },
                        onCancel = viewModel::cancel,
                        onExpand = viewModel::toggleEditor,
                    )
                } else {
                    OutlinedTextField(
                        value = field,
                        onValueChange = { value ->
                            field = value
                            viewModel.onEditorChanged(value.text, value.selection.min, value.selection.max)
                        },
                        textStyle = MonoStyles.editor,
                        visualTransformation = SqlVisualTransformation(
                            plain = MaterialTheme.colorScheme.onSurface,
                            keyword = MaterialTheme.colorScheme.primary,
                            string = LocalSemanticColors.current.success,
                            number = LocalSemanticColors.current.cellNumber,
                            comment = LocalSemanticColors.current.cellNull,
                            identifier = LocalSemanticColors.current.cellDate,
                            parameter = LocalSemanticColors.current.warning,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (wide) 320.dp else 160.dp)
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
                    // Flat keys on a strip of their own, so they read as an extension of the
                    // keyboard below rather than as buttons belonging to the editor.
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .drawBehind { drawRect(semantic.hairline, size = size.copy(height = 1.dp.toPx())) },
                        contentPadding = PaddingValues(horizontal = Spacing.m, vertical = Spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(KEY_ROW_ITEMS) { item ->
                            Box(
                                modifier = Modifier
                                    .height(40.dp)
                                    .widthIn(min = 44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(semantic.surfaceRaised)
                                    .clickable(role = Role.Button) { viewModel.append(item) }
                                    .padding(horizontal = Spacing.m),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(item, style = MonoStyles.cell.copy(fontSize = 14.sp))
                            }
                        }
                    }
                }

                if (!state.editorCollapsed) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        if (state.running) {
                            Button(
                                onClick = viewModel::cancel,
                                shape = Shapes.button,
                                modifier = Modifier.height(52.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = semantic.danger,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
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
                                modifier = Modifier.height(52.dp),
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
                            // Only where writes are possible at all; on a read-only connection there
                            // is nothing a transaction could hold back.
                            if (!state.readOnly && !state.inTransaction) {
                                OutlinedButton(
                                    onClick = { viewModel.setTransaction(true) },
                                    enabled = state.connectionName != null,
                                    shape = Shapes.button,
                                ) { Text(stringResource(R.string.transaction_begin)) }
                            }
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
                }

                if (state.inTransaction) {
                    TransactionBar(
                        onCommit = { viewModel.setTransaction(false) },
                        onRollback = viewModel::rollback,
                    )
                }

                if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val outputPane: @Composable ColumnScope.() -> Unit = {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
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

                // weight(1f), so the result gets whatever is left rather than whatever happens to be
                // below the last control — in landscape that was nothing at all.
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        state.connectionName == null -> NoSessionState(onBack)
                        state.panel == QueryPanel.HISTORY -> HistoryPanel(history) { viewModel.load(it, saved = false) }
                        state.panel == QueryPanel.FAVOURITES -> FavouritesPanel(
                            favourites = favourites,
                            onLoad = { viewModel.load(it, saved = true) },
                            onDelete = viewModel::deleteFavourite,
                        )

                        else -> ResultPanel(
                            state = state,
                            onCellSelected = { selectedCell = it },
                            onSort = viewModel::sortResult,
                            onFilterChange = viewModel::setResultFilter,
                            onChartSpec = viewModel::setChartSpec,
                            onToggleFilter = viewModel::toggleFilterBar,
                            onToggleChart = viewModel::toggleChart,
                        )
                    }
                }
        }

        // Above both layouts: whatever is being read or typed stays on screen underneath it.
        val lostBanner: @Composable () -> Unit = {
            ConnectionLostBanner(state = sessionState, onReconnect = viewModel::reconnect)
        }

        if (wide) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                lostBanner()
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(EDITOR_PANE_WEIGHT)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        content = editorPane,
                    )
                    VerticalDivider()
                    Column(
                        modifier = Modifier.weight(1f - EDITOR_PANE_WEIGHT).fillMaxHeight(),
                        content = outputPane,
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                lostBanner()
                editorPane()
                outputPane()
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

    state.writeConfirmation?.let { confirmation ->
        WriteConfirmDialog(
            confirmation = confirmation,
            onConfirm = viewModel::confirmWrite,
            onDismiss = viewModel::dismissWriteConfirmation,
        )
    }

    state.closing?.let { tab ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCloseTab,
            title = { Text(stringResource(R.string.query_tab_close_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(stringResource(R.string.query_tab_close_body))
                    Text(
                        text = tab.sql.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
                        style = MonoStyles.cell,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.closeTab(tab.id) }) {
                    Text(stringResource(R.string.query_tab_close_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCloseTab) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (state.tabLimitReached) {
        AlertDialog(
            onDismissRequest = viewModel::dismissTabLimit,
            title = { Text(stringResource(R.string.query_tab_limit_title)) },
            text = { Text(stringResource(R.string.query_tab_limit_body, QueryTabs.MAX_TABS)) },
            confirmButton = {
                Button(onClick = viewModel::dismissTabLimit) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (renameDialogOpen) {
        TabNameDialog(
            initial = state.active.title.orEmpty(),
            onSave = { name ->
                viewModel.renameTab(state.activeTabId, name)
                renameDialogOpen = false
            },
            onDismiss = { renameDialogOpen = false },
        )
    }

    state.comparison?.let { outcome ->
        SnapshotSheet(outcome = outcome, onDismiss = viewModel::dismissComparison)
    }

    state.snapshotNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSnapshotNotice,
            title = { Text(stringResource(R.string.snapshot_notice_title)) },
            text = { Text(snapshotNoticeText(notice)) },
            confirmButton = {
                Button(onClick = viewModel::dismissSnapshotNotice) {
                    Text(stringResource(R.string.snapshot_close))
                }
            },
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

/**
 * What the dialog after a snapshot says.
 *
 * Three sentences at most, and the second one is the important one: a snapshot that kept only
 * part of the result has to say so at the moment it is taken, not later when the comparison it
 * produced turns out to be about a slice nobody knew about.
 */
@Composable
private fun snapshotNoticeText(notice: SnapshotNotice): String = when (notice) {
    is SnapshotNotice.Taken -> listOfNotNull(
        stringResource(R.string.snapshot_notice_taken, notice.rows),
        notice.trimmedFrom?.let {
            stringResource(R.string.snapshot_notice_trimmed, it, notice.rows)
        },
        stringResource(R.string.snapshot_notice_partial_source).takeIf { notice.sourceTruncated },
    ).joinToString("\n\n")

    is SnapshotNotice.TooWide ->
        stringResource(R.string.snapshot_notice_too_wide, notice.columnCount, notice.maxColumns)

    SnapshotNotice.NoResult -> stringResource(R.string.snapshot_notice_no_result)
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
    onFilterChange: (ResultFilter) -> Unit,
    onChartSpec: (ChartSpec) -> Unit,
    onToggleFilter: () -> Unit,
    onToggleChart: () -> Unit,
) {
    val result = state.result
    when {
        state.updateCount != null -> Placeholder(R.string.query_rows_changed)
        result == null -> Placeholder(R.string.query_no_result_yet)
        result.columns.isEmpty() -> Placeholder(R.string.query_no_result_yet)
        else -> Column {
            val semantic = LocalSemanticColors.current
            val visible = remember(result, state.resultFilter) {
                ResultFilters.apply(result, state.resultFilter)
            }

            // A plan is worth reading before the rows are: these are the parts of it that decide
            // whether the query is a good idea. A JSON plan is shown as the tree it is; anything
            // else falls back to the flat reading, which is what an older server gives.
            ExplainPlanSection(result)

            // The count in the ordinary text colour, the added LIMIT quietly under it, and the
            // ways of looking at the rows at the end of the same bar.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRect(
                            semantic.hairline,
                            topLeft = Offset(0f, size.height - 1.dp.toPx()),
                            size = size.copy(height = 1.dp.toPx()),
                        )
                    }
                    .padding(start = Spacing.l),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.query_result_summary, result.rowCount, result.durationMs),
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (result.limitAdded) {
                        Text(
                            text = stringResource(R.string.grid_limit_added),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = semantic.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onToggleFilter) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = stringResource(
                            if (state.filterOpen) R.string.filter_hide else R.string.filter_show,
                        ),
                        // A filter that is narrowing the rows says so even while its bar is
                        // folded away, so an empty-looking result is never a mystery.
                        tint = if (state.resultFilter.isActive) {
                            LocalSemanticColors.current.warning
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
                IconButton(onClick = onToggleChart) {
                    Icon(
                        imageVector = if (state.showChart) Icons.Default.TableRows else Icons.Default.BarChart,
                        contentDescription = stringResource(
                            if (state.showChart) R.string.chart_hide else R.string.chart_show,
                        ),
                    )
                }
            }

            // A snapshot leaves no mark on the result it was taken from, and an unmarked
            // snapshot is one that gets compared against by accident an hour later. When it
            // was taken, and how much of it there is, is the whole of what has to be said here.
            state.snapshot?.let { snapshot ->
                Text(
                    text = stringResource(
                        R.string.snapshot_state,
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.takenAt)),
                        snapshot.rowCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSemanticColors.current.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Spacing.l),
                )
            }
            if (state.filterOpen) {
                ResultFilterBar(
                    table = result,
                    filter = state.resultFilter,
                    shownRows = visible.rowCount,
                    onFilterChange = onFilterChange,
                )
            }

            if (state.showChart) {
                // The chart draws the rows that are on screen, so narrowing the grid narrows the
                // picture with it — two readings of one thing, never of two different things.
                ResultChartPanel(
                    table = visible,
                    spec = state.chartSpec,
                    onSpecChange = onChartSpec,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ResultGrid(
                    table = visible,
                    showLimitNote = false,
                    modifier = Modifier.fillMaxSize(),
                    onCellClick = { onCellSelected(it) },
                    sort = state.resultSort,
                    onSort = onSort,
                )
            }
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

/**
 * §7.4: a favourite with `:parameters` asks for the values on a small form before it runs.
 *
 * Each value carries a type, because text is not always what was meant: an empty box for a number
 * is "no value", and NULL is a value no amount of typing can express.
 */
@Composable
private fun ParameterDialog(
    names: List<String>,
    onRun: (Map<String, ParameterValue>) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember(names) { mutableStateMapOf<String, ParameterValue>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.query_parameters_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                names.forEach { name ->
                    val value = values[name] ?: ParameterValue()
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        OutlinedTextField(
                            value = value.text,
                            onValueChange = { values[name] = value.copy(text = it) },
                            label = { Text(name) },
                            // NULL has nothing to type, so the field says so by being closed.
                            enabled = value.type != ParameterType.NULL,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            ParameterType.entries.forEach { type ->
                                FilterChip(
                                    selected = value.type == type,
                                    onClick = { values[name] = value.copy(type = type) },
                                    label = { Text(stringResource(type.labelRes())) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onRun(names.associateWith { values[it] ?: ParameterValue() }) }) {
                Text(stringResource(R.string.query_run))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@StringRes
private fun ParameterType.labelRes(): Int = when (this) {
    ParameterType.TEXT -> R.string.parameter_type_text
    ParameterType.NUMBER -> R.string.parameter_type_number
    ParameterType.DATE -> R.string.parameter_type_date
    ParameterType.BOOLEAN -> R.string.parameter_type_boolean
    ParameterType.NULL -> R.string.parameter_type_null
}

/**
 * The last thing between a hand-typed write and the database (§7.4).
 *
 * It says what will run, how many rows that is estimated to be, and where — the connection, its
 * environment and the database — because on a phone the three of them are a tab away from each
 * other and nothing on screen otherwise repeats them. A production connection asks for the
 * database name to be typed: it is the one gesture a thumb cannot make by accident.
 */
@Composable
private fun WriteConfirmDialog(
    confirmation: WriteConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    var typed by remember(confirmation) { mutableStateOf("") }
    val blocked = confirmation.exceedsLimit
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.write_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(confirmation.sql, style = MonoStyles.cell)

                Text(
                    text = confirmation.estimatedRows
                        ?.let { stringResource(R.string.write_confirm_rows, it) }
                        ?: stringResource(R.string.write_confirm_rows_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (blocked) MaterialTheme.colorScheme.error else semantic.textSecondary,
                )

                Text(
                    text = listOfNotNull(
                        confirmation.connectionName,
                        stringResource(confirmation.environment.shortLabel()),
                        confirmation.database,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (confirmation.environment.isProduction) {
                        MaterialTheme.colorScheme.error
                    } else {
                        semantic.textSecondary
                    },
                )

                if (blocked) {
                    Text(
                        text = stringResource(
                            R.string.write_confirm_over_limit,
                            confirmation.maxAffectedRows,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (confirmation.requiresTypedDatabase) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = {
                            Text(
                                stringResource(
                                    R.string.write_confirm_type_database,
                                    confirmation.database.orEmpty(),
                                ),
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !blocked && confirmation.confirms(typed),
            ) {
                Text(stringResource(R.string.write_confirm_run))
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
 * The editor while a result is being read: one line of the query, and the two things still worth
 * doing to it — run it again, or open it to change it.
 *
 * Everything else (the database chips, the key row, the completion chips, EXPLAIN) belongs to
 * writing a query, and comes back with the editor. This strip is one row tall, because the rows
 * it saves are rows of the result.
 */
@Composable
private fun CollapsedEditor(
    sql: String,
    running: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onExpand: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .padding(start = Spacing.l, end = Spacing.s),
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
        IconButton(onClick = if (running) onCancel else onRun) {
            Icon(
                imageVector = if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = stringResource(
                    if (running) R.string.query_cancel else R.string.query_run,
                ),
            )
        }
        IconButton(onClick = onExpand) {
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = stringResource(R.string.query_edit),
                tint = semantic.textSecondary,
            )
        }
    }
}

/**
 * The bar of an open transaction: what is happening, and the two ways out of it.
 *
 * It stays on screen while the editor is folded away, because a transaction nobody can see is a
 * transaction somebody will forget to commit.
 */
@Composable
private fun TransactionBar(onCommit: () -> Unit, onRollback: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.warning.copy(alpha = 0.14f))
            .drawBehind { drawRect(semantic.warning.copy(alpha = 0.4f), size = size.copy(height = 1.dp.toPx())) }
            .padding(start = Spacing.l, end = Spacing.m, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(Icons.Default.CallSplit, contentDescription = null, tint = semantic.warning, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.transaction_open),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.warning,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onRollback, shape = Shapes.button) { Text(stringResource(R.string.transaction_rollback)) }
        Button(onClick = onCommit, shape = Shapes.button) {
            Text(stringResource(R.string.transaction_commit))
        }
    }
}

/**
 * The key names [QueryShortcuts] speaks. Everything else is text, or somebody else's shortcut.
 */
private fun Key.shortcutName(): String = when (this) {
    Key.Enter, Key.NumPadEnter -> "ENTER"
    Key.F -> "F"
    Key.S -> "S"
    Key.Escape -> "ESCAPE"
    else -> ""
}

/** The editor gets a little less than half: the result is the wider of the two things to read. */
private const val EDITOR_PANE_WEIGHT = 0.42f

/**
 * The tab strip, in the two shapes it needs.
 *
 * On a wide window the tabs are a scrolling row of chips: there is room for several names, and
 * seeing them all at once is the point of having them.
 *
 * On a phone there is no such room, and a strip of chips there is actively harmful. At 360dp a row
 * of four names either elides every one of them down to "SEL…" — which tells you nothing about
 * which tab is which — or scrolls sideways, directly above an editor that also scrolls sideways,
 * so a horizontal swipe becomes a guess about which of the two will take it. Worse, the strip
 * grows as tabs are added, eating the four or five lines of SQL the editor has to begin with.
 *
 * So the phone gets one row that never grows: the name of the tab you are in, "3/5" so you know
 * there are others and where you are among them, and a tap to open the full list as a menu, where
 * every name has the whole width and closing one is a deliberate second target. Vertical space is
 * fixed, the names are legible, and nothing competes with the editor for a sideways swipe.
 */
@Composable
private fun QueryTabBar(
    tabs: List<QueryTab>,
    activeId: Long,
    wide: Boolean,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onRename: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
    onClose: (Long) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    var listOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val activeIndex = tabs.indexOfFirst { it.id == activeId }.coerceAtLeast(0)

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (wide) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                    FilterChip(
                        selected = tab.id == activeId,
                        onClick = { onSelect(tab.id) },
                        label = {
                            Text(
                                text = tabLabel(tab, index),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        // The dot is the whole mark: a tab whose text is not a favourite yet.
                        leadingIcon = if (tab.unsaved) {
                            {
                                Text(
                                    text = stringResource(R.string.query_tab_unsaved_mark),
                                    color = semantic.warning,
                                )
                            }
                        } else {
                            null
                        },
                        trailingIcon = if (tabs.size > 1) {
                            {
                                IconButton(onClick = { onClose(tab.id) }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.query_tab_close),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                TextButton(onClick = { listOpen = true }) {
                    if (tabs[activeIndex].unsaved) {
                        Text(
                            text = stringResource(R.string.query_tab_unsaved_mark),
                            color = semantic.warning,
                            modifier = Modifier.padding(end = Spacing.xs),
                        )
                    }
                    Text(
                        text = tabLabel(tabs[activeIndex], activeIndex),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = stringResource(
                            R.string.query_tab_position,
                            activeIndex + 1,
                            tabs.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.textSecondary,
                        modifier = Modifier.padding(start = Spacing.xs),
                    )
                    Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.query_tab_switch))
                }
                DropdownMenu(expanded = listOpen, onDismissRequest = { listOpen = false }) {
                    tabs.forEachIndexed { index, tab ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = tabLabel(tab, index),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (tab.id == activeId) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            leadingIcon = if (tab.unsaved) {
                                {
                                    Text(
                                        text = stringResource(R.string.query_tab_unsaved_mark),
                                        color = semantic.warning,
                                    )
                                }
                            } else {
                                null
                            },
                            trailingIcon = if (tabs.size > 1) {
                                {
                                    IconButton(
                                        onClick = {
                                            listOpen = false
                                            onClose(tab.id)
                                        },
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.query_tab_close),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            onClick = {
                                listOpen = false
                                onSelect(tab.id)
                            },
                        )
                    }
                }
            }
        }

        IconButton(onClick = onNew) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.query_tab_new))
        }

        // Rename, duplicate and close act on the tab you are in, so they are one menu in both
        // layouts rather than a second control per chip.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.query_tab_switch))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.query_tab_rename)) },
                    onClick = {
                        menuOpen = false
                        onRename(activeId)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.query_tab_duplicate)) },
                    onClick = {
                        menuOpen = false
                        onDuplicate(activeId)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.query_tab_close)) },
                    onClick = {
                        menuOpen = false
                        onClose(activeId)
                    },
                )
            }
        }
    }
}

/** A tab's name, its first line of SQL, or a number — in that order of preference. */
@Composable
private fun tabLabel(tab: QueryTab, index: Int): String =
    QueryTabs.label(tab) ?: stringResource(R.string.query_tab_untitled, index + 1)

@Composable
private fun TabNameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.query_tab_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.query_tab_rename_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onSave(name) }) { Text(stringResource(R.string.connection_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
