package hu.laurel.sqlpulse.ui.query

import hu.laurel.sqlpulse.ui.appLocale
import hu.laurel.sqlpulse.data.format.LocaleFormat
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BasicAlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.chart.ChartSpec
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.db.SavedQueryEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.grid.ResultFilter
import hu.laurel.sqlpulse.data.grid.ResultFilters
import hu.laurel.sqlpulse.data.sql.ParameterType
import hu.laurel.sqlpulse.data.sql.ParameterValue
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.ui.chart.ResultChartPanel
import hu.laurel.sqlpulse.ui.components.ConnectionLostBanner
import hu.laurel.sqlpulse.ui.components.ConnectionTitle
import hu.laurel.sqlpulse.ui.components.DialogButtons
import hu.laurel.sqlpulse.ui.components.DialogCard
import hu.laurel.sqlpulse.ui.components.DialogHeading
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.components.LabeledField
import hu.laurel.sqlpulse.ui.components.SqlBlock
import hu.laurel.sqlpulse.ui.components.isWideWindow
import hu.laurel.sqlpulse.ui.connections.shortLabel
import hu.laurel.sqlpulse.ui.copyToClipboard
import hu.laurel.sqlpulse.ui.explain.ExplainPlanSection
import hu.laurel.sqlpulse.ui.grid.CellSelection
import hu.laurel.sqlpulse.ui.grid.ExportSheet
import hu.laurel.sqlpulse.ui.grid.ResultFilterBar
import hu.laurel.sqlpulse.ui.grid.ResultGrid
import hu.laurel.sqlpulse.ui.labelRes
import hu.laurel.sqlpulse.data.query.KeyAction
import hu.laurel.sqlpulse.data.query.KeyBar
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.EngineFeature
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialects
import hu.laurel.sqlpulse.data.sql.dialect.SqlSyntax
import hu.laurel.sqlpulse.ui.engine.LocalEngineFeatures
import hu.laurel.sqlpulse.ui.settings.KeyFace
import hu.laurel.sqlpulse.ui.settings.keyName
import hu.laurel.sqlpulse.ui.snapshot.SnapshotSheet
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import hu.laurel.sqlpulse.data.snapshot.SnapshotLabels
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Query editor (§7.4): monospace field with highlighting, the key row the phone keyboard lacks,
 * and the result, history and favourites underneath.
 */
@Composable
fun QueryEditorScreen(
    onBack: () -> Unit,
    viewModel: QueryEditorViewModel = hiltViewModel(),
) {
    QueryEditorContent(onBack = onBack, viewModel = viewModel)
}

/** The screen itself, drawn from whatever [QueryEditorController] it is handed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueryEditorContent(
    onBack: () -> Unit,
    viewModel: QueryEditorController,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current
    val context = LocalContext.current
    var favouriteDialogOpen by remember { mutableStateOf(false) }
    var exportMenuOpen by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var snippetsOpen by remember { mutableStateOf(false) }
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()
    val engineFeatures = LocalEngineFeatures.current
    // What the live engine calls things: its quote on the key bar, its keywords in the colouring.
    val syntax = remember(engineFeatures.engine) { SqlDialects.forEngine(engineFeatures.engine ?: DatabaseEngine.MYSQL) }
    val storedKeyBar by viewModel.keyBar.collectAsStateWithLifecycle()
    val keyBar = remember(storedKeyBar, syntax) { KeyBar.forEngine(storedKeyBar, syntax) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var selectedCell by remember { mutableStateOf<CellSelection?>(null) }
    var detailRow by remember { mutableStateOf<Int?>(null) }
    val editState by viewModel.resultEditing.state.collectAsStateWithLifecycle()
    var snapshotMenuOpen by remember { mutableStateOf(false) }
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val environment = ConnectionEnvironment.fromName(
        (sessionState as? SqlSessionState.Ready)?.connection?.environment,
    )

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
    //
    // The completion list's own state lives here too, because its keys (arrows, Enter, Tab, Esc)
    // are handled by this same handler: they are the list's only while it is on screen.
    var completionRow by remember { mutableIntStateOf(0) }
    var completionDismissed by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var editorFocused by remember { mutableStateOf(false) }
    val completionOpen = state.suggestions.isNotEmpty() && !state.editorCollapsed && editorFocused &&
        completionDismissed != (state.sql to state.selectionStart)
    LaunchedEffect(state.suggestions) { completionRow = 0 }
    val onKey: (KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown) return@handler false
        val shortcut = QueryShortcuts.of(
            KeyPress(
                key = event.key.shortcutName(),
                ctrl = event.isCtrlPressed,
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
            ),
            completionOpen = completionOpen,
        )
        when (shortcut) {
            QueryShortcut.COMPLETION_NEXT -> {
                completionRow = CompletionRanking.move(completionRow, 1, state.suggestions.size)
                true
            }

            QueryShortcut.COMPLETION_PREVIOUS -> {
                completionRow = CompletionRanking.move(completionRow, -1, state.suggestions.size)
                true
            }

            QueryShortcut.COMPLETION_ACCEPT -> {
                state.suggestions.getOrNull(completionRow)?.let(viewModel::complete)
                true
            }

            QueryShortcut.COMPLETION_CLOSE -> {
                completionDismissed = state.sql to state.selectionStart
                true
            }

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

            // Consumed even when there is nothing to take back, so the text field's own undo
            // never runs behind the editor's back and desynchronises the two.
            QueryShortcut.UNDO -> {
                if (state.canUndo) viewModel.undo()
                true
            }

            QueryShortcut.REDO -> {
                if (state.canRedo) viewModel.redo()
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
                // The connection, marked with its environment's colour, and under it the database
                // the statements run against — a tap away from being another one (§7.3).
                title = {
                    ConnectionTitle(
                        name = state.connectionName ?: stringResource(R.string.query_title),
                        environment = environment,
                        database = state.database,
                        databases = state.databases,
                        onSelectDatabase = viewModel::selectDatabase,
                        placeholder = stringResource(R.string.query_no_database),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    // Undo and redo stay one tap away; the rest is behind the overflow so the
                    // connection name stays readable.
                    IconButton(
                        onClick = viewModel::undo,
                        enabled = state.canUndo,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(R.string.editor_undo),
                        )
                    }
                    IconButton(
                        onClick = viewModel::redo,
                        enabled = state.canRedo,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = stringResource(R.string.editor_redo),
                        )
                    }
                    // Format, find and favourite live behind the overflow: five icons left the
                    // connection's name no room on a 390dp phone.
                    var overflowOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { overflowOpen = true }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.storage_more))
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.query_format)) },
                                leadingIcon = { Icon(Icons.Default.FormatAlignLeft, contentDescription = null) },
                                enabled = state.sql.isNotBlank(),
                                onClick = {
                                    overflowOpen = false
                                    viewModel.format()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.query_find)) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    findOpen = !findOpen
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.query_favourite_add)) },
                                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                                enabled = state.sql.isNotBlank(),
                                onClick = {
                                    overflowOpen = false
                                    favouriteDialogOpen = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val wide = isWideWindow()

        // Two columns where there is room for two: on a tablet the result no longer has to share
        // the height with the editor, and neither has to be scrolled to reach the other.
        val editorPane: @Composable ColumnScope.() -> Unit = {
                QueryTabBar(
                    tabs = state.tabs,
                    activeId = state.activeTabId,
                    onSelect = viewModel::selectTab,
                    onNew = viewModel::newTab,
                    onRename = { renameDialogOpen = true },
                    onDuplicate = { viewModel.duplicateTab(it) },
                    onClose = viewModel::requestCloseTab,
                )

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
                            // The whole selection, not only its start: an undo or a snippet puts a
                            // range back, and a caret would lose it.
                            selection = TextRange(
                                state.selectionStart.coerceIn(0, state.sql.length),
                                state.selectionEnd.coerceIn(0, state.sql.length),
                            ),
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
                    SqlEditorField(
                        value = field,
                        syntax = syntax,
                        onValueChange = { value ->
                            field = value
                            viewModel.onEditorChanged(value.text, value.selection.min, value.selection.max)
                        },
                        completion = if (completionOpen) state.suggestions else emptyList(),
                        completionRow = completionRow,
                        onCompletionPick = viewModel::complete,
                        onCompletionDismiss = { completionDismissed = state.sql to state.selectionStart },
                        onFocusChange = { editorFocused = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = if (wide) 420.dp else 300.dp),
                    )
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
                        items(keyBar, key = { it.id }) { item ->
                            val enabled = when (item.action) {
                                KeyAction.Undo -> state.canUndo
                                KeyAction.Redo -> state.canRedo
                                else -> true
                            }
                            Box(
                                modifier = Modifier
                                    .height(40.dp)
                                    .widthIn(min = 44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(semantic.surfaceRaised)
                                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = keyName(item)) {
                                        when (val action = item.action) {
                                            is KeyAction.Insert -> viewModel.insertKey(action.text)
                                            KeyAction.Undo -> viewModel.undo()
                                            KeyAction.Redo -> viewModel.redo()
                                            KeyAction.Snippets -> snippetsOpen = true
                                        }
                                    }
                                    .padding(horizontal = Spacing.m),
                                contentAlignment = Alignment.Center,
                            ) {
                                KeyFace(item, dimmed = !enabled)
                            }
                        }
                    }
                }

                if (!state.editorCollapsed) {
                    // As in the design: what will happen on the left, in small type; the one
                    // thing to press on the right, big enough for a thumb. The rarer actions
                    // share a menu instead of a row of buttons that would not fit a phone.
                    var runMenuOpen by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.query_row_limit, state.rowLimit),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = semantic.textSecondary,
                            )
                            Text(
                                text = stringResource(
                                    if (state.readOnly) R.string.db_read_only else R.string.query_run_hint,
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = semantic.textSecondary,
                            )
                        }
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
                            Box {
                                IconButton(onClick = { runMenuOpen = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.query_more_actions),
                                        tint = semantic.textSecondary,
                                    )
                                }
                                DropdownMenu(expanded = runMenuOpen, onDismissRequest = { runMenuOpen = false }) {
                                    // Hidden, not greyed out, where the engine has no plan to read.
                                    if (engineFeatures.has(EngineFeature.EXPLAIN)) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.query_explain)) },
                                            enabled = state.sql.isNotBlank() && state.connectionName != null,
                                            onClick = {
                                                runMenuOpen = false
                                                viewModel.explain()
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.editor_snippets)) },
                                        onClick = {
                                            runMenuOpen = false
                                            snippetsOpen = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.editor_share_query)) },
                                        enabled = state.sql.isNotBlank(),
                                        onClick = {
                                            runMenuOpen = false
                                            viewModel.shareQuery()
                                        },
                                    )
                                    if (state.isScript && !state.hasSelection) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.query_run_current)) },
                                            enabled = state.connectionName != null,
                                            onClick = {
                                                runMenuOpen = false
                                                viewModel.runCurrent()
                                            },
                                        )
                                    }
                                    // Only where writes are possible at all; on a read-only connection
                                    // there is nothing a transaction could hold back.
                                    if (!state.readOnly && !state.inTransaction) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.transaction_begin)) },
                                            enabled = state.connectionName != null,
                                            onClick = {
                                                runMenuOpen = false
                                                viewModel.setTransaction(true)
                                            },
                                        )
                                    }
                                }
                            }
                            Button(
                                onClick = { viewModel.run() },
                                enabled = state.sql.isNotBlank() && state.connectionName != null,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.height(52.dp),
                                contentPadding = PaddingValues(start = 18.dp, end = 22.dp),
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
                                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
                                    modifier = Modifier.padding(start = Spacing.s),
                                )
                            }
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

                if (snippetsOpen) {
                    val active = state.active
                    val selected = if (active.hasSelection) {
                        val start = active.selectionStart.coerceIn(0, active.sql.length)
                        active.sql.substring(start, active.selectionEnd.coerceIn(start, active.sql.length))
                    } else {
                        active.sql
                    }
                    SnippetSheet(
                        snippets = snippets,
                        selectedText = selected,
                        hasSelection = active.hasSelection,
                        onInsert = viewModel::insertSnippet,
                        onSave = viewModel::saveSnippet,
                        onDelete = viewModel::deleteSnippet,
                        onDismiss = { snippetsOpen = false },
                    )
                }
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
                            editState = editState,
                            onUndoEdit = viewModel.resultEditing::undo,
                            onRowLongPress = { detailRow = it },
                            onSort = viewModel::sortResult,
                            onFilterChange = viewModel::setResultFilter,
                            onChartSpec = viewModel::setChartSpec,
                            onToggleFilter = viewModel::toggleFilterBar,
                            onToggleChart = viewModel::toggleChart,
                            // The time machine and the export act on the rows, so they sit with the
                            // rows' own tools rather than in the screen's top bar.
                            extraActions = {
                            // The time machine (roadmap): freeze this result, and later hold the same
                            // query's answer up against it. A menu rather than a button because taking a
                            // snapshot and comparing with one are two different moments, and the second
                            // one only exists once the first has happened.
                            if (state.result != null || state.snapshot != null) {
                                Box {
                                    IconButton(onClick = { snapshotMenuOpen = true }) {
                                        Icon(
                                            Icons.Default.PhotoCamera,
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
                                IconButton(onClick = { exportMenuOpen = true }) {
                                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.export))
                                }
                                if (exportMenuOpen) {
                                    ExportSheet(
                                        rowCount = state.visibleResult?.rowCount ?: 0,
                                        onExport = viewModel::export,
                                        onDismiss = { exportMenuOpen = false },
                                    )
                                }
                            }
                            },
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

    // Editing needs a table and a key, which only some queries have (§7.6); the sheets, the
    // confirmation and the rest of it live with the edit code.
    ResultEditDialogs(
        controller = viewModel.resultEditing,
        state = editState,
        rows = state.visibleResult,
        selectedCell = selectedCell,
        onSelectedCellChange = { selectedCell = it },
        detailRow = detailRow,
        onDetailRowChange = { detailRow = it },
        onCopy = { context.copyToClipboard(it) },
    )

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
        BasicAlertDialog(onDismissRequest = viewModel::dismissCloseTab) {
            TabCloseCard(
                firstLine = tab.sql.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
                onConfirm = { viewModel.closeTab(tab.id) },
                onDismiss = viewModel::dismissCloseTab,
            )
        }
    }

    if (state.tabLimitReached) {
        BasicAlertDialog(onDismissRequest = viewModel::dismissTabLimit) {
            NoticeCard(
                title = stringResource(R.string.query_tab_limit_title),
                body = stringResource(R.string.query_tab_limit_body, QueryTabs.MAX_TABS),
                closeLabel = stringResource(R.string.cancel),
                onClose = viewModel::dismissTabLimit,
            )
        }
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
        SnapshotSheet(
            outcome = outcome,
            onDismiss = viewModel::dismissComparison,
            onPickKey = viewModel::compareWithSnapshotByKey,
        )
    }

    state.snapshotNotice?.let { notice ->
        BasicAlertDialog(onDismissRequest = viewModel::dismissSnapshotNotice) {
            NoticeCard(
                title = stringResource(R.string.snapshot_notice_title),
                body = snapshotNoticeText(notice),
                closeLabel = stringResource(R.string.snapshot_close),
                onClose = viewModel::dismissSnapshotNotice,
            )
        }
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
    editState: ResultEditUiState,
    onUndoEdit: () -> Unit,
    onRowLongPress: (Int) -> Unit,
    onSort: (String) -> Unit,
    onFilterChange: (ResultFilter) -> Unit,
    onChartSpec: (ChartSpec) -> Unit,
    onToggleFilter: () -> Unit,
    onToggleChart: () -> Unit,
    extraActions: @Composable () -> Unit = {},
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
                extraActions()
            }

            ResultEditStrip(editState, onUndo = onUndoEdit)

            // A snapshot leaves no mark on the result it was taken from, and an unmarked
            // snapshot is one that gets compared against by accident an hour later. When it
            // was taken, and how much of it there is, is the whole of what has to be said here.
            state.snapshot?.let { snapshot ->
                val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
                val time = SnapshotLabels.clock(snapshot.takenAt, locale)
                // A snapshot from another connection or database says so: dev and production
                // results look alike, and the line is the only place that tells them apart.
                val from = SnapshotLabels.originNote(snapshot.origin, state.connectionId, state.database)
                Text(
                    text = if (from == null) {
                        stringResource(R.string.snapshot_state, time, snapshot.rowCount)
                    } else {
                        stringResource(R.string.snapshot_state_from, time, snapshot.rowCount, from)
                    },
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
                    onRowLongPress = onRowLongPress,
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
    val locale = appLocale()
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
                    text = "${LocaleFormat.dateTime(entry.executedAt, locale)} · " +
                        "${LocaleFormat.integer(entry.durationMs.toLong(), locale)} ms · ${LocaleFormat.integer(entry.rowCount.toLong(), locale)}",
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParameterDialog(
    names: List<String>,
    onRun: (Map<String, ParameterValue>) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember(names) { mutableStateMapOf<String, ParameterValue>() }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        ParameterCard(
            names = names,
            values = values,
            onValue = { name, value -> values[name] = value },
            onRun = { onRun(names.associateWith { values[it] ?: ParameterValue() }) },
            onDismiss = onDismiss,
        )
    }
}

/** The parameter form's card, apart from its window, so a screenshot can draw it. */
@Composable
fun ParameterCard(
    names: List<String>,
    values: Map<String, ParameterValue>,
    onValue: (String, ParameterValue) -> Unit,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogCard {
        DialogHeading(title = stringResource(R.string.query_parameters_title))
        Column(
            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            names.forEach { name ->
                val value = values[name] ?: ParameterValue()
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    OutlinedTextField(
                        value = value.text,
                        onValueChange = { onValue(name, value.copy(text = it)) },
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
                                onClick = { onValue(name, value.copy(type = type)) },
                                label = { Text(stringResource(type.labelRes())) },
                            )
                        }
                    }
                }
            }
        }
        DialogButtons(
            cancelLabel = stringResource(R.string.cancel),
            onCancel = onDismiss,
            actionLabel = stringResource(R.string.query_run),
            onAction = onRun,
            enabled = true,
        )
    }
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WriteConfirmDialog(
    confirmation: WriteConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember(confirmation) { mutableStateOf("") }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        WriteConfirmCard(
            confirmation = confirmation,
            typed = typed,
            onTyped = { typed = it },
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/** The dialog's card, apart from its window, so a screenshot can draw it. */
@Composable
fun WriteConfirmCard(
    confirmation: WriteConfirmation,
    typed: String,
    onTyped: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    val blocked = confirmation.exceedsLimit
    val production = confirmation.environment.isProduction
    DialogCard(danger = production) {
        DialogHeading(
            title = stringResource(R.string.write_confirm_title),
            // Where it runs: the connection, its environment and the database, because on a phone
            // the three of them are a tab away and nothing else on screen repeats them.
            subtitle = listOfNotNull(
                confirmation.connectionName,
                stringResource(confirmation.environment.shortLabel()).uppercase(),
                confirmation.database,
            ).joinToString(" · "),
            danger = production,
        )
        SqlBlock(confirmation.sql)
        Text(
            text = confirmation.estimatedRows
                ?.let { stringResource(R.string.write_confirm_rows, it) }
                ?: stringResource(R.string.write_confirm_rows_unknown),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = if (blocked) MaterialTheme.colorScheme.error else semantic.textSecondary,
        )
        confirmation.previews.forEach { preview ->
            WriteRowPreviewTable(
                preview = preview,
                statementNumber = (preview.statementIndex + 1).takeIf { confirmation.statements.size > 1 },
            )
        }
        if (blocked) {
            Text(
                text = stringResource(R.string.write_confirm_over_limit, confirmation.maxAffectedRows),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.error,
            )
        } else if (confirmation.requiresTypedDatabase) {
            LabeledField(
                value = typed,
                onValueChange = onTyped,
                label = { Text(stringResource(R.string.write_confirm_type_database, confirmation.database.orEmpty())) },
                mono = true,
            )
        }
        DialogButtons(
            cancelLabel = stringResource(R.string.cancel),
            onCancel = onDismiss,
            actionLabel = stringResource(R.string.write_confirm_run),
            onAction = onConfirm,
            enabled = !blocked && confirmation.confirms(typed),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavouriteNameDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        NameCard(
            title = stringResource(R.string.query_favourite_add),
            label = stringResource(R.string.query_favourite_name),
            name = name,
            onName = { name = it },
            onSave = { onSave(name) },
            onDismiss = onDismiss,
        )
    }
}

/** A dialog that asks for one name (a favourite, a tab), apart from its window for screenshots. */
@Composable
fun NameCard(
    title: String,
    label: String,
    name: String,
    onName: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogCard {
        DialogHeading(title = title)
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DialogButtons(
            cancelLabel = stringResource(R.string.cancel),
            onCancel = onDismiss,
            actionLabel = stringResource(R.string.connection_save),
            onAction = onSave,
            enabled = true,
        )
    }
}

/** Closing a tab throws its text away, so the card shows which tab it is, by its first line. */
@Composable
fun TabCloseCard(firstLine: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DialogCard(danger = true) {
        DialogHeading(title = stringResource(R.string.query_tab_close_title), danger = true)
        Text(stringResource(R.string.query_tab_close_body), style = MaterialTheme.typography.bodyMedium)
        if (firstLine.isNotEmpty()) SqlBlock(firstLine)
        DialogButtons(
            cancelLabel = stringResource(R.string.cancel),
            onCancel = onDismiss,
            actionLabel = stringResource(R.string.query_tab_close_confirm),
            onAction = onConfirm,
            enabled = true,
            danger = true,
        )
    }
}

/** A message that is only acknowledged: a title, a few sentences and one button. */
@Composable
fun NoticeCard(title: String, body: String, closeLabel: String, onClose: () -> Unit) {
    DialogCard {
        DialogHeading(title = title)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        DialogButtons(
            cancelLabel = "",
            onCancel = {},
            actionLabel = closeLabel,
            onAction = onClose,
            enabled = true,
            showCancel = false,
        )
    }
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
    Key.Z -> "Z"
    Key.Y -> "Y"
    Key.Escape -> "ESCAPE"
    Key.DirectionDown -> "ARROWDOWN"
    Key.DirectionUp -> "ARROWUP"
    Key.Tab -> "TAB"
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
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onRename: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
    onClose: (Long) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    var menuOpen by remember { mutableStateOf(false) }

    // One strip of tabs on every width, as in the design: each open query is visible and one tap
    // away, the way tabs are in any editor. The active one is lifted onto a surface of its own.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    semantic.hairline,
                    topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = size.copy(height = 1.dp.toPx()),
                )
            }
            .padding(start = Spacing.m, end = Spacing.xs, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                val active = tab.id == activeId
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .border(
                            1.dp,
                            if (active) MaterialTheme.colorScheme.outline else Color.Transparent,
                            RoundedCornerShape(10.dp),
                        )
                        .selectable(selected = active, role = Role.Tab) { onSelect(tab.id) }
                        .padding(start = Spacing.m, end = if (tabs.size > 1) Spacing.xs else Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The dot is the whole mark: a tab whose text is not a favourite yet.
                    if (tab.unsaved) {
                        Box(Modifier.size(6.dp).background(semantic.warning, CircleShape))
                    }
                    Text(
                        text = tabLabel(tab, index),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = if (active) MaterialTheme.colorScheme.onSurface else semantic.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    if (tabs.size > 1) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) { onClose(tab.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.query_tab_close),
                                tint = semantic.textSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabNameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        NameCard(
            title = stringResource(R.string.query_tab_rename),
            label = stringResource(R.string.query_tab_rename_label),
            name = name,
            onName = { name = it },
            onSave = { onSave(name) },
            onDismiss = onDismiss,
        )
    }
}

/**
 * The SQL editor as the design draws it: no box around it, a gutter of line numbers, and lines
 * that run on to the right instead of wrapping, so a line number always means one line of SQL.
 * Gutter and text share one vertical scroll and one line height, which keeps them aligned.
 */
@Composable
private fun SqlEditorField(
    value: TextFieldValue,
    syntax: SqlSyntax,
    onValueChange: (TextFieldValue) -> Unit,
    completion: List<CompletionItem>,
    completionRow: Int,
    onCompletionPick: (CompletionItem) -> Unit,
    onCompletionDismiss: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    // Window coordinates of the editor and of the text inside it, plus the last text layout: the
    // cursor's rectangle is in the layout's own coordinates and needs both to become a position.
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fieldOrigin by remember { mutableStateOf(Offset.Zero) }
    var editorRect by remember { mutableStateOf<Rect?>(null) }
    val windowWidth = LocalContext.current.resources.displayMetrics.widthPixels
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    // Scrolling the editor moves the line the list hangs from, so the list gives way instead of
    // trailing behind; the next keystroke brings it back.
    val dismiss by rememberUpdatedState(onCompletionDismiss)
    LaunchedEffect(verticalScroll, horizontalScroll) {
        snapshotFlow { verticalScroll.isScrollInProgress || horizontalScroll.isScrollInProgress }
            .collect { if (it) dismiss() }
    }
    val textStyle = MonoStyles.editor.copy(
        fontSize = 14.sp,
        lineHeight = 23.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val lines = value.text.count { it == '\n' } + 1
    BoxWithConstraints(modifier = modifier.onGloballyPositioned { editorRect = it.boundsInWindow() }) {
        val width = maxWidth
        Row(modifier = Modifier.fillMaxWidth().verticalScroll(verticalScroll)) {
            Column(
                modifier = Modifier
                    .width(40.dp)
                    .padding(top = Spacing.m, bottom = Spacing.m, end = Spacing.m)
                    .clearAndSetSemantics { },
                horizontalAlignment = Alignment.End,
            ) {
                // One text of numbers, one per line, laid out by the same rules as the field:
                // separate texts would each round their height and drift out of step.
                BasicText(
                    (1..lines).joinToString("\n"),
                    style = textStyle.copy(
                        color = semantic.textSecondary.copy(alpha = 0.7f),
                        textAlign = TextAlign.End,
                    ),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                onTextLayout = { layout = it },
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = SqlVisualTransformation(
                    plain = MaterialTheme.colorScheme.onSurface,
                    keyword = MaterialTheme.colorScheme.primary,
                    string = semantic.success,
                    number = semantic.cellNumber,
                    comment = semantic.cellNull,
                    identifier = semantic.cellDate,
                    parameter = semantic.warning,
                    syntax = syntax,
                ),
                modifier = Modifier
                    .horizontalScroll(horizontalScroll)
                    // At least as wide as the room left of the gutter, so a tap anywhere on an
                    // empty line still lands in the field.
                    .widthIn(min = width - 40.dp)
                    .padding(top = Spacing.m, bottom = Spacing.m, end = Spacing.m)
                    .onFocusChanged { onFocusChange(it.isFocused) }
                    .onGloballyPositioned { fieldOrigin = it.positionInWindow() },
            )
        }
        val rect = editorRect
        val textLayout = layout
        if (completion.isNotEmpty() && rect != null && textLayout != null && value.text.length == textLayout.layoutInput.text.length) {
            val caret = textLayout.getCursorRect(value.selection.start.coerceIn(0, value.text.length))
            val top = fieldOrigin.y + caret.top
            // A cursor scrolled out of the editor has no line to hang the list from.
            if (top >= rect.top && fieldOrigin.y + caret.bottom <= rect.bottom) {
                CompletionPopup(
                    items = completion,
                    selected = completionRow,
                    prefix = completionPrefix(value),
                    anchor = CompletionAnchor(
                        cursorX = (fieldOrigin.x + caret.left).roundToInt(),
                        cursorTop = top.roundToInt(),
                        cursorBottom = (fieldOrigin.y + caret.bottom).roundToInt(),
                        editorBottom = rect.bottom.roundToInt(),
                        windowWidth = windowWidth,
                    ),
                    onPick = onCompletionPick,
                )
            }
        }
    }
}

/** The word the cursor is touching, for the bold part of each row. */
private fun completionPrefix(value: TextFieldValue): String {
    val at = value.selection.start.coerceIn(0, value.text.length)
    var start = at
    while (start > 0 && (value.text[start - 1].isLetterOrDigit() || value.text[start - 1] == '_')) start--
    return value.text.substring(start, at)
}
