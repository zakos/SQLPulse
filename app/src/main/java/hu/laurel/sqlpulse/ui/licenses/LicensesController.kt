package hu.laurel.sqlpulse.ui.licenses

import hu.laurel.sqlpulse.data.licenses.LicensedComponent
import kotlinx.coroutines.flow.StateFlow

/** One license text, loaded from `assets/licenses/`, with the name it is shown under. */
data class LicenseTextBlock(val label: String, val text: String)

/** What the detail view shows: SQLPulse itself or one bundled component. */
data class LicenseDetail(
    val title: String,
    val version: String?,
    val sourceUrl: String?,
    val copyright: List<String>,
    val note: String?,
    val texts: List<LicenseTextBlock>,
)

data class LicensesUiState(
    val versionName: String = "",
    val versionCode: Int = 0,
    /** The repo's NOTICE file, verbatim. Empty until loaded. */
    val notice: String = "",
    /** Set while one component's (or SQLPulse's own) license text is open. */
    val detail: LicenseDetail? = null,
)

/**
 * What the screen reads and asks for. [LicensesViewModel] is the one real implementation; the
 * interface lets the screen be drawn from a fixed state in the screenshot tests.
 */
interface LicensesController {
    val uiState: StateFlow<LicensesUiState>
    fun openApp()
    fun open(component: LicensedComponent)
    fun closeDetail()
}
