package hu.laurel.sqlpulse.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.ui.copyToClipboard
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors

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
                colors = sqlPulseTopBarColors(),
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

@OptIn(ExperimentalLayoutApi::class)
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
        Text(
            text = stringResource(R.string.diag_excluded),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
            modifier = Modifier.padding(top = Spacing.l, bottom = Spacing.s),
        )
        // What the report leaves out, as ticks: the promise is the point of this screen, so it is
        // shown before the report rather than buried in the intro paragraph.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                R.string.diag_excluded_hosts,
                R.string.diag_excluded_users,
                R.string.diag_excluded_databases,
                R.string.diag_excluded_queries,
                R.string.diag_excluded_secrets,
            ).forEach { label ->
                Row(
                    modifier = Modifier
                        .background(semantic.success.copy(alpha = 0.13f), Shapes.chip)
                        .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = semantic.success,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(stringResource(label), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp))
                }
            }
        }
        text?.let {
            Text(
                text = it,
                style = MonoStyles.cell.copy(fontSize = 12.sp, lineHeight = 20.sp),
                modifier = Modifier
                    .padding(top = Spacing.l)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, Shapes.card)
                    .border(1.dp, semantic.hairline, Shapes.card)
                    .padding(14.dp),
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
