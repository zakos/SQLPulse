package hu.laurel.sqlpulse.ui.schemadiff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.connection.ConnectionRepository
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.schema.SchemaDiff
import hu.laurel.sqlpulse.data.schema.SchemaDiffOptions
import hu.laurel.sqlpulse.data.schema.SchemaDiffRepository
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the schema comparison: picks the two sides, loads each from the cache, refreshes the one
 * with the open session on request, and recomputes the difference whenever either side or an
 * option changes. The comparison itself is [SchemaDiff]; nothing here decides what differs.
 */
@HiltViewModel
class SchemaDiffViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val repository: SchemaDiffRepository,
) : ViewModel(), SchemaDiffController {

    private val _uiState = MutableStateFlow(SchemaDiffUiState(now = System.currentTimeMillis()))
    override val uiState: StateFlow<SchemaDiffUiState> = _uiState.asStateFlow()

    private val jobs = mutableMapOf<DiffSide, Job>()
    private var diffJob: Job? = null
    private var defaultsChosen = false

    init {
        viewModelScope.launch {
            combine(connectionRepository.observeAll(), repository.liveConnection) { list, live -> list to live }
                .collect { (list, live) ->
                    _uiState.update { it.copy(connections = list, liveConnectionId = live) }
                    if (!defaultsChosen && list.isNotEmpty()) {
                        defaultsChosen = true
                        chooseDefaults(list, live)
                    }
                }
        }
    }

    /**
     * The pair most people open this screen for: the open connection (or the first) against the
     * other side of the dev/production line — production when A is not, something else when it is.
     */
    private fun chooseDefaults(list: List<ConnectionEntity>, live: Long?) {
        val a = list.firstOrNull { it.id == live } ?: list.first()
        val aProduction = ConnectionEnvironment.fromName(a.environment).isProduction
        // Only another connection of the same engine can be compared with A.
        val others = list.filter { it.id != a.id && it.engine == a.engine }
        val b = others.firstOrNull { ConnectionEnvironment.fromName(it.environment).isProduction != aProduction }
            ?: others.firstOrNull()
        selectConnection(DiffSide.A, a.id)
        b?.let { selectConnection(DiffSide.B, it.id) }
    }

    override fun selectConnection(side: DiffSide, connectionId: Long) {
        jobs[side]?.cancel()
        setSide(side) { SchemaDiffSideState(connectionId = connectionId, loading = true) }
        _uiState.update { it.copy(result = null) }
        jobs[side] = viewModelScope.launch {
            val engine = engineOf(connectionId)
            val databases = runCatching { repository.databases(connectionId, engine) }.getOrDefault(emptyList())
            setSide(side) { it.copy(databases = databases, loading = false) }
            val other = _uiState.value.side(side.other()).database
            val configured = _uiState.value.connection(connectionId)?.database
            // The same name as the other side first: on a pair of servers, that is the twin.
            val choice = listOf(other, configured).firstOrNull { it != null && it in databases }
                ?: databases.firstOrNull()
            if (choice != null) load(side, choice)
        }
    }

    override fun selectDatabase(side: DiffSide, database: String) {
        jobs[side]?.cancel()
        jobs[side] = viewModelScope.launch { load(side, database) }
    }

    private suspend fun load(side: DiffSide, database: String) {
        val connectionId = _uiState.value.side(side).connectionId ?: return
        setSide(side) { it.copy(database = database, capture = null, loading = true, refreshFailed = false) }
        val capture = runCatching { repository.load(connectionId, database, engineOf(connectionId)) }.getOrNull()
        setSide(side) { it.copy(capture = capture, loading = false) }
        _uiState.update { it.copy(now = System.currentTimeMillis()) }
        recompute()
    }

    override fun refresh(side: DiffSide) {
        val state = _uiState.value
        val current = state.side(side)
        val connectionId = current.connectionId ?: return
        val database = current.database ?: return
        if (!state.isLive(side)) return
        // Only the tables the other side also has need their columns read: one that is missing
        // there is reported as missing whatever its columns are.
        val otherNames = state.side(side.other()).capture?.side?.tables
            ?.map { it.name.lowercase() }?.toSet()
            ?.takeIf { it.isNotEmpty() }
        jobs[side]?.cancel()
        jobs[side] = viewModelScope.launch {
            setSide(side) { it.copy(progress = 0 to 0, refreshFailed = false) }
            try {
                val capture = repository.refresh(
                    connectionId = connectionId,
                    database = database,
                    wanted = { name -> otherNames == null || name.lowercase() in otherNames },
                    onProgress = { done, total -> setSide(side) { it.copy(progress = done to total) } },
                    engine = engineOf(connectionId),
                )
                setSide(side) { it.copy(capture = capture, progress = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // What was there before stays: a failed refresh does not make the old capture wrong.
                setSide(side) { it.copy(progress = null, refreshFailed = true) }
            }
            _uiState.update { it.copy(now = System.currentTimeMillis()) }
            recompute()
        }
    }

    override fun swap() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _uiState.update { it.copy(a = it.b.copy(progress = null), b = it.a.copy(progress = null)) }
        recompute()
    }

    override fun setOptions(options: SchemaDiffOptions) {
        _uiState.update { it.copy(options = options) }
        recompute()
    }

    private fun recompute() {
        diffJob?.cancel()
        val state = _uiState.value
        // A database that was never listed is not an empty one: comparing against it would report
        // every table of the other side as missing.
        val a = state.a.capture?.takeIf { it.tablesCapturedAt != null }
        val b = state.b.capture?.takeIf { it.tablesCapturedAt != null }
        // Two engines have nothing to compare; the screen explains why instead of showing a result.
        if (a == null || b == null || state.enginesDiffer) {
            _uiState.update { it.copy(result = null) }
            return
        }
        diffJob = viewModelScope.launch {
            // Pure, but a few hundred tables is still work best kept off the frame.
            val result = withContext(Dispatchers.Default) { SchemaDiff.compare(a.side, b.side, state.options) }
            _uiState.update { it.copy(result = result) }
        }
    }

    private fun engineOf(connectionId: Long): DatabaseEngine =
        _uiState.value.connection(connectionId)?.let { DatabaseEngine.fromName(it.engine) } ?: DatabaseEngine.MYSQL

    private fun setSide(side: DiffSide, change: (SchemaDiffSideState) -> SchemaDiffSideState) {
        _uiState.update {
            if (side == DiffSide.A) it.copy(a = change(it.a)) else it.copy(b = change(it.b))
        }
    }

    private fun DiffSide.other(): DiffSide = if (this == DiffSide.A) DiffSide.B else DiffSide.A
}
