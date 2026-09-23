package hu.laurel.sqlpulse.ui.pulse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.schema.Health
import hu.laurel.sqlpulse.data.schema.HealthRules
import hu.laurel.sqlpulse.data.schema.MetricFormat
import hu.laurel.sqlpulse.data.schema.ServerMetrics
import hu.laurel.sqlpulse.data.schema.ServerRepository
import hu.laurel.sqlpulse.data.schema.ServerSample
import hu.laurel.sqlpulse.data.schema.Series
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * How often the server is asked.
 *
 * Every sample is a round trip through the tunnel, so this is a battery setting as much as a
 * detail setting. One second is for watching something happen; fifteen is for leaving the screen
 * open beside you.
 */
enum class PulseInterval(val millis: Long) {
    FAST(1_000),
    NORMAL(5_000),
    SLOW(15_000),
}

/** The metrics the screen shows, in the order they answer "is something wrong". */
enum class MetricId {
    QUERIES,
    THREADS_RUNNING,
    THREADS_CONNECTED,
    LOCK_WAITS,
    BUFFER_HIT,
    SLOW_QUERIES,
    TRAFFIC_OUT,
    REPLICATION_LAG,
}

/** One tile: a number to read, a colour to glance at, and the line behind it. */
data class Metric(
    val id: MetricId,
    val display: String,
    val health: Health = Health.CALM,
    val series: Series = Series(),
)

data class PulseUiState(
    val metrics: List<Metric> = emptyList(),
    val interval: PulseInterval = PulseInterval.NORMAL,
    val connected: Boolean = false,
    /** True once two samples exist; before that there is no rate to show. */
    val live: Boolean = false,
    val error: String? = null,
)

/**
 * The live server screen — the app's namesake.
 *
 * Nothing here writes, and nothing needs a grant beyond what the server screen already uses. The
 * loop runs only while the screen is on top: [start] and [stop] are called from the UI, and the
 * session going away stops it too, because there is nothing to ask.
 */
@HiltViewModel
class PulseViewModel @Inject constructor(
    private val server: ServerRepository,
    sessions: SqlSessionManager,
) : ViewModel(), PulseController {

    private val _uiState = MutableStateFlow(PulseUiState())
    override val uiState: StateFlow<PulseUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var previous: ServerSample? = null
    private var series: Map<MetricId, Series> = MetricId.entries.associateWith { Series() }

    init {
        sessions.state
            .onEach { state ->
                val ready = state is SqlSessionState.Ready
                _uiState.value = _uiState.value.copy(connected = ready)
                if (!ready) {
                    stop()
                    reset()
                }
            }
            .launchIn(viewModelScope)
    }

    override fun setInterval(interval: PulseInterval) {
        _uiState.value = _uiState.value.copy(interval = interval)
    }

    /** Starts sampling. Calling it twice does not start a second loop. */
    override fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            while (isActive) {
                sampleOnce()
                delay(_uiState.value.interval.millis)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    /**
     * One reading.
     *
     * A failed sample does not stop the loop and does not clear the lines already drawn: a tunnel
     * hiccup is exactly the moment the previous few minutes are worth keeping on screen. The
     * failed reading is simply not added.
     */
    private suspend fun sampleOnce() {
        if (!_uiState.value.connected) return
        val current = try {
            server.sample()
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = e.message)
            return
        }
        val before = previous
        previous = current
        if (before == null) {
            // The first reading has nothing to compare against; the counters alone say nothing.
            _uiState.value = _uiState.value.copy(error = null)
            return
        }
        // A restart resets every counter, so the lines so far describe a server that is gone.
        if (ServerMetrics.restarted(before, current)) reset(keepConnected = true)
        record(before, current)
    }

    private fun record(before: ServerSample, current: ServerSample) {
        val queries = ServerMetrics.rate(before, current, "Queries")
            ?: ServerMetrics.rate(before, current, "Questions")
        val threadsRunning = current.value("Threads_running")
        val threadsConnected = current.value("Threads_connected")
        val lockWaits = current.value("Innodb_row_lock_current_waits")
        val hitRate = ServerMetrics.bufferPoolHitRate(before, current)
        val slow = ServerMetrics.rate(before, current, "Slow_queries")
        val out = ServerMetrics.rate(before, current, "Bytes_sent")
        val lag = current.replicationLagSeconds

        series = series.mapValues { (id, line) ->
            when (id) {
                MetricId.QUERIES -> line.plus(queries)
                MetricId.THREADS_RUNNING -> line.plus(threadsRunning?.toDouble())
                MetricId.THREADS_CONNECTED -> line.plus(threadsConnected?.toDouble())
                MetricId.LOCK_WAITS -> line.plus(lockWaits?.toDouble())
                MetricId.BUFFER_HIT -> line.plus(hitRate)
                MetricId.SLOW_QUERIES -> line.plus(slow)
                MetricId.TRAFFIC_OUT -> line.plus(out)
                MetricId.REPLICATION_LAG -> line.plus(lag?.toDouble())
            }
        }

        val metrics = buildList {
            add(metric(MetricId.QUERIES, MetricFormat.rate(queries)))
            add(
                metric(
                    MetricId.THREADS_RUNNING,
                    MetricFormat.count(threadsRunning),
                    HealthRules.threadsRunning(threadsRunning),
                ),
            )
            add(metric(MetricId.THREADS_CONNECTED, MetricFormat.count(threadsConnected)))
            add(
                metric(
                    MetricId.LOCK_WAITS,
                    MetricFormat.count(lockWaits),
                    HealthRules.lockWaits(lockWaits),
                ),
            )
            add(
                metric(
                    MetricId.BUFFER_HIT,
                    MetricFormat.percent(hitRate),
                    HealthRules.bufferPoolHitRate(hitRate),
                ),
            )
            add(
                metric(
                    MetricId.SLOW_QUERIES,
                    MetricFormat.rate(slow),
                    HealthRules.slowQueries(slow),
                ),
            )
            add(metric(MetricId.TRAFFIC_OUT, MetricFormat.perSecond(out)))
            // Only where there is replication at all: an empty tile on every standalone server
            // would be a question nobody asked.
            if (lag != null) {
                add(
                    metric(
                        MetricId.REPLICATION_LAG,
                        MetricFormat.duration(lag),
                        HealthRules.replicationLag(lag),
                    ),
                )
            }
        }

        _uiState.value = _uiState.value.copy(metrics = metrics, live = true, error = null)
    }

    private fun metric(id: MetricId, display: String, health: Health = Health.CALM) =
        Metric(id, display, health, series[id] ?: Series())

    private fun reset(keepConnected: Boolean = false) {
        previous = null
        series = MetricId.entries.associateWith { Series() }
        _uiState.value = PulseUiState(
            interval = _uiState.value.interval,
            connected = keepConnected && _uiState.value.connected,
        )
    }
}
