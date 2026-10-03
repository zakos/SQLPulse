package hu.laurel.sqlpulse.ui.screenshots

import android.net.Uri
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.ssh.HostKeyPrompt
import hu.laurel.sqlpulse.ssh.TunnelState
import hu.laurel.sqlpulse.ui.connections.ConnectionEditorController
import hu.laurel.sqlpulse.ui.connections.ConnectionEditorScreenContent
import hu.laurel.sqlpulse.ui.connections.ConnectionForm
import hu.laurel.sqlpulse.ui.engine.EngineUnavailableContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

/**
 * The engine choice in the connection editor, before the other engines land (phase 1 of
 * docs/tobb-motor-terv.md): PostgreSQL selectable but "coming soon", SQLite with its file
 * placeholder instead of the server fields, and the page a MySQL-only screen shows elsewhere.
 */
class EngineShots {
    @get:Rule
    val paparazzi = app.cash.paparazzi.Paparazzi(deviceConfig = DesignPhone.copy(screenHeight = 1700), showSystemUi = false)

    @Test
    fun notAvailableForEngine() = paparazzi.screen {
        EngineUnavailableContent(engineName = "PostgreSQL", onBack = {})
    }

    @androidx.compose.runtime.Composable
    private fun Editor(form: ConnectionForm) {
        ConnectionEditorScreenContent(onBack = {}, onOpenKeyStore = {}, viewModel = FakeEditor(form))
    }

    private class FakeEditor(form: ConnectionForm) : ConnectionEditorController {
        override val form: StateFlow<ConnectionForm> = MutableStateFlow(form)
        override val error: StateFlow<String?> = MutableStateFlow(null)
        override val hostKeyPrompt: StateFlow<HostKeyPrompt?> = MutableStateFlow(null)
        override val keys: StateFlow<List<SshKeyEntity>> = MutableStateFlow(emptyList())
        override val readOnlyOffer: StateFlow<Boolean> = MutableStateFlow(false)
        override val serverVersion: StateFlow<String?> = MutableStateFlow(null)
        override val tunnel: StateFlow<TunnelState> = MutableStateFlow(TunnelState.Disconnected)
        override fun acceptHostKey() = Unit
        override fun acceptReadOnlyOffer() = Unit
        override fun clearCertificate() = Unit
        override fun dismissReadOnlyOffer() = Unit
        override fun importCertificate(uri: Uri, name: String) = Unit
        override fun rejectHostKey() = Unit
        override fun save(onSaved: (Long) -> Unit) = Unit
        override fun setEnvironment(environment: ConnectionEnvironment) = Unit
        override fun setUseSsh(useSsh: Boolean) = Unit
        override fun stopTest() = Unit
        override fun test() = Unit
        override fun update(transform: (ConnectionForm) -> ConnectionForm) = Unit
    }
}
