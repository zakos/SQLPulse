package hu.laurel.sqlpulse.ui.schema

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.schema.ObjectKind
import hu.laurel.sqlpulse.data.schema.SchemaCacheRepository
import hu.laurel.sqlpulse.data.schema.SchemaEvent
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.SchemaRoutine
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.schema.SchemaTrigger
import hu.laurel.sqlpulse.data.schema.SchemaView
import hu.laurel.sqlpulse.data.schema.valueOrNull
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
    /**
     * True when what is on screen came out of the cache rather than off the server.
     *
     * The lists themselves are the same type either way, which is the point: a table read from
     * the cache is browsed exactly like a live one. Nothing but this flag — and the marker the
     * screen draws from it — says where it came from, so it must never be set optimistically.
     */
    val cached: Boolean = false,
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
    private val cache: SchemaCacheRepository,
    private val sessions: SqlSessionManager,
) : ViewModel() {

    /**
     * Whose cache to read, which outlives the session that filled it.
     *
     * A lost session still names its connection, so the moment the tunnel drops there is still
     * something to browse. A session that was never opened names nothing, and then there is
     * genuinely nothing to show.
     */
    private var connectionId: Long? = null

    private val _uiState = MutableStateFlow(SchemaBrowserUiState())
    val uiState: StateFlow<SchemaBrowserUiState> = _uiState.asStateFlow()

    init {
        sessions.state
            .onEach { state ->
                _uiState.value = _uiState.value.copy(session = state)
                when (state) {
                    is SqlSessionState.Ready -> {
                        connectionId = state.connection.id
                        // Start on the database the connection points at.
                        loadDatabases(preferred = sessions.database.value ?: state.connection.database)
                    }

                    // The network moved, not the user: what was fetched stays readable, and what
                    // was not is served from the cache instead of being blanked.
                    is SqlSessionState.Lost -> {
                        connectionId = state.connection.id
                        loadDatabases(preferred = _uiState.value.selectedDatabase)
                    }

                    else -> {
                        connectionId = null
                        schema.clearCache()
                        _uiState.value = _uiState.value.copy(
                            databases = emptyList(),
                            tables = emptyList(),
                            routines = emptyList(),
                            triggers = emptyList(),
                            events = emptyList(),
                            selectedDatabase = null,
                            cached = false,
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
                val view = read { id -> cache.databases(id) } ?: SchemaView.Live(schema.databases())
                val databases = view.valueOrNull().orEmpty()
                val selected = preferred?.takeIf { it in databases } ?: databases.firstOrNull()
                _uiState.value = _uiState.value.copy(
                    databases = databases,
                    selectedDatabase = selected,
                    loading = false,
                    cached = view is SchemaView.Cached,
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
                    ObjectKind.TABLES, ObjectKind.VIEWS -> {
                        val view = read { id -> cache.tables(id, database, userAsked = refresh) }
                            ?: SchemaView.Live(schema.tables(database, refresh))
                        _uiState.value.copy(
                            tables = view.valueOrNull().orEmpty(),
                            cached = view is SchemaView.Cached,
                        )
                    }

                    ObjectKind.ROUTINES -> _uiState.value.copy(routines = schema.routines(database))
                    ObjectKind.TRIGGERS -> _uiState.value.copy(triggers = schema.triggers(database))
                    ObjectKind.EVENTS -> _uiState.value.copy(events = schema.events(database))
                }.copy(loading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = describe(e))
            }
        }
    }

    /**
     * Reads through the cache when this browser knows whose cache it is, live otherwise.
     *
     * Null rather than a view when there is no connection to name: the caller then asks the
     * server directly and gets [NoSqlSessionException] if there is no session, which is the same
     * answer this screen has always given.
     */
    private suspend fun <T> read(block: suspend (Long) -> SchemaView<T>): SchemaView<T>? =
        connectionId?.let { block(it) }

    private fun describe(e: Exception): String = when (e) {
        is NoSqlSessionException -> ""
        is SQLException -> context.explain(SqlFailures.of(e))
        else -> e.message ?: e.toString()
    }
}
