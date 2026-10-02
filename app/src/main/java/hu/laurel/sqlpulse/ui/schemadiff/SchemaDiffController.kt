package hu.laurel.sqlpulse.ui.schemadiff

import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.schema.SchemaCapture
import hu.laurel.sqlpulse.data.schema.SchemaDiffOptions
import hu.laurel.sqlpulse.data.schema.SchemaDiffResult
import kotlinx.coroutines.flow.StateFlow

enum class DiffSide { A, B }

/** One side of the comparison: which connection and database, and what the cache holds of it. */
data class SchemaDiffSideState(
    val connectionId: Long? = null,
    val database: String? = null,
    val databases: List<String> = emptyList(),
    val capture: SchemaCapture? = null,
    val loading: Boolean = false,
    /** Structures read so far and in all, while a live refresh runs. */
    val progress: Pair<Int, Int>? = null,
    val refreshFailed: Boolean = false,
)

data class SchemaDiffUiState(
    val connections: List<ConnectionEntity> = emptyList(),
    /** The connection with the open session: the only side that can be refreshed. */
    val liveConnectionId: Long? = null,
    val a: SchemaDiffSideState = SchemaDiffSideState(),
    val b: SchemaDiffSideState = SchemaDiffSideState(),
    val options: SchemaDiffOptions = SchemaDiffOptions(),
    val result: SchemaDiffResult? = null,
    /** The clock the capture ages are measured against, held in the state so a screenshot is fixed. */
    val now: Long = 0,
) {
    fun side(side: DiffSide): SchemaDiffSideState = if (side == DiffSide.A) a else b

    fun connection(id: Long?): ConnectionEntity? = connections.firstOrNull { it.id == id }

    fun isLive(side: DiffSide): Boolean =
        liveConnectionId != null && side(side).connectionId == liveConnectionId
}

/**
 * What the comparison screen reads and asks for. [SchemaDiffViewModel] is the real one; the
 * interface lets the screenshot tests draw the screen from a fixed state.
 */
interface SchemaDiffController {
    val uiState: StateFlow<SchemaDiffUiState>
    fun selectConnection(side: DiffSide, connectionId: Long)
    fun selectDatabase(side: DiffSide, database: String)
    fun refresh(side: DiffSide)
    fun swap()
    fun setOptions(options: SchemaDiffOptions)
}
