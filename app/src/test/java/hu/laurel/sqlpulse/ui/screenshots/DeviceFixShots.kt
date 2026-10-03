package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.schema.ServerFact
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.ui.connections.ProductionConfirmCard
import hu.laurel.sqlpulse.ui.keys.KeyImportCard
import hu.laurel.sqlpulse.ui.keys.KeyImportState
import hu.laurel.sqlpulse.ui.schema.SchemaBrowserActions
import hu.laurel.sqlpulse.ui.schema.SchemaBrowserContent
import hu.laurel.sqlpulse.ui.schema.SchemaBrowserUiState
import hu.laurel.sqlpulse.ui.server.ServerController
import hu.laurel.sqlpulse.ui.server.ServerPanel
import hu.laurel.sqlpulse.ui.server.ServerScreenContent
import hu.laurel.sqlpulse.ui.server.ServerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

/** What the phone showed wrong on the first real-device test, drawn the way it should look. */
class DeviceFixShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private val facts = listOf(
        ServerFact("version", "8.4.3"),
        ServerFact("hostname", "mantis"),
        ServerFact("build", "MySQL Community Server - GPL"),
        ServerFact("current_user_name", "root@10.0.0.4"),
        ServerFact("max_connections", "151"),
        ServerFact("read_only", "0"),
        ServerFact("time_zone", "SYSTEM"),
        ServerFact("charset", "utf8mb4"),
        ServerFact("Uptime", "142747"),
        ServerFact("Threads_connected", "7"),
        ServerFact("Threads_running", "2"),
        ServerFact("Ssl_version", "TLSv1.3"),
        ServerFact("Ssl_cipher", "TLS_AES_256_GCM_SHA384"),
        ServerFact("JDBC driver", "modern"),
        ServerFact("some_unknown_variable", "x"),
    )

    private fun server(dark: Boolean, facts: List<ServerFact>) = paparazzi.screen(dark = dark) {
        ServerScreenContent(onBack = {}, viewModel = object : ServerController {
            override val uiState: StateFlow<ServerUiState> =
                MutableStateFlow(ServerUiState(connected = true, panel = ServerPanel.QUERIES, facts = facts))
            override fun dismissGrants() = Unit
            override fun kill(processId: Long) = Unit
            override fun refresh() = Unit
            override fun selectPanel(panel: ServerPanel) = Unit
            override fun setReplicationRaw(raw: Boolean) = Unit
            override fun setSlowSort(sort: SlowSort) = Unit
            override fun showGrants(account: String) = Unit
        })
    }

    @Test
    fun serverFactsDark() = server(true, facts)

    @Test
    fun serverFactsLight() = server(false, facts)

    @Test
    fun serverFactsNoTlsLight() = server(
        false,
        facts.filterNot { it.label.startsWith("Ssl") } + listOf(ServerFact("Ssl_version", ""), ServerFact("Ssl_cipher", "—")),
    )

    @Test
    fun schemaBrowserLongName() = paparazzi.screen(dark = false) {
        val connection = ConnectionEntity(
            id = 2, name = "mantis", color = "Amber", sshHost = "jump.test.local", sshUser = "deploy",
            sshKeyId = null, dbHost = "10.0.4.12", database = "mantis_production_eu", dbUser = "app_ro", environment = "PRODUCTION",
        )
        SchemaBrowserContent(
            state = SchemaBrowserUiState(
                session = SqlSessionState.Ready(connection, "8.4.3"),
                databases = listOf("mantis_production_eu"),
                selectedDatabase = "mantis_production_eu",
            ),
            capturedAt = null,
            actions = SchemaBrowserActions(),
        )
    }

    @Composable
    private fun Over(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0xA0050608)))
            Box(Modifier.align(Alignment.Center).padding(horizontal = 16.dp)) { content() }
        }
    }

    @Test
    fun productionConfirmServer() = paparazzi.screen(dark = false) {
        Over {
            ProductionConfirmCard("webshop", "10.0.4.12:3306/shop", isFile = false, onConfirm = {}, onCancel = {})
        }
    }

    @Test
    fun productionConfirmFile() = paparazzi.screen(dark = false) {
        Over {
            ProductionConfirmCard("basic", "basic.db", isFile = true, onConfirm = {}, onCancel = {})
        }
    }

    @Test
    fun keyImport() = paparazzi.screen(dark = false) {
        Over {
            KeyImportCard(
                state = KeyImportState(),
                keyText = "",
                onKeyTextChanged = {},
                onPickFile = {},
                onImport = { _, _ -> },
                onGenerate = {},
                onDismiss = {},
            )
        }
    }
}
