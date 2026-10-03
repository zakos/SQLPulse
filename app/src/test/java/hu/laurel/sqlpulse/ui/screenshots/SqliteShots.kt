package hu.laurel.sqlpulse.ui.screenshots

import android.net.Uri
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.ssh.HostKeyPrompt
import hu.laurel.sqlpulse.ssh.TunnelState
import hu.laurel.sqlpulse.ui.connections.ConnectionEditorController
import hu.laurel.sqlpulse.ui.connections.ConnectionEditorScreenContent
import hu.laurel.sqlpulse.ui.connections.ConnectionForm
import hu.laurel.sqlpulse.ui.connections.ConnectionListActions
import hu.laurel.sqlpulse.ui.connections.ConnectionListContent
import hu.laurel.sqlpulse.ui.connections.ConnectionListUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

/**
 * A SQLite file as a connection: the editor's file section in the states it can be in, and the
 * connection list with file cards next to a server one.
 */
class SqliteShots {
    // Tall, so the whole file section shows above the pinned save bar.
    @get:Rule
    val paparazzi = app.cash.paparazzi.Paparazzi(deviceConfig = DesignPhone.copy(screenHeight = 2300), showSystemUi = false)

    @Test
    fun editor() = paparazzi.screen {
        Editor(
            ConnectionForm(name = "Terepi felmérés", environment = ConnectionEnvironment.DEVELOPMENT)
                .withEngine(DatabaseEngine.SQLITE)
                .copy(
                    fileName = "felmeres-2026-10.db", fileSize = 3_412_992, fileCopiedAt = COPIED_AT,
                    fileCanRefresh = true,
                ),
        )
    }

    /** Writable, picked just now, and in WAL mode: the notes that can stack up. */
    @Test
    fun editorWritable() = paparazzi.screen {
        Editor(
            ConnectionForm(name = "Leltár", environment = ConnectionEnvironment.TEST, readOnly = false)
                .withEngine(DatabaseEngine.SQLITE)
                .copy(
                    fileName = "leltar.sqlite3", fileSize = 18_874_368, fileCopiedAt = COPIED_AT,
                    fileWal = true, fileStaged = "/data/staging.part",
                ),
        )
    }

    /** A connection restored from a backup: the name is back, the file is not. */
    @Test
    fun editorMissing() = paparazzi.screen {
        Editor(
            ConnectionForm(id = 5, name = "Régi mérések", environment = ConnectionEnvironment.UNSET)
                .withEngine(DatabaseEngine.SQLITE)
                .copy(fileName = "meresek.db"),
        )
    }

    @Test
    fun list() = paparazzi.screen {
        ConnectionListContent(
            state = ConnectionListUiState(
                connections = listOf(
                    file(1, "Terepi felmérés", "felmeres-2026-10.db", "DEVELOPMENT", readOnly = true),
                    file(2, "Leltár", "leltar.sqlite3", "TEST", readOnly = false),
                    file(3, "Régi mérések", "meresek.db", "UNSET", readOnly = true),
                    ConnectionEntity(
                        id = 4, name = "Riport replika", color = "Blue", useSshTunnel = false, sshHost = "",
                        sshUser = "", sshKeyId = null, dbHost = "reports.dev", database = "dwh", dbUser = "ro",
                        environment = "DEVELOPMENT",
                    ),
                ),
                // The third has no copy on this device.
                fileSizes = mapOf(1L to 3_412_992L, 2L to 18_874_368L),
            ),
            confirming = null,
            now = COPIED_AT,
            actions = ConnectionListActions(),
        )
    }

    private fun file(id: Long, name: String, fileName: String, environment: String, readOnly: Boolean) =
        ConnectionEntity(
            id = id, name = name, color = "Teal", engine = "SQLITE", fileName = fileName,
            useSshTunnel = false, sshHost = "", sshUser = "", sshKeyId = null, dbHost = "", database = "",
            dbUser = "", readOnly = readOnly, environment = environment,
        )

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

    private companion object {
        const val COPIED_AT = 1_790_000_000_000L
    }
}
