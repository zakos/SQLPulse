package hu.laurel.sqlpulse.data.schema

/**
 * One reading of the server's status variables.
 *
 * MySQL reports most of these as counters that only ever go up since the server started, so a
 * single reading says almost nothing: 12 million queries is meaningless without knowing over how
 * long. Two readings and the time between them are what make a rate.
 */
data class ServerSample(
    /** The phone's clock when the answer came back. */
    val takenAtMs: Long,
    /** The server's own uptime, which is how a restart is noticed. */
    val uptimeSeconds: Long,
    /** `SHOW GLOBAL STATUS`, as numbers; anything that was not a number is left out. */
    val values: Map<String, Long>,
    /** Seconds this replica is behind, or null when this server is not a replica. */
    val replicationLagSeconds: Long? = null,
) {
    fun value(key: String): Long? = values[key]
}

/**
 * The live server screen (the app's namesake).
 *
 * Everything here is arithmetic on two samples. Nothing is smoothed or guessed: where an answer
 * cannot be computed honestly — the first sample, a restarted server, a variable this version does
 * not have — it is null, and the screen shows a dash rather than a made-up zero.
 */
object ServerMetrics {

    /**
     * True when the server restarted between the two samples.
     *
     * Its counters all went back to zero, so every rate computed across that gap would be a large
     * negative number or a nonsense spike. Uptime going backwards is the reliable signal.
     */
    fun restarted(previous: ServerSample, current: ServerSample): Boolean =
        current.uptimeSeconds < previous.uptimeSeconds

    /** Seconds between two samples, by the phone's clock. */
    fun elapsedSeconds(previous: ServerSample, current: ServerSample): Double =
        (current.takenAtMs - previous.takenAtMs) / 1_000.0

    /**
     * How fast a counter is climbing, per second.
     *
     * Null when the variable is missing from either sample, when the server restarted, when no
     * measurable time has passed, or when the counter went backwards on a server that did not
     * restart — the last is a counter that was reset by hand, and inventing a number for it would
     * be worse than saying nothing.
     */
    fun rate(previous: ServerSample, current: ServerSample, key: String): Double? {
        if (restarted(previous, current)) return null
        val before = previous.value(key) ?: return null
        val after = current.value(key) ?: return null
        if (after < before) return null
        val seconds = elapsedSeconds(previous, current)
        if (seconds <= 0.0) return null
        return (after - before) / seconds
    }

    /**
     * The share of page reads served from memory over the window, 0..1.
     *
     * Deliberately over the window rather than since startup: the lifetime figure of a server that
     * has been up for a month is 0.99 whatever it is doing right now, which is exactly when the
     * number is being looked at.
     */
    fun bufferPoolHitRate(previous: ServerSample, current: ServerSample): Double? {
        if (restarted(previous, current)) return null
        val requests = delta(previous, current, "Innodb_buffer_pool_read_requests") ?: return null
        val disk = delta(previous, current, "Innodb_buffer_pool_reads") ?: return null
        // Nothing was read at all: idle, not a miss. A rate would be a division by zero.
        if (requests <= 0L) return null
        return ((requests - disk).coerceAtLeast(0L)).toDouble() / requests.toDouble()
    }

    private fun delta(previous: ServerSample, current: ServerSample, key: String): Long? {
        val before = previous.value(key) ?: return null
        val after = current.value(key) ?: return null
        return (after - before).takeIf { it >= 0L }
    }

    /** Status variables worth asking for. Asking for all of them is several hundred rows. */
    val WATCHED = listOf(
        "Uptime",
        "Queries",
        "Questions",
        "Slow_queries",
        "Threads_running",
        "Threads_connected",
        "Aborted_connects",
        "Innodb_row_lock_current_waits",
        "Innodb_row_lock_waits",
        "Innodb_buffer_pool_read_requests",
        "Innodb_buffer_pool_reads",
        "Bytes_sent",
        "Bytes_received",
        "Created_tmp_disk_tables",
    )
}

/**
 * The last few readings of one metric, oldest first.
 *
 * Fixed length: the screen draws a line of a fixed width, and an unbounded list on a screen left
 * open all afternoon is a leak.
 */
data class Series(val points: List<Double> = emptyList(), val capacity: Int = 60) {

    fun plus(value: Double): Series =
        copy(points = (points + value).takeLast(capacity))

    /** A gap — a sample that could not be computed — ends the line rather than drawing a zero. */
    fun plus(value: Double?): Series = if (value == null) this else plus(value)

    val latest: Double? get() = points.lastOrNull()

    /** The top of the drawn line. Never zero, so a flat line at zero still has somewhere to sit. */
    val peak: Double get() = points.maxOrNull()?.coerceAtLeast(MIN_PEAK) ?: MIN_PEAK

    private companion object {
        const val MIN_PEAK = 1e-9
    }
}

/** How a metric reads at a glance. The colours on the screen come from this, not from the number. */
enum class Health { CALM, BUSY, ALARMED }

/**
 * Where a number stops being ordinary.
 *
 * These are rules of thumb, not measurements of this server: a replica ten minutes behind is a
 * problem anywhere, while a hundred running threads may be a Tuesday on one server and an incident
 * on another. They colour the screen; they never stop anything.
 */
object HealthRules {

    fun threadsRunning(count: Long?): Health = when {
        count == null -> Health.CALM
        count >= 64 -> Health.ALARMED
        count >= 16 -> Health.BUSY
        else -> Health.CALM
    }

    fun lockWaits(current: Long?): Health = when {
        current == null || current == 0L -> Health.CALM
        current >= 5 -> Health.ALARMED
        else -> Health.BUSY
    }

    fun replicationLag(seconds: Long?): Health = when {
        seconds == null -> Health.CALM
        seconds >= 300 -> Health.ALARMED
        seconds >= 30 -> Health.BUSY
        else -> Health.CALM
    }

    /** Below 95% of reads coming from memory, the server is going to disk for its working set. */
    fun bufferPoolHitRate(rate: Double?): Health = when {
        rate == null -> Health.CALM
        rate < 0.90 -> Health.ALARMED
        rate < 0.95 -> Health.BUSY
        else -> Health.CALM
    }

    fun slowQueries(perSecond: Double?): Health = when {
        perSecond == null || perSecond <= 0.0 -> Health.CALM
        perSecond >= 1.0 -> Health.ALARMED
        else -> Health.BUSY
    }
}

/**
 * Numbers as a phone screen should show them.
 *
 * A rate of 12345.6789 queries a second is not readable at a glance, and a dash is the honest
 * answer for a number that could not be measured — never a zero, which would read as "quiet".
 */
object MetricFormat {

    const val ABSENT = "—"

    /** A per-second rate: enough decimals to see movement, few enough to read. */
    fun rate(value: Double?): String = when {
        value == null -> ABSENT
        value >= 100 -> value.toLong().toString()
        value >= 10 -> String.format("%.1f", value)
        else -> String.format("%.2f", value)
    }

    fun count(value: Long?): String = value?.toString() ?: ABSENT

    fun percent(value: Double?): String =
        if (value == null) ABSENT else "${(value * 100).toLong()}%"

    /** Bytes per second, in the same units as a table's size. */
    fun perSecond(bytesPerSecond: Double?): String =
        if (bytesPerSecond == null) ABSENT else formatByteSize(bytesPerSecond.toLong()) + "/s"

    /**
     * A replica's lag, as a length of time rather than a count of seconds: "2h 5m" is read
     * faster than 7500.
     */
    fun duration(seconds: Long?): String = when {
        seconds == null -> ABSENT
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
    }
}
