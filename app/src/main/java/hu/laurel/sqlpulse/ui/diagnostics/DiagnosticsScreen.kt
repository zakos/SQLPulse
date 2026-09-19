package hu.laurel.sqlpulse.ui.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.ui.copyToClipboard
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * The diagnostics report: what to paste into a bug report, and nothing else.
 *
 * Shown as a dialog over whatever the user was doing, because it is read once and copied — a
 * destination of its own would have to be navigated away from to get back to the failure it
 * describes. [DiagnosticsScreen] is the same thing as a screen, for a menu that wants one.
 *
 * What is on screen is exactly the string that goes to the clipboard: the text is rendered once,
 * in the data layer, so nobody can be shown a redacted report and copy an unredacted one.
 */
@Composable
fun DiagnosticsDialog(
    onDismiss: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val text = state.report?.asText()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diag_title)) },
        text = {
            DiagnosticsBody(
                text = text,
                loading = state.loading,
                connected = state.connected,
                copied = state.copied,
                modifier = Modifier.heightIn(max = 420.dp),
            )
        },
        confirmButton = {
            Button(
                enabled = text != null,
                onClick = {
                    text?.let { context.copyToClipboard(it) }
                    viewModel.markCopied()
                },
            ) { Text(stringResource(R.string.diag_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.diag_close)) }
        },
    )
}

/** The same report with a top bar, for wherever a full destination suits better than a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val text = state.report?.asText()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.diag_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            DiagnosticsBody(
                text = text,
                loading = state.loading,
                connected = state.connected,
                copied = state.copied,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = text != null,
                onClick = {
                    text?.let { context.copyToClipboard(it) }
                    viewModel.markCopied()
                },
                modifier = Modifier.fillMaxWidth().padding(Spacing.l),
            ) { Text(stringResource(R.string.diag_copy)) }
        }
    }
}

@Composable
private fun DiagnosticsBody(
    text: String?,
    loading: Boolean,
    connected: Boolean,
    copied: Boolean,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(R.string.diag_intro),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
        if (!connected && !loading) {
            Text(
                text = stringResource(R.string.diag_no_connection),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = Spacing.l))
        }
        text?.let {
            Text(
                text = it,
                style = MonoStyles.cell,
                modifier = Modifier.padding(top = Spacing.m),
            )
        }
        if (copied) {
            Text(
                text = stringResource(R.string.diag_copied),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.success,
                modifier = Modifier.padding(top = Spacing.s),
            )
        }
    }
}
