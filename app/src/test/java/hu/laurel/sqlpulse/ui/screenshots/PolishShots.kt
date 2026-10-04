package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.snapshot.MatchStrategy
import hu.laurel.sqlpulse.data.snapshot.ResultSnapshot
import hu.laurel.sqlpulse.data.snapshot.SnapshotOrigin
import hu.laurel.sqlpulse.data.snapshot.SnapshotRow
import hu.laurel.sqlpulse.ui.query.QueryEditorContent
import hu.laurel.sqlpulse.ui.query.QueryEditorUiState
import hu.laurel.sqlpulse.ui.query.QueryTab
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

/** The snapshot line under a result when the snapshot was taken somewhere else. */
class PolishShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    // 14:05 on the machine's own clock, so the picture does not depend on where it is recorded.
    private val takenAt = LocalDate.of(2026, 10, 2).atTime(14, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun snapshot(origin: SnapshotOrigin) = ResultSnapshot(
        columns = listOf("id"),
        rows = emptyList<SnapshotRow>(),
        strategy = MatchStrategy.WholeRow,
        takenAt = takenAt,
        sourceRowCount = 12,
        sourceTruncated = false,
        origin = origin,
    )

    private fun state(snapshot: ResultSnapshot) = QueryEditorUiState(
        tabs = listOf(
            QueryTab(id = 1, title = "napi bevétel", sql = QueryEditorShots.SQL, database = "billing", result = QueryEditorShots.RESULT, editorCollapsed = true),
        ),
        activeTabId = 1,
        databases = listOf("billing", "shop"),
        connectionName = "Számlázó",
        connectionId = 1,
        snapshots = mapOf(1L to snapshot),
    )

    @Test
    fun snapshotFromAnotherConnection() = paparazzi.screen {
        val origin = SnapshotOrigin(2, "Éles webshop", ConnectionEnvironment.PRODUCTION, "Production", "webshop", "SELECT 1")
        QueryEditorContent(onBack = {}, viewModel = FakeQueryController(state(snapshot(origin))))
    }

    @Test
    fun snapshotFromThisConnection() = paparazzi.screen {
        val origin = SnapshotOrigin(1, "Számlázó", ConnectionEnvironment.TEST, "Amber", "billing", "SELECT 1")
        QueryEditorContent(onBack = {}, viewModel = FakeQueryController(state(snapshot(origin))))
    }
}
