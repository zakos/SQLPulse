package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import hu.laurel.sqlpulse.data.alerts.AlertCatalog
import hu.laurel.sqlpulse.data.alerts.AlertState
import hu.laurel.sqlpulse.data.schema.MetricId
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.ui.alerts.AlertsSheetContent
import hu.laurel.sqlpulse.ui.alerts.AlertsUiState
import hu.laurel.sqlpulse.ui.theme.Shapes
import org.junit.Rule
import org.junit.Test

/** The alert rules sheet: one rule firing, one waiting, one open for editing. */
class AlertsShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private val now = 1_760_000_000_000L

    private fun state(allowed: Boolean) = AlertsUiState(
        connectionName = "mantis",
        engine = DatabaseEngine.MYSQL,
        rules = AlertCatalog.defaultRules(DatabaseEngine.MYSQL).map {
            when (it.metric) {
                MetricId.REPLICATION_LAG, MetricId.LOCK_WAITS, MetricId.THREADS_RUNNING -> it.copy(enabled = true)
                else -> it
            }
        },
        states = mapOf(
            MetricId.LOCK_WAITS to AlertState(streak = 4, firingSinceMs = now - 150_000, lastFiredMs = now - 150_000, lastValue = 6.0),
            MetricId.THREADS_RUNNING to AlertState(lastValue = 3.0),
        ),
        notificationsAllowed = allowed,
    )

    private fun sheet(allowed: Boolean, expanded: MetricId?) = paparazzi.screen {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes.sheet,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                AlertsSheetContent(
                    state = state(allowed),
                    onSave = {},
                    onOpenNotificationSettings = {},
                    initiallyExpanded = expanded,
                    nowMs = now,
                )
            }
        }
    }

    @Test
    fun rules() = sheet(allowed = true, expanded = MetricId.LOCK_WAITS)

    @Test
    fun rulesWithoutNotificationPermission() = sheet(allowed = false, expanded = null)
}
