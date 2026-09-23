package hu.laurel.sqlpulse.ui.settings

import hu.laurel.sqlpulse.data.settings.Settings
import hu.laurel.sqlpulse.ui.theme.ThemePreference
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screen reads and asks for. [SettingsViewModel] is the one real implementation; the interface lets
 * the screen be drawn from a fixed state in the screenshot tests that hold it against the design.
 */
interface SettingsController {
    fun setAutoLock(minutes: Int)
    fun setBlockScreenshots(block: Boolean)
    fun setBlockWritesWithoutWhere(block: Boolean)
    fun setGridFontScale(scale: Int)
    fun setMaxAffectedRows(rows: Int)
    fun setRowLimit(limit: Int)
    fun setTheme(theme: ThemePreference)
    val settings: StateFlow<Settings>
}
