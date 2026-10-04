package hu.laurel.sqlpulse.data.alerts

import androidx.annotation.StringRes
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.MetricId

/** The words for a metric: one place for the Pulse tiles and the alerts, so both name it alike. */
object AlertText {

    @StringRes
    fun metricLabel(id: MetricId): Int = when (id) {
        MetricId.QUERIES -> R.string.pulse_queries
        MetricId.THREADS_RUNNING -> R.string.pulse_threads_running
        MetricId.THREADS_CONNECTED -> R.string.pulse_threads_connected
        MetricId.LOCK_WAITS -> R.string.pulse_lock_waits
        MetricId.BUFFER_HIT -> R.string.pulse_buffer_hit
        MetricId.SLOW_QUERIES -> R.string.pulse_slow_queries
        MetricId.TRAFFIC_OUT -> R.string.pulse_traffic_out
        MetricId.REPLICATION_LAG -> R.string.pulse_replication_lag
        MetricId.PG_CONNECTIONS, MetricId.MS_USER_CONNECTIONS -> R.string.pulse_threads_connected
        MetricId.PG_ACTIVE -> R.string.pulse_pg_active
        MetricId.PG_IDLE_IN_XACT -> R.string.pulse_pg_idle_in_xact
        MetricId.PG_COMMITS -> R.string.pulse_pg_commits
        MetricId.PG_ROLLBACKS -> R.string.pulse_pg_rollbacks
        MetricId.PG_CACHE_HIT, MetricId.MS_CACHE_HIT -> R.string.pulse_cache_hit
        MetricId.PG_TUPLES_READ -> R.string.pulse_pg_tuples_read
        MetricId.PG_TUPLES_WRITTEN -> R.string.pulse_pg_tuples_written
        MetricId.PG_DEADLOCKS -> R.string.pulse_pg_deadlocks
        MetricId.MS_BATCH_REQUESTS -> R.string.pulse_ms_batch_requests
        MetricId.MS_TRANSACTIONS -> R.string.pulse_ms_transactions
        MetricId.MS_BLOCKED -> R.string.pulse_ms_blocked
        MetricId.MS_LOCK_WAITS -> R.string.pulse_ms_lock_waits
        MetricId.MS_PAGE_LIFE -> R.string.pulse_ms_page_life
    }
}
