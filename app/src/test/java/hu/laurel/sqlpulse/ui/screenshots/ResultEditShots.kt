package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.sql.NotEditableReason
import hu.laurel.sqlpulse.data.sql.ResultEditTarget
import hu.laurel.sqlpulse.ui.query.QueryEditorContent
import hu.laurel.sqlpulse.ui.query.QueryEditorUiState
import hu.laurel.sqlpulse.ui.query.QueryTab
import hu.laurel.sqlpulse.ui.query.ResultEditStatus
import hu.laurel.sqlpulse.ui.query.ResultEditUiState
import org.junit.Rule
import org.junit.Test

/** The line under a query result that says whether its rows can be edited in place. */
class ResultEditShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private fun state(sql: String) = QueryEditorUiState(
        tabs = listOf(
            QueryTab(id = 1, title = "számlák", sql = sql, database = "billing", result = QueryEditorShots.RESULT, editorCollapsed = true),
        ),
        activeTabId = 1,
        databases = listOf("billing", "shop"),
        connectionName = "Számlázó",
    )

    @Test
    fun editable() = paparazzi.screen {
        val target = ResultEditTarget("billing", "invoices", List(6) { "c$it" }, mapOf("id" to 0))
        QueryEditorContent(
            onBack = {},
            viewModel = FakeQueryController(
                state("SELECT * FROM invoices ORDER BY id DESC"),
                ResultEditUiState(status = ResultEditStatus.Editable(target)),
            ),
        )
    }

    @Test
    fun readOnlyJoin() = paparazzi.screen {
        QueryEditorContent(
            onBack = {},
            viewModel = FakeQueryController(
                state("SELECT i.id, c.name FROM invoices i JOIN customers c ON c.id = i.customer_id"),
                ResultEditUiState(status = ResultEditStatus.ReadOnly(NotEditableReason.JOIN)),
            ),
        )
    }

    @Test
    fun readOnlyLight() = paparazzi.screen(dark = false) {
        QueryEditorContent(
            onBack = {},
            viewModel = FakeQueryController(
                state("SELECT name FROM invoices"),
                ResultEditUiState(status = ResultEditStatus.ReadOnly(NotEditableReason.KEY_NOT_SELECTED)),
            ),
        )
    }
}
