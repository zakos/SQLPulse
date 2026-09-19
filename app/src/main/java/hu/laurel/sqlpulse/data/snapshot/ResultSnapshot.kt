package hu.laurel.sqlpulse.data.snapshot

import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable

/**
 * The time machine's memory: one frozen result, and the rules for freezing it.
 *
 * Everything in this package is plain Kotlin on purpose. A snapshot is only interesting because
 * of what is compared later, and "what counts as the same row" is the kind of decision that has
 * to be provable rather than eyeballed on a phone screen — so it lives here, with no Android and
 * no coroutines, and the view model does nothing but hold the result of these functions.
 */

/**
 * How much of a result may be kept.
 *
 * A snapshot is held in memory for as long as the tab lives, and it is held *beside* the result
 * it was taken from, so the honest cost of the feature is two copies of the rows. The ceilings
 * below are therefore about the phone, not about the server: ten thousand rows of thirty columns
 * is a third of a million cells, which is the point where holding a second copy of them for an
 * indefinite amount of time stops being reasonable.
 *
 * The cell ceiling is the one that decides, because width matters as much as height: two thousand
 * rows of four columns is a much smaller thing than two thousand rows of eighty.
 */
object SnapshotLimits {
    /** Never more rows than this, however narrow the result is. */
    const val MAX_ROWS = 2_000

    /** Rows times columns. What actually bounds the memory. */
    const val MAX_CELLS = 40_000

    /**
     * Beyond this many columns a snapshot is refused rather than trimmed.
     *
     * Trimming rows off the bottom still leaves a usable comparison; a result this wide would be
     * cut down to a handful of rows by the cell ceiling, and a comparison of three rows out of
     * four hundred is not a comparison, it is a trap.
     */
    const val MAX_COLUMNS = 120
}

/**
 * One row as it stood, with the value that identifies it.
 *
 * [key] is what matching uses: the primary key values when the result carries one, otherwise the
 * whole row. It is kept as a string rather than recomputed on every comparison because the same
 * row is looked up once per row of the other side.
 */
data class SnapshotRow(
    val cells: List<CellValue>,
    val key: String,
)

/** What a row is matched by, which is also what the comparison screen has to admit to. */
sealed interface MatchStrategy {
    /** The result carried a primary key, so a row keeps its identity even when every value moved. */
    data class PrimaryKey(val columns: List<String>) : MatchStrategy

    /**
     * No usable key, so a row is only itself as long as nothing in it changed.
     *
     * A comparison matched this way can never report a change: an edited row leaves as one row
     * gone and comes back as one row new. The screen says so rather than pretending otherwise.
     */
    data object WholeRow : MatchStrategy
}

/**
 * A result as it was at one moment, bounded and self-describing.
 *
 * [sourceRowCount] is how many rows the result had, [rows] is how many were kept. When they
 * differ the comparison is over the kept part only, and every screen that shows it must say so —
 * "nothing changed" about a third of the rows is a different sentence from "nothing changed".
 */
data class ResultSnapshot(
    val columns: List<String>,
    val rows: List<SnapshotRow>,
    val strategy: MatchStrategy,
    /** Wall clock at capture, passed in rather than read here so the function stays pure. */
    val takenAt: Long,
    val sourceRowCount: Int,
    /** True when the query itself had already stopped short of the whole answer. */
    val sourceTruncated: Boolean,
) {
    /** True when rows were dropped to fit [SnapshotLimits]. */
    val truncated: Boolean get() = rows.size < sourceRowCount

    val rowCount: Int get() = rows.size

    /** True when the comparison can only ever say "new" and "gone", never "changed". */
    val matchesWholeRow: Boolean get() = strategy is MatchStrategy.WholeRow
}

/** What taking a snapshot did. Refusals are values, not exceptions: the screen has to word them. */
sealed interface SnapshotOutcome {
    data class Taken(val snapshot: ResultSnapshot) : SnapshotOutcome

    /** There was nothing on screen to freeze — an empty result set has no columns. */
    data object NoResult : SnapshotOutcome

    /** Refused: see [SnapshotLimits.MAX_COLUMNS]. */
    data class TooWide(val columnCount: Int, val maxColumns: Int) : SnapshotOutcome
}

/**
 * Taking a snapshot, and deciding what identifies a row in it.
 */
object ResultSnapshots {

    /**
     * Freezes [table], trimming it to fit if it has to.
     *
     * [keyColumns] are the column labels that identify a row — normally the primary key of the one
     * table the result came from, as found by [primaryKeyColumns]. They are only believed if every
     * one of them is present and the values they produce are actually unique in this result: a
     * left join can repeat a key, and matching on a key that repeats would pair rows at random.
     * When they cannot be believed the whole row becomes the key, which is always correct and only
     * less informative.
     */
    fun take(
        table: ResultTable,
        takenAt: Long,
        keyColumns: List<String> = emptyList(),
        maxRows: Int = SnapshotLimits.MAX_ROWS,
        maxCells: Int = SnapshotLimits.MAX_CELLS,
    ): SnapshotOutcome {
        val labels = table.columns.map { it.label }
        if (labels.isEmpty()) return SnapshotOutcome.NoResult
        if (labels.size > SnapshotLimits.MAX_COLUMNS) {
            return SnapshotOutcome.TooWide(labels.size, SnapshotLimits.MAX_COLUMNS)
        }

        // Integer division on purpose: a row that would only fit partly does not fit.
        val budget = minOf(maxRows, maxCells / labels.size).coerceAtLeast(1)
        val kept = table.rows.take(budget)

        val indexes = keyIndexes(labels, keyColumns)
        val strategy = strategyFor(labels, kept, keyColumns, indexes)
        val rows = kept.map { cells ->
            SnapshotRow(cells = cells, key = keyOf(cells, strategy, indexes))
        }
        return SnapshotOutcome.Taken(
            ResultSnapshot(
                columns = labels,
                rows = rows,
                strategy = strategy,
                takenAt = takenAt,
                sourceRowCount = table.rows.size,
                sourceTruncated = table.truncated,
            ),
        )
    }

    /**
     * The single table a result came from, or null when it came from none or several.
     *
     * A join has no one primary key to speak of, and a computed column has no table at all, so
     * both fall back to whole-row matching. Columns the driver could not attribute are ignored
     * rather than counted against the result: `SELECT id, COUNT(*) FROM t GROUP BY id` still names
     * `t` for `id`, and the count is nobody's column.
     */
    fun sourceTable(columns: List<ColumnMeta>): String? =
        columns.mapNotNull { it.table }.distinct().singleOrNull()

    /**
     * The key columns to use for a result of [columns], given the source table's [primaryKey].
     *
     * Every part of the key has to be in the result: half a composite key identifies half a row.
     * Comparison is case-insensitive because MySQL's column names are, and the labels come back
     * however they were typed in the SELECT.
     */
    fun primaryKeyColumns(columns: List<String>, primaryKey: List<String>): List<String> {
        if (primaryKey.isEmpty()) return emptyList()
        val present = primaryKey.mapNotNull { key ->
            columns.firstOrNull { it.equals(key, ignoreCase = true) }
        }
        return if (present.size == primaryKey.size) present else emptyList()
    }

    /** Where the key columns sit, or null when any of them is missing or named twice. */
    private fun keyIndexes(columns: List<String>, keyColumns: List<String>): List<Int>? {
        if (keyColumns.isEmpty()) return null
        val indexes = keyColumns.map { key ->
            val matches = columns.withIndex().filter { it.value.equals(key, ignoreCase = true) }
            // Two columns with the same label: which one the key means is anyone's guess.
            val single = matches.singleOrNull() ?: return null
            single.index
        }
        return indexes
    }

    /** Key matching only survives if the keys it produces are distinct; otherwise the whole row. */
    private fun strategyFor(
        columns: List<String>,
        rows: List<List<CellValue>>,
        keyColumns: List<String>,
        indexes: List<Int>?,
    ): MatchStrategy {
        if (indexes == null) return MatchStrategy.WholeRow
        val seen = HashSet<String>(rows.size * 2)
        for (cells in rows) {
            // A NULL in the key is not an identity: in SQL it is not even equal to itself.
            if (indexes.any { cells.getOrNull(it) is CellValue.Null }) return MatchStrategy.WholeRow
            if (!seen.add(joinCells(cells, indexes))) return MatchStrategy.WholeRow
        }
        // Report the labels as they appear in the result, not as the schema spells them.
        return MatchStrategy.PrimaryKey(indexes.map { columns[it] }.ifEmpty { keyColumns })
    }

    /** The identity of a row under [strategy]: its key values, or every value it has. */
    fun keyOf(cells: List<CellValue>, strategy: MatchStrategy, indexes: List<Int>?): String =
        when (strategy) {
            is MatchStrategy.PrimaryKey -> joinCells(cells, indexes ?: cells.indices.toList())
            MatchStrategy.WholeRow -> joinCells(cells, cells.indices.toList())
        }

    /**
     * Where the key columns of [strategy] sit in [columns], for a result being matched against a
     * snapshot that was taken with that strategy.
     */
    fun indexesFor(columns: List<String>, strategy: MatchStrategy): List<Int>? = when (strategy) {
        is MatchStrategy.PrimaryKey -> keyIndexes(columns, strategy.columns)
        MatchStrategy.WholeRow -> null
    }

    private fun joinCells(cells: List<CellValue>, indexes: List<Int>): String =
        indexes.joinToString("\u001F") { signature(cells.getOrNull(it)) }

    /**
     * A cell as a string that two cells can be compared by.
     *
     * The type tag is part of it because the text `5` and the number 5 are not the same answer,
     * and a comparison that hid the difference would be hiding exactly the kind of surprise this
     * screen exists to show. A BLOB is only ever known by its size here — the grid never loads the
     * bytes — so two different blobs of equal length read as unchanged, and the screen says that
     * where it matters.
     */
    fun signature(cell: CellValue?): String = when (cell) {
        null, CellValue.Null -> "\u0000"
        is CellValue.Text -> "t:${cell.value}"
        is CellValue.Number -> "n:${cell.value}"
        is CellValue.Date -> "d:${cell.value}"
        is CellValue.Bool -> "b:${cell.value}"
        is CellValue.Blob -> "x:${cell.sizeBytes}"
    }
}
