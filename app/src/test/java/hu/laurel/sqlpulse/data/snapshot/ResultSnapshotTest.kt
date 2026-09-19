package hu.laurel.sqlpulse.data.snapshot

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Helpers shared by both suites: a result table is tedious to spell out cell by cell. */
internal fun table(
    columns: List<String>,
    rows: List<List<Any?>>,
    tableName: String? = "hivasok",
    truncated: Boolean = false,
): ResultTable = ResultTable(
    columns = columns.map {
        ColumnMeta(label = it, type = CellType.TEXT, typeName = "VARCHAR", table = tableName)
    },
    rows = rows.map { row -> row.map { cell(it) } },
    truncated = truncated,
)

internal fun cell(value: Any?): CellValue = when (value) {
    null -> CellValue.Null
    is Int -> CellValue.Number(value.toString())
    is Long -> CellValue.Number(value.toString())
    is Boolean -> CellValue.Bool(value)
    else -> CellValue.Text(value.toString())
}

internal fun taken(outcome: SnapshotOutcome): ResultSnapshot =
    (outcome as SnapshotOutcome.Taken).snapshot

class ResultSnapshotTest {

    @Test
    fun `a primary key present in the result becomes the match strategy`() {
        val snapshot = taken(
            ResultSnapshots.take(
                table(listOf("id", "nev"), listOf(listOf(1, "Anna"), listOf(2, "Bela"))),
                takenAt = 100L,
                keyColumns = listOf("id"),
            ),
        )
        assertEquals(MatchStrategy.PrimaryKey(listOf("id")), snapshot.strategy)
        assertEquals(2, snapshot.rowCount)
        assertFalse(snapshot.truncated)
    }

    @Test
    fun `the key is matched case-insensitively, as MySQL matches column names`() {
        val columns = listOf("ID", "nev")
        assertEquals(listOf("ID"), ResultSnapshots.primaryKeyColumns(columns, listOf("id")))
    }

    @Test
    fun `half a composite key identifies nothing, so it is not used`() {
        val columns = listOf("hivas_id", "nev")
        assertEquals(
            emptyList<String>(),
            ResultSnapshots.primaryKeyColumns(columns, listOf("hivas_id", "sorszam")),
        )
    }

    @Test
    fun `a key that repeats in the result falls back to whole-row matching`() {
        val snapshot = taken(
            ResultSnapshots.take(
                table(listOf("id", "nev"), listOf(listOf(1, "Anna"), listOf(1, "Bela"))),
                takenAt = 0L,
                keyColumns = listOf("id"),
            ),
        )
        assertEquals(MatchStrategy.WholeRow, snapshot.strategy)
        assertTrue(snapshot.matchesWholeRow)
    }

    @Test
    fun `a NULL key is not an identity, so it falls back to whole-row matching`() {
        val snapshot = taken(
            ResultSnapshots.take(
                table(listOf("id", "nev"), listOf(listOf(null, "Anna"), listOf(2, "Bela"))),
                takenAt = 0L,
                keyColumns = listOf("id"),
            ),
        )
        assertEquals(MatchStrategy.WholeRow, snapshot.strategy)
    }

    @Test
    fun `no key columns at all means whole-row matching`() {
        val snapshot = taken(
            ResultSnapshots.take(table(listOf("a"), listOf(listOf(1))), takenAt = 0L),
        )
        assertEquals(MatchStrategy.WholeRow, snapshot.strategy)
    }

    @Test
    fun `a result with no columns is nothing to freeze`() {
        assertEquals(
            SnapshotOutcome.NoResult,
            ResultSnapshots.take(ResultTable.EMPTY, takenAt = 0L),
        )
    }

    @Test
    fun `a result too wide to bound is refused rather than trimmed`() {
        val columns = (1..SnapshotLimits.MAX_COLUMNS + 1).map { "c$it" }
        val outcome = ResultSnapshots.take(table(columns, listOf(columns.map { 1 })), takenAt = 0L)
        val refusal = outcome as SnapshotOutcome.TooWide
        assertEquals(SnapshotLimits.MAX_COLUMNS + 1, refusal.columnCount)
        assertEquals(SnapshotLimits.MAX_COLUMNS, refusal.maxColumns)
    }

    @Test
    fun `too many rows are trimmed to the row ceiling and the snapshot admits it`() {
        val rows = (1..50).map { listOf(it, "n$it") }
        val snapshot = taken(
            ResultSnapshots.take(
                table(listOf("id", "nev"), rows),
                takenAt = 0L,
                keyColumns = listOf("id"),
                maxRows = 10,
                maxCells = 10_000,
            ),
        )
        assertEquals(10, snapshot.rowCount)
        assertEquals(50, snapshot.sourceRowCount)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun `width counts against height, because the cell ceiling is what bounds the memory`() {
        val columns = listOf("a", "b", "c", "d")
        val rows = (1..20).map { listOf(it, it, it, it) }
        val snapshot = taken(
            ResultSnapshots.take(
                table(columns, rows),
                takenAt = 0L,
                maxRows = 100,
                maxCells = 12,
            ),
        )
        assertEquals(3, snapshot.rowCount)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun `a query that already stopped short is remembered as such`() {
        val snapshot = taken(
            ResultSnapshots.take(
                table(listOf("a"), listOf(listOf(1)), truncated = true),
                takenAt = 0L,
            ),
        )
        assertTrue(snapshot.sourceTruncated)
        assertFalse(snapshot.truncated)
    }

    @Test
    fun `the source table is the one every attributed column came from`() {
        val meta = listOf(
            ColumnMeta("id", CellType.NUMBER, "BIGINT", "hivasok"),
            ColumnMeta("db", CellType.NUMBER, "BIGINT", null),
        )
        assertEquals("hivasok", ResultSnapshots.sourceTable(meta))
    }

    @Test
    fun `a join has no single source table`() {
        val meta = listOf(
            ColumnMeta("id", CellType.NUMBER, "BIGINT", "hivasok"),
            ColumnMeta("nev", CellType.TEXT, "VARCHAR", "ugyfelek"),
        )
        assertNull(ResultSnapshots.sourceTable(meta))
    }

    @Test
    fun `text and a number that read the same are not the same value`() {
        assertTrue(
            ResultSnapshots.signature(CellValue.Text("5")) !=
                ResultSnapshots.signature(CellValue.Number("5")),
        )
        assertEquals(
            ResultSnapshots.signature(CellValue.Null),
            ResultSnapshots.signature(null),
        )
    }
}
