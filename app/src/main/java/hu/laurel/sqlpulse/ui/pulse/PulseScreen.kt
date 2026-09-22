package hu.laurel.sqlpulse.ui.pulse

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.Health
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.components.Sparkline
import hu.laurel.sqlpulse.ui.components.StatusDot
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors

/**
 * The live server screen the app is named after.
 *
 * A tile per metric: the number now, the colour saying whether that is ordinary, and the last
 * minute behind it. Everything here reads; the screen cannot change anything on the server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseScreen(
    onBack: () -> Unit,
    viewModel: PulseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current

    // Sampling runs only while this screen is on top: every reading is a round trip through the
    // tunnel, and a screen nobody is looking at should not be spending the battery.
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = { Text(stringResource(R.string.pulse_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
            ) {
                PulseInterval.entries.forEachIndexed { index, candidate ->
                    SegmentedButton(
                        selected = state.interval == candidate,
                        onClick = { viewModel.setInterval(candidate) },
                        shape = SegmentedButtonDefaults.itemShape(index, PulseInterval.entries.size),
                        label = { Text(stringResource(candidate.labelRes())) },
                    )
                }
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.danger,
                    modifier = Modifier.padding(horizontal = Spacing.l),
                )
            }

            when {
                !state.connected -> Placeholder(R.string.pulse_no_session)
                // The first reading has nothing to compare against: a counter on its own says
                // how much has happened since the server started, not what is happening now.
                !state.live -> Placeholder(R.string.pulse_waiting)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = TILE_MIN_WIDTH),
                    contentPadding = PaddingValues(Spacing.l),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.metrics, key = { it.id.name }) { metric ->
                        MetricTile(metric)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(metric: Metric) {
    val semantic = LocalSemanticColors.current
    val colour = when (metric.health) {
        Health.CALM -> semantic.success
        Health.BUSY -> semantic.warning
        Health.ALARMED -> semantic.danger
    }

    HairlineCard {
        Column(
            modifier = Modifier.padding(start = Spacing.m, end = Spacing.m, top = Spacing.m, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(metric.id.labelRes()),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = semantic.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The colour of a tile is never the only sign that it wants attention.
                if (metric.health != Health.CALM) {
                    StatusDot(
                        color = colour,
                        label = stringResource(
                            if (metric.health == Health.ALARMED) R.string.pulse_health_alarmed else R.string.pulse_health_busy,
                        ),
                    )
                }
            }
            Text(
                metric.display,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
                // A calm number stays in the ordinary text colour; only what is worth noticing
                // takes a colour, or every tile would be shouting.
                color = if (metric.health == Health.CALM) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    colour
                },
            )
            Sparkline(
                series = metric.series,
                color = if (metric.health == Health.CALM) MaterialTheme.colorScheme.primary else colour,
                modifier = Modifier.fillMaxWidth().height(SPARKLINE_HEIGHT),
            )
            Text(
                stringResource(metric.id.unitRes()),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = semantic.textSecondary,
            )
        }
    }
}

@Composable
private fun Placeholder(@StringRes textId: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(textId),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalSemanticColors.current.textSecondary,
            modifier = Modifier.padding(Spacing.xl),
        )
    }
}

@StringRes
private fun PulseInterval.labelRes(): Int = when (this) {
    PulseInterval.FAST -> R.string.pulse_interval_fast
    PulseInterval.NORMAL -> R.string.pulse_interval_normal
    PulseInterval.SLOW -> R.string.pulse_interval_slow
}

@StringRes
private fun MetricId.labelRes(): Int = when (this) {
    MetricId.QUERIES -> R.string.pulse_queries
    MetricId.THREADS_RUNNING -> R.string.pulse_threads_running
    MetricId.THREADS_CONNECTED -> R.string.pulse_threads_connected
    MetricId.LOCK_WAITS -> R.string.pulse_lock_waits
    MetricId.BUFFER_HIT -> R.string.pulse_buffer_hit
    MetricId.SLOW_QUERIES -> R.string.pulse_slow_queries
    MetricId.TRAFFIC_OUT -> R.string.pulse_traffic_out
    MetricId.REPLICATION_LAG -> R.string.pulse_replication_lag
}

/** What the number is counted in — the tile is unreadable without it. */
@StringRes
private fun MetricId.unitRes(): Int = when (this) {
    MetricId.QUERIES, MetricId.SLOW_QUERIES -> R.string.pulse_unit_per_second
    MetricId.THREADS_RUNNING, MetricId.THREADS_CONNECTED, MetricId.LOCK_WAITS ->
        R.string.pulse_unit_now

    MetricId.BUFFER_HIT -> R.string.pulse_unit_window
    MetricId.TRAFFIC_OUT -> R.string.pulse_unit_out
    MetricId.REPLICATION_LAG -> R.string.pulse_unit_behind
}

private val TILE_MIN_WIDTH = 160.dp
private val SPARKLINE_HEIGHT = 44.dp
