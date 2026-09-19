package hu.laurel.sqlpulse.data.snapshot

import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable

/** What happened to one row between the snapshot and the later run. */
enum class RowChangeKind { ADDED, REMOVED, CHANGED }

/** One column of one row that moved, with both values, because only both of them say anything. */
data class CellChange(
    val columnIndex: Int,
    val column: String,
    val before: CellValue,
    val after: CellValue,
)

/**
 * One row of the comparison.
 *
 * [before] and [after] are the whole rows, so the screen can show the row as well as the change:
 * a changed `status` means little without the `name` next to it. One of the two is null for a row
 * that was added or that disappeared.
 */
data class RowDiff(
    val kind: RowChangeKind,
    /** The key values, where the rows were matched by a key; null under whole-row matching. */
    val key: List<CellValue>?,
    val before: List<CellValue>?,
    val after: List<CellValue>?,
    val cells: List<CellChange> = emptyList(),
)

/**
 * The answer: what is new, what is gone, what moved, and how much of the picture this is.
 *
 * The unchanged rows are counted but not listed. They are the bulk of any real comparison and
 * they are already on screen in the grid behind this one; carrying a third copy of them through
 * the diff would be the one place this feature could plausibly run the phone out of memory.
 */
data class ResultDiff(
    val columns: List<String>,
    val strategy: MatchStrategy,
    val rows: List<RowDiff>,
    val unchangedCount: Int,
    val beforeRowCount: Int,
    val afterRowCount: Int,
    /** True when either side was trimmed, so this is a comparison of the first N rows only. */
    val partial: Boolean,
    val takenAt: Long,
    val comparedAt: Long,
) {
    val addedCount: Int get() = rows.count { it.kind == RowChangeKind.ADDED }
    val removedCount: Int get() = rows.count { it.kind == RowChangeKind.REMOVED }
    val changedCount: Int get() = rows.count { it.kind == RowChangeKind.CHANGED }

    /** True when the two runs are indistinguishable as far as this comparison can see. */
    val identical: Boolean get() = rows.isEmpty()
}

/** What comparing did. Like [SnapshotOutcome], a refusal is a value the screen has to word. */
sealed interface ComparisonOutcome {
    data class Compared(val diff: ResultDiff) : ComparisonOutcome

    /**
     * The later run does not have the same columns, so there is nothing to line up.
     *
     * This is almost always the query having been edited between the two runs, which is worth
     * saying plainly instead of showing every row as gone and every row as new.
     */
    data class ColumnsDiffer(val before: List<String>, val after: List<String>) : ComparisonOutcome

    /** The later run produced nothing to compare — an update count, a `USE`, or an error. */
    data object NoResult : ComparisonOutcome

    /** The later run could not be bounded; see [SnapshotOutcome.TooWide]. */
    data class Refused(val outcome: SnapshotOutcome) : ComparisonOutcome
}

/**
 * Comparing a snapshot with a later result.
 *
 * Matching is by primary key where the snapshot found one, and by the whole row where it did not.
 * The difference is not a detail: with a key, an edited row is one changed row and the screen can
 * name the columns that moved; without one, the same edit reads as one row gone and one row new,
 * because nothing in the data says the two are the same row. The comparison never guesses which
 * is which, and the screen states which of the two happened.
 */
object ResultDiffs {

    /**
     * Compares [before] with the result of a later run.
     *
     * The later result is bounded exactly like the snapshot was, so a query that grew from two
     * thousand rows to ten thousand between the runs costs the same memory as it did the first
     * time. The rows past the bound are not silently ignored — they make the comparison partial,
     * and that flag travels with the diff.
     */
    fun compare(
        before: ResultSnapshot,
        after: ResultTable,
        comparedAt: Long,
        maxRows: Int = SnapshotLimits.MAX_ROWS,
        maxCells: Int = SnapshotLimits.MAX_CELLS,
    ): ComparisonOutcome {
        val keyColumns = (before.strategy as? MatchStrategy.PrimaryKey)?.columns ?: emptyList()
        return when (
            val taken = ResultSnapshots.take(after, comparedAt, keyColumns, maxRows, maxCells)
        ) {
            is SnapshotOutcome.Taken -> compare(before, taken.snapshot)
            SnapshotOutcome.NoResult -> ComparisonOutcome.NoResult
            is SnapshotOutcome.TooWide -> ComparisonOutcome.Refused(taken)
        }
    }

    /** The same comparison between two snapshots, which is what the rules are actually about. */
    fun compare(before: ResultSnapshot, after: ResultSnapshot): ComparisonOutcome {
        if (!sameColumns(before.columns, after.columns)) {
            return ComparisonOutcome.ColumnsDiffer(before.columns, after.columns)
        }

        // If either side could not keep its key — a duplicate turned up in the later run, say —
        // then neither side may use one. Matching half by key and half by row would pair rows
        // that have nothing to do with each other.
        val strategy = if (before.strategy is MatchStrategy.PrimaryKey &&
            after.strategy is MatchStrategy.PrimaryKey
        ) {
            before.strategy
        } else {
            MatchStrategy.WholeRow
        }

        val left = rekey(before, strategy)
        val right = rekey(after, strategy)
        val rows = when (strategy) {
            is MatchStrategy.PrimaryKey -> byKey(left, right, after.columns, strategy)
            MatchStrategy.WholeRow -> byWholeRow(left, right)
        }
        val unchanged = when (strategy) {
            is MatchStrategy.PrimaryKey ->
                right.size - rows.count { it.kind != RowChangeKind.REMOVED }

            MatchStrategy.WholeRow -> right.size - rows.count { it.kind == RowChangeKind.ADDED }
        }

        return ComparisonOutcome.Compared(
            ResultDiff(
                columns = after.columns,
                strategy = strategy,
                rows = rows,
                unchangedCount = unchanged.coerceAtLeast(0),
                beforeRowCount = before.rowCount,
                afterRowCount = after.rowCount,
                partial = before.truncated || after.truncated ||
                    before.sourceTruncated || after.sourceTruncated,
                takenAt = before.takenAt,
                comparedAt = after.takenAt,
            ),
        )
    }

    /**
     * Matching by key: the same key on both sides is the same row, whatever it now says.
     *
     * The order is the one the roadmap words the feature in — what arrived, what left, what
     * moved — and within each group the rows keep the order of the run they come from, so a
     * sorted result stays sorted in the comparison.
     */
    private fun byKey(
        before: List<SnapshotRow>,
        after: List<SnapshotRow>,
        columns: List<String>,
        strategy: MatchStrategy.PrimaryKey,
    ): List<RowDiff> {
        val beforeByKey = before.associateBy { it.key }
        val afterByKey = after.associateBy { it.key }
        val indexes = ResultSnapshots.indexesFor(columns, strategy)

        val added = after.filter { it.key !in beforeByKey }.map { row ->
            RowDiff(
                kind = RowChangeKind.ADDED,
                key = keyValues(row, indexes),
                before = null,
                after = row.cells,
            )
        }
        val removed = before.filter { it.key !in afterByKey }.map { row ->
            RowDiff(
                kind = RowChangeKind.REMOVED,
                key = keyValues(row, indexes),
                before = row.cells,
                after = null,
            )
        }
        val changed = after.mapNotNull { row ->
            val old = beforeByKey[row.key] ?: return@mapNotNull null
            val cells = cellChanges(columns, old.cells, row.cells)
            if (cells.isEmpty()) {
                null
            } else {
                RowDiff(
                    kind = RowChangeKind.CHANGED,
                    key = keyValues(row, indexes),
                    before = old.cells,
                    after = row.cells,
                    cells = cells,
                )
            }
        }
        return added + removed + changed
    }

    /**
     * Matching whole rows, counted rather than paired.
     *
     * Two identical rows are two rows, not one: a result with three rows reading `(x, 1)` and a
     * later one with two of them has lost one, and saying "unchanged" because the value still
     * occurs somewhere would be wrong. So each distinct row is counted on both sides and only the
     * difference in the counts is reported.
     */
    private fun byWholeRow(before: List<SnapshotRow>, after: List<SnapshotRow>): List<RowDiff> {
        val beforeCounts = countByKey(before)
        val afterCounts = countByKey(after)

        val added = mutableListOf<RowDiff>()
        val seenAfter = HashSet<String>()
        for (row in after) {
            if (!seenAfter.add(row.key)) continue
            val surplus = (afterCounts[row.key] ?: 0) - (beforeCounts[row.key] ?: 0)
            repeat(surplus.coerceAtLeast(0)) {
                added += RowDiff(RowChangeKind.ADDED, key = null, before = null, after = row.cells)
            }
        }

        val removed = mutableListOf<RowDiff>()
        val seenBefore = HashSet<String>()
        for (row in before) {
            if (!seenBefore.add(row.key)) continue
            val missing = (beforeCounts[row.key] ?: 0) - (afterCounts[row.key] ?: 0)
            repeat(missing.coerceAtLeast(0)) {
                removed += RowDiff(RowChangeKind.REMOVED, key = null, before = row.cells, after = null)
            }
        }
        return added + removed
    }

    /** Which columns of a matched row moved, with the old value beside the new one. */
    fun cellChanges(
        columns: List<String>,
        before: List<CellValue>,
        after: List<CellValue>,
    ): List<CellChange> = columns.indices.mapNotNull { index ->
        val old = before.getOrNull(index) ?: CellValue.Null
        val new = after.getOrNull(index) ?: CellValue.Null
        if (ResultSnapshots.signature(old) == ResultSnapshots.signature(new)) {
            null
        } else {
            CellChange(index, columns[index], old, new)
        }
    }

    /** Column names decide comparability. MySQL's names are case-insensitive, so this is too. */
    private fun sameColumns(before: List<String>, after: List<String>): Boolean =
        before.size == after.size && before.indices.all { before[it].equals(after[it], ignoreCase = true) }

    /** The rows of [snapshot] keyed by [strategy], which may not be the one it was taken with. */
    private fun rekey(snapshot: ResultSnapshot, strategy: MatchStrategy): List<SnapshotRow> {
        if (strategy == snapshot.strategy) return snapshot.rows
        val indexes = ResultSnapshots.indexesFor(snapshot.columns, strategy)
        return snapshot.rows.map { it.copy(key = ResultSnapshots.keyOf(it.cells, strategy, indexes)) }
    }

    private fun keyValues(row: SnapshotRow, indexes: List<Int>?): List<CellValue>? =
        indexes?.map { row.cells.getOrNull(it) ?: CellValue.Null }

    private fun countByKey(rows: List<SnapshotRow>): Map<String, Int> {
        val counts = HashMap<String, Int>(rows.size * 2)
        for (row in rows) counts[row.key] = (counts[row.key] ?: 0) + 1
        return counts
    }
}
