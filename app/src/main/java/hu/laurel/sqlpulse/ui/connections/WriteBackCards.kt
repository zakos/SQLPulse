package hu.laurel.sqlpulse.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.connection.WriteBackRefusal
import hu.laurel.sqlpulse.ui.components.DialogButtons
import hu.laurel.sqlpulse.ui.components.DialogCard
import hu.laurel.sqlpulse.ui.components.DialogHeading
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * What the write-back dialog is asking or telling (docs/tobb-motor-terv.md §3.2). Shared by the
 * table screen, which asks on the way out, and the connection editor's explicit button.
 */
sealed interface WriteBackPrompt {
    /** The question on leaving the table: changes exist only in the copy. */
    data class Ask(val fileName: String, val production: Boolean) : WriteBackPrompt

    /** The second warning: the original is not what the copy was made from (or cannot be checked). */
    data class Changed(val fileName: String, val production: Boolean, val unknown: Boolean) : WriteBackPrompt

    data class Working(val fileName: String) : WriteBackPrompt

    data class Done(val fileName: String) : WriteBackPrompt

    data class Refused(val reason: WriteBackRefusal) : WriteBackPrompt

    data class Failed(val fileName: String, val message: String) : WriteBackPrompt
}

/** Draws whichever card [prompt] calls for, inside a dialog window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteBackDialog(
    prompt: WriteBackPrompt,
    onWrite: () -> Unit,
    onOverwrite: () -> Unit,
    onKeepLocal: () -> Unit,
    onDismiss: () -> Unit,
) {
    // While the write runs the dialog cannot be dismissed: a half-written original is worse than waiting.
    val dismiss = if (prompt is WriteBackPrompt.Working) ({}) else onDismiss
    BasicAlertDialog(onDismissRequest = dismiss) {
        WriteBackCard(prompt, onWrite, onOverwrite, onKeepLocal, onDismiss)
    }
}

/** The card itself, so a screenshot can draw it without a dialog window. */
@Composable
fun WriteBackCard(
    prompt: WriteBackPrompt,
    onWrite: () -> Unit,
    onOverwrite: () -> Unit,
    onKeepLocal: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (prompt) {
        is WriteBackPrompt.Ask -> ChoiceCard(
            title = stringResource(R.string.writeback_title),
            body = stringResource(R.string.writeback_body, prompt.fileName),
            production = prompt.production,
            actionLabel = stringResource(R.string.writeback_confirm),
            onAction = onWrite,
            onKeepLocal = onKeepLocal,
            onCancel = onDismiss,
        )

        is WriteBackPrompt.Changed -> ChoiceCard(
            title = stringResource(R.string.writeback_changed_title),
            body = stringResource(
                if (prompt.unknown) R.string.writeback_unknown_body else R.string.writeback_changed_body,
                prompt.fileName,
            ),
            production = prompt.production,
            // Overwriting somebody else's changes is the dangerous step whatever the environment.
            danger = true,
            actionLabel = stringResource(R.string.writeback_overwrite),
            onAction = onOverwrite,
            onKeepLocal = onKeepLocal,
            onCancel = onDismiss,
        )

        is WriteBackPrompt.Working -> DialogCard {
            DialogHeading(title = stringResource(R.string.writeback_working, prompt.fileName))
        }

        is WriteBackPrompt.Done -> NoticeCard(
            title = stringResource(R.string.writeback_done, prompt.fileName),
            body = null,
            onClose = onDismiss,
        )

        is WriteBackPrompt.Refused -> NoticeCard(
            title = stringResource(R.string.writeback_refused_title),
            body = stringResource(prompt.reason.messageRes()),
            onClose = onDismiss,
        )

        is WriteBackPrompt.Failed -> NoticeCard(
            title = stringResource(R.string.writeback_failed_title),
            body = stringResource(R.string.writeback_failed, prompt.fileName, prompt.message),
            onClose = onDismiss,
            danger = true,
        )
    }
}

private fun WriteBackRefusal.messageRes(): Int = when (this) {
    WriteBackRefusal.NO_BASELINE -> R.string.writeback_refused_baseline
    WriteBackRefusal.NOTHING_TO_WRITE -> R.string.writeback_refused_nothing
    WriteBackRefusal.NO_PERMISSION -> R.string.writeback_refused_permission
    WriteBackRefusal.TRANSACTION_OPEN -> R.string.writeback_refused_transaction
}

/** Three ways out: do it, leave the changes in the copy, or stay where you are. */
@Composable
private fun ChoiceCard(
    title: String,
    body: String,
    production: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    onKeepLocal: () -> Unit,
    onCancel: () -> Unit,
    danger: Boolean = production,
) {
    val semantic = LocalSemanticColors.current
    DialogCard(danger = danger) {
        DialogHeading(
            title = title,
            subtitle = if (production) stringResource(R.string.writeback_production) else null,
            danger = danger,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Button(
                onClick = onAction,
                shape = Shapes.button,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = if (danger) {
                    ButtonDefaults.buttonColors(containerColor = semantic.danger, contentColor = MaterialTheme.colorScheme.onError)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(actionLabel) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onKeepLocal,
                    shape = Shapes.button,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text(stringResource(R.string.writeback_keep_local)) }
                OutlinedButton(
                    onClick = onCancel,
                    shape = Shapes.button,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String?, onClose: () -> Unit, danger: Boolean = false) {
    DialogCard(danger = danger) {
        DialogHeading(title = title, danger = danger)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        DialogButtons(
            cancelLabel = "",
            onCancel = {},
            actionLabel = stringResource(R.string.writeback_close),
            onAction = onClose,
            enabled = true,
            showCancel = false,
        )
    }
}
