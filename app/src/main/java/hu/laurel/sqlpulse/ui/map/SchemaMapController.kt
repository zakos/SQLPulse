package hu.laurel.sqlpulse.ui.map

import kotlinx.coroutines.flow.StateFlow

/**
 * What the screen reads and asks for. [SchemaMapViewModel] is the one real implementation; the interface lets
 * the screen be drawn from a fixed state in the screenshot tests that hold it against the design.
 */
interface SchemaMapController {
    fun select(table: String?)
    fun setShowGuesses(show: Boolean)
    val uiState: StateFlow<SchemaMapUiState>
}
