package hu.laurel.sqlpulse.data.schema

import java.util.Locale

enum class SlowSort(val orderColumn: String) {
    TOTAL("SUM_TIMER_WAIT"),
    AVERAGE("AVG_TIMER_WAIT"),
    COUNT("COUNT_STAR"),
}

/** One normalised statement from `events_statements_summary_by_digest`. */
data class SlowStatement(
    val digestText: String,
    val schema: String?,
    val count: Long,
    val totalPicos: Long,
    val avgPicos: Long,
    val rowsExamined: Long,
    val rowsSent: Long,
    val noIndexUsed: Long,
    val noGoodIndexUsed: Long,
    val firstSeen: String?,
    val lastSeen: String?,
)

sealed interface SlowStatementsReport {
    data class Rows(val statements: List<SlowStatement>, val sort: SlowSort) : SlowStatementsReport
    data object PerformanceSchemaOff : SlowStatementsReport
    data object NoPrivilege : SlowStatementsReport
    data object Unsupported : SlowStatementsReport
}

/** Pure helpers for the "slowest statements" panel. */
object SlowStatements {

    const val LIMIT = 25

    /**
     * Whether this server can have the digest table at all. MySQL has had it since 5.6 and
     * MariaDB documents it from 10.5; an unknown version is allowed to try, since the server's
     * own "no such table" is a better answer than a guess made from a string.
     */
    fun supported(version: ServerVersion): Boolean = when {
        !version.known -> true
        version.mariaDb -> version.mariaDbAtLeast(10, 5)
        else -> version.mysqlAtLeast(5, 6)
    }

    /**
     * The statement for one sort order.
     *
     * The NULL digest is the bucket the server throws everything into once the table is full; it
     * has no text to show and its time would sit at the top for no useful reason. The sort column
     * comes from a closed enum, so nothing typed is ever interpolated.
     */
    fun query(sort: SlowSort, limit: Int = LIMIT): String =
        """
        SELECT DIGEST_TEXT, SCHEMA_NAME, COUNT_STAR, SUM_TIMER_WAIT, AVG_TIMER_WAIT,
               SUM_ROWS_EXAMINED, SUM_ROWS_SENT, SUM_NO_INDEX_USED, SUM_NO_GOOD_INDEX_USED,
               FIRST_SEEN, LAST_SEEN
        FROM performance_schema.events_statements_summary_by_digest
        WHERE DIGEST_TEXT IS NOT NULL
        ORDER BY ${sort.orderColumn} DESC
        LIMIT ${limit.coerceIn(1, 100)}
        """.trimIndent()

    /** MySQL error numbers: table or column access denied, and the account-level variants. */
    private val DENIED = setOf(1044, 1045, 1142, 1143, 1227)
    private const val NO_SUCH_TABLE = 1146

    fun isAccessDenied(errorCode: Int) = errorCode in DENIED
    fun isMissingTable(errorCode: Int) = errorCode == NO_SUCH_TABLE

    /** Picoseconds as the unit a person reads: ns, µs, ms, s, then minutes and hours. */
    fun formatPicos(picos: Long): String {
        fun fixed(value: Double, unit: String): String {
            val digits = if (value >= 100) 0 else if (value >= 10) 1 else 2
            return String.format(Locale.ROOT, "%.${digits}f %s", value, unit)
        }
        return when {
            picos < 1_000L -> "<1 ns"
            picos < 1_000_000L -> fixed(picos / 1_000.0, "ns")
            picos < 1_000_000_000L -> fixed(picos / 1_000_000.0, "µs")
            picos < 1_000_000_000_000L -> fixed(picos / 1_000_000_000.0, "ms")
            picos < 60_000_000_000_000L -> fixed(picos / 1_000_000_000_000.0, "s")
            else -> {
                val seconds = picos / 1_000_000_000_000L
                val hours = seconds / 3600
                val minutes = seconds % 3600 / 60
                if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds % 60}s"
            }
        }
    }

    /** Counters are unsigned 64-bit; anything beyond Long is clamped rather than wrapped negative. */
    fun parseCounter(text: String?): Long =
        text?.trim()?.toBigDecimalOrNull()?.toBigInteger()
            ?.min(Long.MAX_VALUE.toBigInteger())?.max(0.toBigInteger())?.toLong() ?: 0L
}
