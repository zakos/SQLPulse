package hu.laurel.sqlpulse.ui.connections

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.ssh.TunnelState
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.theme.ConnectionColor
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * Connection editor (§7.2): connection, SSH, MySQL — in that order, because that is the order in
 * which things fail. Save stays disabled until a key is chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditorScreen(
    onBack: () -> Unit,
    onOpenKeyStore: () -> Unit,
    viewModel: ConnectionEditorViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val tunnel by viewModel.tunnel.collectAsStateWithLifecycle()
    val serverVersion by viewModel.serverVersion.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val hostKeyPrompt by viewModel.hostKeyPrompt.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (form.id == 0L) R.string.connection_new else R.string.connection_edit,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Section(stringResource(R.string.connections_title)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { value -> viewModel.update { it.copy(name = value) } },
                    label = { Text(stringResource(R.string.connection_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.connection_colour),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ColorPicker(
                    selected = form.color,
                    onSelect = { value -> viewModel.update { it.copy(color = value) } },
                )
                if (form.color == ConnectionColor.Production) {
                    Text(
                        stringResource(R.string.connection_production),
                        color = semantic.production,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Section(stringResource(R.string.section_ssh)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = form.useSsh,
                        onCheckedChange = viewModel::setUseSsh,
                    )
                    Text(
                        stringResource(R.string.ssh_use_tunnel),
                        modifier = Modifier.padding(start = Spacing.s),
                    )
                }
                if (!form.useSsh) {
                    // Stated plainly once: the port has to be reachable from the phone's network.
                    Text(
                        stringResource(R.string.ssh_direct_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.warning,
                    )
                }
            }

            if (form.useSsh) {
                Section(stringResource(R.string.section_ssh_details)) {
                OutlinedTextField(
                    value = form.sshHost,
                    onValueChange = { value -> viewModel.update { it.copy(sshHost = value) } },
                    label = { Text(stringResource(R.string.ssh_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedTextField(
                        value = form.sshPort,
                        onValueChange = { value -> viewModel.update { it.copy(sshPort = value) } },
                        label = { Text(stringResource(R.string.ssh_port)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.sshUser,
                        onValueChange = { value -> viewModel.update { it.copy(sshUser = value) } },
                        label = { Text(stringResource(R.string.ssh_user)) },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                }

                KeyPicker(
                    keys = keys,
                    selectedId = form.sshKeyId,
                    onSelect = { id -> viewModel.update { it.copy(sshKeyId = id) } },
                    onOpenKeyStore = onOpenKeyStore,
                )
                if (form.sshKeyId == null) {
                    Text(
                        stringResource(R.string.ssh_key_required),
                        color = semantic.warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                }
            }

            Section(stringResource(R.string.section_mysql)) {
                OutlinedTextField(
                    value = form.dbHost,
                    onValueChange = { value -> viewModel.update { it.copy(dbHost = value) } },
                    label = {
                        Text(
                            stringResource(
                                if (form.useSsh) R.string.db_host else R.string.db_host_direct,
                            ),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedTextField(
                        value = form.dbPort,
                        onValueChange = { value -> viewModel.update { it.copy(dbPort = value) } },
                        label = { Text(stringResource(R.string.db_port)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.database,
                        onValueChange = { value -> viewModel.update { it.copy(database = value) } },
                        label = { Text(stringResource(R.string.db_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                }
                OutlinedTextField(
                    value = form.dbUser,
                    onValueChange = { value -> viewModel.update { it.copy(dbUser = value) } },
                    label = { Text(stringResource(R.string.db_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.password,
                    onValueChange = { value ->
                        viewModel.update { it.copy(password = value, passwordTouched = true) }
                    },
                    label = { Text(stringResource(R.string.db_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = form.readOnly,
                        onCheckedChange = { value -> viewModel.update { it.copy(readOnly = value) } },
                    )
                    Text(
                        stringResource(R.string.db_read_only),
                        modifier = Modifier.padding(start = Spacing.s),
                    )
                }

                TlsSection(
                    mode = form.sslMode,
                    certificate = form.caCertificate,
                    onMode = { value -> viewModel.update { it.copy(sslMode = value) } },
                    onImport = viewModel::importCertificate,
                    onClear = viewModel::clearCertificate,
                )
            }

            TestResult(tunnel = tunnel, serverVersion = serverVersion)

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(
                    onClick = { if (tunnel is TunnelState.Active) viewModel.stopTest() else viewModel.test() },
                    enabled = form.canSave,
                    shape = Shapes.button,
                ) { Text(stringResource(R.string.connection_test)) }

                Button(
                    onClick = { viewModel.save { onBack() } },
                    enabled = form.canSave,
                    shape = Shapes.button,
                ) { Text(stringResource(R.string.connection_save)) }
            }
        }

        hostKeyPrompt?.let { prompt ->
            HostKeyDialog(
                prompt = prompt,
                onAccept = viewModel::acceptHostKey,
                onReject = viewModel::rejectHostKey,
            )
        }
    }
}

/**
 * How the MySQL connection itself is protected (research summary, §1).
 *
 * The verifying modes need the CA that signed the server certificate: a database server usually
 * has an internal CA that no public trust store knows about.
 */
@Composable
private fun TlsSection(
    mode: SslMode,
    certificate: String?,
    onMode: (SslMode) -> Unit,
    onImport: (Uri, String) -> Unit,
    onClear: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onImport(it, it.lastPathSegment ?: "ca.pem") }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.tls_mode), style = MaterialTheme.typography.bodyMedium)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            SslMode.entries.forEach { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMode(candidate) }
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = candidate == mode,
                        onClick = { onMode(candidate) },
                    )
                    Column(modifier = Modifier.padding(start = Spacing.s)) {
                        Text(
                            stringResource(
                                when (candidate) {
                                    SslMode.DISABLED -> R.string.tls_disabled
                                    SslMode.REQUIRED -> R.string.tls_required
                                    SslMode.VERIFY_CA -> R.string.tls_verify_ca
                                    SslMode.VERIFY_IDENTITY -> R.string.tls_verify_identity
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(
                                when (candidate) {
                                    SslMode.DISABLED -> R.string.tls_disabled_note
                                    SslMode.REQUIRED -> R.string.tls_required_note
                                    SslMode.VERIFY_CA -> R.string.tls_verify_ca_note
                                    SslMode.VERIFY_IDENTITY -> R.string.tls_verify_identity_note
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.textSecondary,
                        )
                    }
                }
            }
        }

        if (mode.verifiesCertificate) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    shape = Shapes.button,
                ) { Text(stringResource(R.string.tls_import_ca)) }
                certificate?.let {
                    Text(it, style = MonoStyles.cell, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text(stringResource(R.string.cancel)) }
                }
            }
            if (certificate == null) {
                Text(
                    stringResource(R.string.tls_ca_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.warning,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HairlineCard {
            Column(
                modifier = Modifier.padding(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) { content() }
        }
    }
}

@Composable
private fun ColorPicker(selected: ConnectionColor, onSelect: (ConnectionColor) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        ConnectionColor.entries.forEach { option ->
            Surface(
                modifier = Modifier
                    .size(if (option == selected) 32.dp else 24.dp)
                    .clickable { onSelect(option) },
                shape = CircleShape,
                color = option.value,
            ) {}
        }
    }
}

@Composable
private fun KeyPicker(
    keys: List<hu.laurel.sqlpulse.data.db.SshKeyEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onOpenKeyStore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.ssh_key), style = MaterialTheme.typography.bodyMedium)
        if (keys.isEmpty()) {
            OutlinedButton(onClick = onOpenKeyStore, shape = Shapes.button) {
                Text(stringResource(R.string.keys_empty_action))
            }
        } else {
            keys.forEach { key ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(key.id) }
                        .padding(vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = key.id == selectedId,
                        onClick = { onSelect(key.id) },
                    )
                    Column(modifier = Modifier.padding(start = Spacing.s)) {
                        Text(key.name, style = MaterialTheme.typography.bodyMedium)
                        Text(key.fingerprint, style = MonoStyles.fingerprint)
                    }
                }
            }
        }
    }
}

@Composable
private fun TestResult(tunnel: TunnelState, serverVersion: String?) {
    val semantic = LocalSemanticColors.current
    when (tunnel) {
        is TunnelState.Active -> Text(
            "${stringResource(R.string.state_active)} · ${tunnel.host}:${tunnel.port}" +
                (serverVersion?.let { " · MySQL $it" } ?: ""),
            color = semantic.success,
            style = MonoStyles.cell,
        )

        is TunnelState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(tunnel.failure.message, color = semantic.danger, style = MaterialTheme.typography.bodySmall)
            // §11: the raw cause is available, but never in the user's face.
            tunnel.failure.detail?.let { detail ->
                var expanded by rememberSaveable(detail) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(R.string.error_details))
                }
                if (expanded) {
                    Text(detail, color = semantic.textSecondary, style = MonoStyles.cell)
                }
            }
        }

        is TunnelState.Connecting, is TunnelState.Unlocking -> Text(
            stringResource(R.string.state_connecting),
            color = semantic.warning,
            style = MaterialTheme.typography.bodySmall,
        )

        else -> Unit
    }
}
