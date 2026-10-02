package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.WriteLogEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.writelog.WriteSource
import hu.laurel.sqlpulse.ui.writelog.ClearLogCard
import hu.laurel.sqlpulse.ui.writelog.WriteLogController
import hu.laurel.sqlpulse.ui.writelog.WriteLogScreenContent
import hu.laurel.sqlpulse.ui.writelog.WriteLogUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class WriteLogShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private fun controller(state: WriteLogUiState) = object : WriteLogController {
        override val uiState: StateFlow<WriteLogUiState> = MutableStateFlow(state)
        override fun setEnvironment(environment: ConnectionEnvironment?) = Unit
        override fun setSource(source: WriteSource?) = Unit
        override fun setQuery(query: String) = Unit
        override fun clearFilters() = Unit
        override fun toggleExpanded(id: Long) = Unit
        override fun setRetentionDays(days: Int) = Unit
        override fun requestClear() = Unit
        override fun dismissClear() = Unit
        override fun confirmClear() = Unit
        override fun export(format: ExportFormat) = Unit
        override fun shareIntentHandled() = Unit
    }

    private fun entry(
        id: Long,
        minutesAgo: Long,
        connection: String,
        color: String,
        environment: String,
        database: String,
        source: WriteSource,
        statement: String,
        affected: Int?,
        error: String? = null,
        inTransaction: Boolean = false,
        durationMs: Long = 14,
    ) = WriteLogEntity(
        id = id, time = 1_760_000_000_000 - minutesAgo * 60_000, connectionId = id, connectionName = connection,
        connectionColor = color, environment = environment, database = database, source = source.name,
        statement = statement, affectedRows = affected, outcome = if (error == null) "OK" else "FAILED",
        error = error, durationMs = durationMs, inTransaction = inTransaction,
    )

    private val entries = listOf(
        entry(
            1, 3, "Számlázó (éles)", "Production", "PRODUCTION", "billing", WriteSource.SQL_EDITOR,
            "UPDATE invoices SET status = 'paid', paid_at = NOW()\nWHERE id IN (20417, 20418) AND status = 'open'", 2,
        ),
        entry(
            2, 11, "Számlázó (éles)", "Production", "PRODUCTION", "billing", WriteSource.RESULT_EDIT,
            "UPDATE `billing`.`customers` SET `email` = 'kovacs.bela@example.hu' WHERE `id` = '1042'", 1,
        ),
        entry(
            3, 12, "Számlázó (éles)", "Production", "PRODUCTION", "billing", WriteSource.UNDO,
            "UPDATE `billing`.`customers` SET `email` = 'kb@example.hu' WHERE `id` = '1042' AND `email` = 'kovacs.bela@example.hu'", 1,
        ),
        entry(
            4, 95, "Webshop teszt", "Amber", "TEST", "webshop", WriteSource.CSV_IMPORT,
            "-- CSV import into `webshop`.`products`: 480 rows, one transaction\nINSERT INTO `webshop`.`products` (`sku`, `name`, `price`) VALUES (?, ?, ?)", 480,
            durationMs = 2310,
        ),
        entry(
            5, 240, "Fejlesztői", "Green", "DEVELOPMENT", "scratch", WriteSource.SQL_EDITOR,
            "DELETE FROM sessions WHERE expires_at < NOW() - INTERVAL 7 DAY", 311, inTransaction = true,
        ),
    )

    private val base = WriteLogUiState(entries = entries, total = entries.size)

    @Test
    fun list() = paparazzi.screen {
        WriteLogScreenContent(onBack = {}, viewModel = controller(base.copy(expanded = setOf(1L))))
    }

    @Test
    fun prodOnly() = paparazzi.screen {
        WriteLogScreenContent(
            onBack = {},
            viewModel = controller(
                base.copy(
                    entries = entries.filter { it.environment == "PRODUCTION" },
                    total = entries.size,
                    environment = ConnectionEnvironment.PRODUCTION,
                ),
            ),
        )
    }

    @Test
    fun failed() = paparazzi.screen {
        val failed = entry(
            9, 1, "Számlázó (éles)", "Production", "PRODUCTION", "billing", WriteSource.SQL_EDITOR,
            "INSERT INTO invoices (id, customer_id, total)\nVALUES (20417, 1042, 1900)\n-- :id = '20417'", null,
            error = "SQLIntegrityConstraintViolationException: Duplicate entry '20417' for key 'PRIMARY'",
        )
        WriteLogScreenContent(
            onBack = {},
            viewModel = controller(base.copy(entries = listOf(failed) + entries.take(1), total = 2, expanded = setOf(9L))),
        )
    }

    @Test
    fun empty() = paparazzi.screen {
        WriteLogScreenContent(onBack = {}, viewModel = controller(WriteLogUiState()))
    }

    @Test
    fun noMatch() = paparazzi.screen {
        WriteLogScreenContent(
            onBack = {},
            viewModel = controller(base.copy(entries = emptyList(), query = "TRUNCATE")),
        )
    }

    @Test
    fun clearConfirmation() = paparazzi.screen {
        // Wrapped so the card keeps its own height instead of filling the screen.
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            ClearLogCard(count = 340, onConfirm = {}, onDismiss = {})
        }
    }
}
