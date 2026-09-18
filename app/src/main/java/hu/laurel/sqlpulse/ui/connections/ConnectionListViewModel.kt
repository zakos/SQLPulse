package hu.laurel.sqlpulse.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.connection.ConnectionRepository
import hu.laurel.sqlpulse.data.connection.ProductionPolicy
import hu.laurel.sqlpulse.data.connection.WriteAccess
import hu.laurel.sqlpulse.data.connection.WriteUnlockStore
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.ssh.HostKeyPrompt
import hu.laurel.sqlpulse.ssh.TunnelManager
import hu.laurel.sqlpulse.ssh.TunnelState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConnectionListUiState(
    val connections: List<ConnectionEntity> = emptyList(),
    val tunnel: TunnelState = TunnelState.Disconnected,
    val hostKeyPrompt: HostKeyPrompt? = null,
    val serverVersion: String? = null,
    /** Connection id to the end of its write window; empty when nothing is unlocked. */
    val unlockedUntil: Map<Long, Long> = emptyMap(),
) {
    /**
     * Whether this connection may be written to at [now], and why not when it may not.
     *
     * The clock is passed in by the screen, which ticks it while a window is open, so the
     * countdown is drawn from the same answer that decides whether writes go through.
     */
    fun writeAccess(connection: ConnectionEntity, now: Long): WriteAccess =
        ProductionPolicy.writeAccess(
            environment = ConnectionEnvironment.fromName(connection.environment),
            readOnly = connection.readOnly,
            unlockedUntil = unlockedUntil[connection.id],
            now = now,
        )

    /** The list by environment, in [ConnectionEnvironment.ORDER], with empty groups left out. */
    val groups: List<Pair<ConnectionEnvironment, List<ConnectionEntity>>>
        get() = ConnectionEnvironment.group(connections) {
            ConnectionEnvironment.fromName(it.environment)
        }

    /** With everything in one group the headings would say nothing, so they are not drawn. */
    val showsGroupHeadings: Boolean get() = groups.size > 1
}

@HiltViewModel
class ConnectionListViewModel @Inject constructor(
    private val repository: ConnectionRepository,
    private val tunnelManager: TunnelManager,
    private val writeUnlock: WriteUnlockStore,
) : ViewModel() {

    val uiState: StateFlow<ConnectionListUiState> = combine(
        repository.observeAll(),
        tunnelManager.state,
        tunnelManager.hostKeyPrompt,
        tunnelManager.serverVersion,
        writeUnlock.unlockedUntil,
    ) { connections, tunnel, prompt, version, unlocked ->
        ConnectionListUiState(connections, tunnel, prompt, version, unlocked)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ConnectionListUiState(),
    )

    /**
     * The one connection the user is about to open against a production server that may be written
     * to, waiting for them to say yes.
     *
     * Only that combination asks. A production connection that is read-only cannot do damage, and
     * asking every time would turn the question into something to tap through.
     */
    private val _confirming = MutableStateFlow<ConnectionEntity?>(null)
    val confirming: StateFlow<ConnectionEntity?> = _confirming.asStateFlow()

    fun connect(connection: ConnectionEntity) {
        val environment = ConnectionEnvironment.fromName(connection.environment)
        if (environment.isProduction && !connection.readOnly) {
            _confirming.value = connection
        } else {
            tunnelManager.connect(connection.id)
        }
    }

    fun confirmConnect() {
        val connection = _confirming.value ?: return
        _confirming.value = null
        tunnelManager.connect(connection.id)
    }

    fun cancelConnect() {
        _confirming.value = null
    }

    /**
     * Opens the write window (§2.0). The user says so once, and it closes by itself a quarter of
     * an hour later — nothing here has to remember to turn it off again.
     */
    fun unlockWrites(connection: ConnectionEntity) {
        writeUnlock.unlock(connection.id)
    }

    fun lockWrites(connection: ConnectionEntity) {
        writeUnlock.lock(connection.id)
    }

    fun disconnect() {
        // Every window closes with the tunnel: the next session is a new decision.
        writeUnlock.lockAll()
        tunnelManager.disconnect()
    }

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
