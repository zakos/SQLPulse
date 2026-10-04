package hu.laurel.sqlpulse.ui.diagnostics

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.diagnostics.AppFacts
import hu.laurel.sqlpulse.data.diagnostics.ConnectionFacts
import hu.laurel.sqlpulse.data.diagnostics.Diagnostics
import hu.laurel.sqlpulse.data.diagnostics.DiagnosticsReport
import hu.laurel.sqlpulse.data.diagnostics.DiagnosticsSource
import hu.laurel.sqlpulse.data.diagnostics.LastErrorFacts
import hu.laurel.sqlpulse.data.diagnostics.ServerFacts
import hu.laurel.sqlpulse.data.schema.ServerRepository
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.data.sql.SslMode
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val report: DiagnosticsReport? = null,
    val loading: Boolean = true,
    /** False when there is no live session: the report then covers the app and the device only. */
    val connected: Boolean = false,
    /** Set once the user has copied it, so the screen can say so. */
    val copied: Boolean = false,
)

/**
 * Collects what a bug report needs.
 *
 * All this does is read what the app already has and hand it to
 * [hu.laurel.sqlpulse.data.diagnostics.Diagnostics], which decides what may be said. Nothing is
 * unsealed for a report: the credentials stay behind the keystore, and the fields that would hold
 * them are left at their defaults — the report could not print them either way, but there is no
 * reason to have them in memory to find that out.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SqlSessionManager,
    private val server: ServerRepository,
) : ViewModel(), DiagnosticsController {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    override val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, copied = false)
            val state = sessions.state.value
            val connection = sessions.currentConnection()
            // The overview is one statement and may be refused outright — seeing the server's
            // variables needs a grant the user may not have. A report without them is still a
            // report, so a failure here is not one.
            val variables = if (connection != null) {
                runCatching { server.overview().map { it.label to it.value } }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            _uiState.value = DiagnosticsUiState(
                report = Diagnostics.report(
                    DiagnosticsSource(
                        app = appFacts(),
                        connection = connection?.let { facts(it) },
                        server = ServerFacts(variables).takeIf { variables.isNotEmpty() },
                        lastError = (state as? SqlSessionState.Failed)?.failure
                            // No timestamp is kept for it, so the report says the age is unknown
                            // rather than guessing at "just now".
                            ?.let { LastErrorFacts(failure = it) },
                        nowMs = System.currentTimeMillis(),
                    ),
                ),
                loading = false,
                connected = connection != null,
            )
        }
    }

    override fun markCopied() {
        _uiState.value = _uiState.value.copy(copied = true)
    }

    private fun appFacts(): AppFacts {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        return AppFacts(
            versionName = info?.versionName,
            versionCode = info?.longVersionCode ?: 0,
            androidRelease = Build.VERSION.RELEASE,
            androidSdk = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
        )
    }

    /**
     * The stored connection, reduced to what it is rather than where it points.
     *
     * Note what is not mapped: the name, the hosts, the database, the account. The report has no
     * key for them, so passing them would change nothing — but a mapper that never reads them is
     * one less thing to check when this file changes.
     */
    private fun facts(connection: ConnectionEntity): ConnectionFacts = ConnectionFacts(
        useSshTunnel = connection.useSshTunnel,
        hasJumpHost = !connection.sshJumpHost.isNullOrBlank(),
        sshAuthMethod = connection.sshAuthMethod,
        sslMode = SslMode.fromName(connection.sslMode),
        hasCaCertificate = !connection.caCertificate.isNullOrBlank(),
        readOnly = connection.readOnly,
        environment = ConnectionEnvironment.fromName(connection.environment),
        connectTimeoutSeconds = connection.connectTimeoutSeconds,
        queryTimeoutSeconds = connection.queryTimeoutSeconds,
        driver = sessions.driverInUse(),
    )
}
