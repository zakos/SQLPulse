package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.schema.GraphEdge
import hu.laurel.sqlpulse.data.schema.SchemaLayout
import hu.laurel.sqlpulse.ui.map.SchemaMapController
import hu.laurel.sqlpulse.ui.map.SchemaMapScreenContent
import hu.laurel.sqlpulse.ui.map.SchemaMapUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class MapShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @Test
    fun map() = paparazzi.screen {
        val graph = SchemaLayout.build(
            tables = listOf("customers", "payment_methods", "invoices", "payments", "invoice_items", "reminders", "products"),
            edges = listOf(
                GraphEdge("invoices", "customers"),
                GraphEdge("invoices", "payment_methods"),
                GraphEdge("payments", "invoices"),
                GraphEdge("invoice_items", "invoices"),
                GraphEdge("reminders", "invoices"),
                GraphEdge("invoice_items", "products"),
            ),
        )
        SchemaMapScreenContent(onBack = {}, onOpenTable = { _, _ -> }, viewModel = object : SchemaMapController {
            override val uiState: StateFlow<SchemaMapUiState> = MutableStateFlow(
                SchemaMapUiState(database = "billing", graph = graph, selected = "invoices", declaredCount = 6),
            )
            override fun select(table: String?) = Unit
            override fun setShowGuesses(show: Boolean) = Unit
        })
    }
}
