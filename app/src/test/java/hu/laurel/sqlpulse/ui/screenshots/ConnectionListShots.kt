package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.ssh.ConnectStep
import hu.laurel.sqlpulse.ssh.TunnelState
import hu.laurel.sqlpulse.ui.connections.ConnectionListActions
import hu.laurel.sqlpulse.ui.connections.ConnectionListContent
import hu.laurel.sqlpulse.ui.connections.ConnectionListUiState
import org.junit.Rule
import org.junit.Test

class ConnectionListShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private val connections = listOf(
        connection(1, "Webshop", "PRODUCTION", "Production", "bastion.laurel.hu", "db-prod-01", "shop", ssl = "REQUIRED"),
        connection(2, "Számlázó", "TEST", "Amber", "jump.test.local", "10.0.4.12", "billing", readOnly = false),
        connection(3, "Riport replika", "DEVELOPMENT", "Blue", "reports.dev", "reports.dev", "dwh", tunnel = false),
        connection(4, "Régi ERP", "DEVELOPMENT", "Purple", "erp-gw.laurel.hu", "localhost", "erp"),
    )

    @Test
    fun connecting() = paparazzi.screen {
        ConnectionListContent(
            state = ConnectionListUiState(
                connections = connections,
                tunnel = TunnelState.Connecting(2, ConnectStep.MYSQL),
            ),
            confirming = null,
            now = NOW,
            actions = ConnectionListActions(),
        )
    }

    @Test
    fun light() = paparazzi.screen(dark = false) {
        ConnectionListContent(
            state = ConnectionListUiState(
                connections = connections,
                tunnel = TunnelState.Active(1, "127.0.0.1", 40001, NOW, tunnelled = true),
            ),
            confirming = null,
            now = NOW,
            actions = ConnectionListActions(),
        )
    }

    private fun connection(
        id: Long,
        name: String,
        environment: String,
        color: String,
        sshHost: String,
        dbHost: String,
        database: String,
        ssl: String = "DISABLED",
        readOnly: Boolean = true,
        tunnel: Boolean = true,
    ) = ConnectionEntity(
        id = id,
        name = name,
        color = color,
        useSshTunnel = tunnel,
        sshHost = sshHost,
        sshUser = "deploy",
        sshKeyId = null,
        dbHost = dbHost,
        database = database,
        dbUser = "app_ro",
        readOnly = readOnly,
        environment = environment,
        sslMode = ssl,
        lastUsedAt = NOW - id * 3_600_000,
    )

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}
