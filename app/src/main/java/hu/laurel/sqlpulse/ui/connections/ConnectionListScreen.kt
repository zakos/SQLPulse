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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
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
import hu.laurel.sqlpulse.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

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
                    items(state.connections, key = { it.id }) { connection ->
                        ConnectionCard(
                            connection = connection,
                            tunnel = state.tunnel.takeIf { it.connectionId == connection.id },
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
                Text(connection.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    // Without a tunnel there is no bastion to name.
                    if (connection.useSshTunnel) {
                        "${connection.sshUser}@${connection.bastionHost} → " +
                            "${connection.dbHost}:${connection.dbPort}/${connection.database}"
                    } else {
                        "${connection.dbHost}:${connection.dbPort}/${connection.database}"
                    },
                    style = MonoStyles.cell,
                    color = semantic.textSecondary,
                )

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
