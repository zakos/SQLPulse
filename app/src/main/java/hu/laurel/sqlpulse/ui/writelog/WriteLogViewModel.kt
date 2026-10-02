package hu.laurel.sqlpulse.ui.writelog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.WriteLogDao
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.export.ExportManager
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.data.writelog.WriteLogExport
import hu.laurel.sqlpulse.data.writelog.WriteLogFilter
import hu.laurel.sqlpulse.data.writelog.WriteSource
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the user has asked to see and do, apart from the log itself. */
private data class Ui(
    val environment: ConnectionEnvironment? = null,
    val source: WriteSource? = null,
    val query: String = "",
    val expanded: Set<Long> = emptySet(),
    val confirmClear: Boolean = false,
    val shareIntent: android.content.Intent? = null,
    val exportFailed: Boolean = false,
)

@HiltViewModel
class WriteLogViewModel @Inject constructor(
    private val dao: WriteLogDao,
    private val settings: SettingsRepository,
    private val exports: ExportManager,
) : ViewModel(), WriteLogController {

    private val ui = MutableStateFlow(Ui())

    override val uiState: StateFlow<WriteLogUiState> =
        combine(dao.observeAll(), settings.settings, ui) { all, current, ui ->
            WriteLogUiState(
                entries = WriteLogFilter.apply(all, ui.environment, ui.source, ui.query),
                total = all.size,
                environment = ui.environment,
                source = ui.source,
                query = ui.query,
                expanded = ui.expanded,
                retentionDays = current.writeLogDays,
                confirmClear = ui.confirmClear,
                shareIntent = ui.shareIntent,
                exportFailed = ui.exportFailed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WriteLogUiState())

    override fun setEnvironment(environment: ConnectionEnvironment?) = ui.update { it.copy(environment = environment) }

    override fun setSource(source: WriteSource?) = ui.update { it.copy(source = source) }

    override fun setQuery(query: String) = ui.update { it.copy(query = query) }

    override fun clearFilters() = ui.update { it.copy(environment = null, source = null, query = "") }

    override fun toggleExpanded(id: Long) = ui.update {
        it.copy(expanded = if (id in it.expanded) it.expanded - id else it.expanded + id)
    }

    override fun setRetentionDays(days: Int) {
        viewModelScope.launch { settings.setWriteLogDays(days) }
    }

    override fun requestClear() = ui.update { it.copy(confirmClear = true) }

    override fun dismissClear() = ui.update { it.copy(confirmClear = false) }

    override fun confirmClear() {
        ui.update { it.copy(confirmClear = false) }
        viewModelScope.launch { dao.deleteAll() }
    }

    /** Only ever on an explicit tap: the filtered view is what is exported, nothing else. */
    override fun export(format: ExportFormat) {
        val entries = uiState.value.entries
        viewModelScope.launch {
            try {
                val intent = exports.shareIntent(WriteLogExport.toTable(entries), format, "write-log")
                ui.update { it.copy(shareIntent = intent, exportFailed = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ui.update { it.copy(exportFailed = true) }
            }
        }
    }

    override fun shareIntentHandled() = ui.update { it.copy(shareIntent = null, exportFailed = false) }
}
