package hu.laurel.sqlpulse.ui.query

import hu.laurel.sqlpulse.data.chart.ChartSpec
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.db.SavedQueryEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.grid.ResultFilter
import hu.laurel.sqlpulse.data.sql.ParameterValue
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * What the query screen reads and asks for. [QueryEditorViewModel] is the one real implementation;
 * the interface exists so the screen can be drawn from a fixed state — in the screenshot tests
 * that hold it against the design — without the tunnel, the database and Hilt behind it.
 */
interface QueryEditorController {
    fun append(text: String)
    fun cancel()
    fun closeTab(id: Long)
    fun compareWithSnapshot()
    fun complete(suggestion: String)
    fun confirmWrite()
    fun deleteFavourite(favourite: SavedQueryEntity)
    fun discardSnapshot()
    fun dismissCloseTab()
    fun dismissComparison()
    fun dismissParameters()
    fun dismissSnapshotNotice()
    fun dismissTabLimit()
    fun dismissWriteConfirmation()
    fun duplicateTab(id: Long)
    fun explain()
    fun export(format: ExportFormat)
    val favourites: StateFlow<List<SavedQueryEntity>>
    fun format()
    val history: StateFlow<List<QueryHistoryEntity>>
    fun load(sql: String, saved: Boolean = false)
    fun newTab()
    fun onEditorChanged(sql: String, selectionStart: Int, selectionEnd: Int)
    fun reconnect()
    fun renameTab(id: Long, title: String)
    fun replaceAll(find: String, replacement: String)
    fun requestCloseTab(id: Long)
    fun rollback()
    fun run(parameters: Map<String, ParameterValue> = emptyMap())
    fun runCurrent(parameters: Map<String, ParameterValue> = emptyMap())
    fun saveFavourite(name: String)
    fun selectDatabase(database: String)
    fun selectPanel(panel: QueryPanel)
    fun selectStatement(index: Int)
    fun selectTab(id: Long)
    val sessionState: StateFlow<SqlSessionState>
    fun setChartSpec(spec: ChartSpec)
    fun setResultFilter(filter: ResultFilter)
    fun setTransaction(open: Boolean)
    fun shareIntentHandled()
    fun sortResult(column: String)
    fun takeSnapshot()
    fun toggleChart()
    fun toggleEditor()
    fun toggleFilterBar()
    val uiState: StateFlow<QueryEditorUiState>
}
