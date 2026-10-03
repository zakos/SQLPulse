package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import java.math.BigInteger

/** Why a part of the storage screen has nothing to show — each source can fail on its own. */
enum class UnavailableKind {
    /** The server simply does not offer it: MariaDB has no `sys` views, for instance. */
    NOT_ON_SERVER,

    /** MariaDB keeps no index usage unless `userstat` is on. */
    USERSTAT_OFF,

    /** With the performance schema off every index looks unused, which would be a false report. */
    PERFORMANCE_SCHEMA_OFF,

    /** The engine has no such statistics at all (SQLite keeps no index usage, no engine has MySQL's `sys`). */
    NOT_FOR_ENGINE,

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
/** What the "next value" counter of a [StorageTable] is called on its engine. */
enum class CounterKind { AUTO_INCREMENT, SEQUENCE, IDENTITY }

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
    /** Where [autoIncrement] comes from: MySQL's counter, a PostgreSQL sequence, a SQL Server identity. */
    val counter: CounterKind = CounterKind.AUTO_INCREMENT,
) {
    val totalBytes: Long get() = dataBytes + indexBytes

    /** 0..1 of the column type's range used up, or null without an AUTO_INCREMENT counter. */
    val autoIncrementUsage: Double?
        get() = autoIncrement?.let { AutoIncrementHeadroom.usage(it, autoIncrementType) }

    val autoIncrementWarning: Boolean
        get() = autoIncrementUsage?.let { it >= AutoIncrementHeadroom.WARN_AT } == true
}

data class IndexRef(val table: String, val index: String)

/** The moment the "not read since" of an unused-index list counts from. */
enum class UnusedBasis { SERVER_START, USERSTAT, STATS_RESET }

data class UnusedIndexes(
    val indexes: List<IndexRef>,
    /** True when the numbers come from MariaDB's `userstat`, which counts from when it was enabled. */
    val fromUserstat: Boolean,
    /** [STATS_RESET] is PostgreSQL's `pg_stat_reset`; the age is then [StorageSnapshot.uptimeSeconds]. */
    val basis: UnusedBasis = if (fromUserstat) UnusedBasis.USERSTAT else UnusedBasis.SERVER_START,
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
    /** How long the usage counters behind [unused] have been counting (server uptime, or since a stats reset). */
    val uptimeSeconds: Long?,
    /** False where the engine could not size the tables (SQLite without the dbstat table). */
    val sizesAvailable: Boolean = true,
    /** SQLite: the whole file, and the part of it the free list holds. */
    val fileBytes: Long? = null,
    val fileFreeBytes: Long? = null,
    val engine: DatabaseEngine = DatabaseEngine.MYSQL,
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
        val signed = SIGNED_MAX[name] ?: return null
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
