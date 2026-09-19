package hu.laurel.sqlpulse.ui.server

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.diagnostics.DiagnosticsDialog
import hu.laurel.sqlpulse.ui.grid.CellSelection
import hu.laurel.sqlpulse.ui.grid.ResultGrid
import hu.laurel.sqlpulse.ui.grid.asText
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * Server screen: what is running right now, and a short overview (§3, DBA role).
 *
 * Tapping a process id offers to end that statement. It sends `KILL QUERY`, which stops the
 * statement and leaves the connection in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onBack: () -> Unit,
    viewModel: ServerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current
    var killTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    // The diagnostics report lives here because this is where somebody stands when something is
    // wrong with the server, and because it is read once and copied rather than navigated to.
    var showDiagnostics by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    // Not tied to a live session: a report about a connection that will not open
                    // is the one somebody most wants to send.
                    IconButton(onClick = { showDiagnostics = true }) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = stringResource(R.string.diag_open),
                        )
                    }
                    IconButton(onClick = viewModel::refresh, enabled = state.connected) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!state.connected) {
                EmptyState(
                    title = stringResource(R.string.schema_no_session_title),
                    body = stringResource(R.string.schema_no_session_body),
                    actionLabel = stringResource(R.string.cancel),
                    onAction = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }

            if (state.facts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    state.facts.forEach { fact ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text("${fact.label}: ${fact.value}", style = MonoStyles.cell)
                            },
                        )
                    }
                }
            }

            // One row of tabs, because these are four separate questions and each one costs a
            // query against a server that may be busy.
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                items(ServerPanel.entries) { panel ->
                    FilterChip(
                        selected = panel == state.panel,
                        onClick = { viewModel.selectPanel(panel) },
                        label = { Text(stringResource(panel.labelRes())) },
                    )
                }
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
                Text(
                    text = stringResource(R.string.server_process_privilege),
                    color = semantic.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Spacing.l),
                )
            }

            if (state.loading) {
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            val table = state.table
            when {
                table == null -> Unit
                table.rows.isEmpty() && !state.loading -> EmptyState(
                    title = stringResource(state.panel.emptyRes()),
                    body = stringResource(R.string.server_nothing_body),
                    actionLabel = stringResource(R.string.refresh),
                    onAction = viewModel::refresh,
                    modifier = Modifier.fillMaxWidth(),
                )

                else -> ResultGrid(
                    table = table,
                    modifier = Modifier.fillMaxSize(),
                    onCellClick = { selection ->
                        when (state.panel) {
                            // Replication has no thread to end and no account to look up.
                            ServerPanel.REPLICATION -> Unit
                            ServerPanel.USERS -> viewModel.showGrants(selection.value.asText())
                            else -> killTarget = selection.toKillTarget(table)
                        }
                    },
                )
            }
        }
    }

    state.grants?.let { grants ->
        AlertDialog(
            onDismissRequest = viewModel::dismissGrants,
            title = { Text(grants.account) },
            text = {
                Text(
                    // Exactly as the server words them: a GRANT line is what gets pasted
                    // somewhere else, and rewording it would make that useless.
                    text = grants.lines.joinToString("\n\n").ifBlank {
                        stringResource(R.string.server_no_grants)
                    },
                    style = MonoStyles.cell,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissGrants) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDiagnostics) {
        DiagnosticsDialog(onDismiss = { showDiagnostics = false })
    }

    killTarget?.let { (id, info) ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text(stringResource(R.string.server_kill_title, id)) },
            text = { Text(info, style = MonoStyles.cell) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.kill(id)
                        killTarget = null
                    },
                ) { Text(stringResource(R.string.server_kill)) }
            },
            dismissButton = {
                TextButton(onClick = { killTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * The process id of the tapped row, with the Info column as context.
 *
 * Any cell in the row works: what matters is the row, and picking the id out of it is less fiddly
 * than asking the user to hit one specific cell.
 */
private fun CellSelection.toKillTarget(
    processes: hu.laurel.sqlpulse.data.sql.ResultTable,
): Pair<Long, String>? {
    val row = processes.rows.getOrNull(rowIndex) ?: return null
    // The tapped column when it is itself a thread id — on the lock-wait panel that is how the
    // blocking session, rather than the waiting one, gets ended. Otherwise the row's own Id.
    val tapped = processes.columns.indexOfFirst { it.label == column.label }
    val idIndex = tapped.takeIf {
        it >= 0 &&
            processes.columns[it].label.endsWith("id", ignoreCase = true) &&
            row.getOrNull(it)?.asText()?.toLongOrNull() != null
    } ?: processes.columns.indexOfFirst { it.label.equals("Id", ignoreCase = true) }
    if (idIndex < 0) return null
    val id = row.getOrNull(idIndex)?.asText()?.toLongOrNull() ?: return null
    val info = processes.columns.indices
        .filter { it != idIndex }
        .joinToString("\n") { index ->
            "${processes.columns[index].label}: ${row.getOrNull(index)?.asText().orEmpty()}"
        }
    return id to info
}

@StringRes
private fun ServerPanel.labelRes(): Int = when (this) {
    ServerPanel.QUERIES -> R.string.server_panel_queries
    ServerPanel.TRANSACTIONS -> R.string.server_panel_transactions
    ServerPanel.LOCKS -> R.string.server_panel_locks
    ServerPanel.REPLICATION -> R.string.server_panel_replication
    ServerPanel.USERS -> R.string.server_panel_users
}

/** What an empty panel means, which is different for each of them. */
@StringRes
private fun ServerPanel.emptyRes(): Int = when (this) {
    ServerPanel.QUERIES -> R.string.server_no_queries
    ServerPanel.TRANSACTIONS -> R.string.server_no_transactions
    ServerPanel.LOCKS -> R.string.server_no_locks
    ServerPanel.REPLICATION -> R.string.server_no_replication
    ServerPanel.USERS -> R.string.server_no_users
}
