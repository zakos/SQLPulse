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

data class ServerUiState(
    val facts: List<ServerFact> = emptyList(),
    val processes: ResultTable? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val connected: Boolean = false,
)

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
                if (ready) refresh() else _uiState.value = _uiState.value.copy(processes = null)
            }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val processes = server.processList()
                // The overview is optional: a user without those variables still gets the list.
                val facts = runCatching { server.overview() }.getOrDefault(emptyList())
                _uiState.value = _uiState.value.copy(
                    processes = processes,
                    facts = facts,
                    loading = false,
                )
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
