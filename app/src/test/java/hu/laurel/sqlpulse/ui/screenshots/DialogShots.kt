package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.WriteKind
import hu.laurel.sqlpulse.data.sql.WriteRowPreview
import hu.laurel.sqlpulse.ui.grid.ConfirmStatementCard
import hu.laurel.sqlpulse.ui.query.QueryEditorContent
import hu.laurel.sqlpulse.ui.query.QueryEditorUiState
import hu.laurel.sqlpulse.ui.query.QueryTab
import hu.laurel.sqlpulse.ui.query.WriteConfirmCard
import hu.laurel.sqlpulse.ui.query.WriteConfirmation
import org.junit.Rule
import org.junit.Test

/**
 * The confirmations, drawn as their cards over a dimmed screen. A real dialog is a window of its
 * own, which a screenshot of the composition does not contain; the card is the whole of it.
 */
class DialogShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @Composable
    private fun Over(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            QueryEditorContent(
                onBack = {},
                viewModel = FakeQueryController(
                    QueryEditorUiState(
                        tabs = listOf(QueryTab(id = 1, title = "napi bevétel", sql = "SELECT 1", result = QueryEditorShots.RESULT, editorCollapsed = true)),
                        activeTabId = 1,
                        connectionName = "Számlázó",
                    ),
                ),
            )
            Box(Modifier.fillMaxSize().background(Color(0xA0050608)))
            Box(Modifier.align(Alignment.Center).padding(horizontal = 16.dp)) { content() }
        }
    }

    @Test
    fun update() = paparazzi.screen {
        Over {
            ConfirmStatementCard(
                title = stringResource(R.string.confirm_update_title),
                subtitle = null,
                statement = "UPDATE invoices SET statusz = 'paid' WHERE id = 20416 AND statusz = 'overdue'",
                destructive = false,
                requireTableName = null,
                armed = false,
                typedName = "",
                onTypedName = {},
                onConfirm = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun productionDelete() = paparazzi.screen {
        Over {
            ConfirmStatementCard(
                title = stringResource(R.string.confirm_delete_title),
                subtitle = "WEBSHOP · ÉLES",
                statement = "DELETE FROM orders WHERE id = 88213 LIMIT 1",
                destructive = true,
                requireTableName = "orders",
                armed = false,
                typedName = "order",
                onTypedName = {},
                onConfirm = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun typedWrite() = paparazzi.screen {
        Over {
            WriteConfirmCard(
                confirmation = WriteConfirmation(
                    statements = listOf("UPDATE customers SET vip = 1 WHERE last_order > '2026-01-01'"),
                    estimatedRows = 1_204,
                    connectionName = "Webshop",
                    environment = ConnectionEnvironment.PRODUCTION,
                    database = "shop",
                    maxAffectedRows = 5_000,
                ),
                typed = "sh",
                onTyped = {},
                onConfirm = {},
                onDismiss = {},
            )
        }
    }

    private fun invoiceColumns(vararg extra: String) =
        (listOf("id", "ugyfel", "osszeg", "statusz", "kiallitva") + extra).map {
            ColumnMeta(it, if (it == "id" || it.startsWith("osszeg")) CellType.NUMBER else CellType.TEXT, "VARCHAR", "invoices")
        }

    private fun invoiceRow(id: Int, status: String, newStatus: String? = null) = listOfNotNull(
        CellValue.Number(id.toString()),
        CellValue.Text("Kovács és Társa Kft. $id"),
        CellValue.Number("${id * 37 % 900 + 100}.50"),
        CellValue.Text(status),
        CellValue.Date("2026-09-${id % 28 + 1}"),
        newStatus?.let { CellValue.Text(it) },
    )

    @Test
    fun updatePreview() = paparazzi.screen {
        Over {
            WriteConfirmCard(
                confirmation = WriteConfirmation(
                    statements = listOf("UPDATE invoices SET statusz = 'paid' WHERE statusz = 'overdue'"),
                    estimatedRows = 37,
                    connectionName = "Számlázó",
                    environment = ConnectionEnvironment.TEST,
                    database = "billing",
                    maxAffectedRows = 1_000,
                    previews = listOf(
                        WriteRowPreview(
                            kind = WriteKind.UPDATE,
                            changedColumns = listOf("statusz"),
                            table = ResultTable(
                                columns = invoiceColumns("statusz (new)"),
                                rows = (20401..20405).map { invoiceRow(it, "overdue", "paid") },
                            ),
                            totalRows = 37,
                        ),
                    ),
                ),
                typed = "",
                onTyped = {},
                onConfirm = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun deletePreview() = paparazzi.screen {
        Over {
            WriteConfirmCard(
                confirmation = WriteConfirmation(
                    statements = listOf("DELETE FROM invoices WHERE statusz = 'void'"),
                    estimatedRows = 3,
                    connectionName = "Webshop",
                    environment = ConnectionEnvironment.PRODUCTION,
                    database = "shop",
                    maxAffectedRows = 1_000,
                    previews = listOf(
                        WriteRowPreview(
                            kind = WriteKind.DELETE,
                            changedColumns = emptyList(),
                            table = ResultTable(
                                columns = invoiceColumns(),
                                rows = (20411..20413).map { invoiceRow(it, "void") },
                            ),
                            totalRows = 3,
                        ),
                    ),
                ),
                typed = "",
                onTyped = {},
                onConfirm = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun export() = paparazzi.screen {
        Box(Modifier.fillMaxSize()) {
            QueryEditorContent(
                onBack = {},
                viewModel = FakeQueryController(
                    QueryEditorUiState(
                        tabs = listOf(QueryTab(id = 1, title = "napi bevétel", sql = "SELECT 1", result = QueryEditorShots.RESULT, editorCollapsed = true)),
                        activeTabId = 1,
                        connectionName = "Számlázó",
                    ),
                ),
            )
            Box(Modifier.fillMaxSize().background(Color(0xA0050608)))
            androidx.compose.material3.Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = hu.laurel.sqlpulse.ui.theme.Shapes.sheet,
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                androidx.compose.foundation.layout.Column {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .background(Color(0x24FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                    )
                    hu.laurel.sqlpulse.ui.grid.ExportSheetContent(
                        rowCount = 12,
                        selected = hu.laurel.sqlpulse.data.export.ExportFormat.CSV,
                        onSelect = {},
                        onShare = {},
                    )
                }
            }
        }
    }

    @Test
    fun csvImport() = paparazzi.screen {
        Over {
            hu.laurel.sqlpulse.ui.schema.ImportPlanCard(
                plan = hu.laurel.sqlpulse.data.csv.ImportPlan(
                    table = hu.laurel.sqlpulse.data.csv.CsvTable(
                        header = listOf("id", "ugyfel_nev", "Összeg", "statusz", "datum", "belso_kod"),
                        rows = List(1_284) { listOf("1", "x", "1", "paid", "2026-09-01", "k") },
                        malformedRows = 0,
                    ),
                    match = hu.laurel.sqlpulse.data.csv.ColumnMatch(
                        matched = linkedMapOf("id" to "id", "ugyfel_nev" to "ugyfel", "Összeg" to "osszeg", "statusz" to "statusz", "datum" to "kiallitva"),
                        unmatched = listOf("belso_kod"),
                        missing = emptyList(),
                        blocking = emptyList(),
                    ),
                    separator = ';',
                ),
                table = "invoices",
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}
