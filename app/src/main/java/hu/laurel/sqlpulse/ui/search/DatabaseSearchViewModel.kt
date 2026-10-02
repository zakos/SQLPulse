package hu.laurel.sqlpulse.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.TableKind
import hu.laurel.sqlpulse.data.search.DatabaseSearch
import hu.laurel.sqlpulse.data.search.DatabaseSearchRepository
import hu.laurel.sqlpulse.data.search.SearchHitFilter
import hu.laurel.sqlpulse.data.search.SearchMode
import hu.laurel.sqlpulse.data.search.SearchRow
import hu.laurel.sqlpulse.data.sql.NoSqlSessionException
import hu.laurel.sqlpulse.data.sql.SqlFailures
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.ui.explain
import hu.laurel.sqlpulse.ui.handoff.TableFilterHandoff
import java.sql.SQLException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a table that matched shows: its name and the rows that held the term. */
data class TableHits(
    val table: String,
    val rows: List<SearchRow>,
    /** True when the per-table limit was reached, so there may be more rows than are listed. */
    val capped: Boolean,
)

/** A table that was not searched, and why — an error, or a size the user chose not to read. */
data class SkippedTable(val table: String, val reason: String?)

data class DatabaseSearchUiState(
    val connected: Boolean = false,
    val database: String? = null,
    /** Production gets a warning, a lower row limit and large tables skipped by default. */
    val production: Boolean = false,
    val term: String = "",
    val mode: SearchMode = SearchMode.CONTAINS,
    /** 0 means every table. */
    val tableLimit: Int = 0,
    val skipLargeTables: Boolean = false,
    val rowsPerTable: Int = DatabaseSearchViewModel.ROWS_PER_TABLE,
    val running: Boolean = false,
    /** True once a search has been started, so "nothing found" is not shown before one ran. */
    val searched: Boolean = false,
    val tablesDone: Int = 0,
    val tablesTotal: Int = 0,
    val currentTable: String? = null,
    val results: List<TableHits> = emptyList(),
    val skipped: List<SkippedTable> = emptyList(),
    /** Tables left out because of the table limit or the size limit, not because of an error. */
    val notSearched: Int = 0,
    val error: String? = null,
) {
    val canStart: Boolean get() = connected && !running && term.trim().isNotEmpty()
    val matchCount: Int get() = results.sumOf { it.rows.size }
}

/** What the screen reads and asks for; the screenshot tests draw it from a fixed state. */
interface DatabaseSearchController {
    val uiState: StateFlow<DatabaseSearchUiState>
    fun setTerm(term: String)
    fun setMode(mode: SearchMode)
    fun setTableLimit(limit: Int)
    fun setSkipLargeTables(skip: Boolean)
    fun start()
    fun cancel()
}

/**
 * Searches every table of the current database for a value, one table at a time (§7.3).
 *
 * Strictly sequential: there is one JDBC session, and a search that fired a statement per table
 * at once would only queue on it while hiding which table is being read. Tables are walked
 * smallest first, so the first hits come back quickly and a huge table is the last thing waited for.
 * A table that fails is noted and skipped; the search carries on.
 */
@HiltViewModel
class DatabaseSearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val schema: SchemaRepository,
    private val repository: DatabaseSearchRepository,
    private val sessions: SqlSessionManager,
    private val tableFilters: TableFilterHandoff,
) : ViewModel(), DatabaseSearchController {

    private val _uiState = MutableStateFlow(initialState())
    override val uiState: StateFlow<DatabaseSearchUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    private fun initialState(): DatabaseSearchUiState {
        val ready = sessions.state.value as? SqlSessionState.Ready
        val production = ConnectionEnvironment.fromName(ready?.connection?.environment).isProduction
        return DatabaseSearchUiState(
            connected = ready != null,
            database = sessions.database.value ?: ready?.connection?.database,
            production = production,
            tableLimit = if (production) PRODUCTION_TABLE_LIMIT else 0,
            skipLargeTables = production,
            rowsPerTable = if (production) PRODUCTION_ROWS_PER_TABLE else ROWS_PER_TABLE,
        )
    }

    /** Leaves the filter for [table]'s screen to pick up, so it opens on this row. */
    fun offerRowFilter(table: String, row: SearchRow) {
        val state = _uiState.value
        val database = state.database ?: return
        val filter = SearchHitFilter.forRow(row, state.term, state.mode) ?: return
        tableFilters.offer(database, table, filter)
    }

    override fun setTerm(term: String) = _uiState.update { it.copy(term = term) }

    override fun setMode(mode: SearchMode) = _uiState.update { it.copy(mode = mode) }

    override fun setTableLimit(limit: Int) = _uiState.update { it.copy(tableLimit = limit) }

    override fun setSkipLargeTables(skip: Boolean) = _uiState.update { it.copy(skipLargeTables = skip) }

    override fun start() {
        val first = _uiState.value
        if (!first.canStart || first.database == null) return
        job?.cancel()
        _uiState.update {
            it.copy(
                running = true, searched = true, tablesDone = 0, tablesTotal = 0, currentTable = null,
                results = emptyList(), skipped = emptyList(), notSearched = 0, error = null,
            )
        }
        job = viewModelScope.launch {
            try {
                run(first.database, first.term.trim(), first.mode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: NoSqlSessionException) {
                fail(context.getString(hu.laurel.sqlpulse.R.string.dbsearch_no_session))
            } catch (e: SQLException) {
                fail(context.explain(SqlFailures.of(e)))
            } finally {
                _uiState.update { it.copy(running = false, currentTable = null) }
            }
        }
    }

    override fun cancel() {
        job?.cancel()
        // Without this the statement in flight would run on until the server answered, and the
        // screen would sit on "cancelling" for as long as the slowest table takes.
        repository.cancelRunning()
    }

    override fun onCleared() {
        cancel()
    }

    private fun fail(message: String) = _uiState.update { it.copy(error = message) }

    private suspend fun run(database: String, term: String, mode: SearchMode) {
        val state = _uiState.value
        val columns = repository.columns(database)
        val all = schema.tables(database)
            .filter { it.kind == TableKind.TABLE }
            .sortedWith(compareBy({ it.approximateRows ?: Long.MAX_VALUE }, { it.name }))

        // Decide what is left out before starting, so that the progress total is honest.
        val searchable = all.filter { table ->
            DatabaseSearch.plan(database, table.name, columns[table.name].orEmpty(), term, mode, 1) != null
        }
        val sizeOk = if (state.skipLargeTables) {
            searchable.filter { (it.approximateRows ?: 0L) <= LARGE_TABLE_ROWS }
        } else {
            searchable
        }
        val limited = if (state.tableLimit > 0) sizeOk.take(state.tableLimit) else sizeOk
        _uiState.update {
            it.copy(tablesTotal = limited.size, notSearched = searchable.size - limited.size)
        }

        for (table in limited) {
            currentCoroutineContext().ensureActive()
            _uiState.update { it.copy(currentTable = table.name) }
            val plan = DatabaseSearch.plan(
                database, table.name, columns[table.name].orEmpty(), term, mode, state.rowsPerTable,
            ) ?: continue
            try {
                val rows = repository.search(plan)
                if (rows.isNotEmpty()) {
                    val hits = TableHits(table.name, rows, capped = rows.size >= state.rowsPerTable)
                    _uiState.update { it.copy(results = it.results + hits) }
                }
            } catch (e: SQLException) {
                // A cancel surfaces as an SQLException from the driver; it is not a failed table.
                currentCoroutineContext().ensureActive()
                val reason = context.explain(SqlFailures.of(e))
                _uiState.update { it.copy(skipped = it.skipped + SkippedTable(table.name, reason)) }
            }
            _uiState.update { it.copy(tablesDone = it.tablesDone + 1) }
        }
    }

    companion object {
        const val ROWS_PER_TABLE = 50
        const val PRODUCTION_ROWS_PER_TABLE = 20
        const val PRODUCTION_TABLE_LIMIT = 50

        /** Above this many rows (the server's estimate) a table counts as large. */
        const val LARGE_TABLE_ROWS = 1_000_000L
    }
}
