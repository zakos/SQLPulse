package hu.laurel.sqlpulse.ui.map

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.export.ExportManager
import hu.laurel.sqlpulse.data.schema.GraphEdge
import hu.laurel.sqlpulse.data.schema.LinkGuesser
import hu.laurel.sqlpulse.data.schema.SchemaGraph
import hu.laurel.sqlpulse.data.schema.SchemaLayout
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.SchemaSvg
import hu.laurel.sqlpulse.data.schema.TableKind
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SchemaMapUiState(
    val database: String? = null,
    val graph: SchemaGraph = SchemaGraph(),
    val selected: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** True while links read off the column names are being drawn as well. */
    val showGuesses: Boolean = true,
    /** How many links the server itself reported, and how many were only guessed at. */
    val declaredCount: Int = 0,
    val guessedCount: Int = 0,
    /** Set while the share sheet is being opened with the map's SVG. */
    val shareIntent: Intent? = null,
) {
    /** A schema with no foreign keys at all — old, or built before InnoDB was the default. */
    val hasNoDeclaredLinks: Boolean get() = declaredCount == 0
}

/**
 * The map of one database: its tables, and the foreign keys between them.
 *
 * Views are left out. A view has no foreign keys of its own and nothing can reference it, so it
 * would be a box with no lines — noise on a map whose whole subject is the lines.
 */
@HiltViewModel
class SchemaMapViewModel @Inject constructor(
    private val schema: SchemaRepository,
    private val exports: ExportManager,
    private val sessions: SqlSessionManager,
) : ViewModel() {

    /**
     * The map as a file, handed to the share sheet.
     *
     * SVG rather than a picture of the screen: the map is usually wider than the phone, and what
     * gets shared should be the whole schema at a size somebody can read, not the part that
     * happened to be visible.
     */
    fun share() {
        val state = _uiState.value
        val database = state.database ?: return
        if (state.graph.isEmpty) return
        viewModelScope.launch {
            try {
                _uiState.value = state.copy(
                    shareIntent = exports.shareText(
                        content = SchemaSvg.render(state.graph, database),
                        baseName = database,
                        extension = "svg",
                        mimeType = "image/svg+xml",
                    ),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: e.toString())
            }
        }
    }

    fun shareIntentHandled() {
        _uiState.value = _uiState.value.copy(shareIntent = null)
    }

    private val _uiState = MutableStateFlow(SchemaMapUiState())
    val uiState: StateFlow<SchemaMapUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        val database = sessions.database.value
        if (database.isNullOrBlank()) {
            _uiState.value = SchemaMapUiState(loading = false)
            return
        }
        _uiState.value = _uiState.value.copy(database = database, loading = true, error = null)
        viewModelScope.launch {
            try {
                val tables = schema.tables(database).filter { it.kind == TableKind.TABLE }
                val names = tables.map { it.name }.toSet()
                declared = schema.links(database)
                // Only worth asking for the columns where the names are all there is to go on.
                guessed = if (declared.isEmpty()) {
                    LinkGuesser.infer(
                        tables = schema.columnNames(database).filter { it.table in names },
                        known = declared,
                    )
                } else {
                    emptyList()
                }
                rowCounts = tables.associate { it.name to it.approximateRows }
                tableNames = tables.map { it.name }
                _uiState.value = _uiState.value.copy(
                    declaredCount = declared.size,
                    guessedCount = guessed.size,
                    loading = false,
                )
                rebuild()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun select(table: String?) {
        _uiState.value = _uiState.value.copy(selected = table)
    }

    fun setShowGuesses(show: Boolean) {
        _uiState.value = _uiState.value.copy(showGuesses = show)
        rebuild()
    }

    private fun rebuild() {
        val edges = declared + if (_uiState.value.showGuesses) guessed else emptyList()
        _uiState.value = _uiState.value.copy(
            graph = SchemaLayout.build(tableNames, edges, rowCounts),
        )
    }

    private var declared: List<GraphEdge> = emptyList()
    private var guessed: List<GraphEdge> = emptyList()
    private var tableNames: List<String> = emptyList()
    private var rowCounts: Map<String, Long?> = emptyMap()
}
