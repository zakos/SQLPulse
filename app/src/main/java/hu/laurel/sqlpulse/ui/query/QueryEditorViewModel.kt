package hu.laurel.sqlpulse.ui.query

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.db.SavedQueryEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.export.ExportManager
import hu.laurel.sqlpulse.data.query.QueryRepository
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.sql.ColumnSort
import hu.laurel.sqlpulse.data.sql.QueryExecutor
import hu.laurel.sqlpulse.data.sql.ReadOnlyConnectionException
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class QueryPanel { RESULT, HISTORY, FAVOURITES }

/**
 * One statement of a script that has been run.
 *
 * The table is kept per statement rather than only for the last one: a script whose first query
 * answered the question and whose third failed should still show the first answer.
 */
data class StatementRun(
    val sql: String,
    val table: ResultTable? = null,
    val updateCount: Int? = null,
    val switchedTo: String? = null,
    val error: String? = null,
)

data class QueryEditorUiState(
    val sql: String = "",
    val panel: QueryPanel = QueryPanel.RESULT,
    val result: ResultTable? = null,
    val updateCount: Int? = null,
    val running: Boolean = false,
    val error: String? = null,
    val errorDetail: String? = null,
    val rowLimit: Int = SqlGuards.DEFAULT_ROW_LIMIT,
    /** Parameter names waiting for values before the query can run (§7.4). */
    val pendingParameters: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val databases: List<String> = emptyList(),
    /** The database statements run against; movable here, in the schema browser, or with USE. */
    val database: String? = null,
    val readOnly: Boolean = true,
    val connectionName: String? = null,
    /** Set right after a USE, so the editor can say where it moved to. */
    val switchedTo: String? = null,
    /** Sort applied to the loaded result; a query result cannot be re-ordered by the server. */
    val resultSort: ColumnSort? = null,
    /** Every statement of the last run, in order. One entry for an ordinary single query. */
    val statements: List<StatementRun> = emptyList(),
    val selectedStatement: Int = 0,
    /** Where the cursor is, or what is selected, in the editor. */
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val shareIntent: Intent? = null,
) {
    /** True when the editor holds more than one statement, so "run" means "run all of them". */
    val isScript: Boolean get() = SqlScript.split(sql).size > 1

    /** True when part of the editor is selected, so "run" means "run only that". */
    val hasSelection: Boolean get() = selectionEnd > selectionStart
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QueryEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: QueryExecutor,
    private val exports: ExportManager,
    private val queries: QueryRepository,
    private val schema: SchemaRepository,
    private val sessions: SqlSessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QueryEditorUiState())
    val uiState: StateFlow<QueryEditorUiState> = _uiState.asStateFlow()

    private val connectionId = MutableStateFlow(0L)
    private var runJob: Job? = null

    /** Table and column names of the current database, for completion (§7.4). */
    private var schemaWords: List<String> = emptyList()

    val history: StateFlow<List<QueryHistoryEntity>> = connectionId
        .flatMapLatest { id -> if (id == 0L) flowOf(emptyList()) else queries.observeHistory(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favourites: StateFlow<List<SavedQueryEntity>> = connectionId
        .flatMapLatest { id -> if (id == 0L) flowOf(emptyList()) else queries.observeFavourites(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
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
                        schemaWords = emptyList()
                        _uiState.value = _uiState.value.copy(connectionName = null, database = null)
                    }
                }
            }
            .launchIn(viewModelScope)

        // The current database is owned by the session, so the picker follows a USE or a change
        // made in the schema browser.
        sessions.database
            .onEach { database ->
                _uiState.value = _uiState.value.copy(database = database)
                database?.let { loadCompletions(it) }
            }
            .launchIn(viewModelScope)
    }

    fun selectDatabase(database: String) = sessions.selectDatabase(database)

    fun setSelection(start: Int, end: Int) {
        _uiState.value = _uiState.value.copy(selectionStart = start, selectionEnd = end)
    }

    fun selectStatement(index: Int) {
        val state = _uiState.value
        val run = state.statements.getOrNull(index) ?: return
        _uiState.value = state.copy(
            selectedStatement = index,
            result = run.table,
            updateCount = run.updateCount,
            switchedTo = run.switchedTo,
            error = run.error,
            resultSort = null,
        )
    }

    /** Runs only the statement the cursor is in, leaving the rest of the script alone. */
    fun runCurrent(parameters: Map<String, String> = emptyMap()) {
        val state = _uiState.value
        val statement = SqlScript.statementAt(state.sql, state.selectionStart) ?: return
        execute(listOf(statement.sql), parameters)
    }

    fun setSql(sql: String) {
        _uiState.value = _uiState.value.copy(sql = sql, error = null)
    }

    /** Inserts a snippet from the key row above the keyboard (§7.4). */
    fun append(text: String) {
        val current = _uiState.value.sql
        val separator = if (current.isEmpty() || current.endsWith(" ")) "" else " "
        setSql(current + separator + text)
    }

    /**
     * Lays the editor's text out over several lines.
     *
     * The selection is formatted alone when there is one, so one statement of a long script can be
     * tidied without disturbing the rest.
     */
    fun format() {
        val state = _uiState.value
        if (state.sql.isBlank()) return
        if (state.hasSelection) {
            val start = state.selectionStart.coerceIn(0, state.sql.length)
            val end = state.selectionEnd.coerceIn(start, state.sql.length)
            val formatted = SqlFormatter.format(state.sql.substring(start, end))
            setSql(state.sql.substring(0, start) + formatted + state.sql.substring(end))
        } else {
            setSql(SqlScript.split(state.sql).joinToString(";\n\n") { SqlFormatter.format(it.sql) } + ";")
        }
    }

    /** Replaces every occurrence of [find] in the editor. Case-insensitive, like SQL itself. */
    fun replaceAll(find: String, replacement: String) {
        if (find.isEmpty()) return
        setSql(_uiState.value.sql.replace(find, replacement, ignoreCase = true))
    }

    fun selectPanel(panel: QueryPanel) {
        _uiState.value = _uiState.value.copy(panel = panel)
    }

    fun setRowLimit(limit: Int) {
        _uiState.value = _uiState.value.copy(rowLimit = limit.coerceIn(1, MAX_ROW_LIMIT))
    }

    /** Completion candidates for the word being typed. */
    fun suggest(prefix: String) {
        val trimmed = prefix.trim()
        _uiState.value = _uiState.value.copy(
            suggestions = if (trimmed.length < MIN_COMPLETION_CHARS) {
                emptyList()
            } else {
                schemaWords.filter { it.startsWith(trimmed, ignoreCase = true) }.take(MAX_SUGGESTIONS)
            },
        )
    }

    /**
     * Runs what the editor holds: the selection if there is one, otherwise every statement in it.
     * A query with `:parameters` asks for their values first, rather than sending an empty string
     * to the server.
     */
    fun run(parameters: Map<String, String> = emptyMap()) {
        val state = _uiState.value
        // A selection means "run this much of it", which is how a long script is worked through.
        val selected = state.sql.substring(
            state.selectionStart.coerceIn(0, state.sql.length),
            state.selectionEnd.coerceIn(0, state.sql.length),
        )
        val text = selected.takeIf { it.isNotBlank() } ?: state.sql
        execute(SqlScript.split(text).map { it.sql }, parameters)
    }

    /**
     * Runs the statements one after another, stopping at the first failure.
     *
     * Stopping is the safe default: the statements of a script usually depend on each other, and
     * carrying on after an error would apply the rest to a state nobody planned for. What ran
     * before the failure stays on screen, with its results.
     */
    private fun execute(statements: List<String>, parameters: Map<String, String>) {
        val state = _uiState.value
        val id = connectionId.value
        if (statements.isEmpty() || id == 0L || state.running) return

        val needed = statements.flatMap { SqlGuards.parameters(it) }.distinct()
        if (needed.isNotEmpty() && !parameters.keys.containsAll(needed)) {
            _uiState.value = state.copy(pendingParameters = needed)
            return
        }

        runJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                running = true,
                error = null,
                errorDetail = null,
                switchedTo = null,
                pendingParameters = emptyList(),
                panel = QueryPanel.RESULT,
                statements = emptyList(),
                selectedStatement = 0,
                result = null,
                updateCount = null,
                resultSort = null,
            )

            val runs = mutableListOf<StatementRun>()
            for (sql in statements) {
                try {
                    val outcome = executor.run(
                        connectionId = id,
                        sql = sql,
                        parameters = parameters,
                        rowLimit = _uiState.value.rowLimit,
                        readOnly = _uiState.value.readOnly,
                    )
                    runs += StatementRun(
                        sql = sql,
                        // A USE changes nothing on screen except where the next query will run.
                        table = outcome.table.takeIf { outcome.switchedDatabase == null },
                        updateCount = outcome.updateCount,
                        switchedTo = outcome.switchedDatabase,
                    )
                } catch (e: Exception) {
                    runs += StatementRun(sql = sql, error = describe(e))
                    _uiState.value = _uiState.value.copy(errorDetail = e.toString())
                    break
                }
            }

            // Show the last statement that produced something, or the failure if there was one.
            val shown = runs.indexOfLast { it.error != null }
                .takeIf { it >= 0 }
                ?: runs.indexOfLast { it.table != null }.takeIf { it >= 0 }
                ?: runs.lastIndex
            _uiState.value = _uiState.value.copy(running = false, statements = runs)
            selectStatement(shown.coerceAtLeast(0))
        }
    }

    /**
     * Sorts the loaded result in place (§7.5).
     *
     * A query result is not a table page, so there is nothing to re-query: what is sorted is the
     * rows already in memory, and the label says as much when only part of the result is loaded.
     */
    fun sortResult(column: String) {
        val state = _uiState.value
        val result = state.result ?: return
        val sort = TableQuery.nextSort(state.resultSort, column)
        val index = result.columns.indexOfFirst { it.label == column }
        _uiState.value = state.copy(
            resultSort = sort,
            result = if (sort == null) result else result.sortedBy(index, sort.descending),
        )
    }

    /** §12/5 mentions EXPLAIN visualisation later; this is the plain output of it for now. */
    fun explain() {
        val sql = _uiState.value.sql.trim()
        if (sql.isBlank()) return
        if (SqlGuards.classify(sql) != StatementKind.READ) {
            _uiState.value = _uiState.value.copy(
                error = context.getString(R.string.error_explain_read_only),
            )
            return
        }
        setSql("EXPLAIN $sql")
        run()
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
        val result = _uiState.value.result ?: return
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    shareIntent = exports.shareIntent(result, format, "query"),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = describe(e))
            }
        }
    }

    fun shareIntentHandled() {
        _uiState.value = _uiState.value.copy(shareIntent = null)
    }

    fun dismissParameters() {
        _uiState.value = _uiState.value.copy(pendingParameters = emptyList())
    }

    fun saveFavourite(name: String) {
        val id = connectionId.value
        if (id == 0L) return
        viewModelScope.launch { queries.saveFavourite(id, name, _uiState.value.sql) }
    }

    fun deleteFavourite(favourite: SavedQueryEntity) {
        viewModelScope.launch { queries.deleteFavourite(favourite.id) }
    }

    /** One tap from history or favourites puts the SQL back in the editor (§7.4). */
    fun load(sql: String) {
        _uiState.value = _uiState.value.copy(sql = sql, panel = QueryPanel.RESULT, error = null)
    }

    private fun loadDatabases() {
        viewModelScope.launch {
            val databases = runCatching { schema.databases() }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(databases = databases)
        }
    }

    private fun loadCompletions(database: String) {
        viewModelScope.launch {
            schemaWords = runCatching {
                val tables = schema.tables(database)
                val columns = tables.take(COMPLETION_TABLE_BUDGET).flatMap { table ->
                    runCatching { schema.structure(database, table.name).columns.map { it.name } }
                        .getOrDefault(emptyList())
                }
                (tables.map { it.name } + columns).distinct()
            }.getOrDefault(emptyList())
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ReadOnlyConnectionException -> context.getString(R.string.error_read_only)
        is UnsupportedStatementException -> context.getString(R.string.error_unsupported_statement)
        is UnguardedWriteException -> context.getString(R.string.error_no_where_clause)
        // §11: the server's own wording, with a sentence about what it usually means.
        is SQLException -> context.explain(SqlFailures.of(e))
        else -> e.message ?: e.toString()
    }

    private companion object {
        const val MAX_ROW_LIMIT = 10_000
        const val MIN_COMPLETION_CHARS = 2
        const val MAX_SUGGESTIONS = 8

        /**
         * Completion pulls columns for the first tables only: a schema with hundreds of tables
         * would otherwise cost hundreds of round trips through the tunnel before the first query.
         */
        const val COMPLETION_TABLE_BUDGET = 25
    }
}
