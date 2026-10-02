package hu.laurel.sqlpulse.ui.storage

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.schema.StorageRepository
import hu.laurel.sqlpulse.data.schema.StorageSnapshot
import hu.laurel.sqlpulse.data.schema.StorageSort
import hu.laurel.sqlpulse.data.schema.StorageTable
import hu.laurel.sqlpulse.data.schema.StorageTotals
import hu.laurel.sqlpulse.data.sql.SqlFailures
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.ui.explain
import java.sql.SQLException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageUiState(
    val connected: Boolean = false,
    val database: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val snapshot: StorageSnapshot? = null,
    val sort: StorageSort = StorageSort.TOTAL,
) {
    val tables: List<StorageTable> get() = snapshot?.let { sort.apply(it.tables) }.orEmpty()
    val totals: StorageTotals? get() = snapshot?.let { StorageTotals.of(it.tables) }
}

/** What the screen reads and asks for; the screenshot tests draw it from a fixed state. */
interface StorageController {
    val uiState: StateFlow<StorageUiState>
    fun setSort(sort: StorageSort)
    fun refresh()
}

/** Table and index sizes of the selected database (read-only). */
@HiltViewModel
class StorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: StorageRepository,
    private val sessions: SqlSessionManager,
) : ViewModel(), StorageController {

    private val _uiState = MutableStateFlow(StorageUiState())
    override val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        combine(sessions.state, sessions.database) { state, selected ->
            val ready = state as? SqlSessionState.Ready
            _uiState.update {
                it.copy(
                    connected = ready != null,
                    database = selected ?: ready?.connection?.database,
                    snapshot = if (ready == null) null else it.snapshot,
                )
            }
            ready != null
        }.onEach { if (it) refresh() }.launchIn(viewModelScope)
    }

    override fun setSort(sort: StorageSort) = _uiState.update { it.copy(sort = sort) }

    override fun refresh() {
        val database = _uiState.value.database ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val snapshot = repository.load(database)
                _uiState.update { it.copy(loading = false, snapshot = snapshot) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: SQLException) {
                _uiState.update { it.copy(loading = false, error = context.explain(SqlFailures.of(e))) }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }
}
