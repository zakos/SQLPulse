package hu.laurel.sqlpulse.ui.server

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.schema.ServerFact
import hu.laurel.sqlpulse.data.schema.ServerRepository
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlFailures
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.ui.explain
import java.sql.SQLException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** What the server screen is showing. Each is a question a DBA asks separately. */
enum class ServerPanel { QUERIES, TRANSACTIONS, LOCKS, REPLICATION }

data class ServerUiState(
    val facts: List<ServerFact> = emptyList(),
    val panel: ServerPanel = ServerPanel.QUERIES,
    val processes: ResultTable? = null,
    val transactions: ResultTable? = null,
    val lockWaits: ResultTable? = null,
    val replication: ResultTable? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val connected: Boolean = false,
) {
    val table: ResultTable?
        get() = when (panel) {
            ServerPanel.QUERIES -> processes
            ServerPanel.TRANSACTIONS -> transactions
            ServerPanel.LOCKS -> lockWaits
            ServerPanel.REPLICATION -> replication
        }
}

/** The running queries and a short server overview (§3, DBA role). */
@HiltViewModel
class ServerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val server: ServerRepository,
    sessions: SqlSessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUiState())
    val uiState: StateFlow<ServerUiState> = _uiState.asStateFlow()

    init {
        sessions.state
            .onEach { state ->
                val ready = state is SqlSessionState.Ready
                _uiState.value = _uiState.value.copy(connected = ready)
                if (ready) {
                    refresh()
                } else {
                    _uiState.value = _uiState.value.copy(
                        processes = null,
                        transactions = null,
                        lockWaits = null,
                        replication = null,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun selectPanel(panel: ServerPanel) {
        _uiState.value = _uiState.value.copy(panel = panel)
        if (_uiState.value.table == null) refresh()
    }

    /**
     * Reloads what is on screen.
     *
     * Only the panel being looked at: four queries against a busy server, three of which nobody
     * asked for, is not a refresh anyone wants over a tunnel. The overview is fetched alongside,
     * because it is a single cheap statement and it is always visible.
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val state = _uiState.value
                val updated = when (state.panel) {
                    ServerPanel.QUERIES -> state.copy(processes = server.processList())
                    ServerPanel.TRANSACTIONS -> state.copy(transactions = server.transactions())
                    ServerPanel.LOCKS -> state.copy(lockWaits = server.lockWaits())
                    ServerPanel.REPLICATION -> state.copy(replication = server.replication())
                }
                // The overview is optional: a user without those variables still gets the list.
                val facts = runCatching { server.overview() }.getOrDefault(emptyList())
                _uiState.value = updated.copy(facts = facts, loading = false)
            } catch (e: Exception) {
                // Usually "Access denied": seeing other users' queries needs the PROCESS grant (§3).
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = describe(e),
                )
            }
        }
    }

    fun kill(processId: Long) {
        viewModelScope.launch {
            try {
                server.killQuery(processId)
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = describe(e))
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SQLException -> context.explain(SqlFailures.of(e))
        else -> e.message ?: e.toString()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
