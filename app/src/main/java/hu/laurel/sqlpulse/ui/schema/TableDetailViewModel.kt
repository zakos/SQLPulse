package hu.laurel.sqlpulse.ui.schema

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.export.ExportManager
import hu.laurel.sqlpulse.data.`import`.CsvImporter
import hu.laurel.sqlpulse.data.`import`.ImportPlan
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.TableStructure
import hu.laurel.sqlpulse.data.sql.BlobPreview
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnFilter
import hu.laurel.sqlpulse.data.sql.ColumnSort
import hu.laurel.sqlpulse.data.sql.NoPrimaryKeyException
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.RowEdit
import hu.laurel.sqlpulse.data.sql.RowChangedException
import hu.laurel.sqlpulse.data.sql.RowEditor
import hu.laurel.sqlpulse.data.sql.SqlFailures
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.data.sql.TableQuery
import hu.laurel.sqlpulse.ui.explain
import hu.laurel.sqlpulse.ui.grid.asText
import hu.laurel.sqlpulse.ui.theme.ConnectionColor
import java.sql.SQLException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TableTab { DATA, STRUCTURE, DDL }

/**
 * An edit that did not happen because the row had moved on.
 *
 * [currentValue] is what the column holds now; a missing row means somebody deleted it, and then
 * there is nothing to overwrite.
 */
data class EditConflict(
    val edit: RowEdit,
    val currentValue: String?,
    val rowExists: Boolean,
)

data class TableDetailUiState(
    val database: String = "",
    val table: String = "",
    val tab: TableTab = TableTab.DATA,
    val rows: ResultTable? = null,
    val totalRows: Int? = null,
    val loadingMore: Boolean = false,
    val structure: TableStructure? = null,
    val ddl: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** Server-side sort of the data page; null means the table's own order (§7.5). */
    val sort: ColumnSort? = null,
    /** Quick "contains" search on one column (§7.1). */
    val filter: ColumnFilter? = null,
    /** False on a read-only connection, or when the table has no primary key (§7.6). */
    val canEdit: Boolean = false,
    val editBlockedReason: EditBlock? = null,
    /** Set while a statement waits for confirmation. */
    val pendingEdit: RowEdit? = null,
    /** Set when the row changed between being read and being written (§7.6). */
    val conflict: EditConflict? = null,
    /** The BLOB the user asked to look inside, once its first bytes have arrived. */
    val blobPreview: BlobPreview.Preview? = null,
    val loadingBlob: Boolean = false,
    /** A CSV file that has been read and matched, waiting for the user to say go ahead. */
    val importPlan: ImportPlan? = null,
    val importing: Boolean = false,
    /** Set for ten seconds after a successful edit, while it can still be taken back (§7.6). */
    val undoable: RowEdit? = null,
    val isProduction: Boolean = false,
    val shareIntent: Intent? = null,
)

enum class EditBlock { READ_ONLY, NO_PRIMARY_KEY }

@HiltViewModel
class TableDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val schema: SchemaRepository,
    private val rowEditor: RowEditor,
    private val importer: CsvImporter,
    private val exports: ExportManager,
    private val sessions: SqlSessionManager,
) : ViewModel() {

    private val database: String = Uri.decode(savedStateHandle["database"] ?: "")
    private val table: String = Uri.decode(savedStateHandle["table"] ?: "")

    private val _uiState = MutableStateFlow(TableDetailUiState(database = database, table = table))
    val uiState: StateFlow<TableDetailUiState> = _uiState.asStateFlow()

    private var undoJob: Job? = null

    init {
        val connection = sessions.currentConnection()
        _uiState.value = _uiState.value.copy(
            isProduction = ConnectionColor.fromName(connection?.color) == ConnectionColor.Production,
        )
        select(TableTab.DATA)
        // The structure is needed for the primary key even before the Structure tab is opened.
        viewModelScope.launch { loadStructure() }
    }

    fun select(tab: TableTab) {
        _uiState.value = _uiState.value.copy(tab = tab)
        val state = _uiState.value
        val loaded = when (tab) {
            TableTab.DATA -> state.rows != null
            TableTab.STRUCTURE -> state.structure != null
            TableTab.DDL -> state.ddl != null
        }
        if (!loaded) load(tab)
    }

    fun reload() {
        _uiState.value = _uiState.value.copy(rows = null, totalRows = null)
        load(_uiState.value.tab)
    }

    /** Cycles the column through ascending, descending and the table's own order. */
    fun sortBy(column: String) {
        _uiState.value = _uiState.value.copy(
            sort = TableQuery.nextSort(_uiState.value.sort, column),
            rows = null,
            totalRows = null,
        )
        load(TableTab.DATA)
    }

    fun setFilter(column: String?, contains: String) {
        val filter = column?.takeIf { contains.isNotBlank() }?.let { ColumnFilter(it, contains) }
        _uiState.value = _uiState.value.copy(filter = filter, rows = null, totalRows = null)
        load(TableTab.DATA)
    }

    /** Called by the grid as it nears the end of what is loaded (§7.5). */
    fun loadMore() {
        val state = _uiState.value
        val current = state.rows ?: return
        if (state.loadingMore) return
        if (state.totalRows != null && current.rowCount >= state.totalRows) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingMore = true)
            try {
                val next = schema.preview(
                    database = database,
                    table = table,
                    limit = PAGE_SIZE,
                    offset = current.rowCount,
                    sort = state.sort,
                    filter = state.filter,
                )
                _uiState.value = _uiState.value.copy(
                    rows = current.copy(
                        rows = current.rows + next.rows,
                        truncated = next.rows.size == PAGE_SIZE,
                    ),
                    loadingMore = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loadingMore = false, error = describe(e))
            }
        }
    }

    /** Builds the UPDATE and holds it for confirmation; nothing runs until the user says so. */
    fun prepareCellEdit(rowIndex: Int, columnLabel: String, newValue: String?) {
        val state = _uiState.value
        val rows = state.rows ?: return
        val structure = state.structure ?: return
        try {
            val key = primaryKeyOf(rows, structure, rowIndex)
            val oldValue = valueAt(rows, rowIndex, columnLabel)
            _uiState.value = state.copy(
                pendingEdit = rowEditor.prepareUpdate(
                    database = database,
                    table = table,
                    key = key,
                    column = columnLabel,
                    oldValue = oldValue,
                    newValue = newValue,
                ),
            )
        } catch (e: Exception) {
            _uiState.value = state.copy(error = describe(e))
        }
    }

    fun prepareRowDelete(rowIndex: Int) {
        val state = _uiState.value
        val rows = state.rows ?: return
        val structure = state.structure ?: return
        try {
            _uiState.value = state.copy(
                pendingEdit = rowEditor.prepareDelete(
                    database,
                    table,
                    primaryKeyOf(rows, structure, rowIndex),
                ),
            )
        } catch (e: Exception) {
            _uiState.value = state.copy(error = describe(e))
        }
    }

    fun confirmEdit() {
        val edit = _uiState.value.pendingEdit ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pendingEdit = null, loading = true)
            try {
                rowEditor.execute(edit)
                _uiState.value = _uiState.value.copy(loading = false, undoable = edit.takeIf { it.undo != null })
                startUndoWindow()
                reload()
            } catch (e: RowChangedException) {
                // Not an error: the edit simply did not happen, and the user decides what now.
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    conflict = EditConflict(edit, e.currentValue, e.rowExists),
                )
                reload()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = describe(e))
            }
        }
    }

    /** Writes the value anyway, now that the user has seen what they are overwriting. */
    fun overwriteConflict() {
        val conflict = _uiState.value.conflict ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(conflict = null, loading = true)
            try {
                rowEditor.overwrite(conflict.edit)
                _uiState.value = _uiState.value.copy(loading = false)
                reload()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = describe(e))
            }
        }
    }

    /**
     * Reads the beginning of a BLOB cell so it can be looked at.
     *
     * On demand rather than with the page: the grid shows a size for a reason, and loading every
     * BLOB of every row to display "size" would be the opposite of that.
     */
    fun previewBlob(rowIndex: Int, columnLabel: String) {
        val state = _uiState.value
        val rows = state.rows ?: return
        val structure = state.structure ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingBlob = true, blobPreview = null)
            try {
                val (bytes, truncated) = schema.blobBytes(
                    database = database,
                    table = table,
                    key = primaryKeyOf(rows, structure, rowIndex),
                    column = columnLabel,
                )
                _uiState.value = _uiState.value.copy(
                    loadingBlob = false,
                    blobPreview = BlobPreview.of(bytes, truncated),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loadingBlob = false, error = describe(e))
            }
        }
    }

    /** Reads the chosen file and works out what importing it would do. Nothing is written yet. */
    fun prepareImport(uri: Uri) {
        val structure = _uiState.value.structure ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(importing = true, error = null)
            try {
                _uiState.value = _uiState.value.copy(
                    importing = false,
                    importPlan = importer.plan(uri, structure.columns),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(importing = false, error = describe(e))
            }
        }
    }

    fun confirmImport() {
        val plan = _uiState.value.importPlan ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(importPlan = null, importing = true)
            try {
                importer.execute(database, table, plan)
                _uiState.value = _uiState.value.copy(importing = false)
                reload()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(importing = false, error = describe(e))
            }
        }
    }

    fun dismissImport() {
        _uiState.value = _uiState.value.copy(importPlan = null)
    }

    fun dismissBlobPreview() {
        _uiState.value = _uiState.value.copy(blobPreview = null)
    }

    fun dismissConflict() {
        _uiState.value = _uiState.value.copy(conflict = null)
    }

    fun dismissEdit() {
        _uiState.value = _uiState.value.copy(pendingEdit = null)
    }

    /** §7.6: the inverse statement, run without a second confirmation. */
    fun undo() {
        val undo = _uiState.value.undoable?.undo ?: return
        undoJob?.cancel()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(undoable = null)
            try {
                rowEditor.execute(undo)
                reload()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = describe(e))
            }
        }
    }

    fun export(format: ExportFormat) {
        val rows = _uiState.value.rows ?: return
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    shareIntent = exports.shareIntent(rows, format, "$database-$table", table),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = describe(e))
            }
        }
    }

    fun shareIntentHandled() {
        _uiState.value = _uiState.value.copy(shareIntent = null)
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun startUndoWindow() {
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            delay(RowEditor.UNDO_WINDOW_MS)
            _uiState.value = _uiState.value.copy(undoable = null)
        }
    }

    private fun load(tab: TableTab) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                when (tab) {
                    TableTab.DATA -> {
                        val current = _uiState.value
                        val page = schema.preview(
                            database = database,
                            table = table,
                            limit = PAGE_SIZE,
                            offset = 0,
                            sort = current.sort,
                            filter = current.filter,
                        )
                        val total = runCatching {
                            schema.rowCount(database, table, current.filter).toInt()
                        }.getOrNull()
                        _uiState.value = _uiState.value.copy(rows = page, totalRows = total)
                    }

                    TableTab.STRUCTURE -> loadStructure()
                    TableTab.DDL -> _uiState.value = _uiState.value.copy(ddl = schema.ddl(database, table))
                }
                _uiState.value = _uiState.value.copy(loading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = describe(e))
            }
        }
    }

    private suspend fun loadStructure() {
        val structure = runCatching { schema.structure(database, table) }.getOrNull() ?: return
        val readOnly = (sessions.state.value as? SqlSessionState.Ready)?.connection?.readOnly ?: true
        _uiState.value = _uiState.value.copy(
            structure = structure,
            canEdit = !readOnly && structure.primaryKey.isNotEmpty(),
            editBlockedReason = when {
                readOnly -> EditBlock.READ_ONLY
                structure.primaryKey.isEmpty() -> EditBlock.NO_PRIMARY_KEY
                else -> null
            },
        )
    }

    /**
     * The primary key of one loaded row. The values come from the grid, so a row whose key columns
     * were not selected cannot be edited — which is the honest answer, not a guess.
     */
    private fun primaryKeyOf(
        rows: ResultTable,
        structure: TableStructure,
        rowIndex: Int,
    ): Map<String, String?> {
        val key = structure.primaryKey
        if (key.isEmpty()) throw NoPrimaryKeyException()
        return key.associateWith { column ->
            valueAt(rows, rowIndex, column) ?: throw NoPrimaryKeyException()
        }
    }

    private fun valueAt(rows: ResultTable, rowIndex: Int, columnLabel: String): String? {
        val columnIndex = rows.columns.indexOfFirst { it.label == columnLabel }
        if (columnIndex < 0) throw NoPrimaryKeyException()
        val cell = rows.rows.getOrNull(rowIndex)?.getOrNull(columnIndex) ?: return null
        return if (cell is CellValue.Null) null else cell.asText()
    }

    private fun describe(e: Exception): String = when (e) {
        is SQLException -> context.explain(SqlFailures.of(e))
        else -> e.message ?: e.toString()
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
