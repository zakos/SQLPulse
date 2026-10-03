package hu.laurel.sqlpulse.ui.server

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.ServerFact
import hu.laurel.sqlpulse.data.schema.ReplicationReport
import hu.laurel.sqlpulse.data.schema.ServerRepository
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.schema.SlowStatementsReport
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlFailures
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.data.sql.dialect.KillAction
import hu.laurel.sqlpulse.data.sql.dialect.MissingPrivilegeException
import hu.laurel.sqlpulse.data.sql.dialect.OwnSessionException
import hu.laurel.sqlpulse.data.sql.dialect.ServerCapabilities
import hu.laurel.sqlpulse.ui.explain
import hu.laurel.sqlpulse.ui.handoff.EditorHandoff
import java.sql.SQLException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** What the server screen is showing. Each is a question a DBA asks separately. */
enum class ServerPanel { QUERIES, TRANSACTIONS, LOCKS, REPLICATION, SLOW, USERS }

data class ServerUiState(
    val facts: List<ServerFact> = emptyList(),
    val panel: ServerPanel = ServerPanel.QUERIES,
    val processes: ResultTable? = null,
    val transactions: ResultTable? = null,
    val lockWaits: ResultTable? = null,
    val replication: ReplicationReport? = null,
    /** Raw `SHOW REPLICA STATUS` instead of the cards, for the columns the cards leave out. */
    val replicationRaw: Boolean = false,
    val slow: SlowStatementsReport? = null,
    val slowSort: SlowSort = SlowSort.TOTAL,
    val users: ResultTable? = null,
    /** The account whose grants are on screen, with what the server said about it. */
    val grants: AccountGrants? = null,
    /** What the live engine can do here: which kill actions, whether idle sessions can be hidden. */
    val capabilities: ServerCapabilities = ServerCapabilities.MYSQL,
    /** Idle sessions are listed too (only where [ServerCapabilities.idleFilter]). */
    val showIdle: Boolean = false,
    /** The permission the panel on screen needs and the account lacks, instead of an error. */
    val missingPrivilege: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val connected: Boolean = false,
) {
    val table: ResultTable?
        get() = when (panel) {
            ServerPanel.QUERIES -> processes
            ServerPanel.TRANSACTIONS -> transactions
            ServerPanel.LOCKS -> lockWaits
            ServerPanel.REPLICATION -> (replication as? ReplicationReport.Channels)?.raw
            ServerPanel.SLOW -> null
            ServerPanel.USERS -> users
        }

    /** False until the panel on screen has been asked for once. */
    val panelLoaded: Boolean
        get() = when (panel) {
            ServerPanel.REPLICATION -> replication != null
            ServerPanel.SLOW -> slow != null
            else -> table != null
        }
}

/** One account and its GRANT lines, as MySQL words them. */
data class AccountGrants(val account: String, val lines: List<String>)

/** The running queries and a short server overview (§3, DBA role). */
@HiltViewModel
class ServerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val server: ServerRepository,
    sessions: SqlSessionManager,
    private val editorHandoff: EditorHandoff,
) : ViewModel(), ServerController {

    /** Leaves SQL for the query editor to open in a new tab. It is never executed here. */
    fun openInEditor(sql: String) = editorHandoff.offer(sql)

    private val _uiState = MutableStateFlow(ServerUiState())
    override val uiState: StateFlow<ServerUiState> = _uiState.asStateFlow()

    init {
        sessions.state
            .onEach { state ->
                val ready = state is SqlSessionState.Ready
                _uiState.value = _uiState.value.copy(
                    connected = ready,
                    capabilities = if (ready) server.capabilities else ServerCapabilities.MYSQL,
                )
                if (ready) {
                    refresh()
                } else {
                    _uiState.value = _uiState.value.copy(
                        processes = null,
                        transactions = null,
                        lockWaits = null,
                        replication = null,
                        slow = null,
                        users = null,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    override fun selectPanel(panel: ServerPanel) {
        _uiState.value = _uiState.value.copy(panel = panel)
        if (!_uiState.value.panelLoaded) refresh()
    }

    /**
     * Reloads what is on screen.
     *
     * Only the panel being looked at: four queries against a busy server, three of which nobody
     * asked for, is not a refresh anyone wants over a tunnel. The overview is fetched alongside,
     * because it is a single cheap statement and it is always visible.
     */
    override fun refresh() = refresh(keepError = false)

    private fun refresh(keepError: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loading = true,
                error = if (keepError) _uiState.value.error else null,
                missingPrivilege = null,
            )
            try {
                val state = _uiState.value
                val updated = when (state.panel) {
                    ServerPanel.QUERIES -> state.copy(processes = server.processList(state.showIdle))
                    ServerPanel.TRANSACTIONS -> state.copy(transactions = server.transactions())
                    ServerPanel.LOCKS -> state.copy(lockWaits = server.lockWaits())
                    ServerPanel.REPLICATION -> state.copy(replication = server.replication())
                    ServerPanel.SLOW -> state.copy(slow = server.slowStatements(state.slowSort))
                    ServerPanel.USERS -> state.copy(users = server.users())
                }
                // The overview is optional: a user without those variables still gets the list.
                val facts = runCatching { server.overview() }.getOrDefault(emptyList())
                _uiState.value = updated.copy(facts = facts, loading = false)
            } catch (e: MissingPrivilegeException) {
                _uiState.value = _uiState.value.copy(loading = false, missingPrivilege = e.privilege)
            } catch (e: Exception) {
                // Usually "Access denied": seeing other users' queries needs the PROCESS grant (§3).
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = describe(e),
                )
            }
        }
    }

    override fun setSlowSort(sort: SlowSort) {
        if (sort == _uiState.value.slowSort) return
        // The order is part of the query (LIMIT 25 of a different ranking is a different set),
        // so a new sort asks the server again rather than re-sorting what is on screen.
        _uiState.value = _uiState.value.copy(slowSort = sort)
        refresh()
    }

    override fun setReplicationRaw(raw: Boolean) {
        _uiState.value = _uiState.value.copy(replicationRaw = raw)
    }

    /** Asks the server what one account may do. Read-only, like everything on this screen. */
    override fun showGrants(account: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    grants = AccountGrants(account, server.grants(account)),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = describe(e))
            }
        }
    }

    override fun dismissGrants() {
        _uiState.value = _uiState.value.copy(grants = null)
    }

    override fun kill(processId: Long) = stop(processId, _uiState.value.capabilities.primaryKill)

    override fun terminate(processId: Long) = stop(processId, KillAction.TERMINATE)

    override fun setShowIdle(show: Boolean) {
        if (show == _uiState.value.showIdle) return
        _uiState.value = _uiState.value.copy(showIdle = show)
        refresh()
    }

    private fun stop(processId: Long, action: KillAction) {
        viewModelScope.launch {
            try {
                val found = server.stop(processId, action)
                // Gone already: it finished between the list and the tap. Say so, then show the new list.
                if (!found) {
                    _uiState.value = _uiState.value.copy(error = context.getString(R.string.server_kill_gone, processId))
                }
                refresh(keepError = !found)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = describe(e))
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is OwnSessionException -> context.getString(R.string.server_kill_own)
        is MissingPrivilegeException -> context.getString(R.string.server_needs_privilege, e.privilege)
        is SQLException -> context.explain(SqlFailures.of(e))
        else -> e.message ?: e.toString()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
