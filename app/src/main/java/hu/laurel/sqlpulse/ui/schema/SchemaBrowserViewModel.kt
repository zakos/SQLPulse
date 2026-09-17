package hu.laurel.sqlpulse.ui.schema

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.sql.NoSqlSessionException
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class SchemaBrowserUiState(
    val session: SqlSessionState = SqlSessionState.Closed,
    val databases: List<String> = emptyList(),
    val selectedDatabase: String? = null,
    val tables: List<SchemaTable> = emptyList(),
    val filter: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** The search box filters the loaded list rather than going back to the server (§7.3). */
    val visibleTables: List<SchemaTable>
        get() = if (filter.isBlank()) {
            tables
        } else {
            tables.filter { it.name.contains(filter, ignoreCase = true) }
        }
}

@HiltViewModel
class SchemaBrowserViewModel @Inject constructor(
    private val schema: SchemaRepository,
    private val sessions: SqlSessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SchemaBrowserUiState())
    val uiState: StateFlow<SchemaBrowserUiState> = _uiState.asStateFlow()

    init {
        sessions.state
            .onEach { state ->
                _uiState.value = _uiState.value.copy(session = state)
                when (state) {
                    is SqlSessionState.Ready -> {
                        // Start on the database the connection points at.
                        loadDatabases(preferred = sessions.database.value ?: state.connection.database)
                    }

                    else -> {
                        schema.clearCache()
                        _uiState.value = _uiState.value.copy(
                            databases = emptyList(),
                            tables = emptyList(),
                            selectedDatabase = null,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun selectDatabase(database: String) {
        _uiState.value = _uiState.value.copy(selectedDatabase = database, tables = emptyList())
        // The query editor runs against the same database, so picking one here moves both.
        sessions.selectDatabase(database)
        loadTables(database)
    }

    fun setFilter(filter: String) {
        _uiState.value = _uiState.value.copy(filter = filter)
    }

    fun refresh() {
        val database = _uiState.value.selectedDatabase ?: return
        schema.clearCache()
        loadTables(database, refresh = true)
    }

    private fun loadDatabases(preferred: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val databases = schema.databases()
                val selected = preferred?.takeIf { it in databases } ?: databases.firstOrNull()
                _uiState.value = _uiState.value.copy(
                    databases = databases,
                    selectedDatabase = selected,
                    loading = false,
                )
                selected?.let {
                    sessions.selectDatabase(it)
                    loadTables(it)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = describe(e))
            }
        }
    }

    private fun loadTables(database: String, refresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val tables = schema.tables(database, refresh)
                _uiState.value = _uiState.value.copy(tables = tables, loading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = describe(e))
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is NoSqlSessionException -> ""
        else -> e.message ?: e.toString()
    }
}
