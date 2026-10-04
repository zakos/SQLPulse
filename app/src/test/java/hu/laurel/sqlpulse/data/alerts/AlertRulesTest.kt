package hu.laurel.sqlpulse.data.alerts

import hu.laurel.sqlpulse.data.schema.MetricId
import hu.laurel.sqlpulse.data.schema.PulseReading
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRulesTest {

    private val rule = AlertRule(MetricId.THREADS_RUNNING, threshold = 10.0, sustainSamples = 3, cooldownSeconds = 600, enabled = true)
    private val minute = 60_000L

    private fun run(rule: AlertRule, values: List<Double?>, stepMs: Long = 30_000L): List<AlertEvent?> {
        var state = AlertState()
        return values.mapIndexed { i, v ->
            val (next, event) = AlertEvaluator.step(rule, state, v, v.toString(), i * stepMs)
            state = next
            event
        }
    }

    @Test
    fun `fires only after the sustained number of samples`() {
        val events = run(rule, listOf(11.0, 12.0, 13.0, 14.0))
        assertNull(events[0])
        assertNull(events[1])
        assertNotNull(events[2])
        // Still over, but inside the cooldown: no second notification.
        assertNull(events[3])
    }

    @Test
    fun `a dip under the threshold resets the streak`() {
        val events = run(rule, listOf(11.0, 12.0, 5.0, 11.0, 12.0))
        assertTrue(events.all { it == null })
    }

    @Test
    fun `the value equal to the threshold is not over`() {
        assertTrue(run(rule, listOf(10.0, 10.0, 10.0, 10.0)).all { it == null })
    }

    @Test
    fun `a missing reading neither counts nor resets`() {
        val events = run(rule, listOf(11.0, null, 12.0, null, 13.0))
        assertNotNull(events[4])
    }

    @Test
    fun `a still-firing rule is reminded once per cooldown`() {
        val events = run(rule.copy(sustainSamples = 1, cooldownSeconds = 60), List(5) { 20.0 }, stepMs = 30_000L)
        // t = 0 fires, t = 30 s is inside the cooldown, t = 60 s fires again, 90 s quiet, 120 s fires.
        assertEquals(listOf(true, false, true, false, true), events.map { it != null })
    }

    @Test
    fun `the cooldown survives going under and over again`() {
        val values = listOf(20.0, 1.0, 20.0, 1.0, 20.0)
        val events = run(rule.copy(sustainSamples = 1, cooldownSeconds = 600), values, stepMs = 30_000L)
        assertEquals(listOf(true, false, false, false, false), events.map { it != null })
    }

    @Test
    fun `firing state tracks the episode`() {
        var state = AlertState()
        repeat(3) { state = AlertEvaluator.step(rule, state, 50.0, "50", it * minute).first }
        assertTrue(state.firing)
        assertEquals(2 * minute, state.firingSinceMs)
        state = AlertEvaluator.step(rule, state, 1.0, "1", 3 * minute).first
        assertFalse(state.firing)
        assertEquals(2 * minute, state.lastFiredMs)
    }

    @Test
    fun `tracker forgets disabled rules and reports events`() {
        val tracker = AlertTracker()
        val readings = listOf(PulseReading(MetricId.THREADS_RUNNING, 50.0, "50"))
        val rules = listOf(rule.copy(sustainSamples = 1), rule.copy(metric = MetricId.LOCK_WAITS, enabled = false))
        val events = tracker.onReadings(rules, readings, 0L)
        assertEquals(1, events.size)
        assertEquals("50", events.single().display)
        assertEquals(setOf(MetricId.THREADS_RUNNING), tracker.states().keys)
        tracker.onReadings(rules.map { it.copy(enabled = false) }, readings, 1L)
        assertTrue(tracker.states().isEmpty())
    }

    @Test
    fun `codec round trips and survives garbage`() {
        val rules = listOf(rule, AlertRule(MetricId.REPLICATION_LAG, 1.5, 5, 1800, false))
        assertEquals(rules, AlertRuleCodec.decode(AlertRuleCodec.encode(rules)))
        assertTrue(AlertRuleCodec.decode(null).isEmpty())
        assertTrue(AlertRuleCodec.decode("not json").isEmpty())
        assertTrue(AlertRuleCodec.decode("""{"a":1}""").isEmpty())
        // An unknown metric (a later version's) and an absurd value are dealt with, not fatal.
        val decoded = AlertRuleCodec.decode(
            """[{"metric":"GONE","threshold":1},{"metric":"LOCK_WAITS","threshold":3,"sustain":9999,"cooldown":1}]""",
        )
        assertEquals(1, decoded.size)
        assertEquals(AlertCatalog.MAX_SUSTAIN, decoded.single().sustainSamples)
        assertEquals(60, decoded.single().cooldownSeconds)
        assertFalse(decoded.single().enabled)
    }

    @Test
    fun `every engine's rules start off and merge over saved ones`() {
        for (engine in listOf(DatabaseEngine.MYSQL, DatabaseEngine.POSTGRESQL, DatabaseEngine.SQLSERVER)) {
            assertTrue(AlertCatalog.supports(engine))
            assertTrue(AlertCatalog.defaultRules(engine).none { it.enabled })
        }
        assertFalse(AlertCatalog.supports(DatabaseEngine.SQLITE))
        val saved = listOf(AlertRule(MetricId.LOCK_WAITS, 9.0, enabled = true), AlertRule(MetricId.PG_ACTIVE, 1.0, enabled = true))
        val merged = AlertCatalog.merge(DatabaseEngine.MYSQL, saved)
        assertEquals(9.0, merged.first { it.metric == MetricId.LOCK_WAITS }.threshold, 0.0)
        assertTrue(merged.none { it.metric == MetricId.PG_ACTIVE })
    }

    @Test
    fun `thresholds are written with their unit`() {
        assertEquals("60 s", AlertCatalog.formatThreshold(60.0, AlertUnit.SECONDS))
        assertEquals("2", AlertCatalog.formatThreshold(2.0, AlertUnit.COUNT))
        assertEquals("1.50 /s", AlertCatalog.formatThreshold(1.5, AlertUnit.PER_SECOND))
    }
}
