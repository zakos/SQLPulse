package hu.laurel.sqlpulse.ui.query

import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.connection.WriteUnlockStore
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.NoPrimaryKeyException
import hu.laurel.sqlpulse.data.sql.NotEditableReason
import hu.laurel.sqlpulse.data.sql.ResultEditTarget
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.ResultEditabilities
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.RowChangedException
import hu.laurel.sqlpulse.data.sql.RowEdit
import hu.laurel.sqlpulse.data.sql.RowEditor
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.WritesLockedException
import hu.laurel.sqlpulse.data.sql.dialect.EngineFeature
import hu.laurel.sqlpulse.data.writelog.WriteSource
import hu.laurel.sqlpulse.ui.grid.asText
import hu.laurel.sqlpulse.ui.schema.EditConflict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** What the result header says about editing the rows that are on screen. */
sealed interface ResultEditStatus {
    /** Nothing worth saying: no result, not a SELECT, or a connection that is read-only anyway. */
    data object None : ResultEditStatus

    data class Editable(val target: ResultEditTarget) : ResultEditStatus

    data class ReadOnly(val reason: NotEditableReason) : ResultEditStatus
}

/** Everything the query screen needs to edit the rows of its result, apart from the rows. */
data class ResultEditUiState(
    val status: ResultEditStatus = ResultEditStatus.None,
    /** Set while a statement waits for confirmation. */
    val pendingEdit: RowEdit? = null,
    /** Set when the row changed between being read and being written (§7.6). */
    val conflict: EditConflict? = null,
    /** Set for ten seconds after a successful edit, while it can still be taken back (§7.6). */
    val undoable: RowEdit? = null,
    val error: String? = null,
    val isProduction: Boolean = false,
    val busy: Boolean = false,
) {
    val canEdit: Boolean get() = status is ResultEditStatus.Editable
    val table: String? get() = (status as? ResultEditStatus.Editable)?.target?.table
}

/**
 * Row editing for the query screen's result, the way the table page does it: the same edit
 * statements, confirmation and undo, with the cell or row addressed by its place in the grid.
 *
 * Row indices count the rows the grid shows, that is, after the result filter.
 */
interface ResultEditController {
    val state: StateFlow<ResultEditUiState>
    fun prepareCellEdit(rowIndex: Int, columnIndex: Int, newValue: String?)
    fun prepareRowDelete(rowIndex: Int)
    fun confirmEdit()
    fun dismissEdit()
    fun overwriteConflict()
    fun dismissConflict()
    fun undo()
    fun dismissError()
}

/**
 * What decides whether the shown result can be edited. Kept small and comparable so that sorting,
 * which swaps the rows but not the statement, does not ask the schema again.
 */
data class ResultEditKey(
    val tabId: Long,
    val sql: String,
    val database: String?,
    val columns: List<String>,
    val readOnly: Boolean,
)

/** The key of the result the active tab shows, or null where there is nothing to edit. */
fun QueryEditorUiState.resultEditKey(): ResultEditKey? {
    val tab = active
    val result = tab.result ?: return null
    if (result.columns.isEmpty() || tab.updateCount != null) return null
    // The statement that produced this result, not whatever the editor holds now.
    val sql = tab.statements.getOrNull(tab.selectedStatement)?.sql ?: return null
    return ResultEditKey(tab.id, sql, tab.database, result.columns.map { it.label }, readOnly)
}

/** The rows an edit is made against: what the grid shows, and the tab they belong to. */
class ResultEditSource(val tabId: Long, val rows: ResultTable)

@OptIn(ExperimentalCoroutinesApi::class)
class QueryResultEditing(
    private val scope: CoroutineScope,
    private val schema: SchemaRepository,
    private val rowEditor: RowEditor,
    private val sessions: SqlSessionManager,
    private val writeUnlock: WriteUnlockStore,
    private val describe: (Exception) -> String,
    /** The rows on screen right now. */
    private val source: () -> ResultEditSource?,
    /** Runs the tab's statement again, so the grid shows what the server holds after a write. */
    private val refresh: suspend (tabId: Long) -> Unit,
) : ResultEditController {

    private val _state = MutableStateFlow(ResultEditUiState())
    override val state: StateFlow<ResultEditUiState> = _state.asStateFlow()

    private var undoJob: Job? = null

    /** The tab a pending edit, conflict or undo belongs to; the result may be gone by the time it resolves. */
    private var editTab: Long = 0

    /** Follows the result on screen; whenever it, or the production unlock, changes, the verdict is redone. */
    fun track(keys: Flow<ResultEditKey?>) {
        keys.combine(writeUnlock.unlockedUntil) { key, _ -> key }
            .mapLatest { key -> evaluate(key) }
            .onEach { status -> _state.value = _state.value.copy(status = status, isProduction = isProduction()) }
            .launchIn(scope)
    }

    private suspend fun evaluate(key: ResultEditKey?): ResultEditStatus {
        // On a read-only connection every result is read-only; saying so under each of them is noise.
        if (key == null || key.readOnly) return ResultEditStatus.None
        // Asked of the live engine: its quoting decides what a table name in the SELECT is, and an
        // engine that cannot edit results in place says nothing rather than something wrong.
        val dialect = sessions.dialect()
        if (!dialect.supports(EngineFeature.EDITABLE_RESULTS)) return ResultEditStatus.None
        val editable = when (val analysis = dialect.resultEditability(key.sql)) {
            is ResultEditability.NotEditable ->
                return if (analysis.reason == NotEditableReason.NOT_SELECT) {
                    ResultEditStatus.None
                } else {
                    ResultEditStatus.ReadOnly(analysis.reason)
                }

            is ResultEditability.Editable -> analysis
            is ResultEditability.Confirmed -> return ResultEditStatus.None
        }
        val database = editable.database ?: key.database
            ?: return ResultEditStatus.ReadOnly(NotEditableReason.NO_DATABASE)
        val structure = runCatching { schema.structure(database, editable.table) }.getOrNull()
            ?: return ResultEditStatus.ReadOnly(NotEditableReason.UNKNOWN_TABLE)
        return when (val confirmed = ResultEditabilities.confirm(editable, key.database, key.columns, structure)) {
            is ResultEditability.Confirmed ->
                if (writeAccessAllowed()) {
                    ResultEditStatus.Editable(confirmed.target)
                } else {
                    ResultEditStatus.ReadOnly(NotEditableReason.WRITES_LOCKED)
                }

            is ResultEditability.NotEditable -> ResultEditStatus.ReadOnly(confirmed.reason)
            is ResultEditability.Editable -> ResultEditStatus.None
        }
    }

    private fun writeAccess() = writeUnlock.writeAccess(
        connectionId = sessions.currentConnection()?.id ?: 0L,
        environment = ConnectionEnvironment.fromName(sessions.currentConnection()?.environment),
        readOnly = sessions.currentConnection()?.readOnly ?: true,
    )

    private fun writeAccessAllowed() = writeAccess().allowed

    private fun isProduction() =
        ConnectionEnvironment.fromName(sessions.currentConnection()?.environment).isProduction

    // --- Preparing -----------------------------------------------------------------------------

    /** Builds the UPDATE and holds it for confirmation; nothing runs until the user says so. */
    override fun prepareCellEdit(rowIndex: Int, columnIndex: Int, newValue: String?) = prepare { source, target ->
        val column = target.columns.getOrNull(columnIndex) ?: throw NoPrimaryKeyException()
        rowEditor.prepareUpdate(
            database = target.database,
            table = target.table,
            key = keyOf(source.rows, target, rowIndex),
            column = column,
            oldValue = valueAt(source.rows, rowIndex, columnIndex),
            newValue = newValue,
        )
    }

    override fun prepareRowDelete(rowIndex: Int) = prepare { source, target ->
        rowEditor.prepareDelete(target.database, target.table, keyOf(source.rows, target, rowIndex))
    }

    private fun prepare(build: (ResultEditSource, ResultEditTarget) -> RowEdit) {
        val target = (_state.value.status as? ResultEditStatus.Editable)?.target ?: return
        val source = source() ?: return
        try {
            // Checked again here: the unlock window may have closed since the header was drawn.
            val access = writeAccess()
            if (!access.allowed) throw WritesLockedException(access)
            editTab = source.tabId
            _state.value = _state.value.copy(pendingEdit = build(source, target))
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = describe(e))
        }
    }

    // --- Running -------------------------------------------------------------------------------

    override fun confirmEdit() {
        val edit = _state.value.pendingEdit ?: return
        val tab = editTab
        scope.launch {
            _state.value = _state.value.copy(pendingEdit = null, busy = true)
            try {
                val access = writeAccess()
                if (!access.allowed) throw WritesLockedException(access)
                rowEditor.execute(edit, WriteSource.RESULT_EDIT)
                _state.value = _state.value.copy(busy = false, undoable = edit.takeIf { it.undo != null })
                startUndoWindow()
                refresh(tab)
            } catch (e: RowChangedException) {
                // Not an error: the edit simply did not happen, and the user decides what now.
                _state.value = _state.value.copy(busy = false, conflict = EditConflict(edit, e.currentValue, e.rowExists))
                refresh(tab)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = describe(e))
            }
        }
    }

    /** Writes the value anyway, now that the user has seen what they are overwriting. */
    override fun overwriteConflict() {
        val conflict = _state.value.conflict ?: return
        val tab = editTab
        scope.launch {
            _state.value = _state.value.copy(conflict = null, busy = true)
            try {
                rowEditor.overwrite(conflict.edit, WriteSource.RESULT_EDIT)
                _state.value = _state.value.copy(busy = false)
                refresh(tab)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = describe(e))
            }
        }
    }

    /** §7.6: the inverse statement, run without a second confirmation. */
    override fun undo() {
        val undo = _state.value.undoable?.undo ?: return
        val tab = editTab
        undoJob?.cancel()
        scope.launch {
            _state.value = _state.value.copy(undoable = null)
            try {
                rowEditor.execute(undo, WriteSource.UNDO)
                refresh(tab)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = describe(e))
            }
        }
    }

    override fun dismissEdit() {
        _state.value = _state.value.copy(pendingEdit = null)
    }

    override fun dismissConflict() {
        _state.value = _state.value.copy(conflict = null)
    }

    override fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun startUndoWindow() {
        undoJob?.cancel()
        undoJob = scope.launch {
            delay(RowEditor.UNDO_WINDOW_MS)
            _state.value = _state.value.copy(undoable = null)
        }
    }

    // --- Reading the grid ----------------------------------------------------------------------

    /**
     * The key of one shown row, read from the row itself. A row whose key cells are NULL or a BLOB
     * cannot be named in a WHERE clause, and that is refused rather than guessed at.
     */
    private fun keyOf(rows: ResultTable, target: ResultEditTarget, rowIndex: Int): Map<String, String?> =
        target.key.mapValues { (_, position) ->
            val cell = rows.rows.getOrNull(rowIndex)?.getOrNull(position)
            if (cell == null || cell is CellValue.Null || cell is CellValue.Blob) throw NoPrimaryKeyException()
            cell.asText()
        }

    private fun valueAt(rows: ResultTable, rowIndex: Int, columnIndex: Int): String? {
        val cell = rows.rows.getOrNull(rowIndex)?.getOrNull(columnIndex) ?: return null
        return if (cell is CellValue.Null) null else cell.asText()
    }
}
