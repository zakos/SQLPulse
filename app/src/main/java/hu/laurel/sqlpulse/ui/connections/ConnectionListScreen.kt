package hu.laurel.sqlpulse.ui.connections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.connection.ProductionPolicy
import hu.laurel.sqlpulse.data.connection.WriteAccess
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.ssh.ConnectStep
import hu.laurel.sqlpulse.ssh.HostKeyPrompt
import hu.laurel.sqlpulse.ssh.TunnelState
import hu.laurel.sqlpulse.ui.components.ColorRail
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.components.StatusDot
import hu.laurel.sqlpulse.ui.components.StepIndicator
import hu.laurel.sqlpulse.ui.theme.ConnectionColor
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/**
 * The launcher screen (§7.1). A card per connection; one tap starts the unlock and the tunnel, and
 * the card shows which of the four steps is running, because four different things can break.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionListScreen(
    onCreate: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenKeyStore: () -> Unit,
    onOpenSchema: () -> Unit,
    onOpenQuery: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ConnectionListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val confirming by viewModel.confirming.collectAsStateWithLifecycle()

    // A write window is the one thing on this screen that changes without anybody touching it, so
    // the clock only ticks while one is open — and stops again the moment the last one closes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.unlockedUntil.isNotEmpty()) {
        while (state.unlockedUntil.isNotEmpty()) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connections_title)) },
                actions = {
                    if (state.tunnel is TunnelState.Active) {
                        IconButton(onClick = onOpenQuery) {
                            Icon(Icons.Default.Code, contentDescription = stringResource(R.string.query_title))
                        }
                    }
                    IconButton(onClick = onOpenKeyStore) {
                        Icon(Icons.Default.Key, contentDescription = stringResource(R.string.keys_title))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.connections.isNotEmpty()) {
                FloatingActionButton(onClick = onCreate) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.connection_new))
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.connections.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.connections_empty_title),
                    body = stringResource(R.string.connections_empty_body),
                    actionLabel = stringResource(R.string.connections_empty_action),
                    onAction = onCreate,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    state.groups.forEach { (environment, connections) ->
                        if (state.showsGroupHeadings) {
                            item(key = "group-${environment.name}") {
                                Text(
                                    stringResource(environment.label()),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LocalSemanticColors.current.textSecondary,
                                    modifier = Modifier.padding(top = Spacing.s),
                                )
                            }
                        }
                        items(connections, key = { it.id }) { connection ->
                            ConnectionCard(
                                connection = connection,
                                tunnel = state.tunnel.takeIf { it.connectionId == connection.id },
                                writeAccess = state.writeAccess(connection, now),
                                onUnlockWrites = { viewModel.unlockWrites(connection) },
                                onLockWrites = { viewModel.lockWrites(connection) },
                                onClick = {
                                    // An already open connection goes straight to the schema;
                                    // disconnecting lives in the long-press menu and the notification.
                                    if (state.tunnel.connectionId == connection.id &&
                                        state.tunnel is TunnelState.Active
                                    ) {
                                        onOpenSchema()
                                    } else {
                                        viewModel.connect(connection)
                                    }
                                },
                                connected = state.tunnel.connectionId == connection.id &&
                                    state.tunnel is TunnelState.Active,
                                onDisconnect = viewModel::disconnect,
                                onEdit = { onEdit(connection.id) },
                                onDuplicate = { viewModel.duplicate(connection) },
                                onDelete = { viewModel.delete(connection) },
                            )
                        }
                    }
                }
            }
        }

        confirming?.let { connection ->
            ProductionConfirmDialog(
                connection = connection,
                onConfirm = viewModel::confirmConnect,
                onCancel = viewModel::cancelConnect,
            )
        }

        state.hostKeyPrompt?.let { prompt ->
            HostKeyDialog(
                prompt = prompt,
                onAccept = viewModel::acceptHostKey,
                onReject = viewModel::rejectHostKey,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConnectionCard(
    connection: ConnectionEntity,
    tunnel: TunnelState?,
    writeAccess: WriteAccess,
    onUnlockWrites: () -> Unit,
    onLockWrites: () -> Unit,
    onClick: () -> Unit,
    connected: Boolean,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val semantic = LocalSemanticColors.current
    val color = ConnectionColor.fromName(connection.color)
    val environment = ConnectionEnvironment.fromName(connection.environment)

    HairlineCard {
        Row(
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorRail(color.value)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Text(connection.name, style = MaterialTheme.typography.titleMedium)
                    // Only production is worth a badge: it is the one that has to be noticed
                    // before a statement is typed, and the others would only be noise.
                    if (environment.isProduction) {
                        Surface(
                            color = semantic.production.copy(alpha = 0.16f),
                            shape = Shapes.chip,
                        ) {
                            Text(
                                stringResource(environment.shortLabel()),
                                style = MaterialTheme.typography.labelSmall,
                                color = semantic.production,
                                modifier = Modifier.padding(
                                    horizontal = Spacing.s,
                                    vertical = 2.dp,
                                ),
                            )
                        }
                    }
                }
                Text(
                    // Without a tunnel there is no SSH host to name.
                    if (connection.useSshTunnel) {
                        "${connection.sshUser}@${connection.sshHost} → " +
                            "${connection.dbHost}:${connection.dbPort}/${connection.database}"
                    } else {
                        "${connection.dbHost}:${connection.dbPort}/${connection.database}"
                    },
                    style = MonoStyles.cell,
                    color = semantic.textSecondary,
                )

                // Only production says anything here: everywhere else "writes allowed" is the
                // normal state of affairs and would be noise on every card.
                if (environment.isProduction) {
                    WriteAccessLine(writeAccess)
                }

                val label = tunnel.label()
                StatusDot(
                    color = when (tunnel) {
                        is TunnelState.Active -> semantic.success
                        is TunnelState.Connecting, is TunnelState.Unlocking -> semantic.warning
                        is TunnelState.Failed -> semantic.danger
                        else -> semantic.textSecondary
                    },
                    label = stringResource(label),
                    pulsing = tunnel is TunnelState.Connecting || tunnel is TunnelState.Unlocking,
                )

                if (tunnel is TunnelState.Connecting || tunnel is TunnelState.Unlocking ||
                    tunnel is TunnelState.Failed
                ) {
                    StepProgress(tunnel, tunnelled = connection.useSshTunnel)
                }

                if (tunnel is TunnelState.Failed) {
                    Text(
                        tunnel.failure.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.danger,
                    )
                }

                Text(
                    text = connection.lastUsedAt?.let {
                        stringResource(R.string.last_used, DateFormat.getDateTimeInstance().format(Date(it)))
                    } ?: stringResource(R.string.never_used),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }

            Box {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (connected) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.connection_disconnect)) },
                            onClick = { menuOpen = false; onDisconnect() },
                        )
                    }
                    // The write unlock lives with the connection, not with the query screen: the
                    // decision belongs to "this database", not to the statement being typed.
                    if (environment.isProduction && !connection.readOnly) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (writeAccess is WriteAccess.Unlocked) {
                                            R.string.write_unlock_extend
                                        } else {
                                            R.string.write_unlock_action
                                        },
                                    ),
                                )
                            },
                            onClick = { menuOpen = false; onUnlockWrites() },
                        )
                        if (writeAccess is WriteAccess.Unlocked) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.write_unlock_lock_now)) },
                                onClick = { menuOpen = false; onLockWrites() },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.connection_edit)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.connection_duplicate)) },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.connection_delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/**
 * What production is allowed to do at this moment, counted down to the minute.
 *
 * Rounded up rather than down, so a window with seconds left still reads "1 min": a countdown that
 * shows zero while writes still go through is worse than no countdown.
 */
@Composable
private fun WriteAccessLine(access: WriteAccess) {
    // Nothing to say about a connection that is not production; the caller only asks about those,
    // and this keeps the when below honest about the four states that do have something to say.
    if (access is WriteAccess.Open) return
    val semantic = LocalSemanticColors.current
    val (text, color) = when (access) {
        is WriteAccess.Unlocked -> stringResource(
            R.string.write_unlock_remaining,
            ProductionPolicy.minutesLeft(access.remainingMillis),
        ) to semantic.warning

        WriteAccess.ReadOnly -> stringResource(R.string.write_unlock_read_only) to
            semantic.textSecondary

        WriteAccess.Locked -> stringResource(R.string.write_unlock_locked) to
            semantic.textSecondary

        WriteAccess.Expired -> stringResource(R.string.write_unlock_expired) to
            semantic.textSecondary

        WriteAccess.Open -> return
    }

    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun StepProgress(tunnel: TunnelState, tunnelled: Boolean) {
    // A direct connection has no key to unlock and no SSH handshake, so those dots would be a lie.
    val order = if (tunnelled) {
        listOf(ConnectStep.KEY, ConnectStep.SSH, ConnectStep.MYSQL, ConnectStep.SCHEMA)
    } else {
        listOf(ConnectStep.MYSQL, ConnectStep.SCHEMA)
    }
    val current = when (tunnel) {
        is TunnelState.Unlocking -> ConnectStep.KEY
        is TunnelState.Connecting -> tunnel.step
        else -> null
    }
    val failedStep = (tunnel as? TunnelState.Failed)?.failure?.layer?.toStep()
    val completed = when {
        current != null -> order.takeWhile { it != current }.toSet()
        failedStep != null -> order.takeWhile { it != failedStep }.toSet()
        else -> emptySet()
    }
    StepIndicator(
        steps = order,
        completed = completed,
        current = current,
        failed = failedStep,
        labels = { step ->
            when (step) {
                ConnectStep.KEY -> stringResource(R.string.step_key)
                ConnectStep.SSH -> stringResource(R.string.step_ssh)
                ConnectStep.MYSQL -> stringResource(R.string.step_mysql)
                ConnectStep.SCHEMA -> stringResource(R.string.step_schema)
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
    )
}

/**
 * Asked once before a production connection that is allowed to write is opened.
 *
 * Not a lock — the user can say yes — but the moment where "which database am I on" gets answered
 * before the first statement rather than after it.
 */
@Composable
private fun ProductionConfirmDialog(
    connection: ConnectionEntity,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.production_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.production_confirm_body,
                    connection.name,
                    "${connection.dbHost}:${connection.dbPort}/${connection.database}",
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.production_confirm_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun HostKeyDialog(prompt: HostKeyPrompt, onAccept: () -> Unit, onReject: () -> Unit) {
    val changed = prompt.storedFingerprint != null
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(
                stringResource(
                    if (changed) R.string.host_key_changed_title else R.string.host_key_new_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (changed) {
                    Text(
                        stringResource(
                            R.string.host_key_changed_body,
                            prompt.storedFingerprint.orEmpty(),
                            prompt.offeredFingerprint,
                        ),
                    )
                } else {
                    Text(stringResource(R.string.host_key_new_body, "${prompt.host}:${prompt.port}"))
                    Text("${prompt.keyType} ${prompt.offeredFingerprint}", style = MonoStyles.fingerprint)
                }
            }
        },
        confirmButton = {
            // A changed key is blocked outright: the only way past it is the connection editor (§5).
            if (!changed) {
                TextButton(onClick = onAccept) { Text(stringResource(R.string.host_key_accept)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text(stringResource(R.string.host_key_reject)) }
        },
    )
}

private fun TunnelState?.label(): Int = when (this) {
    is TunnelState.Active -> R.string.state_active
    is TunnelState.Connecting -> R.string.state_connecting
    is TunnelState.Unlocking -> R.string.state_unlocking
    is TunnelState.Paused -> R.string.state_paused
    is TunnelState.Failed -> R.string.state_error
    else -> R.string.state_disconnected
}

private fun hu.laurel.sqlpulse.ssh.FailureLayer.toStep(): ConnectStep = when (this) {
    hu.laurel.sqlpulse.ssh.FailureLayer.KEY -> ConnectStep.KEY
    hu.laurel.sqlpulse.ssh.FailureLayer.SSH_AUTH,
    hu.laurel.sqlpulse.ssh.FailureLayer.SSH_NETWORK,
    hu.laurel.sqlpulse.ssh.FailureLayer.HOST_KEY,
    -> ConnectStep.SSH

    hu.laurel.sqlpulse.ssh.FailureLayer.MYSQL -> ConnectStep.MYSQL
    hu.laurel.sqlpulse.ssh.FailureLayer.LOCAL -> ConnectStep.KEY
}
