package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.alerts.AlertRule
import hu.laurel.sqlpulse.data.alerts.AlertTracker
import hu.laurel.sqlpulse.data.schema.MetricId
import hu.laurel.sqlpulse.data.schema.ServerRepository
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SqlSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Real samples through the real Pulse profile into the alert tracker (no notification is posted).
 * The test's own session is a connection, so "connected threads > 0" is true on any live server.
 */
class AlertIntegrationTest {

    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession
    private lateinit var server: ServerRepository

    @Before
    fun connect() {
        val found = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlSession(config)
        server = ServerRepository(IntegrationSessions.manager(session, config.database))
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) runCatching { session.close() }
    }

    @Test
    fun `a rule on connected threads fires after the sustained samples`() = runBlocking {
        val rules = listOf(
            AlertRule(MetricId.THREADS_CONNECTED, threshold = 0.0, sustainSamples = 3, cooldownSeconds = 600, enabled = true),
            // Off, so it must stay silent however high the number is.
            AlertRule(MetricId.THREADS_RUNNING, threshold = -1.0, sustainSamples = 1, enabled = false),
        )
        val tracker = AlertTracker()
        var before = server.sample()
        val fired = mutableListOf<Int>()
        var now = 0L
        for (n in 1..5) {
            Thread.sleep(1_100) // a rate needs elapsed time between the two samples
            val current = server.sample()
            now += 30_000L
            val events = tracker.onReadings(rules, server.pulse.readings(before, current), now)
            if (events.isNotEmpty()) fired += n
            before = current
        }
        // Sample 1 and 2 build the streak, 3 fires; 4 and 5 are inside the ten-minute cooldown.
        assertEquals(listOf(3), fired)
        val state = tracker.states().getValue(MetricId.THREADS_CONNECTED)
        assertTrue(state.firing)
        assertTrue((state.lastValue ?: 0.0) > 0.0)
        assertEquals(setOf(MetricId.THREADS_CONNECTED), tracker.states().keys)
    }
}
