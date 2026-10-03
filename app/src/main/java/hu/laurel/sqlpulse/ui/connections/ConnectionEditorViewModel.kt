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
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.connection.ConnectionRepository
import hu.laurel.sqlpulse.data.connection.ConnectionTimeouts
import hu.laurel.sqlpulse.data.connection.DatabaseFileInfo
import hu.laurel.sqlpulse.data.connection.JumpCredential
import hu.laurel.sqlpulse.data.connection.JumpHostCredentials
import hu.laurel.sqlpulse.data.connection.LocalDatabaseFiles
import hu.laurel.sqlpulse.data.connection.ProductionPolicy
import hu.laurel.sqlpulse.data.connection.ProductionShape
import hu.laurel.sqlpulse.data.connection.SaveRefusal
import hu.laurel.sqlpulse.data.connection.SqliteFile
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.data.keys.SshKeyRepository
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.data.sql.SslProperties
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialects
import hu.laurel.sqlpulse.di.IoDispatcher
import hu.laurel.sqlpulse.security.UnlockCancelledException
import hu.laurel.sqlpulse.ssh.HostKeyPrompt
import hu.laurel.sqlpulse.ssh.SshAuthMethod
import hu.laurel.sqlpulse.ssh.TunnelManager
import hu.laurel.sqlpulse.ssh.TunnelState
import hu.laurel.sqlpulse.ui.theme.ConnectionColor
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConnectionForm(
    val id: Long = 0,
    /** Which database product this is; decides which of the fields below mean anything. */
    val engine: DatabaseEngine = DatabaseEngine.MYSQL,
    /** SQLite: where the file was picked from, and its name. Unused by server engines. */
    val fileUri: String? = null,
    val fileName: String? = null,
    /** Size and time of the app's copy, from the saved copy or the one just made. */
    val fileSize: Long? = null,
    val fileCopiedAt: Long? = null,
    /** The file is in WAL mode, so changes still in its `-wal` file are not in the copy. */
    val fileWal: Boolean = false,
    /** A copy made in this editor and not yet attached to a saved connection (its path). */
    val fileStaged: String? = null,
    /** Whether the original can still be read without the picker, so the copy can be refreshed. */
    val fileCanRefresh: Boolean = false,
    /** A copy is being made. */
    val fileBusy: Boolean = false,
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
    /**
     * Off means the first hop uses the credential of the second, which is what every connection
     * did before the jump host could have one of its own.
     */
    val jumpSeparateCredential: Boolean = false,
    val jumpAuthMethod: SshAuthMethod = SshAuthMethod.KEY,
    val jumpKeyId: Long? = null,
    val jumpPassword: String = "",
    val jumpPasswordTouched: Boolean = false,
    val dbHost: String = "localhost",
    val dbPort: String = "3306",
    val database: String = "",
    val dbUser: String = "",
    val password: String = "",
    val passwordTouched: Boolean = false,
    val readOnly: Boolean = true,
    /** Development, test, production, or left unsaid. Groups the list and warns before opening. */
    val environment: ConnectionEnvironment = ConnectionEnvironment.UNSET,
    /** Seconds, as typed: kept as text so a half-deleted number does not become a default. */
    val connectTimeout: String = ConnectionTimeouts.DEFAULT_CONNECT_SECONDS.toString(),
    val queryTimeout: String = ConnectionTimeouts.DEFAULT_QUERY_SECONDS.toString(),
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
            // An engine whose implementation has not landed is shown, but cannot be saved.
            SqlDialects.forEngine(engine).connectable &&
            (if (engine.hasServer) canSaveServer else hasFile)

    /**
     * A file connection needs a copy to open: one saved earlier or one made now. A connection
     * restored from a backup has a name but no copy, and cannot be saved until the file is chosen.
     */
    val hasFile: Boolean
        get() = fileName != null && (fileStaged != null || fileSize != null) && !fileBusy

    /** The server half of [canSave]: host, port, TLS, timeouts and, with a tunnel, the SSH host. */
    private val canSaveServer: Boolean
        get() = dbHost.isNotBlank() &&
            dbPort.toIntOrNull() != null &&
            // A verifying TLS mode without a CA file would fail at connect time, not at save.
            // SQL Server verifies against the phone's own CAs without a file, which is what Azure
            // SQL (a public CA) needs.
            (engine == DatabaseEngine.SQLSERVER || !SslProperties.missingCertificate(sslMode, caCertificate)) &&
            ConnectionTimeouts.isValid(connectTimeout) &&
            ConnectionTimeouts.isValid(queryTimeout) &&
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
        get() = jumpHost.isBlank() || (
            jumpUser.isNotBlank() &&
                jumpPort.toIntOrNull() != null &&
                hasJumpCredential
            )

    /**
     * The form switched to [next]. The port follows the engine while it still holds the previous
     * engine's default — a port the user typed is theirs and stays.
     */
    fun withEngine(next: DatabaseEngine): ConnectionForm {
        val port = dbPort.trim()
        val followsDefault = port.isEmpty() || port == engine.defaultPort?.toString()
        return copy(
            engine = next,
            dbPort = if (followsDefault && next.defaultPort != null) next.defaultPort.toString() else dbPort,
        )
    }

    /** Nothing to check while the hops share a credential: the one above has already been checked. */
    private val hasJumpCredential: Boolean
        get() = !jumpSeparateCredential || when (jumpAuthMethod) {
            SshAuthMethod.KEY -> jumpKeyId != null
            SshAuthMethod.PASSWORD -> jumpPassword.isNotEmpty()
        }

    /** What [ProductionPolicy] judges: the rules read the form, not the saved row. */
    val shape: ProductionShape
        get() = ProductionShape(
            environment = environment,
            tunnelled = useSsh,
            sslMode = sslMode,
            readOnly = readOnly,
            queryTimeoutSeconds = ConnectionTimeouts.parse(queryTimeout)
                ?: ConnectionTimeouts.DEFAULT_QUERY_SECONDS,
            local = !engine.hasServer,
        )

    /** Why this form may not be saved as it stands, or null. */
    val refusal: SaveRefusal? get() = ProductionPolicy.refusal(shape)

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
    private val localFiles: LocalDatabaseFiles,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel(), ConnectionEditorController {

    private val connectionId: Long = savedStateHandle.get<Long>("connectionId") ?: 0L

    private val _form = MutableStateFlow(ConnectionForm())
    override val form: StateFlow<ConnectionForm> = _form.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    override val keys: StateFlow<List<SshKeyEntity>> = keyRepository.observeKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val tunnel: StateFlow<TunnelState> = tunnelManager.state

    override val serverVersion: StateFlow<String?> = tunnelManager.serverVersion

    init {
        if (connectionId != 0L) {
            viewModelScope.launch {
                connections.byId(connectionId)?.let { entity ->
                    val info = withContext(io) { localFiles.info(entity.id) }
                    _form.value = entity.toForm(
                        info = info,
                        canRefresh = localFiles.canRefresh(entity.fileUri),
                        hasPassword = connections.hasPassword(entity.id),
                        hasSshPassword = connections.hasSshPassword(entity.id),
                        hasJumpSshPassword = connections.hasJumpSshPassword(entity.id),
                    )
                }
            }
        }
    }

    override fun update(transform: (ConnectionForm) -> ConnectionForm) {
        _form.value = transform(_form.value)
    }

    /**
     * The offer shown when a connection is marked as production while it may still be written to.
     *
     * An offer rather than a silent change: turning somebody's read-only switch on behind their
     * back is how a feature gets a reputation for fighting the user.
     */
    private val _readOnlyOffer = MutableStateFlow(false)
    override val readOnlyOffer: StateFlow<Boolean> = _readOnlyOffer.asStateFlow()

    override fun setEnvironment(environment: ConnectionEnvironment) {
        val current = _form.value
        _form.value = current.copy(environment = environment)
        // Only worth asking where it would change something: a connection that is already
        // read-only needs no offer, and leaving production needs none either.
        if (ProductionPolicy.defaultReadOnly(environment) &&
            !current.environment.isProduction &&
            !current.readOnly
        ) {
            _readOnlyOffer.value = true
        }
    }

    override fun acceptReadOnlyOffer() {
        _form.value = _form.value.copy(readOnly = true)
        _readOnlyOffer.value = false
    }

    override fun dismissReadOnlyOffer() {
        _readOnlyOffer.value = false
    }

    /**
     * Switching the tunnel also moves the sensible TLS default: inside a tunnel the tunnel is the
     * encryption, while a direct connection should verify the server it is talking to.
     */
    override fun setUseSsh(useSsh: Boolean) {
        val current = _form.value
        val untouchedDefault = current.sslMode == SslMode.defaultFor(current.useSsh)
        _form.value = current.copy(
            useSsh = useSsh,
            sslMode = if (untouchedDefault) SslMode.defaultFor(useSsh) else current.sslMode,
        )
    }

    /** Copies a CA certificate chosen in the file picker into the app's storage. */
    override fun importCertificate(uri: Uri, name: String) {
        viewModelScope.launch {
            try {
                val stored = certificateStore.import(uri, name)
                _form.value = _form.value.copy(caCertificate = stored)
            } catch (e: Exception) {
                _error.value = context.getString(R.string.error_certificate_invalid)
            }
        }
    }

    override fun clearCertificate() {
        _form.value = _form.value.copy(caCertificate = null)
    }

    /** CA files already imported, so a second connection can reuse one. */
    val availableCertificates: List<String> get() = certificateStore.list()

    /** The copy made in this editor that nothing has claimed yet; deleted if the editor is left. */
    private var staged: File? = null

    /** A file picked in the system picker: kept in the app, and its permission remembered. */
    override fun chooseFile(uri: Uri) = stageFrom(uri, remember = true)

    /** The same file again, from where it was first picked, while Android still lets us read it. */
    override fun refreshFile() {
        val uri = _form.value.fileUri ?: return
        stageFrom(Uri.parse(uri), remember = false)
    }

    private fun stageFrom(uri: Uri, remember: Boolean) {
        if (_form.value.fileBusy) return
        _form.value = _form.value.copy(fileBusy = true)
        _error.value = null
        viewModelScope.launch {
            try {
                val copy = withContext(io) { localFiles.stage(uri) }
                if (remember) localFiles.rememberAccess(uri)
                staged?.let(localFiles::discard)
                staged = copy.file
                val current = _form.value
                _form.value = current.copy(
                    fileBusy = false,
                    fileUri = uri.toString(),
                    fileName = copy.name ?: current.fileName ?: DEFAULT_FILE_NAME,
                    fileSize = copy.info.sizeBytes,
                    fileCopiedAt = copy.info.copiedAt,
                    fileWal = copy.info.walMode,
                    fileStaged = copy.file.path,
                    fileCanRefresh = localFiles.canRefresh(uri.toString()),
                    // A connection with no name yet is named after the file, which is what the
                    // user would type anyway.
                    name = current.name.ifBlank { (copy.name ?: "").substringBeforeLast('.') },
                )
            } catch (e: SqliteFile.NotSqliteException) {
                _form.value = _form.value.copy(fileBusy = false)
                _error.value = context.getString(
                    if (e.empty) R.string.sqlite_error_empty else R.string.sqlite_error_not_database,
                )
            } catch (e: Exception) {
                _form.value = _form.value.copy(fileBusy = false)
                _error.value = context.getString(R.string.sqlite_error_copy, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    override fun onCleared() {
        staged?.let(localFiles::discard)
    }

    override fun save(onSaved: (Long) -> Unit) {
        val form = _form.value
        if (!form.canSave) return
        // The policy refuses before anything is written, and says which rule was broken: "cannot
        // save" on its own sends the user hunting through four sections of the form.
        form.refusal?.let { refusal ->
            _error.value = refusal.message()
            return
        }
        viewModelScope.launch {
            try {
                val saved = connections.save(
                    form.toEntity(),
                    // Only re-seal a password when its field was actually edited.
                    password = form.password.toCharArray().takeIf { form.passwordTouched },
                    sshPassword = form.sshPassword.toCharArray()
                        .takeIf { form.sshPasswordTouched && form.useSsh },
                    jumpSshPassword = form.jumpPassword.toCharArray().takeIf {
                        form.jumpPasswordTouched && form.useSsh && form.jumpSeparateCredential &&
                            form.jumpAuthMethod == SshAuthMethod.PASSWORD
                    },
                )
                val pending = staged
                if (pending != null && !form.engine.hasServer) {
                    try {
                        withContext(io) { localFiles.commit(pending, saved.id) }
                        staged = null
                    } catch (e: Exception) {
                        // The row exists now; the next save must update it, not add a second one.
                        _form.value = _form.value.copy(id = saved.id)
                        _error.value = context.getString(R.string.sqlite_error_copy, e.message ?: e.javaClass.simpleName)
                        return@launch
                    }
                }
                onSaved(saved.id)
            } catch (e: UnlockCancelledException) {
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    /** §7.2: save, build the tunnel, report step by step, then tear it down. */
    override fun test() {
        save { id -> tunnelManager.connect(id) }
    }

    override fun stopTest() = tunnelManager.disconnect()

    override fun acceptHostKey() {
        tunnelManager.acceptHostKey()
    }

    override fun rejectHostKey() {
        tunnelManager.rejectHostKey()
    }

    override val hostKeyPrompt: StateFlow<HostKeyPrompt?> = tunnelManager.hostKeyPrompt

    /** Explicit unblock after a host key change (§5). */
    fun forgetHostKey() {
        val form = _form.value
        viewModelScope.launch {
            tunnelManager.forgetHostKey(form.sshHost, form.sshPort.toIntOrNull() ?: 22)
        }
    }

    /** The refusal in the user's own words. */
    private fun SaveRefusal.message(): String = when (this) {
        SaveRefusal.Unprotected -> context.getString(R.string.policy_unprotected)
        is SaveRefusal.QueryTimeoutTooLong -> context.getString(
            R.string.policy_query_timeout_too_long,
            maxSeconds,
            requestedSeconds,
        )
    }

    private fun ConnectionForm.toEntity() = ConnectionEntity(
        id = id,
        name = name.trim(),
        color = color.name,
        engine = engine.name,
        fileUri = fileUri.takeUnless { engine.hasServer },
        fileName = fileName.takeUnless { engine.hasServer },
        // A file has nothing to tunnel to; saving the flag on would ask for an SSH key it never uses.
        useSshTunnel = useSsh && engine.hasServer,
        sshHost = sshHost.trim(),
        sshPort = sshPort.toIntOrNull() ?: 22,
        sshUser = sshUser.trim(),
        sshAuthMethod = sshAuthMethod.name,
        sshJumpHost = jumpHost.trim().takeIf { it.isNotBlank() },
        sshJumpPort = jumpPort.toIntOrNull() ?: 22,
        sshJumpUser = jumpUser.trim().takeIf { it.isNotBlank() },
        // Null is "the first hop shares the credential of the second" — the behaviour of every
        // row saved before these two columns existed, and the default here.
        sshJumpAuthMethod = jumpAuthMethod.name.takeIf { jumpSeparateCredential },
        sshJumpKeyId = jumpKeyId.takeIf {
            jumpSeparateCredential && jumpAuthMethod == SshAuthMethod.KEY
        },
        // Kept rather than cleared when the tunnel or the method changes, so going back is painless.
        sshKeyId = sshKeyId,
        dbHost = dbHost.trim(),
        dbPort = dbPort.toIntOrNull() ?: 3306,
        database = database.trim(),
        dbUser = dbUser.trim(),
        readOnly = readOnly,
        environment = environment.name,
        connectTimeoutSeconds = ConnectionTimeouts.parse(connectTimeout)
            ?: ConnectionTimeouts.DEFAULT_CONNECT_SECONDS,
        queryTimeoutSeconds = ConnectionTimeouts.parse(queryTimeout)
            ?: ConnectionTimeouts.DEFAULT_QUERY_SECONDS,
        sslMode = sslMode.name,
        caCertificate = caCertificate,
    )

    private fun ConnectionEntity.toForm(
        info: DatabaseFileInfo?,
        canRefresh: Boolean,
        hasPassword: Boolean,
        hasSshPassword: Boolean,
        hasJumpSshPassword: Boolean,
    ) = ConnectionForm(
        id = id,
        engine = DatabaseEngine.fromName(engine),
        fileUri = fileUri,
        fileName = fileName,
        fileSize = info?.sizeBytes,
        fileCopiedAt = info?.copiedAt,
        fileWal = info?.walMode == true,
        fileCanRefresh = canRefresh,
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
        jumpSeparateCredential = JumpHostCredentials.isSeparate(sshJumpAuthMethod, sshJumpKeyId),
        jumpAuthMethod = SshAuthMethod.fromName(sshJumpAuthMethod),
        jumpKeyId = (JumpHostCredentials.resolve(sshJumpAuthMethod, sshJumpKeyId)
            as? JumpCredential.Key)?.keyId,
        jumpPassword = if (hasJumpSshPassword) PLACEHOLDER_PASSWORD else "",
        jumpPasswordTouched = false,
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
        environment = ConnectionEnvironment.fromName(environment),
        connectTimeout = ConnectionTimeouts
            .sane(connectTimeoutSeconds, ConnectionTimeouts.DEFAULT_CONNECT_SECONDS).toString(),
        queryTimeout = ConnectionTimeouts
            .sane(queryTimeoutSeconds, ConnectionTimeouts.DEFAULT_QUERY_SECONDS).toString(),
        sslMode = SslMode.fromName(sslMode),
        caCertificate = caCertificate,
    )

    private companion object {
        /** Stands in for a stored password so the field is not blank; never sent anywhere. */
        const val PLACEHOLDER_PASSWORD = "••••••••"

        /** A provider that does not name the file still gets a connection that can be recognised. */
        const val DEFAULT_FILE_NAME = "database.db"
    }
}
