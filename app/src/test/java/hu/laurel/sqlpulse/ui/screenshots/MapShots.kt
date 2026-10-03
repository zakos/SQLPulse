package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.schema.GraphEdge
import hu.laurel.sqlpulse.data.schema.LinkGuesser
import hu.laurel.sqlpulse.data.schema.SchemaLayout
import hu.laurel.sqlpulse.data.schema.TableColumns
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

    /** A schema with no foreign keys and prefixed names, drawn from guessed links (MantisBT). */
    @Test
    fun mapGuessed() = paparazzi.screen {
        fun t(name: String, vararg cols: String, pk: List<String> = listOf("id")) =
            TableColumns(name, cols.toList(), pk)
        val tables = listOf(
            t("mantis_bug_table", "id", "project_id", "reporter_id", "handler_id", "category_id", "bug_text_id"),
            t("mantis_bug_text_table", "id"),
            t("mantis_bug_file_table", "id", "bug_id", "user_id"),
            t("mantis_bugnote_table", "id", "bug_id", "reporter_id", "bugnote_text_id"),
            t("mantis_bugnote_text_table", "id"),
            t("mantis_bug_history_table", "id", "user_id", "bug_id"),
            t("mantis_bug_relationship_table", "id", "source_bug_id", "destination_bug_id"),
            t("mantis_category_table", "id", "project_id", "user_id"),
            t("mantis_project_table", "id"),
            t("mantis_user_table", "id"),
            t("mantis_config_table", "config_id", pk = listOf("config_id")),
            t("mantis_filters_table", "id"),
            t("mantis_plugin_table", "basename", pk = listOf("basename")),
        )
        val guessed = LinkGuesser.infer(tables)
        val graph = SchemaLayout.build(tables.map { it.table }, guessed)
        SchemaMapScreenContent(onBack = {}, onOpenTable = { _, _ -> }, viewModel = object : SchemaMapController {
            override val uiState: StateFlow<SchemaMapUiState> = MutableStateFlow(
                SchemaMapUiState(database = "mantis", graph = graph, guessedCount = guessed.size),
            )
            override fun select(table: String?) = Unit
            override fun setShowGuesses(show: Boolean) = Unit
        })
    }
}
