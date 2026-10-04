package hu.laurel.sqlpulse.data.alerts

import hu.laurel.sqlpulse.data.backup.JsonException
import hu.laurel.sqlpulse.data.backup.JsonValue
import hu.laurel.sqlpulse.data.schema.MetricId
import hu.laurel.sqlpulse.data.schema.PulseReading
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine

/** What a metric's number is counted in; only drives how a threshold is written down. */
enum class AlertUnit(val suffix: String) { COUNT(""), PER_SECOND("/s"), SECONDS("s") }

/**
 * One alert on one Pulse metric: "[metric] > [threshold] for [sustainSamples] samples in a row".
 *
 * Only ">" exists on purpose: every metric offered here is one where bigger is worse, and a
 * second comparator would double the editor for rules nobody asked for.
 */
data class AlertRule(
    val metric: MetricId,
    val threshold: Double,
    /** Consecutive samples over the threshold before it fires; one spike must not wake the phone. */
    val sustainSamples: Int = 3,
    /** Minimum gap between two notifications for this rule, also while it stays over. */
    val cooldownSeconds: Int = 600,
    /** Off until the user turns it on for this connection. */
    val enabled: Boolean = false,
)

/** Which metrics can carry an alert on which engine, and the starting rule for each. */
object AlertCatalog {

    /** Alerting needs a number that means the same thing at every sample, so only these tiles qualify. */
    private val defaults: Map<DatabaseEngine, List<Pair<AlertRule, AlertUnit>>> = mapOf(
        DatabaseEngine.MYSQL to listOf(
            AlertRule(MetricId.REPLICATION_LAG, 60.0) to AlertUnit.SECONDS,
            AlertRule(MetricId.THREADS_RUNNING, 32.0) to AlertUnit.COUNT,
            AlertRule(MetricId.THREADS_CONNECTED, 200.0) to AlertUnit.COUNT,
            AlertRule(MetricId.LOCK_WAITS, 2.0) to AlertUnit.COUNT,
            AlertRule(MetricId.SLOW_QUERIES, 1.0) to AlertUnit.PER_SECOND,
        ),
        DatabaseEngine.POSTGRESQL to listOf(
            AlertRule(MetricId.REPLICATION_LAG, 60.0) to AlertUnit.SECONDS,
            AlertRule(MetricId.PG_ACTIVE, 32.0) to AlertUnit.COUNT,
            AlertRule(MetricId.PG_CONNECTIONS, 150.0) to AlertUnit.COUNT,
            AlertRule(MetricId.PG_IDLE_IN_XACT, 2.0) to AlertUnit.COUNT,
            AlertRule(MetricId.PG_DEADLOCKS, 0.0, sustainSamples = 1) to AlertUnit.COUNT,
        ),
        DatabaseEngine.SQLSERVER to listOf(
            AlertRule(MetricId.MS_BLOCKED, 0.0) to AlertUnit.COUNT,
            AlertRule(MetricId.MS_USER_CONNECTIONS, 200.0) to AlertUnit.COUNT,
            AlertRule(MetricId.MS_LOCK_WAITS, 5.0) to AlertUnit.PER_SECOND,
        ),
    )

    fun supports(engine: DatabaseEngine): Boolean = engine in defaults

    /** The rules an engine can have, all off. */
    fun defaultRules(engine: DatabaseEngine): List<AlertRule> =
        defaults[engine].orEmpty().map { it.first }

    fun unit(engine: DatabaseEngine, metric: MetricId): AlertUnit =
        defaults[engine]?.firstOrNull { it.first.metric == metric }?.second ?: AlertUnit.COUNT

    /**
     * The engine's rules with what the user saved laid over them, in the engine's order.
     * A stored rule for a metric the engine does not offer is dropped (the connection's engine can change).
     */
    fun merge(engine: DatabaseEngine, stored: List<AlertRule>): List<AlertRule> {
        val byMetric = stored.associateBy { it.metric }
        return defaultRules(engine).map { byMetric[it.metric] ?: it }
    }

    const val MAX_SUSTAIN = 60
    const val MAX_COOLDOWN_SECONDS = 24 * 3600

    /** Keeps hand-edited or damaged values inside what the sampler can honour. */
    fun sanitize(rule: AlertRule): AlertRule = rule.copy(
        threshold = if (rule.threshold.isFinite()) rule.threshold else 0.0,
        sustainSamples = rule.sustainSamples.coerceIn(1, MAX_SUSTAIN),
        cooldownSeconds = rule.cooldownSeconds.coerceIn(60, MAX_COOLDOWN_SECONDS),
    )

    /** "60 s", "2", "1.5 /s": a threshold as the editor and the notification write it. */
    fun formatThreshold(value: Double, unit: AlertUnit): String {
        val number = if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.ROOT, "%.2f", value)
        }
        return if (unit.suffix.isEmpty()) number else "$number ${unit.suffix}"
    }
}

/** What one rule is doing right now. Lives in memory only: it ends with the connection. */
data class AlertState(
    /** Consecutive samples over the threshold, counting up to [AlertRule.sustainSamples] and beyond. */
    val streak: Int = 0,
    /** Set while the rule is firing. */
    val firingSinceMs: Long? = null,
    val lastFiredMs: Long? = null,
    val lastValue: Double? = null,
) {
    val firing: Boolean get() = firingSinceMs != null
}

/** A notification the evaluator decided to send. Carries numbers only: never text from the server. */
data class AlertEvent(val rule: AlertRule, val value: Double, val display: String, val sinceMs: Long)

/**
 * The decision of whether a rule fires, as pure arithmetic over the rule's state and the latest reading.
 *
 * - A reading that could not be measured (null) neither counts nor resets: a gap is not "all clear".
 * - A rule starts firing once [AlertRule.sustainSamples] consecutive samples were over the threshold.
 * - While it stays over, it is reminded at most once per cooldown; going back under ends the episode.
 * - The cooldown also holds across episodes, so a value hovering at the threshold cannot flap
 *   its way around the sustain count into a notification every few minutes.
 */
object AlertEvaluator {

    fun step(
        rule: AlertRule,
        state: AlertState,
        value: Double?,
        display: String,
        nowMs: Long,
    ): Pair<AlertState, AlertEvent?> {
        if (value == null) return state to null
        if (value <= rule.threshold) {
            // Back under: the episode is over, but lastFiredMs stays so the cooldown survives it.
            return state.copy(streak = 0, firingSinceMs = null, lastValue = value) to null
        }
        val streak = (state.streak + 1).coerceAtMost(Int.MAX_VALUE - 1)
        var next = state.copy(streak = streak, lastValue = value)
        if (streak < rule.sustainSamples) return next to null
        if (next.firingSinceMs == null) next = next.copy(firingSinceMs = nowMs)
        val last = next.lastFiredMs
        val cooledDown = last == null || nowMs - last >= rule.cooldownSeconds * 1_000L
        if (!cooledDown) return next to null
        next = next.copy(lastFiredMs = nowMs)
        return next to AlertEvent(rule, value, display, next.firingSinceMs ?: nowMs)
    }
}

/**
 * Evaluates a connection's rules against successive Pulse readings and keeps each rule's state.
 * No Android and no I/O, so the integration tests can drive it with real samples.
 */
class AlertTracker {

    private var states: Map<MetricId, AlertState> = emptyMap()

    fun states(): Map<MetricId, AlertState> = states

    /** Feeds one sample's readings; returns the notifications to send. Disabled rules are forgotten. */
    fun onReadings(rules: List<AlertRule>, readings: List<PulseReading>, nowMs: Long): List<AlertEvent> {
        val events = mutableListOf<AlertEvent>()
        val next = HashMap<MetricId, AlertState>()
        for (rule in rules.filter { it.enabled }) {
            val reading = readings.firstOrNull { it.id == rule.metric }
            val (state, event) = AlertEvaluator.step(
                rule, states[rule.metric] ?: AlertState(), reading?.value, reading?.display.orEmpty(), nowMs,
            )
            next[rule.metric] = state
            if (event != null) events += event
        }
        states = next
        return events
    }

    fun clear() {
        states = emptyMap()
    }
}

/** The stored form of a connection's rules: a small JSON array, in a DataStore string. */
object AlertRuleCodec {

    fun encode(rules: List<AlertRule>): String = JsonValue.Arr(
        rules.map {
            JsonValue.Obj(
                linkedMapOf(
                    "metric" to JsonValue.Str(it.metric.name),
                    "threshold" to JsonValue.Num(it.threshold.toString()),
                    "sustain" to JsonValue.Num(it.sustainSamples.toString()),
                    "cooldown" to JsonValue.Num(it.cooldownSeconds.toString()),
                    "enabled" to JsonValue.Bool(it.enabled),
                ),
            )
        },
    ).write()

    /** Reads what [encode] wrote; anything unreadable gives fewer rules, never a crash. */
    fun decode(text: String?): List<AlertRule> {
        if (text.isNullOrBlank()) return emptyList()
        val root = try {
            JsonValue.parse(text)
        } catch (_: JsonException) {
            return emptyList()
        }
        val items = (root as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { item ->
            val fields = (item as? JsonValue.Obj)?.fields ?: return@mapNotNull null
            val name = (fields["metric"] as? JsonValue.Str)?.value ?: return@mapNotNull null
            val metric = MetricId.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            val threshold = (fields["threshold"] as? JsonValue.Num)?.text?.toDoubleOrNull() ?: return@mapNotNull null
            AlertCatalog.sanitize(
                AlertRule(
                    metric = metric,
                    threshold = threshold,
                    sustainSamples = (fields["sustain"] as? JsonValue.Num)?.text?.toDoubleOrNull()?.toInt() ?: 3,
                    cooldownSeconds = (fields["cooldown"] as? JsonValue.Num)?.text?.toDoubleOrNull()?.toInt() ?: 600,
                    enabled = (fields["enabled"] as? JsonValue.Bool)?.value ?: false,
                ),
            )
        }.distinctBy { it.metric }
    }
}
