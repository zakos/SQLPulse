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
import hu.laurel.sqlpulse.data.csv.CsvImporter
import hu.laurel.sqlpulse.data.csv.ImportPlan
import hu.laurel.sqlpulse.data.schema.LinkTrail
import hu.laurel.sqlpulse.data.schema.LookupOutcome
import hu.laurel.sqlpulse.data.schema.RowFilter
import hu.laurel.sqlpulse.data.schema.RowLink
import hu.laurel.sqlpulse.data.schema.RowLinks
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.TableStructure
import hu.laurel.sqlpulse.data.schema.TrailStep
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
    /** Links a row of this table can be walked along to its parents; may be guessed (§7.3). */
    val parentLinks: List<RowLink> = emptyList(),
    /** Set while the walk along the relationships is open; null when it is not. */
    val walk: WalkState? = null,
)

/** One table pointing at the row being looked at, and how many of its rows match. */
data class ChildCount(val link: RowLink, val rows: Long?)

/**
 * The walk from row to row (§7.3), held in memory for as long as the screen lives.
 *
 * [trail] always begins with the table the walk started from, so stepping back off the first
 * linked step lands where the user was rather than nowhere.
 */
data class WalkState(
    val trail: LinkTrail,
    val rows: ResultTable? = null,
    val loading: Boolean = false,
    /** How many rows the lookup found, where one row was expected. Null for a child listing. */
    val outcome: LookupOutcome? = null,
    /** The parent links of the table now on screen, so the walk can carry on from here. */
    val links: List<RowLink> = emptyList(),
    val selectedRow: Int? = null,
    val children: List<ChildCount> = emptyList(),
    val childrenLoading: Boolean = false,
    val error: String? = null,
) {
    val step: TrailStep? get() = trail.current
}

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
) : ViewModel(), TableDetailController {

    private val database: String = Uri.decode(savedStateHandle["database"] ?: "")
    private val table: String = Uri.decode(savedStateHandle["table"] ?: "")

    private val _uiState = MutableStateFlow(TableDetailUiState(database = database, table = table))
    override val uiState: StateFlow<TableDetailUiState> = _uiState.asStateFlow()

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

    override fun select(tab: TableTab) {
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
    override fun sortBy(column: String) {
        _uiState.value = _uiState.value.copy(
            sort = TableQuery.nextSort(_uiState.value.sort, column),
            rows = null,
            totalRows = null,
        )
        load(TableTab.DATA)
    }

    override fun setFilter(column: String?, contains: String) {
        val filter = column?.takeIf { contains.isNotBlank() }?.let { ColumnFilter(it, contains) }
        _uiState.value = _uiState.value.copy(filter = filter, rows = null, totalRows = null)
        load(TableTab.DATA)
    }

    /** Called by the grid as it nears the end of what is loaded (§7.5). */
    override fun loadMore() {
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
    override fun prepareCellEdit(rowIndex: Int, columnLabel: String, newValue: String?) {
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

    override fun prepareRowDelete(rowIndex: Int) {
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

    override fun confirmEdit() {
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
    override fun overwriteConflict() {
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
    override fun prepareImport(uri: Uri) {
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

    override fun confirmImport() {
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

    override fun dismissImport() {
        _uiState.value = _uiState.value.copy(importPlan = null)
    }

    fun dismissBlobPreview() {
        _uiState.value = _uiState.value.copy(blobPreview = null)
    }

    override fun dismissConflict() {
        _uiState.value = _uiState.value.copy(conflict = null)
    }

    override fun dismissEdit() {
        _uiState.value = _uiState.value.copy(pendingEdit = null)
    }

    /** §7.6: the inverse statement, run without a second confirmation. */
    override fun undo() {
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

    /**
     * The link a cell belongs to, or null where following it is not on offer.
     *
     * Null for a column that references nothing, and null for a NULL value: a NULL foreign key
     * points at nothing at all, and offering to open it would promise a row that cannot exist.
     */
    override fun parentLinkFor(rowIndex: Int, columnLabel: String): RowLink? =
        linkFor(_uiState.value.parentLinks, _uiState.value.rows, rowIndex, columnLabel)

    /** The same question about a row of the walk, which is a different table's row. */
    override fun walkParentLinkFor(rowIndex: Int, columnLabel: String): RowLink? {
        val walk = _uiState.value.walk ?: return null
        return linkFor(walk.links, walk.rows, rowIndex, columnLabel)
    }

    /** Opens the row a cell of the table points at. */
    override fun openParent(rowIndex: Int, columnLabel: String) {
        val rows = _uiState.value.rows ?: return
        val link = parentLinkFor(rowIndex, columnLabel) ?: return
        walkToParent(baseTrail(), rows, rowIndex, link)
    }

    /** The same, from a row already reached by walking. */
    override fun openParentFromWalk(rowIndex: Int, columnLabel: String) {
        val walk = _uiState.value.walk ?: return
        val rows = walk.rows ?: return
        val link = walkParentLinkFor(rowIndex, columnLabel) ?: return
        walkToParent(walk.trail, rows, rowIndex, link)
    }

    /** Opens the walk on a row of the table and counts what points at it. */
    override fun showChildrenOf(rowIndex: Int) {
        val rows = _uiState.value.rows ?: return
        val state = _uiState.value
        val trail = baseTrail()
        _uiState.value = state.copy(
            walk = WalkState(
                trail = trail,
                rows = rows,
                links = state.parentLinks,
                selectedRow = rowIndex,
                childrenLoading = true,
            ),
        )
        countChildren(database, table, rowMap(rows, rowIndex))
    }

    /** The same for a row of the walk: what points at the row now on screen. */
    override fun selectWalkRow(rowIndex: Int) {
        val walk = _uiState.value.walk ?: return
        val rows = walk.rows ?: return
        val step = walk.step ?: return
        _uiState.value = _uiState.value.copy(
            walk = walk.copy(selectedRow = rowIndex, children = emptyList(), childrenLoading = true),
        )
        countChildren(step.database, step.table, rowMap(rows, rowIndex))
    }

    /** Shows the rows of one child table that point at the selected row. */
    override fun openChildren(link: RowLink) {
        val walk = _uiState.value.walk ?: return
        val rows = walk.rows ?: return
        val rowIndex = walk.selectedRow ?: return
        val filter = RowLinks.childFilter(link, rowMap(rows, rowIndex)) ?: return
        walkTo(
            trail = walk.trail,
            step = TrailStep(
                database = link.childDatabase,
                table = link.childTable,
                filter = filter,
                label = link.childTable,
                guessed = link.guessed,
            ),
            limit = RowLinks.CHILD_LIMIT,
            expectOne = false,
        )
    }

    /** One step back along the trail; stepping off the first linked step closes the walk. */
    override fun walkBack() {
        val walk = _uiState.value.walk ?: return
        val back = walk.trail.pop()
        val step = back.current
        if (!walk.trail.canGoBack || step == null || step.filter == null) {
            closeWalk()
            return
        }
        loadStep(back, step, RowLinks.CHILD_LIMIT, expectOne = false)
    }

    override fun closeWalk() {
        _uiState.value = _uiState.value.copy(walk = null)
    }

    private fun baseTrail() = LinkTrail(listOf(TrailStep(database, table, filter = null, label = table)))

    private fun walkToParent(trail: LinkTrail, rows: ResultTable, rowIndex: Int, link: RowLink) {
        val filter = RowLinks.parentFilter(link, rowMap(rows, rowIndex)) ?: return
        walkTo(
            trail = trail,
            step = TrailStep(
                database = link.parentDatabase,
                table = link.parentTable,
                filter = filter,
                label = link.parentTable,
                guessed = link.guessed,
            ),
            limit = RowLinks.PARENT_LIMIT,
            expectOne = true,
        )
    }

    private fun walkTo(trail: LinkTrail, step: TrailStep, limit: Int, expectOne: Boolean) {
        loadStep(trail.push(step), step, limit, expectOne)
    }

    private fun loadStep(trail: LinkTrail, step: TrailStep, limit: Int, expectOne: Boolean) {
        val filter: RowFilter = step.filter ?: return
        _uiState.value = _uiState.value.copy(
            walk = WalkState(trail = trail, loading = true),
        )
        viewModelScope.launch {
            try {
                val rows = schema.rowsMatching(step.database, step.table, filter, limit)
                val links = runCatching { schema.parentLinks(step.database, step.table) }
                    .getOrDefault(emptyList())
                _uiState.value = _uiState.value.copy(
                    walk = WalkState(
                        trail = trail,
                        rows = rows,
                        links = links,
                        outcome = RowLinks.outcome(rows.rowCount).takeIf { expectOne },
                    ),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    walk = WalkState(trail = trail, error = describe(e)),
                )
            }
        }
    }

    private fun countChildren(database: String, table: String, row: Map<String, String?>) {
        viewModelScope.launch {
            val counts = try {
                schema.childLinks(database, table).mapNotNull { link ->
                    val filter = RowLinks.childFilter(link, row) ?: return@mapNotNull null
                    ChildCount(
                        link = link,
                        rows = runCatching {
                            schema.countMatching(link.childDatabase, link.childTable, filter)
                        }.getOrNull(),
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    walk = _uiState.value.walk?.copy(childrenLoading = false, error = describe(e)),
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                walk = _uiState.value.walk?.copy(children = counts, childrenLoading = false),
            )
        }
    }

    private fun linkFor(
        links: List<RowLink>,
        rows: ResultTable?,
        rowIndex: Int,
        columnLabel: String,
    ): RowLink? {
        if (rows == null) return null
        val link = RowLinks.linkForColumn(links, columnLabel) ?: return null
        return link.takeIf { RowLinks.parentFilter(it, rowMap(rows, rowIndex)) != null }
    }

    /** One loaded row as column name to value, which is all a link lookup needs. */
    private fun rowMap(rows: ResultTable, rowIndex: Int): Map<String, String?> {
        val row = rows.rows.getOrNull(rowIndex) ?: return emptyMap()
        return rows.columns.mapIndexed { index, column ->
            val cell = row.getOrNull(index)
            column.label to if (cell == null || cell is CellValue.Null) null else cell.asText()
        }.toMap()
    }

    override fun export(format: ExportFormat) {
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

    override fun shareIntentHandled() {
        _uiState.value = _uiState.value.copy(shareIntent = null)
    }

    override fun dismissError() {
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
        val links = runCatching { schema.parentLinks(database, table) }.getOrDefault(emptyList())
        _uiState.value = _uiState.value.copy(
            structure = structure,
            parentLinks = links,
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
