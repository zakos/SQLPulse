package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.schema.ReplicationReport
import hu.laurel.sqlpulse.data.schema.ReplicationStatus
import hu.laurel.sqlpulse.data.schema.ServerFact
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.schema.SlowStatement
import hu.laurel.sqlpulse.data.schema.SlowStatementsReport
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.server.ServerController
import hu.laurel.sqlpulse.ui.server.ServerPanel
import hu.laurel.sqlpulse.ui.server.ServerScreenContent
import hu.laurel.sqlpulse.ui.server.ServerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

/** The replication cards and the slowest-statements list of the server screen. */
class ServerOpsShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private val facts = listOf(
        ServerFact("Kiszolgáló", "MySQL 8.0.39"),
        ServerFact("Üzemidő", "41 nap 6 óra"),
    )

    private fun draw(state: ServerUiState) = paparazzi.screen {
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

    private fun replication(vararg rows: Map<String, String?>): ReplicationReport.Channels =
        ReplicationReport.Channels(
            ReplicationStatus.sortedBySeverity(ReplicationStatus.parse(rows.toList())),
            ResultTable.EMPTY,
        )

    @Test
    fun replicationOk() = draw(
        ServerUiState(
            connected = true,
            panel = ServerPanel.REPLICATION,
            facts = facts,
            replication = replication(
                mapOf(
                    "Channel_Name" to "", "Source_Host" to "db-primary.internal", "Source_Port" to "3306",
                    "Source_User" to "repl", "Replica_IO_Running" to "Yes", "Replica_SQL_Running" to "Yes",
                    "Seconds_Behind_Source" to "2", "Replica_SQL_Running_State" to "Replica has read all relay log; waiting for more updates",
                    "Retrieved_Gtid_Set" to "3e11fa47-71ca-11e1-9e33-c80aa9429562:1-5812",
                    "Executed_Gtid_Set" to "3e11fa47-71ca-11e1-9e33-c80aa9429562:1-5810",
                    "Source_Log_File" to "binlog.000042", "Read_Source_Log_Pos" to "88213",
                ),
            ),
        ),
    )

    @Test
    fun replicationError() = draw(
        ServerUiState(
            connected = true,
            panel = ServerPanel.REPLICATION,
            facts = facts,
            replication = replication(
                mapOf(
                    "Channel_Name" to "orders", "Source_Host" to "10.0.4.7", "Source_Port" to "3306",
                    "Replica_IO_Running" to "Yes", "Replica_SQL_Running" to "No", "Seconds_Behind_Source" to null,
                    "Last_SQL_Error" to "Could not execute Write_rows event on table shop.invoices; Duplicate entry '20416' for key 'PRIMARY'",
                ),
                mapOf(
                    "Channel_Name" to "archive", "Source_Host" to "10.0.4.9", "Source_Port" to "3306",
                    "Replica_IO_Running" to "Yes", "Replica_SQL_Running" to "Yes", "Seconds_Behind_Source" to "412",
                ),
            ),
        ),
    )

    @Test
    fun replicationNoPrivilege() = draw(
        ServerUiState(connected = true, panel = ServerPanel.REPLICATION, facts = facts, replication = ReplicationReport.NoPrivilege),
    )

    @Test
    fun slowStatements() = draw(
        ServerUiState(
            connected = true,
            panel = ServerPanel.SLOW,
            facts = facts,
            slow = SlowStatementsReport.Rows(
                listOf(
                    SlowStatement(
                        "SELECT `c` . * , SUM ( `ii` . `qty` ) FROM `customers` `c` JOIN `invoice_items` `ii` ON `ii` . `customer_id` = `c` . `id` WHERE `c` . `region` = ? GROUP BY `c` . `id`",
                        "shop", 1_204, 8_412_000_000_000_000, 6_987_000_000_000, 91_230_551, 1_204, 1_198,
                        1_198, "2026-09-12 08:14:02.118233", "2026-10-02 07:59:41.003112",
                    ),
                    SlowStatement(
                        "UPDATE `invoices` SET `status` = ? WHERE `id` = ?",
                        "shop", 48_210, 912_000_000_000_000, 18_900_000_000, 48_210, 48_210, 0, 0,
                        "2026-09-12 08:14:02.118233", "2026-10-02 07:59:55.730001",
                    ),
                ),
                SlowSort.TOTAL,
            ),
        ),
    )

    @Test
    fun slowPerformanceSchemaOff() = draw(
        ServerUiState(connected = true, panel = ServerPanel.SLOW, facts = facts, slow = SlowStatementsReport.PerformanceSchemaOff),
    )
}
