package hu.laurel.sqlpulse.ui.connections

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.connection.CertificateStore
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.data.sql.SslProperties
import hu.laurel.sqlpulse.data.connection.ConnectionRepository
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.data.keys.SshKeyRepository
import hu.laurel.sqlpulse.security.UnlockCancelledException
import hu.laurel.sqlpulse.ssh.SshAuthMethod
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
    /** A key, or a password, for entering the SSH host. */
    val sshAuthMethod: SshAuthMethod = SshAuthMethod.KEY,
    val sshKeyId: Long? = null,
    val sshPassword: String = "",
    val sshPasswordTouched: Boolean = false,
    /** Optional first hop; empty means the SSH host is dialled directly. */
    val jumpHost: String = "",
    val jumpPort: String = "22",
    val jumpUser: String = "",
    val dbHost: String = "localhost",
    val dbPort: String = "3306",
    val database: String = "",
    val dbUser: String = "",
    val password: String = "",
    val passwordTouched: Boolean = false,
    val readOnly: Boolean = true,
    /** How the MySQL connection itself is protected, independently of the SSH tunnel. */
    val sslMode: SslMode = SslMode.DISABLED,
    val caCertificate: String? = null,
) {
    /**
     * With the tunnel on, the SSH host needs an address, a user and whichever credential the
     * chosen method uses. With it off, the SSH fields are irrelevant and only the database matters.
     */
    val canSave: Boolean
        get() = name.isNotBlank() &&
            dbHost.isNotBlank() &&
            dbPort.toIntOrNull() != null &&
            // A verifying TLS mode without a CA file would fail at connect time, not at save.
            !SslProperties.missingCertificate(sslMode, caCertificate) &&
            (
                !useSsh || (
                    sshHost.isNotBlank() &&
                        sshUser.isNotBlank() &&
                        sshPort.toIntOrNull() != null &&
                        hasSshCredential &&
                        jumpIsComplete
                    )
                )

    /**
     * A jump host is either left out entirely or filled in: a host with no user is a connection
     * that would fail at the first hop, and there is no sensible default for a user name.
     */
    private val jumpIsComplete: Boolean
        get() = jumpHost.isBlank() || (jumpUser.isNotBlank() && jumpPort.toIntOrNull() != null)

    private val hasSshCredential: Boolean
        get() = when (sshAuthMethod) {
            SshAuthMethod.KEY -> sshKeyId != null
            SshAuthMethod.PASSWORD -> sshPassword.isNotEmpty()
        }
}

@HiltViewModel
class ConnectionEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val certificateStore: CertificateStore,
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
                    _form.value = entity.toForm(
                        hasPassword = connections.hasPassword(entity.id),
                        hasSshPassword = connections.hasSshPassword(entity.id),
                    )
                }
            }
        }
    }

    fun update(transform: (ConnectionForm) -> ConnectionForm) {
        _form.value = transform(_form.value)
    }

    /**
     * Switching the tunnel also moves the sensible TLS default: inside a tunnel the tunnel is the
     * encryption, while a direct connection should verify the server it is talking to.
     */
    fun setUseSsh(useSsh: Boolean) {
        val current = _form.value
        val untouchedDefault = current.sslMode == SslMode.defaultFor(current.useSsh)
        _form.value = current.copy(
            useSsh = useSsh,
            sslMode = if (untouchedDefault) SslMode.defaultFor(useSsh) else current.sslMode,
        )
    }

    /** Copies a CA certificate chosen in the file picker into the app's storage. */
    fun importCertificate(uri: Uri, name: String) {
        viewModelScope.launch {
            try {
                val stored = certificateStore.import(uri, name)
                _form.value = _form.value.copy(caCertificate = stored)
            } catch (e: Exception) {
                _error.value = context.getString(R.string.error_certificate_invalid)
            }
        }
    }

    fun clearCertificate() {
        _form.value = _form.value.copy(caCertificate = null)
    }

    /** CA files already imported, so a second connection can reuse one. */
    val availableCertificates: List<String> get() = certificateStore.list()

    fun save(onSaved: (Long) -> Unit) {
        val form = _form.value
        if (!form.canSave) return
        viewModelScope.launch {
            try {
                val saved = connections.save(
                    form.toEntity(),
                    // Only re-seal a password when its field was actually edited.
                    password = form.password.toCharArray().takeIf { form.passwordTouched },
                    sshPassword = form.sshPassword.toCharArray()
                        .takeIf { form.sshPasswordTouched && form.useSsh },
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
        sshAuthMethod = sshAuthMethod.name,
        sshJumpHost = jumpHost.trim().takeIf { it.isNotBlank() },
        sshJumpPort = jumpPort.toIntOrNull() ?: 22,
        sshJumpUser = jumpUser.trim().takeIf { it.isNotBlank() },
        // Kept rather than cleared when the tunnel or the method changes, so going back is painless.
        sshKeyId = sshKeyId,
        dbHost = dbHost.trim(),
        dbPort = dbPort.toIntOrNull() ?: 3306,
        database = database.trim(),
        dbUser = dbUser.trim(),
        readOnly = readOnly,
        sslMode = sslMode.name,
        caCertificate = caCertificate,
    )

    private fun ConnectionEntity.toForm(hasPassword: Boolean, hasSshPassword: Boolean) = ConnectionForm(
        id = id,
        name = name,
        color = ConnectionColor.fromName(color),
        useSsh = useSshTunnel,
        sshHost = sshHost,
        sshPort = sshPort.toString(),
        sshUser = sshUser,
        sshAuthMethod = SshAuthMethod.fromName(sshAuthMethod),
        jumpHost = sshJumpHost.orEmpty(),
        jumpPort = sshJumpPort.toString(),
        jumpUser = sshJumpUser.orEmpty(),
        sshKeyId = sshKeyId,
        sshPassword = if (hasSshPassword) PLACEHOLDER_PASSWORD else "",
        sshPasswordTouched = false,
        dbHost = dbHost,
        dbPort = dbPort.toString(),
        database = database,
        dbUser = dbUser,
        password = if (hasPassword) PLACEHOLDER_PASSWORD else "",
        passwordTouched = false,
        readOnly = readOnly,
        sslMode = SslMode.fromName(sslMode),
        caCertificate = caCertificate,
    )

    private companion object {
        /** Stands in for a stored password so the field is not blank; never sent anywhere. */
        const val PLACEHOLDER_PASSWORD = "••••••••"
    }
}
