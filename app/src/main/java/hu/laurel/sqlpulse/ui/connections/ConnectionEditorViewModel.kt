package hu.laurel.sqlpulse.ui.connections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.connection.ConnectionRepository
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.data.keys.SshKeyRepository
import hu.laurel.sqlpulse.security.UnlockCancelledException
import hu.laurel.sqlpulse.ssh.TunnelManager
import hu.laurel.sqlpulse.ssh.TunnelState
import hu.laurel.sqlpulse.ui.theme.ConnectionColor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConnectionForm(
    val id: Long = 0,
    val name: String = "",
    val color: ConnectionColor = ConnectionColor.Blue,
    /** On by default: a tunnel is the safe shape, and the spec's original rule (§4). */
    val useSsh: Boolean = true,
    val sshHost: String = "",
    val sshPort: String = "22",
    val sshUser: String = "",
    val sshKeyId: Long? = null,
    val dbHost: String = "localhost",
    val dbPort: String = "3306",
    val database: String = "",
    val dbUser: String = "",
    val password: String = "",
    val passwordTouched: Boolean = false,
    val readOnly: Boolean = true,
) {
    /**
     * With the tunnel on, §5 still holds: no key, no save, and no password-authentication path to
     * fall back on. With it off, the SSH fields are irrelevant and only the database matters.
     */
    val canSave: Boolean
        get() = name.isNotBlank() &&
            dbHost.isNotBlank() &&
            dbPort.toIntOrNull() != null &&
            (
                !useSsh || (
                    sshHost.isNotBlank() &&
                        sshUser.isNotBlank() &&
                        sshKeyId != null &&
                        sshPort.toIntOrNull() != null
                    )
                )
}

@HiltViewModel
class ConnectionEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val connections: ConnectionRepository,
    private val keyRepository: SshKeyRepository,
    private val tunnelManager: TunnelManager,
) : ViewModel() {

    private val connectionId: Long = savedStateHandle.get<Long>("connectionId") ?: 0L

    private val _form = MutableStateFlow(ConnectionForm())
    val form: StateFlow<ConnectionForm> = _form.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val keys: StateFlow<List<SshKeyEntity>> = keyRepository.observeKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tunnel: StateFlow<TunnelState> = tunnelManager.state

    val serverVersion: StateFlow<String?> = tunnelManager.serverVersion

    init {
        if (connectionId != 0L) {
            viewModelScope.launch {
                connections.byId(connectionId)?.let { entity ->
                    _form.value = entity.toForm(hasPassword = connections.hasPassword(entity.id))
                }
            }
        }
    }

    fun update(transform: (ConnectionForm) -> ConnectionForm) {
        _form.value = transform(_form.value)
    }

    fun save(onSaved: (Long) -> Unit) {
        val form = _form.value
        if (!form.canSave) return
        viewModelScope.launch {
            try {
                val saved = connections.save(
                    form.toEntity(),
                    // Only re-seal the password when the field was actually edited.
                    password = form.password.toCharArray().takeIf { form.passwordTouched },
                )
                onSaved(saved.id)
            } catch (e: UnlockCancelledException) {
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    /** §7.2: save, build the tunnel, report step by step, then tear it down. */
    fun test() {
        save { id -> tunnelManager.connect(id) }
    }

    fun stopTest() = tunnelManager.disconnect()

    fun acceptHostKey() = tunnelManager.acceptHostKey()

    fun rejectHostKey() = tunnelManager.rejectHostKey()

    val hostKeyPrompt = tunnelManager.hostKeyPrompt

    /** Explicit unblock after a host key change (§5). */
    fun forgetHostKey() {
        val form = _form.value
        viewModelScope.launch {
            tunnelManager.forgetHostKey(form.sshHost, form.sshPort.toIntOrNull() ?: 22)
        }
    }

    private fun ConnectionForm.toEntity() = ConnectionEntity(
        id = id,
        name = name.trim(),
        color = color.name,
        useSshTunnel = useSsh,
        sshHost = sshHost.trim(),
        sshPort = sshPort.toIntOrNull() ?: 22,
        sshUser = sshUser.trim(),
        // Kept rather than cleared when the tunnel is switched off, so switching back is painless.
        sshKeyId = if (useSsh) requireNotNull(sshKeyId) else sshKeyId,
        dbHost = dbHost.trim(),
        dbPort = dbPort.toIntOrNull() ?: 3306,
        database = database.trim(),
        dbUser = dbUser.trim(),
        readOnly = readOnly,
    )

    private fun ConnectionEntity.toForm(hasPassword: Boolean) = ConnectionForm(
        id = id,
        name = name,
        color = ConnectionColor.fromName(color),
        useSsh = useSshTunnel,
        sshHost = sshHost,
        sshPort = sshPort.toString(),
        sshUser = sshUser,
        sshKeyId = sshKeyId,
        dbHost = dbHost,
        dbPort = dbPort.toString(),
        database = database,
        dbUser = dbUser,
        password = if (hasPassword) PLACEHOLDER_PASSWORD else "",
        passwordTouched = false,
        readOnly = readOnly,
    )

    private companion object {
        /** Stands in for a stored password so the field is not blank; never sent anywhere. */
        const val PLACEHOLDER_PASSWORD = "••••••••"
    }
}
