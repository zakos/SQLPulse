package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseProfilesTest {

    private fun sample(atMs: Long, uptime: Long, vararg values: Pair<String, Long>, lag: Long? = null) =
        ServerSample(atMs, uptime, values.toMap(), lag)

    private fun List<PulseReading>.of(id: MetricId) = first { it.id == id }

    // ------------------------------------------------------------------ MySQL (unchanged)

    @Test
    fun `mysql tiles keep their order and the lag tile only appears on a replica`() {
        val a = sample(0, 10, "Queries" to 100, "Threads_running" to 3)
        val b = sample(5_000, 15, "Queries" to 600, "Threads_running" to 3, "Threads_connected" to 9)
        val tiles = MySqlPulse.readings(a, b)
        assertEquals(
            listOf(
                MetricId.QUERIES, MetricId.THREADS_RUNNING, MetricId.THREADS_CONNECTED, MetricId.LOCK_WAITS,
                MetricId.BUFFER_HIT, MetricId.SLOW_QUERIES, MetricId.TRAFFIC_OUT,
            ),
            tiles.map { it.id },
        )
        assertEquals(100.0, tiles.of(MetricId.QUERIES).value!!, 0.001)
        assertEquals("100", tiles.of(MetricId.QUERIES).display)
        assertEquals("—", tiles.of(MetricId.SLOW_QUERIES).display)

        val replica = MySqlPulse.readings(a, b.copy(replicationLagSeconds = 400))
        assertEquals(MetricId.REPLICATION_LAG, replica.last().id)
        assertEquals(Health.ALARMED, replica.last().health)
    }

    // ------------------------------------------------------------------ PostgreSQL

    private val pgBefore = sample(
        0, 1_000,
        "connections" to 20, "max_connections" to 100, "active" to 2, "idle_in_xact" to 0,
        "xact_commit" to 1_000, "xact_rollback" to 10, "blks_read" to 100, "blks_hit" to 900,
        "tup_returned" to 5_000, "tup_fetched" to 1_000, "tup_inserted" to 10, "tup_updated" to 20,
        "tup_deleted" to 30, "deadlocks" to 0,
    )

    @Test
    fun `postgres turns the cumulative counters into per second rates over the window`() {
        val after = sample(
            10_000, 1_010,
            "connections" to 95, "max_connections" to 100, "active" to 4, "idle_in_xact" to 6,
            "xact_commit" to 1_500, "xact_rollback" to 20, "blks_read" to 150, "blks_hit" to 1_850,
            "tup_returned" to 6_000, "tup_fetched" to 2_000, "tup_inserted" to 110, "tup_updated" to 120,
            "tup_deleted" to 130, "deadlocks" to 2,
        )
        val tiles = PostgresPulse.readings(pgBefore, after)
        assertEquals(50.0, tiles.of(MetricId.PG_COMMITS).value!!, 0.001)
        assertEquals(1.0, tiles.of(MetricId.PG_ROLLBACKS).value!!, 0.001)
        // (1850 - 900) hits against (150 - 100) reads in the window: 950 of 1000.
        assertEquals(0.95, tiles.of(MetricId.PG_CACHE_HIT).value!!, 0.001)
        assertEquals("95%", tiles.of(MetricId.PG_CACHE_HIT).display)
        // returned + fetched: (1000 + 1000) / 10 s
        assertEquals(200.0, tiles.of(MetricId.PG_TUPLES_READ).value!!, 0.001)
        assertEquals(30.0, tiles.of(MetricId.PG_TUPLES_WRITTEN).value!!, 0.001)
        assertEquals(2.0, tiles.of(MetricId.PG_DEADLOCKS).value!!, 0.001)
        assertEquals(Health.ALARMED, tiles.of(MetricId.PG_DEADLOCKS).health)
        // 95 of 100 slots, and six sessions waiting in a transaction.
        assertEquals(Health.ALARMED, tiles.of(MetricId.PG_CONNECTIONS).health)
        assertEquals(Health.ALARMED, tiles.of(MetricId.PG_IDLE_IN_XACT).health)
        assertEquals("6", tiles.of(MetricId.PG_IDLE_IN_XACT).display)
        assertTrue(tiles.none { it.id == MetricId.REPLICATION_LAG })
    }

    @Test
    fun `postgres shows no rate after a restart`() {
        val restarted = sample(10_000, 3, "xact_commit" to 5, "blks_hit" to 1, "blks_read" to 1, "deadlocks" to 0)
        val tiles = PostgresPulse.readings(pgBefore, restarted)
        assertNull(tiles.of(MetricId.PG_COMMITS).value)
        assertEquals("—", tiles.of(MetricId.PG_COMMITS).display)
        assertNull(tiles.of(MetricId.PG_CACHE_HIT).value)
        assertNull(tiles.of(MetricId.PG_DEADLOCKS).value)
    }

    @Test
    fun `an idle postgres window has no cache hit ratio rather than a division by zero`() {
        val same = pgBefore.copy(takenAtMs = 5_000, uptimeSeconds = 1_005)
        assertNull(PostgresPulse.readings(pgBefore, same).of(MetricId.PG_CACHE_HIT).value)
    }

    @Test
    fun `a standby adds the replication lag tile`() {
        val tiles = PostgresPulse.readings(
            pgBefore,
            pgBefore.copy(takenAtMs = 5_000, uptimeSeconds = 1_005, replicationLagSeconds = 45),
        )
        val lag = tiles.of(MetricId.REPLICATION_LAG)
        assertEquals("45s", lag.display)
        assertEquals(Health.BUSY, lag.health)
    }

    // ------------------------------------------------------------------ SQL Server

    @Test
    fun `sql server derives rates from the cumulative counters and reads the instantaneous ones as they are`() {
        val a = sample(
            0, 500, "batch_requests" to 10_000, "transactions" to 2_000, "lock_waits" to 5,
            "user_connections" to 12, "page_life_expectancy" to 200, "cache_hit" to 9_990, "cache_hit_base" to 10_000,
            "blocked" to 0,
        )
        val b = sample(
            5_000, 505, "batch_requests" to 10_500, "transactions" to 2_100, "lock_waits" to 5,
            "user_connections" to 14, "page_life_expectancy" to 250, "cache_hit" to 9_990, "cache_hit_base" to 10_000,
            "blocked" to 3,
        )
        val tiles = SqlServerPulse.readings(a, b)
        assertEquals(100.0, tiles.of(MetricId.MS_BATCH_REQUESTS).value!!, 0.001)
        assertEquals(20.0, tiles.of(MetricId.MS_TRANSACTIONS).value!!, 0.001)
        assertEquals(0.0, tiles.of(MetricId.MS_LOCK_WAITS).value!!, 0.001)
        assertEquals("14", tiles.of(MetricId.MS_USER_CONNECTIONS).display)
        assertEquals("99%", tiles.of(MetricId.MS_CACHE_HIT).display)
        // Under five minutes a page stays in memory: the cache is too small for the load.
        assertEquals(Health.ALARMED, tiles.of(MetricId.MS_PAGE_LIFE).health)
        assertEquals(Health.BUSY, tiles.of(MetricId.MS_BLOCKED).health)
    }

    @Test
    fun `sql server without the cache ratio counters shows a dash`() {
        val a = sample(0, 500, "batch_requests" to 1)
        val b = sample(5_000, 505, "batch_requests" to 1)
        val tiles = SqlServerPulse.readings(a, b)
        assertNull(tiles.of(MetricId.MS_CACHE_HIT).value)
        assertEquals("—", tiles.of(MetricId.MS_CACHE_HIT).display)
        assertNotNull(tiles.of(MetricId.MS_BATCH_REQUESTS))
    }

    // ------------------------------------------------------------------ health rules

    @Test
    fun `the new health rules`() {
        assertEquals(Health.CALM, HealthRules.connectionsOfMax(10, 100))
        assertEquals(Health.BUSY, HealthRules.connectionsOfMax(75, 100))
        assertEquals(Health.ALARMED, HealthRules.connectionsOfMax(90, 100))
        assertEquals(Health.CALM, HealthRules.connectionsOfMax(null, 100))
        assertEquals(Health.CALM, HealthRules.idleInTransaction(0))
        assertEquals(Health.BUSY, HealthRules.idleInTransaction(1))
        assertEquals(Health.ALARMED, HealthRules.idleInTransaction(5))
        assertEquals(Health.ALARMED, HealthRules.pageLifeExpectancy(299))
        assertEquals(Health.BUSY, HealthRules.pageLifeExpectancy(999))
        assertEquals(Health.CALM, HealthRules.pageLifeExpectancy(1_000))
    }
}
