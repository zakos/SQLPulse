package hu.laurel.sqlpulse.data.alerts

import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.schema.MetricId
import hu.laurel.sqlpulse.data.schema.ServerMetrics
import hu.laurel.sqlpulse.data.schema.ServerRepository
import hu.laurel.sqlpulse.data.schema.ServerSample
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.MissingPrivilegeException
import hu.laurel.sqlpulse.di.ApplicationScope
import hu.laurel.sqlpulse.security.LockManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Samples the server for alert rules, slowly, and only while it can be useful.
 *
 * The loop exists only while a session is [SqlSessionState.Ready] — which is exactly as long as the
 * tunnel (and its foreground-service notification) lives — and only while at least one rule is on
 * for that connection. Locking the app tears the tunnel down, the session closes and the loop ends
 * with it; the lock check inside the loop is a second guard, not the mechanism. Nothing is scheduled
 * with WorkManager or any alarm, and nothing runs after a disconnect.
 *
 * It is separate from the Pulse screen's own refresh (which is faster and only runs while that
 * screen is open), so alerts keep working with the screen closed. Reads only: the same
 * `sample()` the screen uses, under the connection's (production-capped) query timeout.
 */
@Singleton
class AlertMonitor @Inject constructor(
    private val sessions: SqlSessionManager,
    private val server: ServerRepository,
    private val rules: AlertRulesRepository,
    private val notifier: AlertNotifier,
    private val lock: LockManager,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** What the rules sheet shows: where each rule stands for the connection being watched. */
    data class Status(
        val connectionId: Long? = null,
        val states: Map<MetricId, AlertState> = emptyMap(),
        /** True while samples are being taken for alerts. */
        val watching: Boolean = false,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val tracker = AlertTracker()
    private var started = false

    /** Called once from the application class. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            sessions.state.collectLatest { state ->
                // collectLatest cancels the previous connection's loop the moment the state changes.
                tracker.clear()
                _status.value = Status()
                val connection = (state as? SqlSessionState.Ready)?.connection ?: return@collectLatest
                val engine = DatabaseEngine.fromName(connection.engine)
                if (!AlertCatalog.supports(engine)) return@collectLatest
                watch(connection, engine)
            }
        }
    }

    private suspend fun watch(connection: ConnectionEntity, engine: DatabaseEngine) {
        // The previous sample outlives rule edits: a rate needs two samples, and an edit should not
        // cost a whole interval of blindness.
        var previous: ServerSample? = null
        rules.rules(connection.id, engine).collectLatest { list ->
            if (list.none { it.enabled }) {
                tracker.clear()
                _status.value = Status(connection.id)
                return@collectLatest
            }
            _status.value = _status.value.copy(connectionId = connection.id, watching = true)
            while (currentCoroutineContext().isActive) {
                if (!lock.locked.value) {
                    when (val result = sampleOnce()) {
                        Sampled.NoPrivilege -> return@collectLatest
                        Sampled.Failed -> Unit
                        is Sampled.Got -> {
                            val before = previous
                            previous = result.sample
                            if (before != null) evaluate(connection, engine, list, before, result.sample)
                        }
                    }
                }
                delay(INTERVAL_MS)
            }
        }
    }

    private sealed interface Sampled {
        data class Got(val sample: ServerSample) : Sampled
        data object Failed : Sampled
        data object NoPrivilege : Sampled
    }

    /** One reading under the query timeout; a failed one is skipped, a refusal ends the watching. */
    private suspend fun sampleOnce(): Sampled = try {
        Sampled.Got(withTimeout(sessions.queryTimeoutSeconds() * 1_000L) { server.sample() })
    } catch (e: MissingPrivilegeException) {
        // Asking again every half minute would only repeat the refusal.
        Sampled.NoPrivilege
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A tunnel hiccup is not an alert; the next sample tries again.
        Sampled.Failed
    }

    private fun evaluate(
        connection: ConnectionEntity,
        engine: DatabaseEngine,
        list: List<AlertRule>,
        before: ServerSample,
        current: ServerSample,
    ) {
        // A restart zeroes the counters: the readings across it would be nonsense.
        if (ServerMetrics.restarted(before, current)) {
            tracker.clear()
            return
        }
        val readings = server.pulse.readings(before, current)
        val events = tracker.onReadings(list, readings, System.currentTimeMillis())
        _status.value = _status.value.copy(connectionId = connection.id, states = tracker.states())
        for (event in events) notifier.notify(connection.id, connection.name, engine, event)
    }

    companion object {
        /** Low on purpose: every sample is a round trip through the tunnel. */
        const val INTERVAL_MS = 30_000L
    }
}
