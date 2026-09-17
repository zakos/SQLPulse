package hu.laurel.sqlpulse.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.connection.ConnectionRepository
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.ssh.HostKeyPrompt
import hu.laurel.sqlpulse.ssh.TunnelManager
import hu.laurel.sqlpulse.ssh.TunnelState
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConnectionListUiState(
    val connections: List<ConnectionEntity> = emptyList(),
    val tunnel: TunnelState = TunnelState.Disconnected,
    val hostKeyPrompt: HostKeyPrompt? = null,
    val serverVersion: String? = null,
)

@HiltViewModel
class ConnectionListViewModel @Inject constructor(
    private val repository: ConnectionRepository,
    private val tunnelManager: TunnelManager,
) : ViewModel() {

    val uiState: StateFlow<ConnectionListUiState> = combine(
        repository.observeAll(),
        tunnelManager.state,
        tunnelManager.hostKeyPrompt,
        tunnelManager.serverVersion,
    ) { connections, tunnel, prompt, version ->
        ConnectionListUiState(connections, tunnel, prompt, version)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ConnectionListUiState(),
    )

    fun connect(connection: ConnectionEntity) = tunnelManager.connect(connection.id)

    fun disconnect() = tunnelManager.disconnect()

    fun acceptHostKey() = tunnelManager.acceptHostKey()

    fun rejectHostKey() = tunnelManager.rejectHostKey()

    fun delete(connection: ConnectionEntity) {
        viewModelScope.launch { repository.delete(connection) }
    }

    fun duplicate(connection: ConnectionEntity) {
        viewModelScope.launch { repository.duplicate(connection) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
