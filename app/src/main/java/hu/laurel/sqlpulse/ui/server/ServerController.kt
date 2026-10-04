package hu.laurel.sqlpulse.ui.server

import hu.laurel.sqlpulse.data.schema.SlowSort
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screen reads and asks for. [ServerViewModel] is the one real implementation; the interface lets
 * the screen be drawn from a fixed state in the screenshot tests that hold it against the design.
 */
interface ServerController {
    fun dismissGrants()
    /** The gentle stop: cancels the statement where the engine can, otherwise ends the session. */
    fun kill(processId: Long)

    /** Ends the whole session and rolls its transaction back (offered only where the engine has it). */
    fun terminate(processId: Long) {}

    /** Whether the process list also shows sessions that are doing nothing (where the engine filters). */
    fun setShowIdle(show: Boolean) {}
    fun refresh()
    fun selectPanel(panel: ServerPanel)
    fun setReplicationRaw(raw: Boolean)
    fun setSlowSort(sort: SlowSort)
    fun showGrants(account: String)
    val uiState: StateFlow<ServerUiState>
}
