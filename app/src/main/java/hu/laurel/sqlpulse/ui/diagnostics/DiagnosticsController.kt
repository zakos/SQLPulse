package hu.laurel.sqlpulse.ui.diagnostics

import kotlinx.coroutines.flow.StateFlow

/**
 * What the screen reads and asks for. [DiagnosticsViewModel] is the one real implementation; the interface lets
 * the screen be drawn from a fixed state in the screenshot tests that hold it against the design.
 */
interface DiagnosticsController {
    fun markCopied()
    val uiState: StateFlow<DiagnosticsUiState>
}
