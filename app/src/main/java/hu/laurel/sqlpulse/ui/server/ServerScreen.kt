package hu.laurel.sqlpulse.ui.server

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.diagnostics.DiagnosticsDialog
import hu.laurel.sqlpulse.ui.grid.CellSelection
import hu.laurel.sqlpulse.ui.grid.ResultGrid
import hu.laurel.sqlpulse.ui.grid.asText
import hu.laurel.sqlpulse.ui.query.SqlVisualTransformation
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors

/**
 * Server screen: what is running right now, and a short overview (§3, DBA role).
 *
 * Tapping a process id offers to end that statement. It sends `KILL QUERY`, which stops the
 * statement and leaves the connection in place.
 */
@Composable
fun ServerScreen(
    onBack: () -> Unit,
    viewModel: ServerViewModel = hiltViewModel(),
) {
    ServerScreenContent(onBack = onBack, viewModel = viewModel)
}

/** The screen itself, drawn from whatever [ServerController] it is handed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreenContent(
    onBack: () -> Unit,
    viewModel: ServerController,
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
                colors = sqlPulseTopBarColors(),
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

            // What this server is, as a short card of label and value: read once when the screen
            // opens, and the first thing asked when something is wrong.
            if (state.facts.isNotEmpty()) {
                HairlineCard(modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.xs, bottom = Spacing.m)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.facts.forEach { fact ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                            ) {
                                Text(
                                    fact.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = semantic.textSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    fact.value,
                                    style = MonoStyles.cell,
                                    textAlign = TextAlign.End,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
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

                // Running statements as cards: who, how long, what, and the way to stop it. A grid
                // would need a scroll to the right to reach the statement, which is the part to read.
                state.panel == ServerPanel.QUERIES -> ProcessCards(
                    table = table,
                    onKill = { id, info -> killTarget = id to info },
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

/** Seconds a statement may run before its card is drawn as the one to look at. */
private const val LONG_RUNNING_SECONDS = 30L

@Composable
private fun ProcessCards(table: ResultTable, onKill: (Long, String) -> Unit) {
    val semantic = LocalSemanticColors.current
    fun column(name: String) = table.columns.indexOfFirst { it.label.equals(name, ignoreCase = true) }
    val idColumn = column("Id")
    val userColumn = column("User")
    val hostColumn = column("Host")
    val dbColumn = column("db")
    val timeColumn = column("Time")
    val stateColumn = column("State")
    val infoColumn = column("Info")
    val highlight = SqlVisualTransformation(
        plain = MaterialTheme.colorScheme.onSurface,
        keyword = MaterialTheme.colorScheme.primary,
        string = semantic.success,
        number = semantic.cellNumber,
        comment = semantic.cellNull,
        identifier = semantic.cellDate,
        parameter = semantic.warning,
    )

    LazyColumn(
        contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(table.rows) { row ->
            fun cell(index: Int) = row.getOrNull(index)?.takeIf { it !is CellValue.Null }?.asText()
            val id = cell(idColumn)?.toLongOrNull()
            val seconds = cell(timeColumn)?.toLongOrNull() ?: 0
            val long = seconds >= LONG_RUNNING_SECONDS
            val timeColour = when {
                long -> semantic.danger
                seconds >= 1 -> semantic.warning
                else -> semantic.textSecondary
            }
            val info = cell(infoColumn)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, Shapes.card)
                    .border(1.dp, if (long) semantic.danger else semantic.hairline, Shapes.card)
                    .padding(horizontal = 14.dp, vertical = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text("#${id ?: "?"}", style = MonoStyles.cell.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        listOfNotNull(cell(userColumn), cell(hostColumn)?.substringBefore(':')).joinToString("@"),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = semantic.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.server_seconds, seconds),
                        style = MonoStyles.cell.copy(fontWeight = FontWeight.SemiBold),
                        color = timeColour,
                    )
                }
                if (info != null) {
                    Text(
                        highlight.filter(AnnotatedString(info)).text,
                        style = MonoStyles.cell.copy(fontSize = 12.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = Spacing.s),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(cell(stateColumn), cell(dbColumn)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = semantic.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (id != null && info != null) {
                        OutlinedButton(
                            onClick = {
                                onKill(
                                    id,
                                    table.columns.indices.filter { it != idColumn }.joinToString("\n") { index ->
                                        "${table.columns[index].label}: ${row.getOrNull(index)?.asText().orEmpty()}"
                                    },
                                )
                            },
                            shape = Shapes.button,
                            border = BorderStroke(1.dp, semantic.danger.copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = semantic.danger),
                            contentPadding = PaddingValues(horizontal = Spacing.m),
                            modifier = Modifier.height(40.dp),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.server_kill), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
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
