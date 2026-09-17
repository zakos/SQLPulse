package hu.laurel.sqlpulse.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import kotlinx.coroutines.delay

/** Editing one cell: the new value, with NULL as an explicit choice rather than an empty string. */
@Composable
fun CellEditDialog(
    columnLabel: String,
    initialValue: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue.orEmpty()) }
    var isNull by remember { mutableStateOf(initialValue == null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(columnLabel) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; isNull = false },
                    enabled = !isNull,
                    textStyle = MonoStyles.cell,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(checked = isNull, onCheckedChange = { isNull = it })
                    Text(stringResource(R.string.cell_set_null))
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(if (isNull) null else text) }) {
                Text(stringResource(R.string.connection_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The confirmation of §7.6: the generated statement in a code block with the WHERE clause called
 * out, and a confirm button that stays inactive for the first second so it cannot be tapped
 * through by accident.
 *
 * On a production connection a delete also requires the table name to be typed.
 */
@Composable
fun ConfirmStatementDialog(
    title: String,
    statement: String,
    destructive: Boolean,
    requireTableName: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var armed by remember { mutableStateOf(false) }
    var typedName by remember { mutableStateOf("") }
    val semantic = LocalSemanticColors.current

    LaunchedEffect(statement) {
        delay(ARM_DELAY_MS)
        armed = true
    }

    val nameMatches = requireTableName == null || typedName.trim() == requireTableName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = if (destructive) semantic.danger else MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, Shapes.button)
                        .padding(Spacing.m),
                ) {
                    val whereIndex = statement.indexOf(" WHERE ")
                    if (whereIndex >= 0) {
                        Text(statement.substring(0, whereIndex), style = MonoStyles.cell)
                        Text(
                            text = statement.substring(whereIndex + 1),
                            style = MonoStyles.cell,
                            color = semantic.warning,
                            textDecoration = TextDecoration.Underline,
                        )
                    } else {
                        Text(statement, style = MonoStyles.cell)
                    }
                }

                requireTableName?.let { table ->
                    Text(
                        text = stringResource(R.string.confirm_type_table_name, table),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.danger,
                    )
                    OutlinedTextField(
                        value = typedName,
                        onValueChange = { typedName = it },
                        singleLine = true,
                        textStyle = MonoStyles.cell,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = armed && nameMatches,
                shape = Shapes.button,
            ) {
                Text(stringResource(if (destructive) R.string.row_delete else R.string.confirm_run))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** §7.6: the first second after the dialog appears, the confirm button does nothing. */
private const val ARM_DELAY_MS = 1_000L
