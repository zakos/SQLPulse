package hu.laurel.sqlpulse.data.schema

import java.math.BigInteger

/** Why a part of the storage screen has nothing to show — each source can fail on its own. */
enum class UnavailableKind {
    /** The server simply does not offer it: MariaDB has no `sys` views, for instance. */
    NOT_ON_SERVER,

    /** MariaDB keeps no index usage unless `userstat` is on. */
    USERSTAT_OFF,

    /** With the performance schema off every index looks unused, which would be a false report. */
    PERFORMANCE_SCHEMA_OFF,

    /** The statement failed; [StorageSource.Unavailable.detail] carries the server's words. */
    FAILED,
}

sealed interface StorageSource<out T> {
    data class Loaded<T>(val value: T) : StorageSource<T>
    data class Unavailable(val kind: UnavailableKind, val detail: String? = null) : StorageSource<Nothing>
}

/** An index and the size InnoDB reports for it. */
data class IndexSize(val index: String, val bytes: Long)

/** One base table (never a view) with what `information_schema.TABLES` says about it. */
data class StorageTable(
    val name: String,
    val engine: String?,
    val rowFormat: String?,
    /** An estimate: InnoDB's `TABLE_ROWS` can be off by tens of percent. */
    val rowsEstimate: Long?,
    val dataBytes: Long,
    val indexBytes: Long,
    val freeBytes: Long,
    /** The *next* AUTO_INCREMENT value. BigInteger because BIGINT UNSIGNED does not fit a Long. */
    val autoIncrement: BigInteger?,
    /** `COLUMN_TYPE` of the AUTO_INCREMENT column, e.g. `int(10) unsigned`. */
    val autoIncrementType: String?,
    val createTime: String?,
    val updateTime: String?,
    val collation: String?,
    /** Secondary indexes, biggest first; empty when the server would not say. */
    val indexes: List<IndexSize> = emptyList(),
) {
    val totalBytes: Long get() = dataBytes + indexBytes

    /** 0..1 of the column type's range used up, or null without an AUTO_INCREMENT counter. */
    val autoIncrementUsage: Double?
        get() = autoIncrement?.let { AutoIncrementHeadroom.usage(it, autoIncrementType) }

    val autoIncrementWarning: Boolean
        get() = autoIncrementUsage?.let { it >= AutoIncrementHeadroom.WARN_AT } == true
}

data class IndexRef(val table: String, val index: String)

data class UnusedIndexes(
    val indexes: List<IndexRef>,
    /** True when the numbers come from MariaDB's `userstat`, which counts from when it was enabled. */
    val fromUserstat: Boolean,
)

/** One index the server judges covered by another (`sys.schema_redundant_indexes`). */
data class RedundantIndex(
    val table: String,
    val index: String,
    val columns: String,
    val coveredBy: String,
    val coveredByColumns: String,
)

data class StorageSnapshot(
    val database: String,
    val tables: List<StorageTable>,
    /** Whether per-index sizes were readable; when not, the rows simply omit them. */
    val indexSizesAvailable: Boolean,
    val unused: StorageSource<UnusedIndexes>,
    val redundant: StorageSource<List<RedundantIndex>>,
    val uptimeSeconds: Long?,
)

data class StorageTotals(val dataBytes: Long, val indexBytes: Long, val freeBytes: Long, val tableCount: Int) {
    val totalBytes: Long get() = dataBytes + indexBytes

    companion object {
        fun of(tables: List<StorageTable>) = StorageTotals(
            dataBytes = tables.sumOf { it.dataBytes },
            indexBytes = tables.sumOf { it.indexBytes },
            freeBytes = tables.sumOf { it.freeBytes },
            tableCount = tables.size,
        )
    }
}

enum class StorageSort {
    TOTAL, ROWS, FREE, NAME;

    fun apply(tables: List<StorageTable>): List<StorageTable> = when (this) {
        TOTAL -> tables.sortedWith(compareByDescending<StorageTable> { it.totalBytes }.thenBy { it.name.lowercase() })
        ROWS -> tables.sortedWith(compareByDescending<StorageTable> { it.rowsEstimate ?: -1L }.thenBy { it.name.lowercase() })
        FREE -> tables.sortedWith(compareByDescending<StorageTable> { it.freeBytes }.thenBy { it.name.lowercase() })
        NAME -> tables.sortedBy { it.name.lowercase() }
    }
}

/**
 * How close an AUTO_INCREMENT counter is to the end of its column type.
 *
 * A counter that hits the ceiling turns every INSERT into a duplicate-key error, and fixing it
 * means an ALTER on a table that is already huge — worth a warning long before it happens.
 */
object AutoIncrementHeadroom {
    const val WARN_AT = 0.8

    private val TYPE = Regex(
        """^\s*(tinyint|smallint|mediumint|int|integer|bigint)\b(?:\(\d+\))?\s*(unsigned)?""",
        RegexOption.IGNORE_CASE,
    )

    private val SIGNED_MAX = mapOf(
        "tinyint" to BigInteger.valueOf(127),
        "smallint" to BigInteger.valueOf(32_767),
        "mediumint" to BigInteger.valueOf(8_388_607),
        "int" to BigInteger.valueOf(2_147_483_647),
        "bigint" to BigInteger.valueOf(Long.MAX_VALUE),
    )

    /** The largest value the column can hold, or null for a type this does not know. */
    fun maxOf(columnType: String?): BigInteger? {
        val match = TYPE.find(columnType.orEmpty()) ?: return null
        val name = match.groupValues[1].lowercase().let { if (it == "integer") "int" else it }
        val signed = SIGNED_MAX.getValue(name)
        // Unsigned doubles the range plus one: 127 -> 255, 2^31-1 -> 2^32-1.
        return if (match.groupValues[2].isNotEmpty()) signed.shiftLeft(1).add(BigInteger.ONE) else signed
    }

    fun usage(next: BigInteger, columnType: String?): Double? {
        val max = maxOf(columnType) ?: return null
        return next.toBigDecimal()
            .divide(max.toBigDecimal(), 6, java.math.RoundingMode.HALF_UP)
            .toDouble()
            .coerceIn(0.0, 1.0)
    }
}

object ServerFlavor {
    fun isMariaDb(version: String?): Boolean = version?.contains("mariadb", ignoreCase = true) == true
}
