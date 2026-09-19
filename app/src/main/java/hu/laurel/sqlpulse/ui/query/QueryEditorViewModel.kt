package hu.laurel.sqlpulse.ui.query

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.db.SavedQueryEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.export.ExportManager
import hu.laurel.sqlpulse.data.query.DraftBook
import hu.laurel.sqlpulse.data.query.QueryDraft
import hu.laurel.sqlpulse.data.query.QueryDraftStore
import hu.laurel.sqlpulse.data.query.QueryRepository
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.data.snapshot.ComparisonOutcome
import hu.laurel.sqlpulse.data.snapshot.ResultDiffs
import hu.laurel.sqlpulse.data.snapshot.ResultSnapshot
import hu.laurel.sqlpulse.data.snapshot.ResultSnapshots
import hu.laurel.sqlpulse.data.snapshot.SnapshotOutcome
import hu.laurel.sqlpulse.data.sql.AffectedRowLimit
import hu.laurel.sqlpulse.data.chart.ChartSpec
import hu.laurel.sqlpulse.data.grid.ResultFilter
import hu.laurel.sqlpulse.data.grid.ResultFilters
import hu.laurel.sqlpulse.data.sql.ColumnSort
import hu.laurel.sqlpulse.data.sql.ExplainJson
import hu.laurel.sqlpulse.data.sql.ParameterValue
import hu.laurel.sqlpulse.data.sql.QueryExecutor
import hu.laurel.sqlpulse.data.sql.ReadOnlyConnectionException
import hu.laurel.sqlpulse.data.sql.WritesLockedException
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlFailures
import hu.laurel.sqlpulse.data.sql.SqlFormatter
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.SqlScript
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.TableQuery
import hu.laurel.sqlpulse.data.sql.UnguardedWriteException
import hu.laurel.sqlpulse.data.sql.UnsupportedStatementException
import hu.laurel.sqlpulse.ui.explain
import java.sql.SQLException
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the dialog in front of a hand-typed write shows, and what it demands before it lets go.
 *
 * It is built before anything is sent: the statements themselves, how many rows they are estimated
 * to change, and which database is about to be changed. The three facts together are what makes
 * "I meant the other connection" visible while it is still a thought.
 */
data class WriteConfirmation(
    /** The write statements of the run, in order, as they were typed. */
    val statements: List<String>,
    /** Null when no count could be derived or the count itself failed: the dialog then says so. */
    val estimatedRows: Long?,
    val connectionName: String?,
    val environment: ConnectionEnvironment,
    val database: String?,
    /** The ceiling from settings; 0 when it is turned off. */
    val maxAffectedRows: Int,
) {
    val sql: String get() = statements.joinToString(";\n")

    /**
     * Production asks for the database name to be typed out. A tap is something a thumb does by
     * itself; typing the name is not.
     */
    val requiresTypedDatabase: Boolean get() = environment.isProduction && !database.isNullOrBlank()

    val exceedsLimit: Boolean get() = AffectedRowLimit.exceeds(estimatedRows, maxAffectedRows)

    /** True when the typed confirmation matches, or when none was asked for. */
    fun confirms(typed: String): Boolean =
        !requiresTypedDatabase || typed.trim() == database
}

/**
 * What taking a snapshot has to say for itself.
 *
 * Taking one is a single tap that produces no visible change on screen, so it always answers:
 * how many rows it kept, and — this is the part that matters — whether that is all of them. A
 * comparison of the first two thousand rows of a result is a useful thing; a comparison of the
 * first two thousand rows that the user believes covers forty thousand is not.
 */
sealed interface SnapshotNotice {
    data class Taken(
        val rows: Int,
        /** The row count of the result when it did not fit, else null. */
        val trimmedFrom: Int?,
        /** True when the query itself had already stopped at the row limit. */
        val sourceTruncated: Boolean,
    ) : SnapshotNotice

    data class TooWide(val columnCount: Int, val maxColumns: Int) : SnapshotNotice

    data object NoResult : SnapshotNotice
}

/**
 * The screen's state: the open tabs, and the few things that belong to the session rather than to
 * any one tab — the connection, the transaction, and the one query that may be running.
 *
 * Everything the editor itself shows is read off the active tab, so the screen can go on asking
 * for `state.sql` without knowing that there is more than one of them.
 */
data class QueryEditorUiState(
    val tabs: List<QueryTab> = listOf(QueryTab(id = FIRST_TAB_ID)),
    val activeTabId: Long = FIRST_TAB_ID,
    val running: Boolean = false,
    val rowLimit: Int = SqlGuards.DEFAULT_ROW_LIMIT,
    val databases: List<String> = emptyList(),
    val readOnly: Boolean = true,
    val connectionName: String? = null,
    /** True while a manual transaction is open: nothing is written until it is committed. */
    val inTransaction: Boolean = false,
    val shareIntent: Intent? = null,
    /** Set while a hand-typed write waits to be confirmed (§7.4). */
    val writeConfirmation: WriteConfirmation? = null,
    /** Set when opening a tab was refused because [QueryTabs.MAX_TABS] is already open. */
    val tabLimitReached: Boolean = false,
    /** The tab a close is waiting to be confirmed for, because its text is not saved anywhere. */
    val closingTab: Long? = null,
    /**
     * The time machine's memory, one snapshot per tab.
     *
     * It lives in the state rather than in the tab because the tab is [QueryTabs]' data and stays
     * that way; and it lives in the view model rather than on the screen because leaving the
     * screen and coming back must not throw away a snapshot that was taken to be compared with
     * something that has not happened yet. A snapshot dies with its tab and with the process —
     * nothing about a result is ever written to disk (§9).
     */
    val snapshots: Map<Long, ResultSnapshot> = emptyMap(),
    /** Set while the comparison sheet is up. */
    val comparison: ComparisonOutcome? = null,
    /** Set right after a snapshot was taken, or refused. */
    val snapshotNotice: SnapshotNotice? = null,
) {
    val active: QueryTab get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()

    val sql: String get() = active.sql
    val panel: QueryPanel get() = active.panel
    val result: ResultTable? get() = active.result
    val updateCount: Int? get() = active.updateCount
    val error: String? get() = active.error
    val errorDetail: String? get() = active.errorDetail
    val pendingParameters: List<String> get() = active.pendingParameters
    val suggestions: List<String> get() = active.suggestions
    val database: String? get() = active.database
    val switchedTo: String? get() = active.switchedTo
    val resultSort: ColumnSort? get() = active.resultSort
    val resultFilter: ResultFilter get() = active.resultFilter
    val filterOpen: Boolean get() = active.filterOpen
    val showChart: Boolean get() = active.showChart
    val chartSpec: ChartSpec? get() = active.chartSpec

    /**
     * The rows the screen actually shows: the result, narrowed by this tab's filter.
     *
     * Everything the user acts on — what is drawn, exported, or kept as a snapshot — comes from
     * here, so a filtered screen cannot quietly export or compare rows that are not on it.
     */
    val visibleResult: ResultTable? get() = active.result?.let {
        ResultFilters.apply(it, active.resultFilter)
    }
    val statements: List<StatementRun> get() = active.statements
    val selectedStatement: Int get() = active.selectedStatement
    val selectionStart: Int get() = active.selectionStart
    val selectionEnd: Int get() = active.selectionEnd
    val editorCollapsed: Boolean get() = active.editorCollapsed
    val isScript: Boolean get() = active.isScript
    val hasSelection: Boolean get() = active.hasSelection

    /** The active tab's snapshot, if it has one. */
    val snapshot: ResultSnapshot? get() = snapshots[activeTabId]

    /** The tab a close confirmation is about, if one is open. */
    val closing: QueryTab? get() = closingTab?.let { id -> tabs.firstOrNull { it.id == id } }
}

/** The id of the tab the screen opens with, before any draft has been restored. */
const val FIRST_TAB_ID = 1L

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class QueryEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: QueryExecutor,
    private val exports: ExportManager,
    private val queries: QueryRepository,
    private val drafts: QueryDraftStore,
    private val schema: SchemaRepository,
    private val sessions: SqlSessionManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QueryEditorUiState())
    val uiState: StateFlow<QueryEditorUiState> = _uiState.asStateFlow()

    private val connectionId = MutableStateFlow(0L)
    private var runJob: Job? = null
    private var completionJob: Job? = null

    /** The run held back by the confirmation dialog, kept whole so confirming replays it exactly. */
    private var pendingRun: PendingRun? = null

    /** Ids are minted here and nowhere else, so [QueryTabs] stays a pure function of its input. */
    private var nextTabId = FIRST_TAB_ID + 1

    /**
     * Table names of the current database, and nothing else.
     *
     * This is the whole of the up-front completion cost: one `information_schema.TABLES` query,
     * which the schema repository already caches for the session. Columns are never fetched here.
     */
    private var tableNames: List<String> = emptyList()
    private var tableDatabase: String? = null

    /** Poked on every keystroke; the collector below is what actually writes, and only rarely. */
    private val draftChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var lastWritten: DraftBook? = null
    private var draftsRestored = false

    val history: StateFlow<List<QueryHistoryEntity>> = connectionId
        .flatMapLatest { id -> if (id == 0L) flowOf(emptyList()) else queries.observeHistory(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favourites: StateFlow<List<SavedQueryEntity>> = connectionId
        .flatMapLatest { id -> if (id == 0L) flowOf(emptyList()) else queries.observeFavourites(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        restoreDrafts()

        // The draft is written after the typing stops, not during it: a keystroke costs nothing
        // here, and the file is rewritten whole. Half a second is short enough that the only way
        // to lose anything is to be killed mid-word.
        draftChanged
            .debounce(DRAFT_DEBOUNCE_MS)
            .onEach { writeDrafts() }
            .launchIn(viewModelScope)

        sessions.state
            .onEach { state ->
                when (state) {
                    is SqlSessionState.Ready -> {
                        connectionId.value = state.connection.id
                        _uiState.value = _uiState.value.copy(
                            readOnly = state.connection.readOnly,
                            connectionName = state.connection.name,
                        )
                        loadDatabases()
                    }

                    else -> {
                        connectionId.value = 0L
                        tableNames = emptyList()
                        tableDatabase = null
                        _uiState.value = _uiState.value.copy(connectionName = null)
                    }
                }
            }
            .launchIn(viewModelScope)

        // The current database is owned by the session, so the picker follows a USE or a change
        // made in the schema browser. It lands on the active tab: another tab's database is what
        // it was, and is set again when that tab comes forward.
        sessions.database
            .onEach { database ->
                updateActive { it.copy(database = database) }
                loadTableNames(database)
            }
            .launchIn(viewModelScope)

        // The transaction belongs to the session, not to this screen: it survives navigating away
        // and dies with the connection, and the bar has to say so either way.
        sessions.inTransaction
            .onEach { open -> _uiState.value = _uiState.value.copy(inTransaction = open) }
            .launchIn(viewModelScope)
    }

    /** The session itself, for the bar that appears when the network moves under it. */
    val sessionState: StateFlow<SqlSessionState> = sessions.state

    /** Asked for by the bar's button; the automatic path is the session manager's own. */
    fun reconnect() = sessions.reconnect()

    // --- Tabs -------------------------------------------------------------------------------

    fun newTab() {
        val state = _uiState.value
        val id = nextTabId
        val opened = QueryTabs.open(state.tabs, id, database = state.database)
        if (opened == null) {
            _uiState.value = state.copy(tabLimitReached = true)
            return
        }
        nextTabId++
        _uiState.value = state.copy(tabs = opened, activeTabId = id, tabLimitReached = false)
        noteDraftChange()
    }

    fun duplicateTab(id: Long) {
        val state = _uiState.value
        val newId = nextTabId
        val tabs = QueryTabs.duplicate(state.tabs, id, newId)
        if (tabs == null) {
            _uiState.value = state.copy(tabLimitReached = true)
            return
        }
        nextTabId++
        _uiState.value = state.copy(tabs = tabs, activeTabId = newId, tabLimitReached = false)
        noteDraftChange()
    }

    fun renameTab(id: Long, title: String) {
        _uiState.value = _uiState.value.copy(tabs = QueryTabs.rename(_uiState.value.tabs, id, title))
        noteDraftChange()
    }

    /**
     * Asks first when the tab holds text that is not saved as a favourite.
     *
     * A tab is closed with a small control next to a label, which is exactly the kind of target a
     * thumb hits by accident; the query in it may be twenty minutes of work.
     */
    fun requestCloseTab(id: Long) {
        val tab = _uiState.value.tabs.firstOrNull { it.id == id } ?: return
        if (tab.unsaved) {
            _uiState.value = _uiState.value.copy(closingTab = id)
        } else {
            closeTab(id)
        }
    }

    fun closeTab(id: Long) {
        val state = _uiState.value
        val active = QueryTabs.activeAfterClose(state.tabs, id, state.activeTabId)
        _uiState.value = state.copy(
            tabs = QueryTabs.close(state.tabs, id),
            activeTabId = active,
            closingTab = null,
            tabLimitReached = false,
            // The snapshot belonged to that tab's query; keeping it for whatever tab reuses the
            // id later would offer a comparison between two unrelated questions.
            snapshots = state.snapshots - id,
        )
        noteDraftChange()
    }

    fun dismissCloseTab() {
        _uiState.value = _uiState.value.copy(closingTab = null)
    }

    fun dismissTabLimit() {
        _uiState.value = _uiState.value.copy(tabLimitReached = false)
    }

    /**
     * Brings a tab forward, and points the session at the database that tab was working in.
     *
     * The connection has one current database, so a tab's database is a wish that is granted when
     * the tab is looked at. Without this, switching tabs would run the next query somewhere else.
     */
    fun selectTab(id: Long) {
        val state = _uiState.value
        if (state.tabs.none { it.id == id }) return
        _uiState.value = state.copy(activeTabId = id, tabLimitReached = false)
        val database = state.tabs.first { it.id == id }.database
        if (database != null && database != sessions.database.value) sessions.selectDatabase(database)
        refreshSuggestions()
        noteDraftChange()
    }

    // --- Editing ----------------------------------------------------------------------------

    fun selectDatabase(database: String) {
        updateActive { it.copy(database = database) }
        sessions.selectDatabase(database)
        noteDraftChange()
    }

    fun setSelection(start: Int, end: Int) {
        updateActive { it.copy(selectionStart = start, selectionEnd = end) }
    }

    fun selectStatement(index: Int) {
        val run = _uiState.value.active.statements.getOrNull(index) ?: return
        updateActive {
            it.copy(
                selectedStatement = index,
                result = run.table,
                updateCount = run.updateCount,
                switchedTo = run.switchedTo,
                error = run.error,
                resultSort = null,
            )
        }
    }

    /** Runs only the statement the cursor is in, leaving the rest of the script alone. */
    fun runCurrent(parameters: Map<String, ParameterValue> = emptyMap()) {
        val tab = _uiState.value.active
        val statement = SqlScript.statementAt(tab.sql, tab.selectionStart) ?: return
        execute(listOf(statement.sql), parameters)
    }

    fun setSql(sql: String) {
        updateActive { it.copy(sql = sql, error = null) }
        refreshSuggestions()
        noteDraftChange()
    }

    /** One call for what a keystroke changes: the text, where the cursor landed, and the hints. */
    fun onEditorChanged(sql: String, selectionStart: Int, selectionEnd: Int) {
        updateActive {
            it.copy(
                sql = sql,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                error = null,
            )
        }
        refreshSuggestions()
        noteDraftChange()
    }

    /**
     * Opens a manual transaction, or closes the open one.
     *
     * Turning it off commits: switching away from "I will decide when this is written" means the
     * work was meant to happen. Discarding is the explicit [rollback].
     */
    fun setTransaction(open: Boolean) {
        viewModelScope.launch {
            try {
                if (open) sessions.beginTransaction() else sessions.commit()
            } catch (e: Exception) {
                updateActive { it.copy(error = describe(e)) }
            }
        }
    }

    fun rollback() {
        viewModelScope.launch {
            try {
                sessions.rollback()
            } catch (e: Exception) {
                updateActive { it.copy(error = describe(e)) }
            }
        }
    }

    fun toggleEditor() {
        updateActive { it.copy(editorCollapsed = !it.editorCollapsed) }
    }

    /** Inserts text at the cursor, which is where the user is looking. */
    fun append(text: String) {
        val tab = _uiState.value.active
        val at = tab.selectionStart.coerceIn(0, tab.sql.length)
        val before = tab.sql.take(at)
        val separator = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
        replaceRange(at, at, separator + text)
    }

    /**
     * Completes the word the cursor sits in.
     *
     * The half-typed word is replaced, not added to: tapping "HIVASOK" after typing "hiv" has to
     * give `FROM HIVASOK`, never `FROM hiv HIVASOK`. Where the word starts is the completion's own
     * answer, so a qualified `a.col` replaces only the part after the dot.
     */
    fun complete(suggestion: String) {
        val tab = _uiState.value.active
        val at = tab.selectionStart.coerceIn(0, tab.sql.length)
        val start = SqlCompletion.contextAt(tab.sql, at).prefixStart.coerceIn(0, at)
        replaceRange(start, at, suggestion)
    }

    /** Replaces a range of the editor's text and leaves the cursor after what was inserted. */
    private fun replaceRange(start: Int, end: Int, text: String) {
        val caret = start + text.length
        updateActive {
            it.copy(
                sql = it.sql.substring(0, start) + text + it.sql.substring(end),
                selectionStart = caret,
                selectionEnd = caret,
                suggestions = emptyList(),
                error = null,
            )
        }
        noteDraftChange()
    }

    /**
     * Lays the editor's text out over several lines.
     *
     * The selection is formatted alone when there is one, so one statement of a long script can be
     * tidied without disturbing the rest.
     */
    fun format() {
        val tab = _uiState.value.active
        if (tab.sql.isBlank()) return
        if (tab.hasSelection) {
            val start = tab.selectionStart.coerceIn(0, tab.sql.length)
            val end = tab.selectionEnd.coerceIn(start, tab.sql.length)
            val formatted = SqlFormatter.format(tab.sql.substring(start, end))
            setSql(tab.sql.substring(0, start) + formatted + tab.sql.substring(end))
        } else {
            setSql(SqlScript.split(tab.sql).joinToString(";\n\n") { SqlFormatter.format(it.sql) } + ";")
        }
    }

    /** Replaces every occurrence of [find] in the editor. Case-insensitive, like SQL itself. */
    fun replaceAll(find: String, replacement: String) {
        if (find.isEmpty()) return
        setSql(_uiState.value.active.sql.replace(find, replacement, ignoreCase = true))
    }

    fun selectPanel(panel: QueryPanel) {
        updateActive { it.copy(panel = panel) }
    }

    fun setRowLimit(limit: Int) {
        _uiState.value = _uiState.value.copy(rowLimit = limit.coerceIn(1, MAX_ROW_LIMIT))
    }

    // --- Completion -------------------------------------------------------------------------

    /**
     * Rebuilds the suggestion chips for wherever the cursor now is.
     *
     * The strategy, in full: table names are loaded once per database and nothing else is loaded
     * in advance, so the size of the schema no longer decides what completion costs. Columns are
     * asked for one table at a time, only for the tables the statement under the cursor actually
     * names, and only when the cursor is somewhere a column could go — and the schema repository
     * caches each answer for the session, so the second keystroke in a WHERE clause is free. A
     * schema with two thousand tables therefore costs exactly the same as one with twenty, and the
     * old 25-table ceiling is gone rather than raised.
     */
    private fun refreshSuggestions() {
        val tab = _uiState.value.active
        val id = tab.id
        val context = SqlCompletion.contextAt(tab.sql, tab.selectionStart)
        completionJob?.cancel()

        // Nothing at all inside a string or a comment: whatever is being written there is prose,
        // a pattern or a note, and a list of table names over it is only in the way.
        if (context.suppressed) {
            updateTab(id) { it.copy(suggestions = emptyList()) }
            return
        }

        val database = tab.database
        completionJob = viewModelScope.launch {
            val candidates = mutableListOf<String>()
            if (database != null && context.columnTables.isNotEmpty()) {
                candidates += columnsOf(database, context.columnTables)
            }
            if (context.wantTables) candidates += tableNames
            candidates += context.keywords

            val suggestions = candidates
                .asSequence()
                .filter { context.prefix.isEmpty() || it.startsWith(context.prefix, ignoreCase = true) }
                // A suggestion identical to what is already typed completes nothing.
                .filterNot { it.equals(context.prefix, ignoreCase = true) }
                .distinct()
                .take(MAX_SUGGESTIONS)
                .toList()
            updateTab(id) { it.copy(suggestions = suggestions) }
        }
    }

    /** Columns of the named tables, one cached lookup each; a table that cannot be read adds none. */
    private suspend fun columnsOf(database: String, tables: List<String>): List<String> =
        tables.take(COLUMN_TABLES_PER_STATEMENT).flatMap { table ->
            runCatching { schema.structure(database, table).columns.map { it.name } }
                .getOrDefault(emptyList())
        }

    private fun loadTableNames(database: String?) {
        if (database == null) {
            tableNames = emptyList()
            tableDatabase = null
            return
        }
        if (database == tableDatabase) return
        tableDatabase = database
        viewModelScope.launch {
            tableNames = runCatching { schema.tables(database).map { it.name } }
                .getOrDefault(emptyList())
            refreshSuggestions()
        }
    }

    // --- Running ----------------------------------------------------------------------------

    /**
     * Runs what the editor holds: the selection if there is one, otherwise every statement in it.
     * A query with `:parameters` asks for their values first, rather than sending an empty string
     * to the server.
     */
    fun run(parameters: Map<String, ParameterValue> = emptyMap()) {
        val tab = _uiState.value.active
        // A selection means "run this much of it", which is how a long script is worked through.
        val selected = tab.sql.substring(
            tab.selectionStart.coerceIn(0, tab.sql.length),
            tab.selectionEnd.coerceIn(0, tab.sql.length),
        )
        val text = selected.takeIf { it.isNotBlank() } ?: tab.sql
        execute(SqlScript.split(text).map { it.sql }, parameters)
    }

    /**
     * Runs the statements one after another, stopping at the first failure.
     *
     * Stopping is the safe default: the statements of a script usually depend on each other, and
     * carrying on after an error would apply the rest to a state nobody planned for. What ran
     * before the failure stays on screen, with its results.
     *
     * The results land on the tab the run started from, by id — a run takes seconds, and the user
     * is free to move to another tab while it happens.
     */
    private fun execute(
        statements: List<String>,
        parameters: Map<String, ParameterValue>,
        confirmed: Boolean = false,
        /**
         * A second wording to try when the first one is refused, or null to let the failure stand.
         *
         * It exists for `EXPLAIN FORMAT=JSON`, which a server older than 5.6 answers with a
         * syntax error. Deciding that from here would mean teaching this function about EXPLAIN.
         */
        fallback: ((String, SQLException) -> String?)? = null,
    ) {
        val state = _uiState.value
        val tabId = state.activeTabId
        val id = connectionId.value
        if (statements.isEmpty() || id == 0L || state.running) return

        val needed = statements.flatMap { SqlGuards.parameters(it) }.distinct()
        if (needed.isNotEmpty() && !parameters.keys.containsAll(needed)) {
            updateTab(tabId) { it.copy(pendingParameters = needed) }
            return
        }

        // A SELECT is never held up; a write typed by hand is, every time. Row editing goes through
        // its own path and is not affected. A read-only connection refuses the write on its own,
        // and being asked to confirm something that cannot run is worse than the refusal.
        val writes = statements.filter { SqlGuards.classify(it) == StatementKind.WRITE }
        if (writes.isNotEmpty() && !confirmed && !state.readOnly) {
            askToConfirm(statements, parameters, writes)
            return
        }

        runJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(running = true)
            updateTab(tabId) {
                it.copy(
                    error = null,
                    errorDetail = null,
                    switchedTo = null,
                    pendingParameters = emptyList(),
                    parameters = if (parameters.isEmpty()) it.parameters else parameters,
                    panel = QueryPanel.RESULT,
                    // Fold the editor away so the result gets the screen.
                    editorCollapsed = true,
                    statements = emptyList(),
                    selectedStatement = 0,
                    result = null,
                    updateCount = null,
                    resultSort = null,
                    // A filter belongs to the rows it was typed for. Carrying it onto the next
                    // result would hide rows the user never chose to hide.
                    resultFilter = ResultFilter(),
                    chartSpec = null,
                )
            }

            val runs = mutableListOf<StatementRun>()

            suspend fun runOne(statement: String): StatementRun {
                val outcome = executor.run(
                    connectionId = id,
                    sql = statement,
                    parameters = parameters,
                    rowLimit = _uiState.value.rowLimit,
                    readOnly = _uiState.value.readOnly,
                )
                return StatementRun(
                    sql = statement,
                    // A USE changes nothing on screen except where the next query will run.
                    table = outcome.table.takeIf { outcome.switchedDatabase == null },
                    updateCount = outcome.updateCount,
                    switchedTo = outcome.switchedDatabase,
                )
            }

            for (sql in statements) {
                val run = try {
                    runOne(sql)
                } catch (e: Exception) {
                    // At most one retry, and only where the caller says this particular failure
                    // is about the statement's wording rather than about the data. Nothing else
                    // is retried: a missing table or a refused privilege fails the same way twice.
                    val instead = (e as? SQLException)?.let { fallback?.invoke(sql, it) }
                    when (instead) {
                        null -> {
                            updateTab(tabId) { it.copy(errorDetail = e.toString()) }
                            StatementRun(sql = sql, error = describe(e))
                        }

                        else -> try {
                            runOne(instead)
                        } catch (again: Exception) {
                            updateTab(tabId) { it.copy(errorDetail = again.toString()) }
                            StatementRun(sql = instead, error = describe(again))
                        }
                    }
                }
                runs += run
                if (run.error != null) break
            }

            // Show the last statement that produced something, or the failure if there was one.
            val shown = runs.indexOfLast { it.error != null }
                .takeIf { it >= 0 }
                ?: runs.indexOfLast { it.table != null }.takeIf { it >= 0 }
                ?: runs.lastIndex
            _uiState.value = _uiState.value.copy(running = false)
            updateTab(tabId) { it.copy(statements = runs) }
            showStatement(tabId, shown.coerceAtLeast(0))
        }
    }

    private fun showStatement(tabId: Long, index: Int) {
        val run = _uiState.value.tabs.firstOrNull { it.id == tabId }?.statements?.getOrNull(index)
            ?: return
        updateTab(tabId) {
            it.copy(
                selectedStatement = index,
                result = run.table,
                updateCount = run.updateCount,
                switchedTo = run.switchedTo,
                error = run.error,
                resultSort = null,
            )
        }
    }

    /**
     * Counts what the write would touch, then puts the dialog up.
     *
     * `running` is held while the count runs so the button cannot be pressed twice, and released
     * with the dialog: confirming goes back through [execute], which checks it again.
     */
    private fun askToConfirm(
        statements: List<String>,
        parameters: Map<String, ParameterValue>,
        writes: List<String>,
    ) {
        pendingRun = PendingRun(statements, parameters)
        _uiState.value = _uiState.value.copy(running = true)
        updateActive { it.copy(error = null, errorDetail = null) }
        viewModelScope.launch {
            val connection = sessions.currentConnection()
            _uiState.value = _uiState.value.copy(
                running = false,
                writeConfirmation = WriteConfirmation(
                    statements = writes,
                    estimatedRows = estimate(writes, parameters),
                    connectionName = connection?.name ?: _uiState.value.connectionName,
                    environment = ConnectionEnvironment.fromName(connection?.environment),
                    database = _uiState.value.database ?: connection?.database,
                    maxAffectedRows = settings.settings.first().maxAffectedRows,
                ),
            )
        }
    }

    /**
     * The rows the whole run would change, or null when any part of it cannot be counted.
     *
     * One uncountable statement makes the total a half-truth, and a half-truth shown as a number
     * is worse than "unknown" — so the whole estimate goes.
     */
    private suspend fun estimate(
        writes: List<String>,
        parameters: Map<String, ParameterValue>,
    ): Long? {
        var total = 0L
        for (sql in writes) {
            total += executor.estimateAffectedRows(sql, parameters) ?: return null
        }
        return total
    }

    /** Runs what the dialog was asking about. */
    fun confirmWrite() {
        val pending = pendingRun ?: return
        pendingRun = null
        _uiState.value = _uiState.value.copy(writeConfirmation = null)
        execute(pending.statements, pending.parameters, confirmed = true)
    }

    fun dismissWriteConfirmation() {
        pendingRun = null
        _uiState.value = _uiState.value.copy(writeConfirmation = null)
    }

    private data class PendingRun(
        val statements: List<String>,
        val parameters: Map<String, ParameterValue>,
    )

    /**
     * Sorts the loaded result in place (§7.5).
     *
     * A query result is not a table page, so there is nothing to re-query: what is sorted is the
     * rows already in memory, and the label says as much when only part of the result is loaded.
     */
    fun sortResult(column: String) {
        val tab = _uiState.value.active
        val result = tab.result ?: return
        val sort = TableQuery.nextSort(tab.resultSort, column)
        val index = result.columns.indexOfFirst { it.label == column }
        updateActive {
            it.copy(
                resultSort = sort,
                result = if (sort == null) result else result.sortedBy(index, sort.descending),
            )
        }
    }

    /** Narrows the rows on screen. Nothing is re-queried; what is not loaded stays unseen. */
    fun setResultFilter(filter: ResultFilter) {
        updateActive { it.copy(resultFilter = filter) }
    }

    /** Folds the filter bar away, keeping whatever it is filtering by. */
    fun toggleFilterBar() {
        updateActive { it.copy(filterOpen = !it.filterOpen) }
    }

    /** Switches between the rows and the picture of them. */
    fun toggleChart() {
        updateActive { it.copy(showChart = !it.showChart) }
    }

    fun setChartSpec(spec: ChartSpec) {
        updateActive { it.copy(chartSpec = spec) }
    }

    /**
     * The plan, asked for as JSON so it can be read as a tree, with the flat table as the answer
     * of last resort.
     *
     * `FORMAT=JSON` needs MySQL 5.6, and the app still connects to servers older than that. A
     * syntax error on the first attempt is what an old server calls "I have never heard of this
     * word", so exactly that failure — and no other — runs the plain `EXPLAIN` instead.
     */
    fun explain() {
        val sql = _uiState.value.active.sql.trim()
        if (sql.isBlank()) return
        if (SqlGuards.classify(sql) != StatementKind.READ) {
            updateActive { it.copy(error = context.getString(R.string.error_explain_read_only)) }
            return
        }
        // The editor keeps the query: rewriting it to "EXPLAIN ..." left the user to delete the
        // word again before running it for real.
        execute(
            statements = SqlScript.split(sql).map { "$EXPLAIN_JSON${it.sql}" },
            parameters = emptyMap(),
            fallback = { statement, e ->
                statement.takeIf { it.startsWith(EXPLAIN_JSON) }
                    ?.takeIf { ExplainJson.isUnsupported(e.errorCode, e.message) }
                    ?.let { "EXPLAIN ${it.removePrefix(EXPLAIN_JSON)}" }
            },
        )
    }

    fun cancel() {
        viewModelScope.launch {
            executor.cancel()
            runJob?.cancel()
            _uiState.value = _uiState.value.copy(running = false)
        }
    }

    /** §7.7: CSV or JSON of what is loaded, straight into the share sheet. */
    fun export(format: ExportFormat) {
        // What is exported is what is on screen. Exporting rows a filter is hiding would be a
        // file that does not match the thing the user was looking at when they asked for it.
        val result = _uiState.value.visibleResult ?: return
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    shareIntent = exports.shareIntent(result, format, "query"),
                )
            } catch (e: Exception) {
                updateActive { it.copy(error = describe(e)) }
            }
        }
    }

    fun shareIntentHandled() {
        _uiState.value = _uiState.value.copy(shareIntent = null)
    }

    fun dismissParameters() {
        updateActive { it.copy(pendingParameters = emptyList()) }
    }

    fun saveFavourite(name: String) {
        val id = connectionId.value
        if (id == 0L) return
        val sql = _uiState.value.active.sql
        // The mark on the tab goes away here, not when the write comes back: what it says is
        // "this text is a favourite now", and it is.
        updateActive { it.copy(savedSql = sql) }
        viewModelScope.launch { queries.saveFavourite(id, name, sql) }
    }

    fun deleteFavourite(favourite: SavedQueryEntity) {
        viewModelScope.launch { queries.deleteFavourite(favourite.id) }
    }

    /**
     * One tap from history or favourites puts the SQL back in the editor (§7.4).
     *
     * [saved] says whether it came from the favourites, which is what decides whether the tab is
     * then marked as holding unsaved text.
     */
    fun load(sql: String, saved: Boolean = false) {
        // A query taken from the history or the favourites is meant to be read and edited, so the
        // editor unfolds even if a result was filling the screen.
        updateActive {
            it.copy(
                sql = sql,
                panel = QueryPanel.RESULT,
                error = null,
                editorCollapsed = false,
                selectionStart = sql.length,
                selectionEnd = sql.length,
                savedSql = if (saved) sql else it.savedSql,
            )
        }
        noteDraftChange()
    }

    // --- Time machine ------------------------------------------------------------------------

    /**
     * Freezes the result the active tab is showing (roadmap: Időgép).
     *
     * The only thing decided here is which columns identify a row: the primary key of the one
     * table the result came from, when the result actually carries every part of it. Everything
     * else — whether that key can be believed, how much of the result fits, what "the same row"
     * means without a key — is [ResultSnapshots]' answer, so it can be stated as an equality in a
     * test rather than demonstrated on a phone.
     */
    fun takeSnapshot() {
        val state = _uiState.value
        val tabId = state.activeTabId
        val tab = state.active
        // The snapshot is of what is on screen, filter and all: comparing against rows that were
        // hidden at the moment it was taken would report changes nobody could have seen.
        val result = state.visibleResult
        if (result == null || result.columns.isEmpty()) {
            _uiState.value = state.copy(snapshotNotice = SnapshotNotice.NoResult)
            return
        }
        viewModelScope.launch {
            val keyColumns = keyColumnsOf(tab.database, result)
            when (val outcome = ResultSnapshots.take(result, System.currentTimeMillis(), keyColumns)) {
                is SnapshotOutcome.Taken -> {
                    val snapshot = outcome.snapshot
                    _uiState.value = _uiState.value.copy(
                        snapshots = _uiState.value.snapshots + (tabId to snapshot),
                        snapshotNotice = SnapshotNotice.Taken(
                            rows = snapshot.rowCount,
                            trimmedFrom = snapshot.sourceRowCount.takeIf { snapshot.truncated },
                            sourceTruncated = snapshot.sourceTruncated,
                        ),
                    )
                }

                is SnapshotOutcome.TooWide -> _uiState.value = _uiState.value.copy(
                    snapshotNotice = SnapshotNotice.TooWide(outcome.columnCount, outcome.maxColumns),
                )

                SnapshotOutcome.NoResult -> _uiState.value = _uiState.value.copy(
                    snapshotNotice = SnapshotNotice.NoResult,
                )
            }
        }
    }

    /**
     * Compares the snapshot with what the tab is showing now.
     *
     * Nothing is re-run: the comparison is between the snapshot and the result that is on screen,
     * which is the same thing the user is looking at. Running the query again is the run button,
     * deliberately — a comparison that silently went to the server would be a write-shaped action
     * hiding behind a read-shaped one.
     */
    fun compareWithSnapshot() {
        val state = _uiState.value
        val snapshot = state.snapshots[state.activeTabId] ?: return
        // Both sides are what the screen shows, for the same reason taking one is.
        val result = state.visibleResult
        val outcome = if (result == null) {
            ComparisonOutcome.NoResult
        } else {
            ResultDiffs.compare(snapshot, result, System.currentTimeMillis())
        }
        _uiState.value = state.copy(comparison = outcome)
    }

    fun dismissComparison() {
        _uiState.value = _uiState.value.copy(comparison = null)
    }

    fun dismissSnapshotNotice() {
        _uiState.value = _uiState.value.copy(snapshotNotice = null)
    }

    /** Throws the snapshot away, for when the next one should be taken from a different run. */
    fun discardSnapshot() {
        val state = _uiState.value
        _uiState.value = state.copy(
            snapshots = state.snapshots - state.activeTabId,
            comparison = null,
        )
    }

    /**
     * The result's own primary key columns, or nothing.
     *
     * A join, a computed column, a result from a database the session is no longer pointed at,
     * or a schema lookup that fails all land in the same place: no key, and the comparison then
     * matches whole rows and says so. Guessing a key from column names would be worse than not
     * having one, because a wrong key pairs rows that have nothing to do with each other.
     */
    private suspend fun keyColumnsOf(database: String?, result: ResultTable): List<String> {
        if (database == null) return emptyList()
        val table = ResultSnapshots.sourceTable(result.columns) ?: return emptyList()
        val primaryKey = runCatching { schema.structure(database, table).primaryKey }
            .getOrDefault(emptyList())
        return ResultSnapshots.primaryKeyColumns(result.columns.map { it.label }, primaryKey)
    }

    // --- Drafts -----------------------------------------------------------------------------

    /**
     * Puts last session's text back before anything else happens.
     *
     * Only the text, the name and the database come back; a restored tab has no result, which is
     * honest — nothing has been run in this process yet.
     */
    private fun restoreDrafts() {
        viewModelScope.launch {
            val book = drafts.load()
            draftsRestored = true
            if (book.drafts.isEmpty()) return@launch
            val state = _uiState.value
            // If the user has already started typing while the file was being read, their text
            // wins: a draft is a safety net, not an instruction.
            if (state.tabs.any { it.sql.isNotEmpty() } || state.tabs.size > 1) return@launch
            val tabs = book.drafts.map { draft ->
                QueryTab(
                    id = draft.id,
                    title = draft.title,
                    sql = draft.sql,
                    database = draft.database,
                    selectionStart = draft.sql.length,
                    selectionEnd = draft.sql.length,
                )
            }
            nextTabId = (tabs.maxOf { it.id } + 1).coerceAtLeast(nextTabId)
            _uiState.value = state.copy(
                tabs = tabs,
                activeTabId = book.activeId ?: tabs.first().id,
            )
            lastWritten = book
            refreshSuggestions()
        }
    }

    private fun noteDraftChange() {
        draftChanged.tryEmit(Unit)
    }

    /** Writes only when something that is actually kept has changed since the last write. */
    private suspend fun writeDrafts() {
        if (!draftsRestored) return
        val state = _uiState.value
        val book = DraftBook(
            drafts = state.tabs.map {
                QueryDraft(id = it.id, title = it.title, sql = it.sql, database = it.database)
            },
            activeId = state.activeTabId,
        )
        if (book == lastWritten) return
        lastWritten = book
        runCatching { drafts.save(book) }
    }

    // --- Plumbing ---------------------------------------------------------------------------

    private fun updateActive(block: (QueryTab) -> QueryTab) = updateTab(_uiState.value.activeTabId, block)

    private fun updateTab(id: Long, block: (QueryTab) -> QueryTab) {
        val state = _uiState.value
        _uiState.value = state.copy(tabs = QueryTabs.replace(state.tabs, id, block))
    }

    private fun describe(e: Exception): String = when (e) {
        is ReadOnlyConnectionException -> context.getString(R.string.error_read_only)
        is WritesLockedException -> context.getString(R.string.error_writes_locked)
        is UnsupportedStatementException -> context.getString(R.string.error_unsupported_statement)
        is UnguardedWriteException -> context.getString(R.string.error_no_where_clause)
        // §11: the server's own wording, with a sentence about what it usually means.
        is SQLException -> context.explain(SqlFailures.of(e))
        else -> e.message ?: e.toString()
    }

    private fun loadDatabases() {
        viewModelScope.launch {
            val databases = runCatching { schema.databases() }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(databases = databases)
        }
    }

    private companion object {
        /** The prefix the plan is asked for with, and the one the retry strips off again. */
        const val EXPLAIN_JSON = "EXPLAIN FORMAT=JSON "

        const val MAX_ROW_LIMIT = 10_000
        const val MAX_SUGGESTIONS = 8

        /** Long enough that a word's worth of typing is one write, short enough to never notice. */
        const val DRAFT_DEBOUNCE_MS = 500L

        /**
         * How many of a statement's tables are worth a column lookup.
         *
         * A join of four tables is already unusual on a phone; beyond that the list would be too
         * long to read anyway, and the cap keeps a pathological query from firing a lookup per
         * table on every keystroke.
         */
        const val COLUMN_TABLES_PER_STATEMENT = 4
    }
}
