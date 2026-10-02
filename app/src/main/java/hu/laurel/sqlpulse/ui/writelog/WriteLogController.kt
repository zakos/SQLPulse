package hu.laurel.sqlpulse.ui.writelog

import android.content.Intent
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.WriteLogEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.writelog.WriteSource
import kotlinx.coroutines.flow.StateFlow

data class WriteLogUiState(
    /** The entries that pass the filters, newest first. */
    val entries: List<WriteLogEntity> = emptyList(),
    /** How many entries the log holds in all, for "12 of 340". */
    val total: Int = 0,
    val environment: ConnectionEnvironment? = null,
    val source: WriteSource? = null,
    val query: String = "",
    val expanded: Set<Long> = emptySet(),
    val retentionDays: Int = 90,
    val confirmClear: Boolean = false,
    /** Set once per export; the screen starts the share sheet and calls [WriteLogController.shareIntentHandled]. */
    val shareIntent: Intent? = null,
    val exportFailed: Boolean = false,
) {
    val filtered: Boolean get() = environment != null || source != null || query.isNotBlank()
}

/**
 * What the screen reads and asks for. [WriteLogViewModel] is the one real implementation; the
 * interface lets the screen be drawn from a fixed state in the screenshot tests.
 */
interface WriteLogController {
    val uiState: StateFlow<WriteLogUiState>
    fun setEnvironment(environment: ConnectionEnvironment?)
    fun setSource(source: WriteSource?)
    fun setQuery(query: String)
    fun clearFilters()
    fun toggleExpanded(id: Long)
    fun setRetentionDays(days: Int)
    fun requestClear()
    fun dismissClear()
    fun confirmClear()
    fun export(format: ExportFormat)
    fun shareIntentHandled()
}
