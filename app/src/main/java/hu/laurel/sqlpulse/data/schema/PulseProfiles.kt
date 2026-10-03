package hu.laurel.sqlpulse.data.schema

/** The metrics the Pulse screen can show, in the order they answer "is something wrong". */
enum class MetricId {
    // MySQL / MariaDB
    QUERIES,
    THREADS_RUNNING,
    THREADS_CONNECTED,
    LOCK_WAITS,
    BUFFER_HIT,
    SLOW_QUERIES,
    TRAFFIC_OUT,
    REPLICATION_LAG,

    // PostgreSQL
    PG_CONNECTIONS,
    PG_ACTIVE,
    PG_IDLE_IN_XACT,
    PG_COMMITS,
    PG_ROLLBACKS,
    PG_CACHE_HIT,
    PG_TUPLES_READ,
    PG_TUPLES_WRITTEN,
    PG_DEADLOCKS,

    // SQL Server
    MS_BATCH_REQUESTS,
    MS_TRANSACTIONS,
    MS_USER_CONNECTIONS,
    MS_BLOCKED,
    MS_LOCK_WAITS,
    MS_PAGE_LIFE,
    MS_CACHE_HIT,
}

/** One tile's worth of arithmetic: the number for the sparkline, its text, and how it reads. */
data class PulseReading(
    val id: MetricId,
    /** The point added to the sparkline; null leaves a gap rather than drawing a zero. */
    val value: Double?,
    val display: String,
    val health: Health = Health.CALM,
)

/**
 * What one engine's Pulse shows. Pure functions of two [ServerSample]s, so every number on the
 * screen is unit-testable without a server; [ServerCatalog.sample] only fetches the counters.
 *
 * A reading is omitted (not shown as a dash) when the engine has no such thing right now — a
 * replication-lag tile on a server that is not a replica would be a question nobody asked.
 */
interface PulseProfile {
    fun readings(previous: ServerSample, current: ServerSample): List<PulseReading>
}

/** The ratio of [good] to [total] over the window between two samples, 0..1, or null when idle. */
private fun windowRatio(good: Long?, total: Long?): Double? {
    if (good == null || total == null || total <= 0L) return null
    return good.coerceIn(0L, total).toDouble() / total.toDouble()
}

/** MySQL and MariaDB: status variables, as the screen has always read them. */
object MySqlPulse : PulseProfile {
    override fun readings(previous: ServerSample, current: ServerSample): List<PulseReading> {
        val queries = ServerMetrics.rate(previous, current, "Queries")
            ?: ServerMetrics.rate(previous, current, "Questions")
        val threadsRunning = current.value("Threads_running")
        val threadsConnected = current.value("Threads_connected")
        val lockWaits = current.value("Innodb_row_lock_current_waits")
        val hitRate = ServerMetrics.bufferPoolHitRate(previous, current)
        val slow = ServerMetrics.rate(previous, current, "Slow_queries")
        val out = ServerMetrics.rate(previous, current, "Bytes_sent")
        val lag = current.replicationLagSeconds
        return buildList {
            add(PulseReading(MetricId.QUERIES, queries, MetricFormat.rate(queries)))
            add(
                PulseReading(
                    MetricId.THREADS_RUNNING, threadsRunning?.toDouble(),
                    MetricFormat.count(threadsRunning), HealthRules.threadsRunning(threadsRunning),
                ),
            )
            add(
                PulseReading(
                    MetricId.THREADS_CONNECTED, threadsConnected?.toDouble(),
                    MetricFormat.count(threadsConnected),
                ),
            )
            add(
                PulseReading(
                    MetricId.LOCK_WAITS, lockWaits?.toDouble(),
                    MetricFormat.count(lockWaits), HealthRules.lockWaits(lockWaits),
                ),
            )
            add(
                PulseReading(
                    MetricId.BUFFER_HIT, hitRate, MetricFormat.percent(hitRate),
                    HealthRules.bufferPoolHitRate(hitRate),
                ),
            )
            add(PulseReading(MetricId.SLOW_QUERIES, slow, MetricFormat.rate(slow), HealthRules.slowQueries(slow)))
            add(PulseReading(MetricId.TRAFFIC_OUT, out, MetricFormat.perSecond(out)))
            // Only where there is replication at all.
            if (lag != null) {
                add(
                    PulseReading(
                        MetricId.REPLICATION_LAG, lag.toDouble(), MetricFormat.duration(lag),
                        HealthRules.replicationLag(lag),
                    ),
                )
            }
        }
    }
}

/**
 * PostgreSQL: `pg_stat_database` summed over all databases, plus a few counts of sessions.
 *
 * The cumulative counters (commits, rollbacks, blocks, tuples, deadlocks) are turned into rates
 * or window ratios exactly like MySQL's status variables; `connections`, `active`,
 * `idle_in_xact` and `max_connections` are instantaneous counts taken with the sample.
 */
object PostgresPulse : PulseProfile {

    /** The columns of `pg_stat_database` that are summed into the sample, under their own names. */
    val COUNTERS = listOf(
        "xact_commit", "xact_rollback", "blks_read", "blks_hit",
        "tup_returned", "tup_fetched", "tup_inserted", "tup_updated", "tup_deleted", "deadlocks",
    )

    override fun readings(previous: ServerSample, current: ServerSample): List<PulseReading> {
        val connections = current.value("connections")
        val max = current.value("max_connections")
        val active = current.value("active")
        val idleInXact = current.value("idle_in_xact")
        val commits = ServerMetrics.rate(previous, current, "xact_commit")
        val rollbacks = ServerMetrics.rate(previous, current, "xact_rollback")
        val hit = ServerMetrics.delta(previous, current, "blks_hit")
        val read = ServerMetrics.delta(previous, current, "blks_read")
        val hitRate = if (ServerMetrics.restarted(previous, current) || hit == null || read == null) {
            null
        } else {
            windowRatio(hit, hit + read)
        }
        val tuplesRead = sumRates(previous, current, "tup_returned", "tup_fetched")
        val tuplesWritten = sumRates(previous, current, "tup_inserted", "tup_updated", "tup_deleted")
        val deadlocks = if (ServerMetrics.restarted(previous, current)) {
            null
        } else {
            ServerMetrics.delta(previous, current, "deadlocks")
        }
        return buildList {
            add(
                PulseReading(
                    MetricId.PG_CONNECTIONS, connections?.toDouble(), MetricFormat.count(connections),
                    HealthRules.connectionsOfMax(connections, max),
                ),
            )
            add(
                PulseReading(
                    MetricId.PG_ACTIVE, active?.toDouble(), MetricFormat.count(active),
                    HealthRules.threadsRunning(active),
                ),
            )
            add(
                PulseReading(
                    MetricId.PG_IDLE_IN_XACT, idleInXact?.toDouble(), MetricFormat.count(idleInXact),
                    HealthRules.idleInTransaction(idleInXact),
                ),
            )
            add(PulseReading(MetricId.PG_COMMITS, commits, MetricFormat.rate(commits)))
            add(PulseReading(MetricId.PG_ROLLBACKS, rollbacks, MetricFormat.rate(rollbacks)))
            add(
                PulseReading(
                    MetricId.PG_CACHE_HIT, hitRate, MetricFormat.percent(hitRate),
                    HealthRules.bufferPoolHitRate(hitRate),
                ),
            )
            add(PulseReading(MetricId.PG_TUPLES_READ, tuplesRead, MetricFormat.rate(tuplesRead)))
            add(PulseReading(MetricId.PG_TUPLES_WRITTEN, tuplesWritten, MetricFormat.rate(tuplesWritten)))
            add(
                PulseReading(
                    MetricId.PG_DEADLOCKS, deadlocks?.toDouble(), MetricFormat.count(deadlocks),
                    if (deadlocks != null && deadlocks > 0) Health.ALARMED else Health.CALM,
                ),
            )
            current.replicationLagSeconds?.let { lag ->
                add(
                    PulseReading(
                        MetricId.REPLICATION_LAG, lag.toDouble(), MetricFormat.duration(lag),
                        HealthRules.replicationLag(lag),
                    ),
                )
            }
        }
    }

    /** The sum of several counters' rates; null as soon as one of them cannot be measured. */
    private fun sumRates(previous: ServerSample, current: ServerSample, vararg keys: String): Double? {
        var total = 0.0
        for (key in keys) total += ServerMetrics.rate(previous, current, key) ?: return null
        return total
    }
}

/**
 * SQL Server: `sys.dm_os_performance_counters`.
 *
 * The "/sec" counters are cumulative in that view, so they are deltas over the window like
 * MySQL's; "User Connections" and "Page life expectancy" are instantaneous, and the buffer cache
 * hit ratio is a fraction counter (value over its `base` row), already instantaneous.
 */
object SqlServerPulse : PulseProfile {

    override fun readings(previous: ServerSample, current: ServerSample): List<PulseReading> {
        val batches = ServerMetrics.rate(previous, current, "batch_requests")
        val transactions = ServerMetrics.rate(previous, current, "transactions")
        val lockWaits = ServerMetrics.rate(previous, current, "lock_waits")
        val connections = current.value("user_connections")
        val blocked = current.value("blocked")
        val pageLife = current.value("page_life_expectancy")
        val hitRate = windowRatio(current.value("cache_hit"), current.value("cache_hit_base"))
        return buildList {
            add(PulseReading(MetricId.MS_BATCH_REQUESTS, batches, MetricFormat.rate(batches)))
            add(PulseReading(MetricId.MS_TRANSACTIONS, transactions, MetricFormat.rate(transactions)))
            add(
                PulseReading(
                    MetricId.MS_USER_CONNECTIONS, connections?.toDouble(), MetricFormat.count(connections),
                ),
            )
            add(
                PulseReading(
                    MetricId.MS_BLOCKED, blocked?.toDouble(), MetricFormat.count(blocked),
                    HealthRules.lockWaits(blocked),
                ),
            )
            add(
                PulseReading(
                    MetricId.MS_LOCK_WAITS, lockWaits, MetricFormat.rate(lockWaits),
                    HealthRules.slowQueries(lockWaits),
                ),
            )
            add(
                PulseReading(
                    MetricId.MS_PAGE_LIFE, pageLife?.toDouble(), MetricFormat.count(pageLife),
                    HealthRules.pageLifeExpectancy(pageLife),
                ),
            )
            add(
                PulseReading(
                    MetricId.MS_CACHE_HIT, hitRate, MetricFormat.percent(hitRate),
                    HealthRules.bufferPoolHitRate(hitRate),
                ),
            )
        }
    }
}
