package hu.laurel.sqlpulse.ui.schema

import android.net.Uri
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.schema.RowLink
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screen reads and asks for. [TableDetailViewModel] is the one real implementation; the interface lets
 * the screen be drawn from a fixed state in the screenshot tests that hold it against the design.
 */
interface TableDetailController {
    fun closeWalk()
    fun confirmEdit()
    fun confirmImport()
    fun dismissConflict()
    fun dismissEdit()
    fun dismissError()
    fun dismissImport()
    fun export(format: ExportFormat)
    fun loadMore()
    fun openChildren(link: RowLink)
    fun openParent(rowIndex: Int, columnLabel: String)
    fun openParentFromWalk(rowIndex: Int, columnLabel: String)
    fun overwriteConflict()
    fun parentLinkFor(rowIndex: Int, columnLabel: String): RowLink?
    fun prepareCellEdit(rowIndex: Int, columnLabel: String, newValue: String?)
    fun prepareImport(uri: Uri)
    fun prepareRowDelete(rowIndex: Int)
    fun select(tab: TableTab)
    fun selectWalkRow(rowIndex: Int)
    fun setFilter(column: String?, contains: String)
    fun shareIntentHandled()
    fun showChildrenOf(rowIndex: Int)
    fun sortBy(column: String)
    val uiState: StateFlow<TableDetailUiState>
    fun undo()
    fun walkBack()
    fun walkParentLinkFor(rowIndex: Int, columnLabel: String): RowLink?
}
