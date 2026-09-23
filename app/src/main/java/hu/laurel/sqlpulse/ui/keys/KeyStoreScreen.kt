package hu.laurel.sqlpulse.ui.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.db.SshKeyAlgorithm
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.components.InfoBadge
import hu.laurel.sqlpulse.ui.copyToClipboard
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors

/**
 * Key store (§5, §7.7). Three ways in — file, paste, generate — all landing in the same import
 * form. Private keys can be deleted but never viewed or exported (§6).
 */
@Composable
fun KeyStoreScreen(
    onBack: () -> Unit,
    viewModel: KeyStoreViewModel = hiltViewModel(),
) {
    KeyStoreScreenContent(onBack = onBack, viewModel = viewModel)
}

/** The screen itself, drawn from whatever [KeyStoreController] it is handed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyStoreScreenContent(
    onBack: () -> Unit,
    viewModel: KeyStoreController,
) {
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var importOpen by remember { mutableStateOf(false) }
    var keyText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SshKeyEntity?>(null) }
    val snackbarHost = remember { SnackbarHostState() }

    // A failure outside the import form (a delete, typically) needs somewhere to be seen.
    LaunchedEffect(importState.error, importOpen) {
        val message = importState.error
        if (message != null && !importOpen) {
            snackbarHost.showSnackbar(message)
            viewModel.clearError()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            viewModel.loadFromUri(it) { text ->
                keyText = text
                importOpen = true
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = { Text(stringResource(R.string.keys_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (keys.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.keys_empty_title),
                    body = stringResource(R.string.keys_empty_body),
                    actionLabel = stringResource(R.string.keys_empty_action),
                    onAction = { importOpen = true },
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    items(keys, key = { it.id }) { key ->
                        KeyCard(
                            key = key,
                            onCopyPublicKey = { context.copyToClipboard(it.publicKey) },
                            onDelete = { deleteTarget = key },
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        ) {
                            OutlinedButton(
                                onClick = { filePicker.launch(arrayOf("*/*")) },
                                shape = Shapes.button,
                            ) { Text(stringResource(R.string.key_import_file)) }
                            OutlinedButton(
                                onClick = { importOpen = true },
                                shape = Shapes.button,
                            ) { Text(stringResource(R.string.key_import_paste)) }
                        }
                    }
                }
            }
        }
    }

    if (importOpen) {
        ImportDialog(
            state = importState,
            keyText = keyText,
            onKeyTextChanged = {
                keyText = it
                viewModel.onKeyTextChanged(it)
            },
            onPickFile = { filePicker.launch(arrayOf("*/*")) },
            onImport = { name, passphrase -> viewModel.import(name, keyText, passphrase) },
            onGenerate = { name -> viewModel.generate(name) },
            onDismiss = {
                importOpen = false
                keyText = ""
                viewModel.dismissImportState()
            },
        )
    }

    deleteTarget?.let { key ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.key_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(stringResource(R.string.key_delete_body, key.name))
                    Text(key.fingerprint, style = MonoStyles.fingerprint)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(key)
                        deleteTarget = null
                    },
                ) { Text(stringResource(R.string.key_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    importState.addedPublicKey?.let { publicKey ->
        PublicKeyDialog(
            publicKey = publicKey,
            generated = importState.addedKeyGenerated,
            onCopy = { context.copyToClipboard(publicKey) },
            onDismiss = {
                importOpen = false
                keyText = ""
                viewModel.dismissImportState()
            },
        )
    }
}

@Composable
private fun KeyCard(key: SshKeyEntity, onCopyPublicKey: (SshKeyEntity) -> Unit, onDelete: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val accent = MaterialTheme.colorScheme.primary
    HairlineCard {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(accent.copy(alpha = 0.14f), Shapes.button),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Key, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(
                        key.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    InfoBadge(
                        if (key.algorithm == SshKeyAlgorithm.ED25519) "ED25519" else "${key.algorithm} ${key.bits}",
                        color = semantic.textSecondary,
                        container = semantic.surfaceRaised,
                    )
                }
                Text(
                    key.fingerprint,
                    style = MonoStyles.fingerprint.copy(fontSize = 12.sp),
                    color = semantic.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // One menu instead of two icons: the name and fingerprint are what the card is for,
            // and they need the width.
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.key_actions), tint = semantic.textSecondary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.key_public_copy)) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onCopyPublicKey(key)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.key_delete), color = semantic.danger) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = semantic.danger) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(
    state: KeyImportState,
    keyText: String,
    onKeyTextChanged: (String) -> Unit,
    onPickFile: () -> Unit,
    onImport: (String, CharArray?) -> Unit,
    onGenerate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_empty_action)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.key_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keyText,
                    onValueChange = onKeyTextChanged,
                    label = { Text(stringResource(R.string.key_import_paste)) },
                    textStyle = MonoStyles.cell,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.needsPassphrase) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.key_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = state.lockedForSeconds == 0,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = onPickFile, shape = Shapes.button) {
                        Text(stringResource(R.string.key_import_file))
                    }
                    OutlinedButton(
                        onClick = {
                            // Generating needs no key text; the pair is made on the device.
                            onGenerate(name)
                        },
                        shape = Shapes.button,
                    ) { Text(stringResource(R.string.key_generate)) }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                enabled = keyText.isNotBlank() && !state.busy && state.lockedForSeconds == 0,
                onClick = {
                    val secret = passphrase.takeIf { it.isNotEmpty() }?.toCharArray()
                    onImport(name, secret)
                    // §5, §6: the pasted key must not stay on the clipboard, and the user is told.
                    context.clearClipboard()
                    Toast.makeText(context, R.string.key_clipboard_cleared, Toast.LENGTH_SHORT).show()
                    passphrase = ""
                },
            ) { Text(stringResource(R.string.keys_empty_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Shown after a key lands in the store.
 *
 * Importing a private key does not create a new pair: the public half is derived from the private
 * one, which is why it can be shown here. The wording says so, because "here is your public key"
 * right after an import reads as if something had been generated.
 */
@Composable
private fun PublicKeyDialog(
    publicKey: String,
    generated: Boolean,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (generated) R.string.key_generated_title else R.string.key_imported_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    stringResource(
                        if (generated) {
                            R.string.key_add_to_authorized_keys
                        } else {
                            R.string.key_imported_body
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(publicKey, style = MonoStyles.cell)
            }
        },
        confirmButton = {
            Button(onClick = { onCopy(); onDismiss() }) {
                Text(stringResource(R.string.key_public_copy))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Clipboard hygiene (§6): a pasted private key is wiped from the clipboard immediately. */
private fun Context.clearClipboard() {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("", ""))
}
