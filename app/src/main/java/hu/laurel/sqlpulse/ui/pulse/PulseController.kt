package hu.laurel.sqlpulse.ui.pulse

import kotlinx.coroutines.flow.StateFlow

/**
 * What the screen reads and asks for. [PulseViewModel] is the one real implementation; the interface lets
 * the screen be drawn from a fixed state in the screenshot tests that hold it against the design.
 */
interface PulseController {
    fun setInterval(interval: PulseInterval)
    fun start()
    fun stop()
    val uiState: StateFlow<PulseUiState>
}
