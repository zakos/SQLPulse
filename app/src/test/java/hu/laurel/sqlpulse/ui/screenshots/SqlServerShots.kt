package hu.laurel.sqlpulse.ui.screenshots

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.ssh.HostKeyPrompt
import hu.laurel.sqlpulse.ssh.TunnelState
import hu.laurel.sqlpulse.ui.connections.ConnectionEditorController
import hu.laurel.sqlpulse.ui.connections.ConnectionEditorScreenContent
import hu.laurel.sqlpulse.ui.connections.ConnectionForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

/**
 * The connection editor with SQL Server selected: the engine notes (SQL authentication only, the
 * database model, `GO`), the read-only warning beside the switch — the driver ignores the flag —
 * and the TLS note for Azure SQL (verification without a CA file).
 */
class SqlServerShots {
    @get:Rule
    val paparazzi = app.cash.paparazzi.Paparazzi(deviceConfig = DesignPhone, showSystemUi = false)

    private val form = ConnectionForm(
        name = "Azure rendelések", environment = ConnectionEnvironment.TEST, useSsh = false,
        dbHost = "rendelesek.database.windows.net", database = "orders", dbUser = "report_reader",
        readOnly = true, sslMode = SslMode.VERIFY_IDENTITY,
    ).withEngine(DatabaseEngine.SQLSERVER)

    /** The top of the form: the engine and what is different about it. */
    @Test
    fun editorSqlServer() = paparazzi.screen {
        ConnectionEditorScreenContent(onBack = {}, onOpenKeyStore = {}, viewModel = FakeEditor(form))
    }

    /**
     * The same form scrolled down to the read-only switch, its warning and the TLS note: the
     * editor is a scrolling column, which a still image only shows from the top, so the screen
     * is drawn tall and shifted up inside a clipped box.
     */
    @Test
    fun editorSqlServerReadOnlyWarning() = paparazzi.screen {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            Box(Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true).requiredHeight(2200.dp).offset(y = (-1130).dp)) {
                ConnectionEditorScreenContent(onBack = {}, onOpenKeyStore = {}, viewModel = FakeEditor(form))
            }
        }
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
