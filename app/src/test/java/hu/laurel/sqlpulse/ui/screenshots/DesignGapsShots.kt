package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import hu.laurel.sqlpulse.data.csv.CsvImport
import hu.laurel.sqlpulse.data.csv.CsvMapping
import hu.laurel.sqlpulse.data.csv.CsvTable
import hu.laurel.sqlpulse.data.csv.ImportPlan
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.ui.grid.ExportSheetContent
import hu.laurel.sqlpulse.ui.query.FullExportProgress
import hu.laurel.sqlpulse.ui.query.FullExportProgressCard
import hu.laurel.sqlpulse.ui.schema.ImportPlanCard
import hu.laurel.sqlpulse.ui.theme.Shapes
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.ui.query.CompletionItem
import hu.laurel.sqlpulse.ui.query.CompletionKind
import hu.laurel.sqlpulse.ui.query.CompletionListCard
import hu.laurel.sqlpulse.ui.query.QueryEditorContent
import hu.laurel.sqlpulse.ui.query.QueryEditorUiState
import hu.laurel.sqlpulse.ui.query.QueryTab
import org.junit.Rule
import org.junit.Test

/** The three places where the screens were still short of the design: completion, CSV mapping, export. */
class DesignGapsShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @Test
    fun completionList() = paparazzi.screen {
        val items = listOf(
            CompletionItem("customer_id", CompletionKind.COLUMN, "bigint · invoices"),
            CompletionItem("currency", CompletionKind.COLUMN, "char(3) · invoices"),
            CompletionItem("customers", CompletionKind.TABLE),
            CompletionItem("CURRENT_DATE", CompletionKind.FUNCTION),
            CompletionItem("cust", CompletionKind.SNIPPET, "SELECT * FROM customers"),
            CompletionItem("CURSOR", CompletionKind.KEYWORD),
        )
        Box {
            QueryEditorContent(
                onBack = {},
                viewModel = FakeQueryController(
                    QueryEditorUiState(
                        tabs = listOf(QueryTab(id = 1, title = "napi bevétel", sql = QueryEditorShots.SQL, database = "billing")),
                        activeTabId = 1,
                        databases = listOf("billing", "shop"),
                        connectionName = "Számlázó",
                    ),
                ),
            )
            Box(Modifier.offset(x = 56.dp, y = 74.dp)) {
                CompletionListCard(items = items, selected = 1, prefix = "cu", onPick = {})
            }
        }
    }

    private fun editorBackdrop(content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) = paparazzi.screen {
        Box(Modifier.fillMaxSize()) {
            QueryEditorContent(
                onBack = {},
                viewModel = FakeQueryController(
                    QueryEditorUiState(
                        tabs = listOf(QueryTab(id = 1, title = "napi bevétel", sql = "SELECT 1", result = QueryEditorShots.RESULT.copy(limitAdded = true), editorCollapsed = true)),
                        activeTabId = 1,
                        connectionName = "Számlázó",
                    ),
                ),
            )
            Box(Modifier.fillMaxSize().background(Color(0xA0050608)))
            content()
        }
    }

    @Test
    fun csvMapping() = editorBackdrop {
        val columns = listOf(
            col("id", "int(11)", nullable = false, extra = "auto_increment"),
            col("ugyfel", "varchar(80)", nullable = false),
            col("osszeg", "decimal(10,2)", nullable = false),
            col("kiallitva", "datetime"),
            col("statusz", "varchar(20)"),
        )
        val header = listOf("id", "ugyfel_nev", "Összeg", "datum", "belso_kod")
        val rows = listOf(
            listOf("20418", "Kovács Anna", "184500", "2026-09-21 14:02", "k1"),
            listOf("20417", "Bartos Kft.", "1 240 000", "2026-09-21 13:47", "k2"),
            listOf("20416", "Nagy Péter", "12990", "tegnap", "k3"),
            listOf("20415", "Laurel Zrt.", null, "2026-09-21 10:05", "k4"),
        )
        val table = CsvTable(header, rows, malformedRows = 0)
        // The by-name match only knows "id"; the rest the person has sent by hand.
        var plan = ImportPlan(table, CsvImport.match(header, columns), ';', columns = columns)
        plan = CsvMapping.remap(plan, 1, "ugyfel")
        plan = CsvMapping.remap(plan, 2, "osszeg")
        plan = CsvMapping.remap(plan, 3, "kiallitva")
        Box(Modifier.align(Alignment.Center).padding(horizontal = 16.dp)) {
            ImportPlanCard(plan = plan, table = "invoices", onConfirm = {}, onDismiss = {})
        }
    }

    @Test
    fun csvMappingBlocked() = editorBackdrop {
        val columns = listOf(
            col("id", "int(11)", nullable = false, extra = "auto_increment"),
            col("ugyfel", "varchar(80)", nullable = false),
            col("osszeg", "decimal(10,2)", nullable = false),
        )
        val header = listOf("id", "nev")
        val table = CsvTable(header, listOf(listOf("1", "Anna"), listOf("2", "Péter")), malformedRows = 0)
        val plan = ImportPlan(table, CsvImport.match(header, columns), ',', columns = columns)
        Box(Modifier.align(Alignment.Center).padding(horizontal = 16.dp)) {
            ImportPlanCard(plan = plan, table = "invoices", onConfirm = {}, onDismiss = {})
        }
    }

    @Test
    fun exportFull() = editorBackdrop {
        androidx.compose.material3.Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = Shapes.sheet,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .background(Color(0x24FFFFFF), RoundedCornerShape(2.dp)),
                )
                ExportSheetContent(
                    rowCount = 500,
                    selected = ExportFormat.CSV,
                    onSelect = {},
                    onShare = {},
                    fullAvailable = true,
                    full = true,
                    onFullChange = {},
                )
            }
        }
    }

    @Test
    fun exportFullProgress() = editorBackdrop {
        Box(Modifier.align(Alignment.Center).padding(horizontal = 16.dp)) {
            FullExportProgressCard(progress = FullExportProgress(rows = 412_500, bytes = 38_400_000), onCancel = {})
        }
    }

    private fun col(name: String, type: String, nullable: Boolean = true, extra: String? = null) =
        SchemaColumn(name, type, nullable, null, isPrimaryKey = false, extra = extra, comment = null)
}
