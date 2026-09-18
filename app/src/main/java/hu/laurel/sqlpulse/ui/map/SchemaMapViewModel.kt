package hu.laurel.sqlpulse.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.schema.SchemaGraph
import hu.laurel.sqlpulse.data.schema.SchemaLayout
import hu.laurel.sqlpulse.data.schema.SchemaRepository
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
)

/**
 * The map of one database: its tables, and the foreign keys between them.
 *
 * Views are left out. A view has no foreign keys of its own and nothing can reference it, so it
 * would be a box with no lines — noise on a map whose whole subject is the lines.
 */
@HiltViewModel
class SchemaMapViewModel @Inject constructor(
    private val schema: SchemaRepository,
    private val sessions: SqlSessionManager,
) : ViewModel() {

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
                val links = schema.links(database)
                _uiState.value = _uiState.value.copy(
                    graph = SchemaLayout.build(
                        tables = tables.map { it.name },
                        edges = links,
                        rows = tables.associate { it.name to it.approximateRows },
                    ),
                    loading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun select(table: String?) {
        _uiState.value = _uiState.value.copy(selected = table)
    }
}
