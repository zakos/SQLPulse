package hu.laurel.sqlpulse.ui.screenshots

import android.net.Uri
import hu.laurel.sqlpulse.data.db.KeyMaterialFormat
import hu.laurel.sqlpulse.data.db.SshKeyAlgorithm
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.data.schema.Health
import hu.laurel.sqlpulse.data.schema.Series
import hu.laurel.sqlpulse.data.schema.ServerFact
import hu.laurel.sqlpulse.data.settings.Settings
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.keys.KeyImportState
import hu.laurel.sqlpulse.ui.keys.KeyStoreScreenContent
import hu.laurel.sqlpulse.ui.keys.KeyStoreController
import hu.laurel.sqlpulse.ui.pulse.Metric
import hu.laurel.sqlpulse.ui.pulse.MetricId
import hu.laurel.sqlpulse.ui.pulse.PulseController
import hu.laurel.sqlpulse.ui.pulse.PulseInterval
import hu.laurel.sqlpulse.ui.pulse.PulseScreenContent
import hu.laurel.sqlpulse.ui.pulse.PulseUiState
import hu.laurel.sqlpulse.ui.server.ServerController
import hu.laurel.sqlpulse.ui.server.ServerPanel
import hu.laurel.sqlpulse.ui.server.ServerScreenContent
import hu.laurel.sqlpulse.ui.server.ServerUiState
import hu.laurel.sqlpulse.ui.settings.SettingsController
import hu.laurel.sqlpulse.ui.settings.SettingsScreenContent
import hu.laurel.sqlpulse.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class ToolScreenShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @Test
    fun pulse() = paparazzi.screen {
        PulseScreenContent(onBack = {}, viewModel = object : PulseController {
            override val uiState: StateFlow<PulseUiState> = MutableStateFlow(
                PulseUiState(
                    connected = true,
                    live = true,
                    interval = PulseInterval.NORMAL,
                    metrics = listOf(
                        Metric(MetricId.QUERIES, "1 284", Health.CALM, series(900, 1010, 980, 1120, 1300, 1190, 1250, 1284)),
                        Metric(MetricId.THREADS_RUNNING, "7", Health.CALM, series(3, 4, 3, 6, 5, 9, 8, 7)),
                        Metric(MetricId.LOCK_WAITS, "2", Health.BUSY, series(0, 0, 0, 1, 0, 1, 3, 2)),
                        Metric(MetricId.BUFFER_HIT, "99,4 %", Health.CALM, series(99.8, 99.7, 99.8, 99.6, 99.5, 99.6, 99.4, 99.4)),
                        Metric(MetricId.SLOW_QUERIES, "3", Health.ALARMED, series(0, 0, 1, 0, 0, 2, 1, 3)),
                        Metric(MetricId.THREADS_CONNECTED, "142", Health.CALM, series(120, 128, 131, 135, 140, 138, 141, 142)),
                        Metric(MetricId.TRAFFIC_OUT, "4,8 MB/s", Health.CALM, series(3.1, 3.9, 4.2, 3.6, 4.4, 5.2, 4.9, 4.8)),
                        Metric(MetricId.REPLICATION_LAG, "—"),
                    ),
                ),
            )
            override fun setInterval(interval: PulseInterval) = Unit
            override fun start() = Unit
            override fun stop() = Unit
        })
    }

    @Test
    fun server() = paparazzi.screen {
        ServerScreenContent(onBack = {}, viewModel = object : ServerController {
            override val uiState: StateFlow<ServerUiState> = MutableStateFlow(
                ServerUiState(
                    connected = true,
                    panel = ServerPanel.QUERIES,
                    facts = listOf(
                        ServerFact("Kiszolgáló", "MySQL 8.0.39"),
                        ServerFact("Driver", "MariaDB Connector/J 3.4"),
                        ServerFact("Titkosítás", "TLS 1.3 · AES_256_GCM"),
                        ServerFact("Üzemidő", "41 nap 6 óra"),
                    ),
                    processes = ResultTable(
                        columns = listOf(
                            ColumnMeta("Id", CellType.NUMBER, "BIGINT", null),
                            ColumnMeta("User", CellType.TEXT, "VARCHAR", null),
                            ColumnMeta("Time", CellType.NUMBER, "INT", null),
                            ColumnMeta("State", CellType.TEXT, "VARCHAR", null),
                            ColumnMeta("Info", CellType.TEXT, "VARCHAR", null),
                        ),
                        rows = listOf(
                            listOf(CellValue.Number("1842"), CellValue.Text("report"), CellValue.Number("84"), CellValue.Text("Sending data"), CellValue.Text("SELECT c.*, SUM(ii.qty) FROM customers c JOIN …")),
                            listOf(CellValue.Number("1851"), CellValue.Text("app"), CellValue.Number("2"), CellValue.Text("Waiting for row lock"), CellValue.Text("UPDATE invoices SET status = 'paid' WHERE id = 20416")),
                            listOf(CellValue.Number("1860"), CellValue.Text("app_ro"), CellValue.Number("0"), CellValue.Text("executing"), CellValue.Text("SELECT * FROM information_schema.PROCESSLIST")),
                        ),
                    ),
                ),
            )
            override fun dismissGrants() = Unit
            override fun kill(processId: Long) = Unit
            override fun refresh() = Unit
            override fun selectPanel(panel: ServerPanel) = Unit
            override fun showGrants(account: String) = Unit
        })
    }

    @Test
    fun settings() = paparazzi.screen {
        SettingsScreenContent(onBack = {}, onOpenKeyStore = {}, onOpenBackup = {}, viewModel = object : SettingsController {
            override val settings: StateFlow<Settings> = MutableStateFlow(Settings(theme = ThemePreference.Dark, autoLockMinutes = 15))
            override fun setAutoLock(minutes: Int) = Unit
            override fun setBlockScreenshots(block: Boolean) = Unit
            override fun setBlockWritesWithoutWhere(block: Boolean) = Unit
            override fun setGridFontScale(scale: Int) = Unit
            override fun setMaxAffectedRows(rows: Int) = Unit
            override fun setRowLimit(limit: Int) = Unit
            override fun setTheme(theme: ThemePreference) = Unit
        })
    }

    @Test
    fun keyStore() = paparazzi.screen {
        KeyStoreScreenContent(onBack = {}, viewModel = object : KeyStoreController {
            override val importState: StateFlow<KeyImportState> = MutableStateFlow(KeyImportState())
            override val keys: StateFlow<List<SshKeyEntity>> = MutableStateFlow(
                listOf(
                    key(1, "laptop-ed25519", SshKeyAlgorithm.ED25519, 256, "SHA256:q3Vx0kLr2cX7yQmN8pT5sLd0Fj1aB9fKc"),
                    key(2, "prod-deploy", SshKeyAlgorithm.RSA, 4096, "SHA256:Hk2mW7Qe8rTy1uIo3pAs5dFg7hJk9lT0wA"),
                    key(3, "telefon-generalt", SshKeyAlgorithm.ED25519, 256, "SHA256:b81Zq4Xc6vBn8mQw2eRt4yUi6oPa8sLmQ2"),
                ),
            )
            override fun clearError() = Unit
            override fun delete(key: SshKeyEntity) = Unit
            override fun dismissImportState() = Unit
            override fun generate(name: String) = Unit
            override fun import(name: String, text: String, passphrase: CharArray?) = Unit
            override fun loadFromUri(uri: Uri, onLoaded: (String) -> Unit) = Unit
            override fun onKeyTextChanged(text: String) = Unit
        })
    }

    private fun key(id: Long, name: String, algorithm: SshKeyAlgorithm, bits: Int, fingerprint: String) = SshKeyEntity(
        id = id,
        name = name,
        algorithm = algorithm,
        bits = bits,
        fingerprint = fingerprint,
        materialFormat = KeyMaterialFormat.values().first(),
        sealedPrivateKey = ByteArray(0),
        publicKey = "ssh-ed25519 AAAA… $name",
        createdAt = 0,
    )

    private fun series(vararg v: Number) = Series(points = v.map { it.toDouble() })
}
