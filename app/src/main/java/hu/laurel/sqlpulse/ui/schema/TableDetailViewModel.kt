package hu.laurel.sqlpulse.ui.schema

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.TableStructure
import hu.laurel.sqlpulse.data.sql.ResultTable
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TableTab { DATA, STRUCTURE, DDL }

data class TableDetailUiState(
    val database: String = "",
    val table: String = "",
    val tab: TableTab = TableTab.DATA,
    val preview: ResultTable? = null,
    val structure: TableStructure? = null,
    val ddl: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TableDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val schema: SchemaRepository,
) : ViewModel() {

    private val database: String = Uri.decode(savedStateHandle["database"] ?: "")
    private val table: String = Uri.decode(savedStateHandle["table"] ?: "")

    private val _uiState = MutableStateFlow(TableDetailUiState(database = database, table = table))
    val uiState: StateFlow<TableDetailUiState> = _uiState.asStateFlow()

    init {
        select(TableTab.DATA)
    }

    /** Each tab loads on first visit and is kept afterwards, so switching back is instant. */
    fun select(tab: TableTab) {
        _uiState.value = _uiState.value.copy(tab = tab)
        val state = _uiState.value
        val alreadyLoaded = when (tab) {
            TableTab.DATA -> state.preview != null
            TableTab.STRUCTURE -> state.structure != null
            TableTab.DDL -> state.ddl != null
        }
        if (alreadyLoaded) return
        load(tab)
    }

    fun reload() = load(_uiState.value.tab)

    private fun load(tab: TableTab) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                _uiState.value = when (tab) {
                    TableTab.DATA -> _uiState.value.copy(preview = schema.preview(database, table))
                    TableTab.STRUCTURE -> _uiState.value.copy(structure = schema.structure(database, table))
                    TableTab.DDL -> _uiState.value.copy(ddl = schema.ddl(database, table))
                }.copy(loading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    // §11: the MySQL message verbatim; it usually says exactly what is wrong.
                    error = e.message ?: e.toString(),
                )
            }
        }
    }
}
