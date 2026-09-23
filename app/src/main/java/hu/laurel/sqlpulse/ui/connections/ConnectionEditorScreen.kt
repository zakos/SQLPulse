package hu.laurel.sqlpulse.ui.connections

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.connection.ConnectionTimeouts
import hu.laurel.sqlpulse.data.connection.SaveRefusal
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.ssh.SshAuthMethod
import hu.laurel.sqlpulse.ssh.TunnelState
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.components.LabeledField
import hu.laurel.sqlpulse.ui.components.SectionCaption
import hu.laurel.sqlpulse.ui.components.SegmentedChoice
import hu.laurel.sqlpulse.ui.theme.ConnectionColor
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors

/**
 * Connection editor (§7.2): connection, SSH, MySQL — in that order, because that is the order in
 * which things fail. Save stays disabled until a key is chosen.
 */
@Composable
fun ConnectionEditorScreen(
    onBack: () -> Unit,
    onOpenKeyStore: () -> Unit,
    viewModel: ConnectionEditorViewModel = hiltViewModel(),
) {
    ConnectionEditorScreenContent(onBack = onBack, onOpenKeyStore = onOpenKeyStore, viewModel = viewModel)
}

/** The screen itself, drawn from whatever [ConnectionEditorController] it is handed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditorScreenContent(
    onBack: () -> Unit,
    onOpenKeyStore: () -> Unit,
    viewModel: ConnectionEditorController,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val tunnel by viewModel.tunnel.collectAsStateWithLifecycle()
    val serverVersion by viewModel.serverVersion.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val readOnlyOffer by viewModel.readOnlyOffer.collectAsStateWithLifecycle()
    val hostKeyPrompt by viewModel.hostKeyPrompt.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
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
        // Test and save stay at hand at the bottom however long the form gets, as in the design.
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .drawBehind { drawRect(semantic.hairline, size = size.copy(height = 1.dp.toPx())) }
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.l, vertical = Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                OutlinedButton(
                    onClick = { if (tunnel is TunnelState.Active) viewModel.stopTest() else viewModel.test() },
                    enabled = form.canSave,
                    shape = Shapes.button,
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.connection_test), modifier = Modifier.padding(start = Spacing.s))
                }
                Button(
                    onClick = { viewModel.save { onBack() } },
                    enabled = form.canSave,
                    shape = Shapes.button,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(stringResource(R.string.connection_save)) }
            }
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
                LabeledField(
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

                Text(
                    stringResource(R.string.connection_environment),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SegmentedChoice(
                    options = ConnectionEnvironment.ORDER,
                    selected = form.environment,
                    label = { stringResource(it.shortLabel()) },
                    onSelect = viewModel::setEnvironment,
                )
                if (form.environment.isProduction) {
                    Text(
                        stringResource(
                            if (form.readOnly) {
                                R.string.environment_production_note
                            } else {
                                R.string.environment_production_writable_note
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (form.readOnly) semantic.textSecondary else semantic.warning,
                    )
                    // The policy's own reason, in place, while the form can still be fixed — the
                    // save button repeats it, but by then the user has already pressed it.
                    form.refusal?.let { refusal ->
                        Text(
                            when (refusal) {
                                SaveRefusal.Unprotected ->
                                    stringResource(R.string.policy_unprotected)

                                is SaveRefusal.QueryTimeoutTooLong -> stringResource(
                                    R.string.policy_query_timeout_too_long,
                                    refusal.maxSeconds,
                                    refusal.requestedSeconds,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Section(stringResource(R.string.section_ssh)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ssh_use_tunnel),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier.weight(1f).padding(end = Spacing.m),
                    )
                    Switch(
                        checked = form.useSsh,
                        onCheckedChange = viewModel::setUseSsh,
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
                LabeledField(
                    value = form.sshHost,
                    onValueChange = { value -> viewModel.update { it.copy(sshHost = value) } },
                    label = { Text(stringResource(R.string.ssh_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    mono = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    LabeledField(
                        value = form.sshPort,
                        onValueChange = { value -> viewModel.update { it.copy(sshPort = value) } },
                        label = { Text(stringResource(R.string.ssh_port)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.weight(1f),
                        mono = true,
                    )
                    LabeledField(
                        value = form.sshUser,
                        onValueChange = { value -> viewModel.update { it.copy(sshUser = value) } },
                        label = { Text(stringResource(R.string.ssh_user)) },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                        mono = true,
                    )
                }

                // The optional first hop, for a network where the SSH host is not reachable from
                // outside. Left empty, nothing about the connection changes.
                LabeledField(
                    value = form.jumpHost,
                    onValueChange = { value -> viewModel.update { it.copy(jumpHost = value) } },
                    label = { Text(stringResource(R.string.ssh_jump_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    mono = true,
                )
                if (form.jumpHost.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        LabeledField(
                            value = form.jumpPort,
                            onValueChange = { value -> viewModel.update { it.copy(jumpPort = value) } },
                            label = { Text(stringResource(R.string.ssh_port)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                            ),
                            modifier = Modifier.weight(1f),
                            mono = true,
                        )
                        LabeledField(
                            value = form.jumpUser,
                            onValueChange = { value -> viewModel.update { it.copy(jumpUser = value) } },
                            label = { Text(stringResource(R.string.ssh_user)) },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                            mono = true,
                        )
                    }

                    // The two hops can be different machines run by different people, so the first
                    // one may carry its own credential. Off keeps the old arrangement, which is
                    // what every connection saved so far has.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.ssh_jump_separate_credential),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.weight(1f).padding(end = Spacing.m),
                        )
                        Switch(
                            checked = form.jumpSeparateCredential,
                            onCheckedChange = { value ->
                                viewModel.update { it.copy(jumpSeparateCredential = value) }
                            },
                        )
                    }
                    Text(
                        stringResource(
                            if (form.jumpSeparateCredential) {
                                R.string.ssh_jump_separate_note
                            } else {
                                R.string.ssh_jump_shared_note
                            },
                        ),
                        color = semantic.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    if (form.jumpSeparateCredential) {
                        SegmentedChoice(
                            options = SshAuthMethod.entries,
                            selected = form.jumpAuthMethod,
                            label = { method ->
                                stringResource(
                                    when (method) {
                                        SshAuthMethod.KEY -> R.string.ssh_auth_key
                                        SshAuthMethod.PASSWORD -> R.string.ssh_auth_password
                                    },
                                )
                            },
                            onSelect = { method -> viewModel.update { it.copy(jumpAuthMethod = method) } },
                        )
                        when (form.jumpAuthMethod) {
                            SshAuthMethod.KEY -> {
                                Text(
                                    stringResource(R.string.ssh_jump_key),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                KeyPicker(
                                    keys = keys,
                                    selectedId = form.jumpKeyId,
                                    onSelect = { id -> viewModel.update { it.copy(jumpKeyId = id) } },
                                    onOpenKeyStore = onOpenKeyStore,
                                )
                                if (form.jumpKeyId == null) {
                                    Text(
                                        stringResource(R.string.ssh_key_required),
                                        color = semantic.warning,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }

                            SshAuthMethod.PASSWORD -> LabeledField(
                                value = form.jumpPassword,
                                onValueChange = { value ->
                                    viewModel.update {
                                        it.copy(jumpPassword = value, jumpPasswordTouched = true)
                                    }
                                },
                                label = { Text(stringResource(R.string.ssh_jump_password)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                SegmentedChoice(
                    options = SshAuthMethod.entries,
                    selected = form.sshAuthMethod,
                    label = { method ->
                        stringResource(
                            when (method) {
                                SshAuthMethod.KEY -> R.string.ssh_auth_key
                                SshAuthMethod.PASSWORD -> R.string.ssh_auth_password
                            },
                        )
                    },
                    onSelect = { method -> viewModel.update { it.copy(sshAuthMethod = method) } },
                )

                when (form.sshAuthMethod) {
                    SshAuthMethod.KEY -> {
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

                    SshAuthMethod.PASSWORD -> {
                        LabeledField(
                            value = form.sshPassword,
                            onValueChange = { value ->
                                viewModel.update {
                                    it.copy(sshPassword = value, sshPasswordTouched = true)
                                }
                            },
                            label = { Text(stringResource(R.string.ssh_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.ssh_password_note),
                            color = semantic.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                }
            }

            Section(stringResource(R.string.section_mysql)) {
                LabeledField(
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
                    mono = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    LabeledField(
                        value = form.dbPort,
                        onValueChange = { value -> viewModel.update { it.copy(dbPort = value) } },
                        label = { Text(stringResource(R.string.db_port)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.weight(1f),
                        mono = true,
                    )
                    LabeledField(
                        value = form.database,
                        onValueChange = { value -> viewModel.update { it.copy(database = value) } },
                        label = { Text(stringResource(R.string.db_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                        mono = true,
                    )
                }
                LabeledField(
                    value = form.dbUser,
                    onValueChange = { value -> viewModel.update { it.copy(dbUser = value) } },
                    label = { Text(stringResource(R.string.db_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    mono = true,
                )
                LabeledField(
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
                    Text(
                        stringResource(R.string.db_read_only),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier.weight(1f).padding(end = Spacing.m),
                    )
                    Switch(
                        checked = form.readOnly,
                        onCheckedChange = { value -> viewModel.update { it.copy(readOnly = value) } },
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

            Section(stringResource(R.string.section_timeouts)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    LabeledField(
                        value = form.connectTimeout,
                        onValueChange = { value ->
                            viewModel.update { it.copy(connectTimeout = value.filter(Char::isDigit)) }
                        },
                        label = { Text(stringResource(R.string.timeout_connect)) },
                        isError = !ConnectionTimeouts.isValid(form.connectTimeout),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        mono = true,
                    )
                    LabeledField(
                        value = form.queryTimeout,
                        onValueChange = { value ->
                            viewModel.update { it.copy(queryTimeout = value.filter(Char::isDigit)) }
                        },
                        label = { Text(stringResource(R.string.timeout_query)) },
                        isError = !ConnectionTimeouts.isValid(form.queryTimeout),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        mono = true,
                    )
                }
                Text(
                    stringResource(R.string.timeout_note, ConnectionTimeouts.MAX_SECONDS),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }

            TestResult(tunnel = tunnel, serverVersion = serverVersion)

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

        }

        hostKeyPrompt?.let { prompt ->
            HostKeyDialog(
                prompt = prompt,
                onAccept = viewModel::acceptHostKey,
                onReject = viewModel::rejectHostKey,
            )
        }

        // Offered, not done: the switch stays the user's, and saying no leaves the connection
        // writable — it will simply ask for an unlock before the first write.
        if (readOnlyOffer) {
            AlertDialog(
                onDismissRequest = viewModel::dismissReadOnlyOffer,
                title = { Text(stringResource(R.string.policy_read_only_offer_title)) },
                text = { Text(stringResource(R.string.policy_read_only_offer_body)) },
                confirmButton = {
                    TextButton(onClick = viewModel::acceptReadOnlyOffer) {
                        Text(stringResource(R.string.policy_read_only_offer_accept))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissReadOnlyOffer) {
                        Text(stringResource(R.string.policy_read_only_offer_decline))
                    }
                },
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
        // Four short names on one track, and the chosen one explained under it: the explanation is
        // what matters, but only for the mode that is on.
        SegmentedChoice(
            options = SslMode.entries,
            selected = mode,
            label = { candidate ->
                stringResource(
                    when (candidate) {
                        SslMode.DISABLED -> R.string.tls_disabled_short
                        SslMode.REQUIRED -> R.string.tls_required_short
                        SslMode.VERIFY_CA -> R.string.tls_verify_ca_short
                        SslMode.VERIFY_IDENTITY -> R.string.tls_verify_identity_short
                    },
                )
            },
            onSelect = onMode,
        )
        Text(
            stringResource(
                when (mode) {
                    SslMode.DISABLED -> R.string.tls_disabled_note
                    SslMode.REQUIRED -> R.string.tls_required_note
                    SslMode.VERIFY_CA -> R.string.tls_verify_ca_note
                    SslMode.VERIFY_IDENTITY -> R.string.tls_verify_identity_note
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )

        if (mode.verifiesCertificate) {
            // The imported CA on a line of its own, so a long file name never squeezes the buttons.
            certificate?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = semantic.success, modifier = Modifier.size(18.dp))
                    Text(
                        it,
                        style = MonoStyles.cell,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClear) { Text(stringResource(R.string.cancel)) }
                }
            }
            OutlinedButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                shape = Shapes.button,
            ) { Text(stringResource(R.string.tls_import_ca)) }
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
        // Flat, as in the design: a caption and the fields under it. Boxes around boxes only
        // made the form longer.
        SectionCaption(title, modifier = Modifier.padding(top = Spacing.m))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) { content() }
    }
}

@Composable
private fun ColorPicker(selected: ConnectionColor, onSelect: (ConnectionColor) -> Unit) {
    val ground = MaterialTheme.colorScheme.surface
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        ConnectionColor.entries.forEach { option ->
            val chosen = option == selected
            // The swatch is 36dp, its touch target the full 44; the chosen one gets a ring set
            // off from it by a gap of the card's own colour, so it reads without the fill changing.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .selectable(selected = chosen, role = Role.RadioButton) { onSelect(option) }
                    .semantics { contentDescription = option.name },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .then(if (chosen) Modifier.border(2.dp, option.value, CircleShape) else Modifier)
                        .padding(if (chosen) 4.dp else 0.dp)
                        .background(option.value, CircleShape)
                        .then(if (chosen) Modifier.border(1.dp, ground, CircleShape) else Modifier),
                )
            }
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
