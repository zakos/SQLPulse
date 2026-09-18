package hu.laurel.sqlpulse.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.CellEditor
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Editing one cell, in whatever form the column's type calls for.
 *
 * A free text box for a column that accepts three words is how a typo reaches a database, so an
 * enum is a list, a boolean is two buttons, and a date offers today and now. NULL stays an
 * explicit choice throughout, because an empty string is not the same thing.
 */
@Composable
fun CellEditDialog(
    columnLabel: String,
    initialValue: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** How the column's values look; text for a column whose type says nothing useful. */
    editor: CellEditor = CellEditor.Text,
) {
    var text by remember { mutableStateOf(initialValue.orEmpty()) }
    var isNull by remember { mutableStateOf(initialValue == null) }

    fun set(value: String) {
        text = value
        isNull = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(columnLabel) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                when (editor) {
                    is CellEditor.Choice -> ChoiceRow(
                        options = editor.options,
                        selected = text.takeUnless { isNull },
                        onSelect = ::set,
                    )

                    is CellEditor.Choices -> ChoicesRow(
                        options = editor.options,
                        selected = text.takeUnless { isNull }
                            ?.split(',')
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty(),
                        // A SET is stored comma-separated, in no particular order.
                        onToggle = { chosen -> set(chosen.joinToString(",")) },
                    )

                    else -> Unit
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; isNull = false },
                    enabled = !isNull,
                    textStyle = MonoStyles.cell,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (editor is CellEditor.Number) {
                            KeyboardType.Number
                        } else {
                            KeyboardType.Text
                        },
                    ),
                    singleLine = editor !is CellEditor.Text,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Shortcuts for the values that are otherwise typed out by hand, wrongly.
                val shortcuts = shortcutsFor(editor)
                if (shortcuts.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        shortcuts.forEach { (label, value) ->
                            AssistChip(onClick = { set(value()) }, label = { Text(label) })
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
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
 * The values worth one tap: the two a boolean has, and the date or time of right now.
 *
 * Formatted the way MySQL stores them, in the device's own time zone — which is the one the person
 * reading the screen is in, and the one the server is usually set to as well.
 */
@Composable
private fun shortcutsFor(editor: CellEditor): List<Pair<String, () -> String>> = when (editor) {
    is CellEditor.Bool -> listOf(
        stringResource(R.string.cell_true) to { "1" },
        stringResource(R.string.cell_false) to { "0" },
    )

    is CellEditor.Date -> listOf(
        stringResource(R.string.cell_today) to { now("yyyy-MM-dd") },
    )

    is CellEditor.DateTime -> listOf(
        stringResource(R.string.cell_now) to { now("yyyy-MM-dd HH:mm:ss") },
        stringResource(R.string.cell_today) to { now("yyyy-MM-dd 00:00:00") },
    )

    is CellEditor.Time -> listOf(stringResource(R.string.cell_now) to { now("HH:mm:ss") })
    else -> emptyList()
}

private fun now(pattern: String): String =
    SimpleDateFormat(pattern, Locale.US).format(Date())

/** One of a list. Tapping the chosen one again does nothing; NULL is the checkbox below. */
@Composable
private fun ChoiceRow(options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        items(options) { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option, style = MonoStyles.cell) },
            )
        }
    }
}

/** Any number of a list, for a SET column. */
@Composable
private fun ChoicesRow(
    options: List<String>,
    selected: List<String>,
    onToggle: (List<String>) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        items(options) { option ->
            FilterChip(
                selected = option in selected,
                onClick = {
                    onToggle(if (option in selected) selected - option else selected + option)
                },
                label = { Text(option, style = MonoStyles.cell) },
            )
        }
    }
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
