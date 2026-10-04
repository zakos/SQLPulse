package hu.laurel.sqlpulse.ui.backup

import android.net.Uri
import hu.laurel.sqlpulse.data.backup.MergeResolution
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screen reads and asks for. [BackupViewModel] is the one real implementation; the interface lets
 * the screen be drawn from a fixed state in the screenshot tests that hold it against the design.
 */
interface BackupController {
    fun chooseFile(uri: Uri)
    fun dismissProblem()
    fun export(uri: Uri)
    fun import()
    fun setDefaultResolution(resolution: MergeResolution)
    fun setExportConfirmation(value: String)
    fun setExportPassphrase(value: String)
    fun setImportPassphrase(value: String)
    fun setIncludeSecrets(include: Boolean)
    fun setResolution(sourceName: String, resolution: MergeResolution)
    fun startOver()
    val state: StateFlow<BackupUiState>
    fun suggestedFileName(): String
    fun unlock()
}
