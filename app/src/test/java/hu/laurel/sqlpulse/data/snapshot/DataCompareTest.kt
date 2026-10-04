package hu.laurel.sqlpulse.data.snapshot

import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comparing the same query on two connections: origins, a user-chosen key, and the refusals. */
class DataCompareTest {

    private val columns = listOf("code", "name", "price")
    private val dev = SnapshotOrigin(1, "dev", ConnectionEnvironment.DEVELOPMENT, "Blue", "shop", "SELECT * FROM item")
    private val prod = SnapshotOrigin(2, "prod", ConnectionEnvironment.PRODUCTION, "Production", "shop", "SELECT *  FROM item;")

    /** No key detected: the snapshot is taken whole-row, as for a result with no primary key. */
    private fun devSnapshot(rows: List<List<Any?>>) =
        taken(ResultSnapshots.take(table(columns, rows), 0L, emptyList(), origin = dev))

    private fun compared(outcome: ComparisonOutcome) = (outcome as ComparisonOutcome.Compared).diff

    @Test
    fun `whole row matching cannot see an edit but a chosen key can`() {
        val a = devSnapshot(listOf(listOf("x1", "Pen", 10), listOf("x2", "Ink", 5)))
        val b = table(columns, listOf(listOf("x1", "Pen", 12), listOf("x2", "Ink", 5), listOf("x3", "Pad", 7)))

        val whole = compared(ResultDiffs.compare(a, b, 1L, afterOrigin = prod))
        assertEquals(0, whole.changedCount)
        assertEquals(2, whole.addedCount)
        assertEquals(1, whole.removedCount)

        val keyed = compared(ResultDiffs.compareByKey(a, b, listOf("code"), 1L, afterOrigin = prod))
        assertEquals(1, keyed.changedCount)
        assertEquals(1, keyed.addedCount)
        assertEquals(0, keyed.removedCount)
        assertEquals(1, keyed.unchangedCount)
        assertEquals(MatchStrategy.PrimaryKey(listOf("code")), keyed.strategy)
    }

    @Test
    fun `origins travel with the diff and mark it as cross-connection`() {
        val a = devSnapshot(listOf(listOf("x1", "Pen", 10)))
        val diff = compared(ResultDiffs.compare(a, table(columns, listOf(listOf("x1", "Pen", 10))), 1L, afterOrigin = prod))
        assertEquals(dev, diff.beforeOrigin)
        assertEquals(prod, diff.afterOrigin)
        assertTrue(diff.crossConnection)
        assertFalse(diff.queryDiffers) // spacing and a trailing ; are ignored
    }

    @Test
    fun `the same connection is not cross-connection`() {
        val a = devSnapshot(listOf(listOf("x1", "Pen", 10)))
        val diff = compared(ResultDiffs.compare(a, table(columns, listOf(listOf("x1", "Pen", 10))), 1L, afterOrigin = dev))
        assertFalse(diff.crossConnection)
    }

    @Test
    fun `different query text is flagged`() {
        val other = prod.copy(sql = "SELECT code FROM item")
        assertTrue(dev.queryDiffers(other))
        assertFalse(dev.queryDiffers(prod.copy(sql = null)))
    }

    @Test
    fun `a duplicate key on the later side is refused with the offending value`() {
        val a = devSnapshot(listOf(listOf("x1", "Pen", 10)))
        val b = table(columns, listOf(listOf("x1", "Pen", 10), listOf("x1", "Pen2", 11), listOf("x2", "Ink", 5)))
        val outcome = ResultDiffs.compareByKey(a, b, listOf("code"), 1L) as ComparisonOutcome.KeyUnusable
        assertEquals(KeyProblem.DUPLICATE, outcome.problem)
        assertEquals(ComparedSide.AFTER, outcome.side)
        assertEquals(2, outcome.rowCount)
        assertEquals(listOf(cell("x1")), outcome.example)
    }

    @Test
    fun `a duplicate key on the snapshot side is refused too`() {
        val a = devSnapshot(listOf(listOf("x1", "Pen", 10), listOf("x1", "Pen", 10)))
        val outcome = ResultDiffs.compareByKey(
            a, table(columns, listOf(listOf("x1", "Pen", 10))), listOf("code"), 1L,
        ) as ComparisonOutcome.KeyUnusable
        assertEquals(ComparedSide.BEFORE, outcome.side)
    }

    @Test
    fun `a NULL key is refused rather than treated as an identity`() {
        val a = devSnapshot(listOf(listOf("x1", "Pen", 10)))
        val b = table(columns, listOf(listOf(null, "Pen", 10)))
        val outcome = ResultDiffs.compareByKey(a, b, listOf("code"), 1L) as ComparisonOutcome.KeyUnusable
        assertEquals(KeyProblem.NULL_VALUE, outcome.problem)
    }

    @Test
    fun `a key column that is not in the result is refused`() {
        val a = devSnapshot(listOf(listOf("x1", "Pen", 10)))
        val outcome = ResultDiffs.compareByKey(
            a, table(columns, listOf(listOf("x1", "Pen", 10))), listOf("sku"), 1L,
        ) as ComparisonOutcome.KeyUnusable
        assertEquals(KeyProblem.MISSING_COLUMN, outcome.problem)
    }

    @Test
    fun `a composite key matches on all its columns`() {
        val a = devSnapshot(listOf(listOf("x1", "Pen", 10), listOf("x1", "Ink", 5)))
        val b = table(columns, listOf(listOf("x1", "Pen", 11), listOf("x1", "Ink", 5)))
        val diff = compared(ResultDiffs.compareByKey(a, b, listOf("code", "name"), 1L))
        assertEquals(1, diff.changedCount)
        assertEquals(1, diff.unchangedCount)
    }

    @Test
    fun `different columns still refuse and are reported`() {
        val a = devSnapshot(listOf(listOf("x1", "Pen", 10)))
        val outcome = ResultDiffs.compareByKey(a, table(listOf("code", "name"), listOf(listOf("x1", "Pen"))), listOf("code"), 1L)
        assertTrue(outcome is ComparisonOutcome.ColumnsDiffer)
    }

    @Test
    fun `rows past the cap make the comparison partial`() {
        val a = devSnapshot(listOf(listOf("x1", "Pen", 10)))
        val rows = (1..5).map { listOf("x$it", "n", it) }
        val diff = compared(ResultDiffs.compareByKey(a, table(columns, rows), listOf("code"), 1L, maxRows = 3))
        assertTrue(diff.partial)
        assertEquals(3, diff.afterRowCount)
    }

    @Test
    fun `the vault keeps only the latest snapshot and clears`() {
        val vault = SnapshotVault()
        assertEquals(null, vault.snapshot)
        val first = devSnapshot(listOf(listOf("x1", "Pen", 10)))
        val second = devSnapshot(listOf(listOf("x2", "Ink", 5)))
        vault.keep(first)
        vault.keep(second)
        assertEquals(second, vault.snapshot)
        vault.clear()
        assertEquals(null, vault.snapshot)
    }
}
