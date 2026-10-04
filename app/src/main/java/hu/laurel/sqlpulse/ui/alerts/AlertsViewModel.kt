package hu.laurel.sqlpulse.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.alerts.AlertCatalog
import hu.laurel.sqlpulse.data.alerts.AlertMonitor
import hu.laurel.sqlpulse.data.alerts.AlertNotifier
import hu.laurel.sqlpulse.data.alerts.AlertRule
import hu.laurel.sqlpulse.data.alerts.AlertRulesRepository
import hu.laurel.sqlpulse.data.alerts.AlertState
import hu.laurel.sqlpulse.data.schema.MetricId
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AlertsUiState(
    /** Null without a live session: there is no connection to hold rules for. */
    val connectionName: String? = null,
    val engine: DatabaseEngine? = null,
    val rules: List<AlertRule> = emptyList(),
    val states: Map<MetricId, AlertState> = emptyMap(),
    /** False when Android 13+ notifications are not allowed: alerts then only show in the app. */
    val notificationsAllowed: Boolean = true,
)

/** What the rules sheet reads and asks for; the interface lets a screenshot draw it from fixed state. */
interface AlertsController {
    val uiState: StateFlow<AlertsUiState>
    fun save(rule: AlertRule)
    fun refreshPermission()
}

@HiltViewModel
class AlertsViewModel @Inject constructor(
    sessions: SqlSessionManager,
    private val rules: AlertRulesRepository,
    monitor: AlertMonitor,
    private val notifier: AlertNotifier,
) : ViewModel(), AlertsController {

    private val allowed = MutableStateFlow(notifier.canNotify())
    private var connectionId: Long? = null
    private var engine: DatabaseEngine? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override val uiState: StateFlow<AlertsUiState> = combine(
        sessions.state.flatMapLatest { state ->
            val connection = (state as? SqlSessionState.Ready)?.connection
            val kind = connection?.let { DatabaseEngine.fromName(it.engine) }
            connectionId = connection?.id
            engine = kind
            if (connection == null || kind == null || !AlertCatalog.supports(kind)) {
                flowOf(AlertsUiState())
            } else {
                rules.rules(connection.id, kind).let { flow ->
                    combine(flow, monitor.status) { list, status ->
                        AlertsUiState(
                            connectionName = connection.name,
                            engine = kind,
                            rules = list,
                            states = if (status.connectionId == connection.id) status.states else emptyMap(),
                        )
                    }
                }
            }
        },
        allowed,
    ) { base, ok -> base.copy(notificationsAllowed = ok) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    override fun save(rule: AlertRule) {
        val id = connectionId ?: return
        val kind = engine ?: return
        viewModelScope.launch { rules.save(id, kind, rule) }
    }

    override fun refreshPermission() {
        allowed.value = notifier.canNotify()
    }
}
