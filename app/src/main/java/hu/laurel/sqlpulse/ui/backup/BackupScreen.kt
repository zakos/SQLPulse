package hu.laurel.sqlpulse.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.backup.BackupFile
import hu.laurel.sqlpulse.data.backup.MergeDecision
import hu.laurel.sqlpulse.data.backup.MergeResolution
import hu.laurel.sqlpulse.data.backup.PassphrasePolicy
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors
import java.text.DateFormat
import java.util.Date

/**
 * Carry the configuration to another device, and put one back (§9).
 *
 * The screen is deliberately wordy: every choice here decides whether passwords and private keys
 * leave the phone, and a checkbox called "include secrets" with no sentence under it is not a
 * choice anybody can make well.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current

    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFile.MIME_TYPE),
    ) { uri -> uri?.let(viewModel::export) }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::chooseFile) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
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
            Text(
                text = stringResource(R.string.backup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.textSecondary,
            )

            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.backup_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }

            state.problem?.let { problem ->
                HairlineCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.l),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Text(problemText(problem), color = semantic.danger)
                        OutlinedButton(onClick = viewModel::dismissProblem, shape = Shapes.button) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }

            ExportSection(state, viewModel) {
                saveFile.launch(viewModel.suggestedFileName())
            }

            ImportSection(state, viewModel) {
                // Any type: the extension is ours, and a file manager that does not know it will
                // otherwise hide the file the user is looking for.
                openFile.launch(arrayOf("*/*"))
            }
        }
    }
}

@Composable
private fun ExportSection(
    state: BackupUiState,
    viewModel: BackupViewModel,
    onSave: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    Section(stringResource(R.string.backup_export_section)) {
        Text(stringResource(R.string.backup_scope_title), style = MaterialTheme.typography.titleSmall)

        ScopeChoice(
            selected = !state.includeSecrets,
            title = stringResource(R.string.backup_scope_settings),
            note = stringResource(R.string.backup_scope_settings_note),
            onClick = { viewModel.setIncludeSecrets(false) },
        )
        ScopeChoice(
            selected = state.includeSecrets,
            title = stringResource(R.string.backup_scope_secrets),
            note = stringResource(R.string.backup_scope_secrets_note),
            onClick = { viewModel.setIncludeSecrets(true) },
        )

        OutlinedTextField(
            value = state.exportPassphrase,
            onValueChange = viewModel::setExportPassphrase,
            label = { Text(stringResource(R.string.backup_passphrase)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.exportConfirmation,
            onValueChange = viewModel::setExportConfirmation,
            label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.exportProblem?.let { problem ->
            Text(
                text = when (problem) {
                    PassphrasePolicy.Problem.TOO_SHORT ->
                        stringResource(R.string.backup_passphrase_too_short, PassphrasePolicy.MIN_LENGTH)
                    PassphrasePolicy.Problem.CONFIRMATION_DIFFERS ->
                        stringResource(R.string.backup_passphrase_mismatch)
                },
                style = MaterialTheme.typography.bodySmall,
                color = semantic.danger,
            )
        }
        Text(
            text = stringResource(R.string.backup_passphrase_note),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )

        Button(onClick = onSave, enabled = state.canExport, shape = Shapes.button) {
            Text(stringResource(R.string.backup_save_file))
        }
        if (state.exported) {
            Text(stringResource(R.string.backup_export_done), color = semantic.success)
        }
    }
}

@Composable
private fun ImportSection(
    state: BackupUiState,
    viewModel: BackupViewModel,
    onChooseFile: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    Section(stringResource(R.string.backup_import_section)) {
        Text(
            text = stringResource(R.string.backup_import_intro),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
        OutlinedButton(onClick = onChooseFile, enabled = !state.busy, shape = Shapes.button) {
            Text(stringResource(R.string.backup_choose_file))
        }

        state.fileMetadata?.let { metadata ->
            Text(
                text = stringResource(
                    R.string.backup_file_made,
                    metadata.appVersion.ifBlank { metadata.app },
                    DateFormat.getDateTimeInstance().format(Date(metadata.createdAtEpochMs)),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.backup_file_contents,
                    metadata.connectionCount,
                    metadata.sshKeyCount,
                    metadata.certificateCount,
                    metadata.savedQueryCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
            Text(
                text = if (metadata.containsSecrets) {
                    stringResource(R.string.backup_file_with_secrets)
                } else {
                    stringResource(R.string.backup_file_without_secrets)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (metadata.containsSecrets) semantic.warning else semantic.textSecondary,
            )
        }

        if (state.stage == ImportStage.FILE_CHOSEN) {
            OutlinedTextField(
                value = state.importPassphrase,
                onValueChange = viewModel::setImportPassphrase,
                label = { Text(stringResource(R.string.backup_passphrase)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = viewModel::unlock,
                enabled = !state.busy && state.importPassphrase.isNotEmpty(),
                shape = Shapes.button,
            ) {
                Text(stringResource(R.string.backup_unlock))
            }
        }

        if (state.stage == ImportStage.PREVIEWED) {
            if (state.decisions.isEmpty()) {
                Text(stringResource(R.string.backup_nothing_inside), color = semantic.textSecondary)
            } else {
                Text(stringResource(R.string.backup_ready, state.importable))
            }

            if (state.conflicts.isNotEmpty()) {
                Text(
                    stringResource(R.string.backup_conflicts_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.backup_conflicts_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    MergeResolution.entries.forEach { resolution ->
                        FilterChip(
                            selected = state.defaultResolution == resolution,
                            onClick = { viewModel.setDefaultResolution(resolution) },
                            label = { Text(stringResource(allLabelFor(resolution))) },
                        )
                    }
                }
                state.conflicts.forEach { decision ->
                    ConflictRow(decision) { viewModel.setResolution(decision.sourceName, it) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(
                    onClick = viewModel::import,
                    enabled = !state.busy && state.importable > 0,
                    shape = Shapes.button,
                ) {
                    Text(stringResource(R.string.backup_import_now))
                }
                OutlinedButton(onClick = viewModel::startOver, shape = Shapes.button) {
                    Text(stringResource(R.string.backup_start_over))
                }
            }
        }

        state.outcome?.let { outcome ->
            Text(
                text = stringResource(
                    R.string.backup_import_done,
                    outcome.connectionsImported,
                    outcome.connectionsReplaced,
                    outcome.connectionsSkipped,
                    outcome.savedQueriesImported,
                ),
                color = semantic.success,
            )
            if (outcome.keysImported > 0) {
                Text(
                    text = stringResource(R.string.backup_import_keys, outcome.keysImported),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }
            if (outcome.keysWithoutMaterial > 0) {
                Text(
                    text = stringResource(
                        R.string.backup_import_keys_absent,
                        outcome.keysWithoutMaterial,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.warning,
                )
            }
            if (outcome.connectionsMissingKey > 0) {
                Text(
                    text = stringResource(
                        R.string.backup_import_missing_key,
                        outcome.connectionsMissingKey,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.warning,
                )
            }
            OutlinedButton(onClick = viewModel::startOver, shape = Shapes.button) {
                Text(stringResource(R.string.backup_start_over))
            }
        }
    }
}

@Composable
private fun ConflictRow(decision: MergeDecision, onChoose: (MergeResolution) -> Unit) {
    val semantic = LocalSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(decision.sourceName, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            MergeResolution.entries.forEach { resolution ->
                FilterChip(
                    selected = decision.resolution == resolution,
                    onClick = { onChoose(resolution) },
                    label = { Text(stringResource(labelFor(resolution))) },
                )
            }
        }
        Text(
            text = when (decision.resolution) {
                MergeResolution.KEEP_BOTH ->
                    stringResource(R.string.backup_will_be_named, decision.finalName)
                MergeResolution.REPLACE -> stringResource(R.string.backup_will_replace)
                MergeResolution.SKIP -> stringResource(R.string.backup_will_be_skipped)
            },
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
}

@Composable
private fun ScopeChoice(
    selected: Boolean,
    title: String,
    note: String,
    onClick: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    Row(verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = Spacing.s)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(note, style = MaterialTheme.typography.bodySmall, color = semantic.textSecondary)
        }
    }
}

@Composable
private fun problemText(problem: BackupProblem): String = when (problem) {
    BackupProblem.NotABackup -> stringResource(R.string.backup_error_not_a_backup)
    BackupProblem.WrongPassphrase -> stringResource(R.string.backup_error_wrong_passphrase)
    is BackupProblem.FutureVersion ->
        stringResource(R.string.backup_error_future_version, problem.fileVersion, problem.supportedVersion)
    is BackupProblem.Damaged -> stringResource(R.string.backup_error_damaged, problem.detail)
    is BackupProblem.Failed -> stringResource(R.string.backup_error_failed, problem.detail)
}

private fun labelFor(resolution: MergeResolution): Int = when (resolution) {
    MergeResolution.KEEP_BOTH -> R.string.backup_keep_both
    MergeResolution.REPLACE -> R.string.backup_replace
    MergeResolution.SKIP -> R.string.backup_skip
}

private fun allLabelFor(resolution: MergeResolution): Int = when (resolution) {
    MergeResolution.KEEP_BOTH -> R.string.backup_all_keep_both
    MergeResolution.REPLACE -> R.string.backup_all_replace
    MergeResolution.SKIP -> R.string.backup_all_skip
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HairlineCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) { content() }
        }
    }
}
