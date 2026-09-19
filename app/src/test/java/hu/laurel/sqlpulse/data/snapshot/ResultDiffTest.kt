package hu.laurel.sqlpulse.data.snapshot

import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultDiffTest {

    private fun snapshot(
        rows: List<List<Any?>>,
        columns: List<String> = listOf("id", "nev", "allapot"),
        key: List<String> = listOf("id"),
        at: Long = 0L,
    ): ResultSnapshot = taken(ResultSnapshots.take(table(columns, rows), at, key))

    private fun diffOf(
        before: ResultSnapshot,
        after: ResultTable,
        at: Long = 10L,
    ): ResultDiff = (ResultDiffs.compare(before, after, at) as ComparisonOutcome.Compared).diff

    private val columns = listOf("id", "nev", "allapot")

    @Test
    fun `an untouched result compares as identical`() {
        val rows = listOf(listOf(1, "Anna", "uj"), listOf(2, "Bela", "kesz"))
        val diff = diffOf(snapshot(rows), table(columns, rows))
        assertTrue(diff.identical)
        assertEquals(2, diff.unchangedCount)
        assertFalse(diff.partial)
    }

    @Test
    fun `a row that appeared is reported as new`() {
        val diff = diffOf(
            snapshot(listOf(listOf(1, "Anna", "uj"))),
            table(columns, listOf(listOf(1, "Anna", "uj"), listOf(2, "Bela", "uj"))),
        )
        assertEquals(1, diff.addedCount)
        assertEquals(0, diff.removedCount)
        assertEquals(1, diff.unchangedCount)
        assertEquals(listOf(cell(2)), diff.rows.single().key)
    }

    @Test
    fun `a row that went away is reported as gone`() {
        val diff = diffOf(
            snapshot(listOf(listOf(1, "Anna", "uj"), listOf(2, "Bela", "uj"))),
            table(columns, listOf(listOf(1, "Anna", "uj"))),
        )
        assertEquals(1, diff.removedCount)
        assertEquals(RowChangeKind.REMOVED, diff.rows.single().kind)
        assertEquals(listOf(cell(2), cell("Bela"), cell("uj")), diff.rows.single().before)
        assertNull(diff.rows.single().after)
    }

    @Test
    fun `a changed row names the columns that moved, with both values`() {
        val diff = diffOf(
            snapshot(listOf(listOf(1, "Anna", "uj"))),
            table(columns, listOf(listOf(1, "Anna", "kesz"))),
        )
        val row = diff.rows.single()
        assertEquals(RowChangeKind.CHANGED, row.kind)
        val change = row.cells.single()
        assertEquals("allapot", change.column)
        assertEquals(2, change.columnIndex)
        assertEquals(CellValue.Text("uj"), change.before)
        assertEquals(CellValue.Text("kesz"), change.after)
        assertEquals(0, diff.unchangedCount)
    }

    @Test
    fun `a value becoming NULL is a change, and so is a NULL being filled in`() {
        val diff = diffOf(
            snapshot(listOf(listOf(1, "Anna", null), listOf(2, "Bela", "kesz"))),
            table(columns, listOf(listOf(1, "Anna", "kesz"), listOf(2, "Bela", null))),
        )
        assertEquals(2, diff.changedCount)
        assertEquals(CellValue.Null, diff.rows.first().cells.single().before)
        assertEquals(CellValue.Null, diff.rows.last().cells.single().after)
    }

    @Test
    fun `new, gone and changed come in that order`() {
        val diff = diffOf(
            snapshot(listOf(listOf(1, "Anna", "uj"), listOf(2, "Bela", "uj"))),
            table(columns, listOf(listOf(1, "Anna", "kesz"), listOf(3, "Cili", "uj"))),
        )
        assertEquals(
            listOf(RowChangeKind.ADDED, RowChangeKind.REMOVED, RowChangeKind.CHANGED),
            diff.rows.map { it.kind },
        )
    }

    @Test
    fun `with a key, a row keeps its identity even when every other value moved`() {
        val diff = diffOf(
            snapshot(listOf(listOf(7, "Anna", "uj"))),
            table(columns, listOf(listOf(7, "Annamaria", "kesz"))),
        )
        assertEquals(1, diff.changedCount)
        assertEquals(2, diff.rows.single().cells.size)
    }

    @Test
    fun `without a key, an edited row reads as one gone and one new`() {
        val before = taken(
            ResultSnapshots.take(
                table(listOf("nev", "allapot"), listOf(listOf("Anna", "uj")), tableName = null),
                0L,
            ),
        )
        val diff = diffOf(
            before,
            table(listOf("nev", "allapot"), listOf(listOf("Anna", "kesz")), tableName = null),
        )
        assertEquals(MatchStrategy.WholeRow, diff.strategy)
        assertEquals(1, diff.addedCount)
        assertEquals(1, diff.removedCount)
        assertEquals(0, diff.changedCount)
        assertNull(diff.rows.first().key)
    }

    @Test
    fun `identical rows are counted, not merged`() {
        val before = taken(
            ResultSnapshots.take(
                table(listOf("a"), listOf(listOf(1), listOf(1), listOf(1))),
                0L,
            ),
        )
        val diff = diffOf(before, table(listOf("a"), listOf(listOf(1), listOf(1))))
        assertEquals(1, diff.removedCount)
        assertEquals(2, diff.unchangedCount)
    }

    @Test
    fun `a duplicate key appearing later drops both sides to whole-row matching`() {
        val before = snapshot(listOf(listOf(1, "Anna", "uj")))
        val diff = diffOf(
            before,
            table(columns, listOf(listOf(1, "Anna", "uj"), listOf(1, "Anna", "kesz"))),
        )
        assertEquals(MatchStrategy.WholeRow, diff.strategy)
        assertEquals(1, diff.addedCount)
        assertEquals(0, diff.removedCount)
        assertEquals(1, diff.unchangedCount)
    }

    @Test
    fun `a query edited between the runs is refused instead of compared`() {
        val outcome = ResultDiffs.compare(
            snapshot(listOf(listOf(1, "Anna", "uj"))),
            table(listOf("id", "nev"), listOf(listOf(1, "Anna"))),
            10L,
        )
        val differ = outcome as ComparisonOutcome.ColumnsDiffer
        assertEquals(listOf("id", "nev", "allapot"), differ.before)
        assertEquals(listOf("id", "nev"), differ.after)
    }

    @Test
    fun `a later run with nothing in it is not a comparison`() {
        assertEquals(
            ComparisonOutcome.NoResult,
            ResultDiffs.compare(snapshot(listOf(listOf(1, "Anna", "uj"))), ResultTable.EMPTY, 10L),
        )
    }

    @Test
    fun `a trimmed side makes the whole comparison partial`() {
        val before = taken(
            ResultSnapshots.take(
                table(columns, (1..10).map { listOf(it, "n$it", "uj") }),
                0L,
                listOf("id"),
                maxRows = 4,
            ),
        )
        val diff = (
            ResultDiffs.compare(
                before,
                table(columns, (1..10).map { listOf(it, "n$it", "uj") }),
                10L,
                maxRows = 4,
            ) as ComparisonOutcome.Compared
            ).diff
        assertTrue(diff.partial)
        assertEquals(4, diff.beforeRowCount)
        assertEquals(4, diff.afterRowCount)
        assertTrue(diff.identical)
    }

    @Test
    fun `both times travel with the comparison`() {
        val diff = diffOf(snapshot(listOf(listOf(1, "Anna", "uj")), at = 5L), table(columns, emptyList()), at = 99L)
        assertEquals(5L, diff.takenAt)
        assertEquals(99L, diff.comparedAt)
        assertEquals(1, diff.removedCount)
    }

    @Test
    fun `a blob is only known by its size, so a same-sized blob reads as unchanged`() {
        val changes = ResultDiffs.cellChanges(
            listOf("adat"),
            listOf(CellValue.Blob(1024)),
            listOf(CellValue.Blob(1024)),
        )
        assertTrue(changes.isEmpty())
    }
}
