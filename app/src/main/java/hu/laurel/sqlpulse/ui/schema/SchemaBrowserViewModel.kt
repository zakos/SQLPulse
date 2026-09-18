package hu.laurel.sqlpulse.ui.schema

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.schema.ObjectKind
import hu.laurel.sqlpulse.data.schema.SchemaEvent
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.SchemaRoutine
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.schema.SchemaTrigger
import hu.laurel.sqlpulse.data.schema.TableKind
import hu.laurel.sqlpulse.data.sql.NoSqlSessionException
import hu.laurel.sqlpulse.data.sql.SqlFailures
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.ui.explain
import java.sql.SQLException
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
    val objectKind: ObjectKind = ObjectKind.TABLES,
    val tables: List<SchemaTable> = emptyList(),
    val routines: List<SchemaRoutine> = emptyList(),
    val triggers: List<SchemaTrigger> = emptyList(),
    val events: List<SchemaEvent> = emptyList(),
    val filter: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** The definition of the routine the user tapped, shown in a sheet. */
    val routineDefinition: RoutineDefinition? = null,
) {
    /** The search box filters the loaded list rather than going back to the server (§7.3). */
    val visibleTables: List<SchemaTable>
        get() = tables
            .filter { (it.kind == TableKind.VIEW) == (objectKind == ObjectKind.VIEWS) }
            .filterByName { it.name }

    val visibleRoutines: List<SchemaRoutine> get() = routines.filterByName { it.name }

    val visibleTriggers: List<SchemaTrigger> get() = triggers.filterByName { it.name }

    val visibleEvents: List<SchemaEvent> get() = events.filterByName { it.name }

    /** True when the current tab has nothing to show and nothing is on its way. */
    val isEmpty: Boolean
        get() = !loading && when (objectKind) {
            ObjectKind.TABLES, ObjectKind.VIEWS -> visibleTables.isEmpty()
            ObjectKind.ROUTINES -> visibleRoutines.isEmpty()
            ObjectKind.TRIGGERS -> visibleTriggers.isEmpty()
            ObjectKind.EVENTS -> visibleEvents.isEmpty()
        }

    private fun <T> List<T>.filterByName(name: (T) -> String): List<T> =
        if (filter.isBlank()) this else filter { name(it).contains(filter, ignoreCase = true) }
}

/** A routine's `SHOW CREATE`, or the fact that we are not allowed to see its body. */
data class RoutineDefinition(val routine: SchemaRoutine, val sql: String)

@HiltViewModel
class SchemaBrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
                            routines = emptyList(),
                            triggers = emptyList(),
                            events = emptyList(),
                            selectedDatabase = null,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun selectDatabase(database: String) {
        _uiState.value = _uiState.value.copy(
            selectedDatabase = database,
            tables = emptyList(),
            routines = emptyList(),
            triggers = emptyList(),
            events = emptyList(),
        )
        // The query editor runs against the same database, so picking one here moves both.
        sessions.selectDatabase(database)
        load(database, _uiState.value.objectKind)
    }

    /**
     * Switches between tables, views, routines, triggers and events.
     *
     * Each list is fetched when its tab is first opened rather than all at once: on a slow link
     * four extra `information_schema` queries per database are four waits nobody asked for.
     */
    fun selectObjectKind(kind: ObjectKind) {
        _uiState.value = _uiState.value.copy(objectKind = kind)
        _uiState.value.selectedDatabase?.let { load(it, kind) }
    }

    fun showRoutine(routine: SchemaRoutine) {
        val database = _uiState.value.selectedDatabase ?: return
        viewModelScope.launch {
            try {
                val sql = schema.routineDdl(database, routine)
                _uiState.value = _uiState.value.copy(
                    routineDefinition = RoutineDefinition(routine, sql),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = describe(e))
            }
        }
    }

    fun dismissRoutine() {
        _uiState.value = _uiState.value.copy(routineDefinition = null)
    }

    fun setFilter(filter: String) {
        _uiState.value = _uiState.value.copy(filter = filter)
    }

    fun refresh() {
        val database = _uiState.value.selectedDatabase ?: return
        schema.clearCache()
        load(database, _uiState.value.objectKind, refresh = true)
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
                    load(it, _uiState.value.objectKind)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = describe(e))
            }
        }
    }

    private fun load(database: String, kind: ObjectKind, refresh: Boolean = false) {
        val state = _uiState.value
        val alreadyLoaded = !refresh && when (kind) {
            ObjectKind.TABLES, ObjectKind.VIEWS -> state.tables.isNotEmpty()
            ObjectKind.ROUTINES -> state.routines.isNotEmpty()
            ObjectKind.TRIGGERS -> state.triggers.isNotEmpty()
            ObjectKind.EVENTS -> state.events.isNotEmpty()
        }
        if (alreadyLoaded) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                _uiState.value = when (kind) {
                    ObjectKind.TABLES, ObjectKind.VIEWS ->
                        _uiState.value.copy(tables = schema.tables(database, refresh))

                    ObjectKind.ROUTINES -> _uiState.value.copy(routines = schema.routines(database))
                    ObjectKind.TRIGGERS -> _uiState.value.copy(triggers = schema.triggers(database))
                    ObjectKind.EVENTS -> _uiState.value.copy(events = schema.events(database))
                }.copy(loading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = describe(e))
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is NoSqlSessionException -> ""
        is SQLException -> context.explain(SqlFailures.of(e))
        else -> e.message ?: e.toString()
    }
}
