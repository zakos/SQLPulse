package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import hu.laurel.sqlpulse.data.schema.Health
import hu.laurel.sqlpulse.data.schema.MetricId
import hu.laurel.sqlpulse.data.schema.PostgresReplication
import hu.laurel.sqlpulse.data.schema.ReplicationReport
import hu.laurel.sqlpulse.data.schema.ReplicationStatus
import hu.laurel.sqlpulse.data.schema.Series
import hu.laurel.sqlpulse.data.schema.ServerFact
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.schema.SlowStatement
import hu.laurel.sqlpulse.data.schema.SlowStatementsReport
import hu.laurel.sqlpulse.data.schema.SqlServerReplication
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.PostgresDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlServerDialect
import hu.laurel.sqlpulse.ui.engine.EngineFeatures
import hu.laurel.sqlpulse.ui.engine.LocalEngineFeatures
import hu.laurel.sqlpulse.ui.pulse.Metric
import hu.laurel.sqlpulse.ui.pulse.PulseController
import hu.laurel.sqlpulse.ui.pulse.PulseInterval
import hu.laurel.sqlpulse.ui.pulse.PulseScreenContent
import hu.laurel.sqlpulse.ui.pulse.PulseUiState
import hu.laurel.sqlpulse.ui.server.ServerController
import hu.laurel.sqlpulse.ui.server.ServerPanel
import hu.laurel.sqlpulse.ui.server.ServerScreenContent
import hu.laurel.sqlpulse.ui.server.ServerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

/** The Server and Pulse screens as they read on PostgreSQL and SQL Server. */
class ServerEngineShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private fun series(vararg v: Number) = Series(points = v.map { it.toDouble() })

    private fun server(engine: DatabaseEngine, state: ServerUiState) = paparazzi.screen {
        CompositionLocalProvider(LocalEngineFeatures provides EngineFeatures.of(engine)) {
            ServerScreenContent(onBack = {}, viewModel = object : ServerController {
                override val uiState: StateFlow<ServerUiState> = MutableStateFlow(state)
                override fun dismissGrants() = Unit
                override fun kill(processId: Long) = Unit
                override fun refresh() = Unit
                override fun selectPanel(panel: ServerPanel) = Unit
                override fun setReplicationRaw(raw: Boolean) = Unit
                override fun setSlowSort(sort: SlowSort) = Unit
                override fun showGrants(account: String) = Unit
            })
        }
    }

    private fun pulse(engine: DatabaseEngine, vararg metrics: Metric) = paparazzi.screen {
        CompositionLocalProvider(LocalEngineFeatures provides EngineFeatures.of(engine)) {
            PulseScreenContent(onBack = {}, viewModel = object : PulseController {
                override val uiState: StateFlow<PulseUiState> = MutableStateFlow(
                    PulseUiState(connected = true, live = true, interval = PulseInterval.NORMAL, metrics = metrics.toList()),
                )
                override fun setInterval(interval: PulseInterval) = Unit
                override fun start() = Unit
                override fun stop() = Unit
            })
        }
    }

    private fun table(columns: List<String>, vararg rows: List<Any?>) = ResultTable(
        columns = columns.map { ColumnMeta(it, CellType.TEXT, "TEXT", null) },
        rows = rows.map { row ->
            row.map { if (it == null) CellValue.Null else if (it is Number) CellValue.Number(it.toString()) else CellValue.Text(it.toString()) }
        },
    )

    private val pgFacts = listOf(
        ServerFact("version", "16.13"),
        ServerFact("database", "shop"),
        ServerFact("role", "primary"),
        ServerFact("connections", "42"),
        ServerFact("uptime", "3564000"),
    )

    private val msFacts = listOf(
        ServerFact("version", "16.0.4165.4"),
        ServerFact("edition", "Developer Edition (64-bit)"),
        ServerFact("database", "Sales"),
        ServerFact("connections", "27"),
        ServerFact("uptime", "1047600"),
    )

    // ------------------------------------------------------------------ PostgreSQL

    @Test
    fun postgresQueries() = server(
        DatabaseEngine.POSTGRESQL,
        ServerUiState(
            connected = true,
            panel = ServerPanel.QUERIES,
            facts = pgFacts,
            capabilities = PostgresDialect.server.capabilities,
            processes = table(
                listOf("Id", "User", "Host", "db", "Time", "State", "Info"),
                listOf(18244, "report", "10.0.3.14", "shop", 84, "active · IO:DataFileRead", "SELECT c.*, sum(ii.qty) FROM customers c JOIN invoice_items ii ON ii.customer_id = c.id GROUP BY c.id"),
                listOf(18251, "app", "10.0.3.9", "shop", 2, "active · Lock:transactionid", "UPDATE invoices SET status = 'paid' WHERE id = 20416"),
                listOf(18260, "app_ro", "10.0.3.21", "shop", 0, "active", "SELECT * FROM pg_stat_activity"),
            ),
        ),
    )

    @Test
    fun postgresTransactions() = server(
        DatabaseEngine.POSTGRESQL,
        ServerUiState(
            connected = true,
            panel = ServerPanel.TRANSACTIONS,
            facts = pgFacts,
            capabilities = PostgresDialect.server.capabilities,
            transactions = table(
                listOf("Id", "User", "State", "Seconds", "db", "Query"),
                listOf(18102, "app", "idle in transaction", 1840, "shop", "UPDATE invoices SET status = 'paid' WHERE id = 20416"),
                listOf(18233, "report", "idle in transaction", 95, "shop", "SELECT count(*) FROM invoices"),
                listOf(18244, "report", "active", 12, "shop", "SELECT c.*, sum(ii.qty) FROM customers c JOIN invoice_items ii ON ii.customer_id = c.id GROUP BY c.id"),
            ),
        ),
    )

    @Test
    fun postgresReplication() = server(
        DatabaseEngine.POSTGRESQL,
        ServerUiState(
            connected = true,
            panel = ServerPanel.REPLICATION,
            facts = pgFacts,
            capabilities = PostgresDialect.server.capabilities,
            replication = ReplicationReport.Channels(
                ReplicationStatus.sortedBySeverity(
                    PostgresReplication.primary(
                        listOf(
                            mapOf(
                                "application_name" to "replica-eu", "client_addr" to "10.0.4.7", "client_port" to "51234",
                                "usename" to "repl", "state" to "streaming", "sync_state" to "async",
                                "write_lag" to "0.0012", "flush_lag" to "0.0031", "replay_lag" to "0.0049", "replay_bytes" to "4096",
                            ),
                            mapOf(
                                "application_name" to "replica-archive", "client_addr" to "10.0.4.9", "client_port" to "40122",
                                "usename" to "repl", "state" to "catchup", "sync_state" to "async",
                                "write_lag" to "41.2", "flush_lag" to "41.9", "replay_lag" to "118.4", "replay_bytes" to "812646400",
                            ),
                        ),
                    ),
                ),
                ResultTable.EMPTY,
            ),
        ),
    )

    @Test
    fun postgresSlowStatements() = server(
        DatabaseEngine.POSTGRESQL,
        ServerUiState(
            connected = true,
            panel = ServerPanel.SLOW,
            facts = pgFacts,
            capabilities = PostgresDialect.server.capabilities,
            slow = SlowStatementsReport.Rows(
                listOf(
                    SlowStatement(
                        "SELECT c.*, sum(ii.qty) FROM customers c JOIN invoice_items ii ON ii.customer_id = c.id WHERE c.region = \$1 GROUP BY c.id",
                        "shop", 1_204, 8_412_000_000_000_000, 6_987_000_000_000, null, 91_230, 0, 0, null, null,
                        blocksRead = 4_812_223, dollarPlaceholders = true,
                    ),
                    SlowStatement(
                        "UPDATE invoices SET status = \$1 WHERE id = \$2",
                        "shop", 48_210, 912_000_000_000_000, 18_900_000_000, null, 48_210, 0, 0, null, null,
                        blocksRead = 144_630, dollarPlaceholders = true,
                    ),
                ),
                SlowSort.TOTAL,
            ),
        ),
    )

    @Test
    fun postgresExtensionMissing() = server(
        DatabaseEngine.POSTGRESQL,
        ServerUiState(
            connected = true,
            panel = ServerPanel.SLOW,
            facts = pgFacts,
            capabilities = PostgresDialect.server.capabilities,
            slow = SlowStatementsReport.ExtensionMissing,
        ),
    )

    @Test
    fun postgresPulse() = pulse(
        DatabaseEngine.POSTGRESQL,
        Metric(MetricId.PG_CONNECTIONS, "42", Health.CALM, series(38, 40, 41, 43, 44, 41, 42, 42)),
        Metric(MetricId.PG_ACTIVE, "6", Health.CALM, series(3, 4, 3, 6, 5, 9, 8, 6)),
        Metric(MetricId.PG_IDLE_IN_XACT, "2", Health.BUSY, series(0, 0, 0, 1, 0, 1, 3, 2)),
        Metric(MetricId.PG_COMMITS, "1 284", Health.CALM, series(900, 1010, 980, 1120, 1300, 1190, 1250, 1284)),
        Metric(MetricId.PG_ROLLBACKS, "3,1", Health.CALM, series(1, 2, 1, 4, 3, 2, 3, 3.1)),
        Metric(MetricId.PG_CACHE_HIT, "99%", Health.CALM, series(99.8, 99.7, 99.8, 99.6, 99.5, 99.6, 99.4, 99.4)),
        Metric(MetricId.PG_TUPLES_READ, "48 210", Health.CALM, series(31000, 39000, 42000, 36000, 44000, 52000, 49000, 48210)),
        Metric(MetricId.PG_TUPLES_WRITTEN, "812", Health.CALM, series(500, 600, 700, 650, 800, 790, 830, 812)),
        Metric(MetricId.PG_DEADLOCKS, "1", Health.ALARMED, series(0, 0, 0, 0, 0, 0, 0, 1)),
    )

    // ------------------------------------------------------------------ SQL Server

    @Test
    fun sqlServerQueries() = server(
        DatabaseEngine.SQLSERVER,
        ServerUiState(
            connected = true,
            panel = ServerPanel.QUERIES,
            facts = msFacts,
            capabilities = SqlServerDialect.server.capabilities,
            processes = table(
                listOf("Id", "User", "Host", "db", "Time", "State", "Info"),
                listOf(57, "report", "BI-01", "Sales", 84, "running · PAGEIOLATCH_SH", "SELECT c.*, SUM(ii.qty) FROM customers c JOIN invoice_items ii ON ii.customer_id = c.id GROUP BY c.id"),
                listOf(61, "app", "WEB-03", "Sales", 2, "suspended · LCK_M_U", "UPDATE invoices SET status = 'paid' WHERE id = 20416"),
                listOf(64, "app_ro", "WEB-04", "Sales", 0, "running", "SELECT * FROM sys.dm_exec_requests"),
            ),
        ),
    )

    @Test
    fun sqlServerLocks() = server(
        DatabaseEngine.SQLSERVER,
        ServerUiState(
            connected = true,
            panel = ServerPanel.LOCKS,
            facts = msFacts,
            capabilities = SqlServerDialect.server.capabilities,
            lockWaits = table(
                listOf("WaitingId", "WaitingQuery", "BlockingId", "BlockingQuery", "WaitSeconds", "WaitType"),
                listOf(61, "UPDATE invoices SET status = 'paid' WHERE id = 20416", 52, "BEGIN TRANSACTION; UPDATE invoices SET total = 0 WHERE id = 20416", 118, "LCK_M_U"),
            ),
        ),
    )

    @Test
    fun sqlServerReplication() = server(
        DatabaseEngine.SQLSERVER,
        ServerUiState(
            connected = true,
            panel = ServerPanel.REPLICATION,
            facts = msFacts,
            capabilities = SqlServerDialect.server.capabilities,
            replication = ReplicationReport.Channels(
                ReplicationStatus.sortedBySeverity(
                    SqlServerReplication.parse(
                        listOf(
                            mapOf(
                                "replica_server_name" to "SQL-B", "database_name" to "Sales", "is_local" to "0",
                                "synchronization_state_desc" to "SYNCHRONIZED", "synchronization_health_desc" to "HEALTHY",
                                "log_send_queue_size" to "0", "redo_queue_size" to "12", "secondary_lag_seconds" to "0",
                                "availability_mode_desc" to "SYNCHRONOUS_COMMIT", "last_commit_time" to "2026-10-03 12:04:11",
                            ),
                            mapOf(
                                "replica_server_name" to "SQL-DR", "database_name" to "Sales", "is_local" to "0",
                                "synchronization_state_desc" to "SYNCHRONIZING", "synchronization_health_desc" to "PARTIALLY_HEALTHY",
                                "log_send_queue_size" to "184320", "redo_queue_size" to "20480", "secondary_lag_seconds" to "212",
                                "availability_mode_desc" to "ASYNCHRONOUS_COMMIT", "last_commit_time" to "2026-10-03 12:00:39",
                            ),
                        ),
                    ),
                ),
                ResultTable.EMPTY,
            ),
        ),
    )

    @Test
    fun sqlServerSlowStatements() = server(
        DatabaseEngine.SQLSERVER,
        ServerUiState(
            connected = true,
            panel = ServerPanel.SLOW,
            facts = msFacts,
            capabilities = SqlServerDialect.server.capabilities,
            slow = SlowStatementsReport.Rows(
                listOf(
                    SlowStatement(
                        "SELECT c.*, SUM(ii.qty) FROM customers c JOIN invoice_items ii ON ii.customer_id = c.id WHERE c.region = @region GROUP BY c.id",
                        "Sales", 1_204, 8_412_000_000_000_000, 6_987_000_000_000, null, 91_230, 0, 0,
                        "2026-09-12 08:14:02", "2026-10-02 07:59:41", blocksRead = 9_812_223,
                    ),
                ),
                SlowSort.TOTAL,
            ),
        ),
    )

    @Test
    fun sqlServerNoPrivilege() = server(
        DatabaseEngine.SQLSERVER,
        ServerUiState(
            connected = true,
            panel = ServerPanel.QUERIES,
            facts = msFacts.take(2),
            capabilities = SqlServerDialect.server.capabilities,
            missingPrivilege = "VIEW SERVER STATE",
            processes = null,
        ),
    )

    @Test
    fun sqlServerPulse() = pulse(
        DatabaseEngine.SQLSERVER,
        Metric(MetricId.MS_BATCH_REQUESTS, "412", Health.CALM, series(300, 340, 360, 390, 410, 395, 405, 412)),
        Metric(MetricId.MS_TRANSACTIONS, "96", Health.CALM, series(70, 80, 85, 90, 99, 92, 94, 96)),
        Metric(MetricId.MS_USER_CONNECTIONS, "27", Health.CALM, series(22, 23, 25, 26, 27, 27, 26, 27)),
        Metric(MetricId.MS_BLOCKED, "1", Health.BUSY, series(0, 0, 0, 0, 1, 0, 1, 1)),
        Metric(MetricId.MS_LOCK_WAITS, "0,40", Health.BUSY, series(0, 0, 0.2, 0, 0.3, 0.4, 0.2, 0.4)),
        Metric(MetricId.MS_PAGE_LIFE, "212", Health.ALARMED, series(900, 700, 520, 410, 330, 280, 240, 212)),
        Metric(MetricId.MS_CACHE_HIT, "99%", Health.CALM, series(99.8, 99.7, 99.8, 99.6, 99.5, 99.6, 99.4, 99.4)),
    )

    @Test
    fun sqlServerPulseNoPrivilege() = paparazzi.screen {
        CompositionLocalProvider(LocalEngineFeatures provides EngineFeatures.of(DatabaseEngine.SQLSERVER)) {
            PulseScreenContent(onBack = {}, viewModel = object : PulseController {
                override val uiState: StateFlow<PulseUiState> = MutableStateFlow(
                    PulseUiState(connected = true, missingPrivilege = "VIEW SERVER STATE"),
                )
                override fun setInterval(interval: PulseInterval) = Unit
                override fun start() = Unit
                override fun stop() = Unit
            })
        }
    }
}
