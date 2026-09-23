package hu.laurel.sqlpulse.ui.server

import kotlinx.coroutines.flow.StateFlow

/**
 * What the screen reads and asks for. [ServerViewModel] is the one real implementation; the interface lets
 * the screen be drawn from a fixed state in the screenshot tests that hold it against the design.
 */
interface ServerController {
    fun dismissGrants()
    fun kill(processId: Long)
    fun refresh()
    fun selectPanel(panel: ServerPanel)
    fun showGrants(account: String)
    val uiState: StateFlow<ServerUiState>
}
