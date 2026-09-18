package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerMetricsTest {

    private fun sample(
        atMs: Long,
        uptime: Long,
        vararg values: Pair<String, Long>,
        lag: Long? = null,
    ) = ServerSample(atMs, uptime, values.toMap(), lag)

    @Test
    fun `a counter becomes a rate per second`() {
        val first = sample(1_000, 100, "Queries" to 1_000)
        val second = sample(6_000, 105, "Queries" to 1_500)
        assertEquals(100.0, ServerMetrics.rate(first, second, "Queries")!!, 0.001)
    }

    @Test
    fun `a restarted server reports nothing rather than a negative spike`() {
        val first = sample(1_000, 10_000, "Queries" to 9_000_000)
        val second = sample(6_000, 4, "Queries" to 12)
        assertTrue(ServerMetrics.restarted(first, second))
        assertNull(ServerMetrics.rate(first, second, "Queries"))
        assertNull(ServerMetrics.bufferPoolHitRate(first, second))
    }

    @Test
    fun `a counter that went backwards without a restart is not guessed at`() {
        val first = sample(1_000, 100, "Queries" to 500)
        val second = sample(6_000, 105, "Queries" to 10)
        assertFalse(ServerMetrics.restarted(first, second))
        assertNull(ServerMetrics.rate(first, second, "Queries"))
    }

    @Test
    fun `a variable this server version lacks is absent, not zero`() {
        val first = sample(1_000, 100, "Queries" to 1)
        val second = sample(6_000, 105, "Queries" to 2)
        assertNull(ServerMetrics.rate(first, second, "Innodb_row_lock_waits"))
    }

    @Test
    fun `two samples from the same instant have no rate`() {
        val first = sample(1_000, 100, "Queries" to 1)
        val second = sample(1_000, 100, "Queries" to 50)
        assertNull(ServerMetrics.rate(first, second, "Queries"))
    }

    @Test
    fun `the hit rate covers the window, not the whole uptime`() {
        // Since startup this server looks perfect; in the last window a third of the reads went
        // to disk, which is the thing worth seeing.
        val first = sample(
            1_000,
            100_000,
            "Innodb_buffer_pool_read_requests" to 1_000_000,
            "Innodb_buffer_pool_reads" to 1_000,
        )
        val second = sample(
            6_000,
            100_005,
            "Innodb_buffer_pool_read_requests" to 1_000_300,
            "Innodb_buffer_pool_reads" to 1_100,
        )
        assertEquals(2.0 / 3.0, ServerMetrics.bufferPoolHitRate(first, second)!!, 0.001)
    }

    @Test
    fun `an idle window has no hit rate to report`() {
        val first = sample(
            1_000,
            100,
            "Innodb_buffer_pool_read_requests" to 10,
            "Innodb_buffer_pool_reads" to 1,
        )
        val second = sample(
            6_000,
            105,
            "Innodb_buffer_pool_read_requests" to 10,
            "Innodb_buffer_pool_reads" to 1,
        )
        assertNull(ServerMetrics.bufferPoolHitRate(first, second))
    }

    @Test
    fun `more reads from disk than requests cannot push the rate below zero`() {
        val first = sample(
            1_000,
            100,
            "Innodb_buffer_pool_read_requests" to 100,
            "Innodb_buffer_pool_reads" to 10,
        )
        val second = sample(
            6_000,
            105,
            "Innodb_buffer_pool_read_requests" to 110,
            "Innodb_buffer_pool_reads" to 40,
        )
        assertEquals(0.0, ServerMetrics.bufferPoolHitRate(first, second)!!, 0.001)
    }
}

class SeriesTest {

    @Test
    fun `points arrive oldest first and the length is capped`() {
        var series = Series(capacity = 3)
        listOf(1.0, 2.0, 3.0, 4.0).forEach { series = series.plus(it) }
        assertEquals(listOf(2.0, 3.0, 4.0), series.points)
        assertEquals(4.0, series.latest!!, 0.001)
    }

    @Test
    fun `a sample that could not be computed leaves the line as it was`() {
        val series = Series().plus(5.0).plus(null as Double?)
        assertEquals(listOf(5.0), series.points)
    }

    @Test
    fun `an empty series and a flat zero line still have a peak to draw against`() {
        assertTrue(Series().peak > 0.0)
        assertTrue(Series().plus(0.0).plus(0.0).peak > 0.0)
    }
}

class HealthRulesTest {

    @Test
    fun `a quiet server is calm on every rule`() {
        assertEquals(Health.CALM, HealthRules.threadsRunning(2))
        assertEquals(Health.CALM, HealthRules.lockWaits(0))
        assertEquals(Health.CALM, HealthRules.replicationLag(0))
        assertEquals(Health.CALM, HealthRules.bufferPoolHitRate(0.99))
        assertEquals(Health.CALM, HealthRules.slowQueries(0.0))
    }

    @Test
    fun `the steps go calm, busy, alarmed`() {
        assertEquals(Health.BUSY, HealthRules.threadsRunning(20))
        assertEquals(Health.ALARMED, HealthRules.threadsRunning(100))
        assertEquals(Health.BUSY, HealthRules.replicationLag(60))
        assertEquals(Health.ALARMED, HealthRules.replicationLag(3_600))
        assertEquals(Health.BUSY, HealthRules.bufferPoolHitRate(0.93))
        assertEquals(Health.ALARMED, HealthRules.bufferPoolHitRate(0.5))
        assertEquals(Health.BUSY, HealthRules.lockWaits(1))
        assertEquals(Health.ALARMED, HealthRules.lockWaits(9))
    }

    @Test
    fun `what could not be measured is never shown as alarming`() {
        // A missing number means this version did not answer, not that anything is wrong.
        assertEquals(Health.CALM, HealthRules.threadsRunning(null))
        assertEquals(Health.CALM, HealthRules.replicationLag(null))
        assertEquals(Health.CALM, HealthRules.bufferPoolHitRate(null))
        assertEquals(Health.CALM, HealthRules.slowQueries(null))
        assertEquals(Health.CALM, HealthRules.lockWaits(null))
    }
}

class MetricFormatTest {

    @Test
    fun `a rate keeps the decimals that carry information`() {
        assertEquals("0.25", MetricFormat.rate(0.25))
        assertEquals("12.3", MetricFormat.rate(12.34))
        assertEquals("1234", MetricFormat.rate(1234.56))
    }

    @Test
    fun `what could not be measured shows a dash, never a zero`() {
        assertEquals("—", MetricFormat.rate(null))
        assertEquals("—", MetricFormat.count(null))
        assertEquals("—", MetricFormat.percent(null))
        assertEquals("—", MetricFormat.duration(null))
        assertEquals("—", MetricFormat.perSecond(null))
        // A measured zero is still a zero.
        assertEquals("0.00", MetricFormat.rate(0.0))
        assertEquals("0", MetricFormat.count(0))
    }

    @Test
    fun `a share is shown as whole percent`() {
        assertEquals("99%", MetricFormat.percent(0.994))
        assertEquals("0%", MetricFormat.percent(0.0))
    }

    @Test
    fun `lag is read as a length of time`() {
        assertEquals("45s", MetricFormat.duration(45))
        assertEquals("2m 5s", MetricFormat.duration(125))
        assertEquals("2h 5m", MetricFormat.duration(7_500))
    }
}
