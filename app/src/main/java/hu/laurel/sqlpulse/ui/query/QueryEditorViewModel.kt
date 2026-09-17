package hu.laurel.sqlpulse.ui.query

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.export.ExportManager
import hu.laurel.sqlpulse.data.db.SavedQueryEntity
import hu.laurel.sqlpulse.data.query.QueryRepository
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.sql.QueryExecutor
import hu.laurel.sqlpulse.data.sql.ReadOnlyConnectionException
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.data.sql.UnsupportedStatementException
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
    val readOnly: Boolean = true,
    val connectionName: String? = null,
    val database: String? = null,
    val shareIntent: Intent? = null,
)

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
                            database = state.connection.database,
                        )
                        loadCompletions(state.connection.database)
                    }

                    else -> {
                        connectionId.value = 0L
                        schemaWords = emptyList()
                        _uiState.value = _uiState.value.copy(connectionName = null, database = null)
                    }
                }
            }
            .launchIn(viewModelScope)
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
     * Runs the current statement. A query with `:parameters` asks for their values first, rather
     * than sending an empty string to the server.
     */
    fun run(parameters: Map<String, String> = emptyMap()) {
        val state = _uiState.value
        val id = connectionId.value
        if (state.sql.isBlank() || id == 0L || state.running) return

        val needed = SqlGuards.parameters(state.sql)
        if (needed.isNotEmpty() && !parameters.keys.containsAll(needed)) {
            _uiState.value = state.copy(pendingParameters = needed)
            return
        }

        runJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                running = true,
                error = null,
                errorDetail = null,
                pendingParameters = emptyList(),
                panel = QueryPanel.RESULT,
            )
            try {
                val outcome = executor.run(
                    connectionId = id,
                    sql = state.sql,
                    parameters = parameters,
                    rowLimit = state.rowLimit,
                    readOnly = state.readOnly,
                )
                _uiState.value = _uiState.value.copy(
                    running = false,
                    result = outcome.table,
                    updateCount = outcome.updateCount,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    running = false,
                    error = describe(e),
                    errorDetail = e.toString(),
                )
            }
        }
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
                _uiState.value = _uiState.value.copy(error = e.message ?: e.toString())
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
        // §11: a MySQL error is shown as the server worded it.
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
