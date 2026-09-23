package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.schema.TableKind
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.ui.schema.SchemaBrowserActions
import hu.laurel.sqlpulse.ui.schema.SchemaBrowserContent
import hu.laurel.sqlpulse.ui.schema.SchemaBrowserUiState
import org.junit.Rule
import org.junit.Test

class SchemaShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @Test
    fun browser() = paparazzi.screen {
        SchemaBrowserContent(state = state(), capturedAt = null, actions = SchemaBrowserActions())
    }

    private fun state() = SchemaBrowserUiState(
        session = SqlSessionState.Ready(CONNECTION, "8.0.39"),
        databases = listOf("billing", "shop", "reports"),
        selectedDatabase = "billing",
        tables = listOf(
            table("customers", 48_210, 18L shl 20),
            table("invoice_items", 3_400_000, 612L shl 20),
            table("invoices", 1_200_000, 284L shl 20),
            table("payment_methods", 6, 16L shl 10),
            table("payments", 980_000, 171L shl 20),
            table("reminders", 22_400, 4L shl 20),
            table("tax_rates", 12, 16L shl 10),
        ),
    )

    private fun table(name: String, rows: Long, bytes: Long) = SchemaTable(
        database = "billing",
        name = name,
        kind = TableKind.TABLE,
        approximateRows = rows,
        comment = null,
        engine = "InnoDB",
        collation = "utf8mb4_hungarian_ci",
        dataBytes = bytes,
        indexBytes = 0,
    )

    private companion object {
        val CONNECTION = ConnectionEntity(
            id = 2, name = "Számlázó", color = "Amber", sshHost = "jump.test.local", sshUser = "deploy",
            sshKeyId = null, dbHost = "10.0.4.12", database = "billing", dbUser = "app_ro", environment = "TEST",
        )
    }
}
