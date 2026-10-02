package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.search.SearchMode
import hu.laurel.sqlpulse.data.search.SearchRow
import hu.laurel.sqlpulse.ui.search.DatabaseSearchController
import hu.laurel.sqlpulse.ui.search.DatabaseSearchScreenContent
import hu.laurel.sqlpulse.ui.search.DatabaseSearchUiState
import hu.laurel.sqlpulse.ui.search.SkippedTable
import hu.laurel.sqlpulse.ui.search.TableHits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class DatabaseSearchShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private fun controller(state: DatabaseSearchUiState) = object : DatabaseSearchController {
        override val uiState: StateFlow<DatabaseSearchUiState> = MutableStateFlow(state)
        override fun setTerm(term: String) = Unit
        override fun setMode(mode: SearchMode) = Unit
        override fun setTableLimit(limit: Int) = Unit
        override fun setSkipLargeTables(skip: Boolean) = Unit
        override fun start() = Unit
        override fun cancel() = Unit
    }

    private val base = DatabaseSearchUiState(connected = true, database = "webshop")

    private val hits = listOf(
        TableHits(
            table = "customers",
            rows = listOf(
                SearchRow(listOf("id" to "1042"), listOf("code" to "KB-20417", "note" to "Régi kód: KB-20417 helyett KB-20418")),
                SearchRow(listOf("id" to "2210"), listOf("code" to "KB-20417")),
            ),
            capped = false,
        ),
        TableHits(
            table = "order_notes",
            rows = listOf(
                SearchRow(
                    listOf("order_id" to "88121", "line" to "3"),
                    listOf("body" to "Az ügyfél (kb-20417) telefonon kérte a módosítást, a szállítási cím marad, a számlát viszont a központi címre kéri, külön levélben is megerősítve."),
                ),
            ),
            capped = true,
        ),
    )

    @Test
    fun idle() = paparazzi.screen {
        DatabaseSearchScreenContent(onBack = {}, onOpenTable = { _, _ -> }, viewModel = controller(base))
    }

    @Test
    fun running() = paparazzi.screen {
        DatabaseSearchScreenContent(
            onBack = {},
            onOpenTable = { _, _ -> },
            viewModel = controller(
                base.copy(
                    term = "KB-20417", running = true, searched = true, tablesDone = 14, tablesTotal = 37,
                    currentTable = "order_notes", results = hits.take(1),
                ),
            ),
        )
    }

    @Test
    fun results() = paparazzi.screen {
        DatabaseSearchScreenContent(
            onBack = {},
            onOpenTable = { _, _ -> },
            viewModel = controller(
                base.copy(
                    term = "kb-20417", searched = true, tablesDone = 37, tablesTotal = 37, results = hits,
                    notSearched = 2,
                    skipped = listOf(SkippedTable("audit_log", "A lekérdezés túllépte az időkorlátot.")),
                ),
            ),
        )
    }

    @Test
    fun productionIdle() = paparazzi.screen {
        DatabaseSearchScreenContent(
            onBack = {},
            onOpenTable = { _, _ -> },
            viewModel = controller(base.copy(production = true, tableLimit = 50, skipLargeTables = true, rowsPerTable = 20, term = "KB-20417")),
        )
    }
}
