package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.layout.Box
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
}
