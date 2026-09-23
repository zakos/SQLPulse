package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.chart.ChartSpec
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.db.SavedQueryEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.grid.ResultFilter
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ParameterValue
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.ui.query.QueryEditorContent
import hu.laurel.sqlpulse.ui.query.QueryEditorController
import hu.laurel.sqlpulse.ui.query.QueryEditorUiState
import hu.laurel.sqlpulse.ui.query.QueryPanel
import hu.laurel.sqlpulse.ui.query.QueryTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class QueryEditorShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @Test
    fun editor() = paparazzi.screen {
        QueryEditorContent(onBack = {}, viewModel = FakeQueryController(editorState()))
    }

    @Test
    fun results() = paparazzi.screen {
        QueryEditorContent(onBack = {}, viewModel = FakeQueryController(resultState()))
    }

    @Test
    fun resultsLight() = paparazzi.screen(dark = false) {
        QueryEditorContent(onBack = {}, viewModel = FakeQueryController(resultState()))
    }

    private fun editorState() = QueryEditorUiState(
        tabs = listOf(
            QueryTab(id = 1, title = "napi bevétel", sql = SQL, database = "billing", suggestions = listOf("customer_id", "currency", "created_by")),
            QueryTab(id = 2, title = "lejárt számlák"),
            QueryTab(id = 3, title = "ügyfél keresés"),
        ),
        activeTabId = 1,
        databases = listOf("billing", "shop"),
        connectionName = "Számlázó",
    )

    private fun resultState() = editorState().let { state ->
        state.copy(
            tabs = state.tabs.map {
                if (it.id == 1L) it.copy(result = RESULT, editorCollapsed = true, suggestions = emptyList()) else it
            },
        )
    }

    private companion object {
        const val SQL = "-- napi bevétel fizetési mód szerint\nSELECT DATE(i.issued_at) AS nap,\n       i.payment_method,\n       COUNT(*) AS db,\n       SUM(i.total) AS osszeg\nFROM invoices i\nWHERE i.issued_at >= :tol\n  AND i.status = 'paid'\n  AND i.cu"

        val RESULT = ResultTable(
            columns = listOf(
                ColumnMeta("id", CellType.NUMBER, "BIGINT", "invoices"),
                ColumnMeta("ugyfel", CellType.TEXT, "VARCHAR", "invoices"),
                ColumnMeta("osszeg", CellType.NUMBER, "DECIMAL", "invoices"),
                ColumnMeta("statusz", CellType.TEXT, "ENUM", "invoices"),
                ColumnMeta("kiallitva", CellType.DATE, "DATETIME", "invoices"),
                ColumnMeta("megjegyzes", CellType.TEXT, "JSON", "invoices"),
            ),
            rows = listOf(
                row("20418", "Kovács Anna", "184500", "paid", "2026-09-21 14:02", "{\"forras\":\"web\"}"),
                row("20417", "Bartos Kft.", "1240000", "paid", "2026-09-21 13:47", null),
                row("20416", "Nagy Péter", "12990", "overdue", "2026-09-21 11:20", "2. felszólítás"),
                row("20415", "Laurel Zrt.", "560000", "paid", "2026-09-21 10:05", null),
                row("20414", "Tóth Eszter", "8450", "draft", "2026-09-20 18:31", ""),
                row("20413", "Kiss és Tsa", "96000", "paid", "2026-09-20 16:12", "{\"forras\":\"api\"}"),
                row("20412", "Horváth Gábor", "23700", "paid", "2026-09-20 15:58", null),
                row("20411", "Fekete Bt.", "310000", "overdue", "2026-09-20 09:44", "részletfizetés"),
                row("20410", "Szabó Lili", "4990", "paid", "2026-09-19 22:10", null),
                row("20409", "Mentor Kft.", "78400", "paid", "2026-09-19 17:03", null),
                row("20408", "Varga Dóra", "15000", "void", "2026-09-19 12:40", "sztornó"),
                row("20407", "Balogh Ádám", "2250000", "paid", "2026-09-19 08:15", null),
            ),
            limitAdded = true,
            durationMs = 38,
        )

        fun row(vararg v: String?): List<CellValue> = v.mapIndexed { i, s ->
            when {
                s == null -> CellValue.Null
                i == 0 || i == 2 -> CellValue.Number(s)
                i == 4 -> CellValue.Date(s)
                else -> CellValue.Text(s)
            }
        }
    }
}

/** A controller that holds still: the state it is given, and nothing that answers back. */
class FakeQueryController(state: QueryEditorUiState) : QueryEditorController {
    override val uiState: StateFlow<QueryEditorUiState> = MutableStateFlow(state)
    override val history: StateFlow<List<QueryHistoryEntity>> = MutableStateFlow(emptyList())
    override val favourites: StateFlow<List<SavedQueryEntity>> = MutableStateFlow(emptyList())
    override val sessionState: StateFlow<SqlSessionState> = MutableStateFlow(
        SqlSessionState.Ready(
            ConnectionEntity(name = "Számlázó", color = "Amber", sshHost = "jump.test.local", sshUser = "deploy", sshKeyId = null, dbHost = "10.0.4.12", database = "billing", dbUser = "app_ro", environment = "TEST"),
            "8.0.39",
        ),
    )
    override fun append(text: String) = Unit
    override fun cancel() = Unit
    override fun closeTab(id: Long) = Unit
    override fun compareWithSnapshot() = Unit
    override fun complete(suggestion: String) = Unit
    override fun confirmWrite() = Unit
    override fun deleteFavourite(favourite: SavedQueryEntity) = Unit
    override fun discardSnapshot() = Unit
    override fun dismissCloseTab() = Unit
    override fun dismissComparison() = Unit
    override fun dismissParameters() = Unit
    override fun dismissSnapshotNotice() = Unit
    override fun dismissTabLimit() = Unit
    override fun dismissWriteConfirmation() = Unit
    override fun duplicateTab(id: Long) = Unit
    override fun explain() = Unit
    override fun export(format: ExportFormat) = Unit
    override fun format() = Unit
    override fun load(sql: String, saved: Boolean) = Unit
    override fun newTab() = Unit
    override fun onEditorChanged(sql: String, selectionStart: Int, selectionEnd: Int) = Unit
    override fun reconnect() = Unit
    override fun renameTab(id: Long, title: String) = Unit
    override fun replaceAll(find: String, replacement: String) = Unit
    override fun requestCloseTab(id: Long) = Unit
    override fun rollback() = Unit
    override fun run(parameters: Map<String, ParameterValue>) = Unit
    override fun runCurrent(parameters: Map<String, ParameterValue>) = Unit
    override fun saveFavourite(name: String) = Unit
    override fun selectDatabase(database: String) = Unit
    override fun selectPanel(panel: QueryPanel) = Unit
    override fun selectStatement(index: Int) = Unit
    override fun selectTab(id: Long) = Unit
    override fun setChartSpec(spec: ChartSpec) = Unit
    override fun setResultFilter(filter: ResultFilter) = Unit
    override fun setTransaction(open: Boolean) = Unit
    override fun shareIntentHandled() = Unit
    override fun sortResult(column: String) = Unit
    override fun takeSnapshot() = Unit
    override fun toggleChart() = Unit
    override fun toggleEditor() = Unit
    override fun toggleFilterBar() = Unit
}
