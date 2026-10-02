package hu.laurel.sqlpulse.ui.query

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnEditors
import hu.laurel.sqlpulse.data.sql.EditKind
import hu.laurel.sqlpulse.data.sql.NotEditableReason
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.components.DialogButtons
import hu.laurel.sqlpulse.ui.components.DialogCard
import hu.laurel.sqlpulse.ui.components.DialogHeading
import hu.laurel.sqlpulse.ui.grid.CellEditDialog
import hu.laurel.sqlpulse.ui.grid.CellSelection
import hu.laurel.sqlpulse.ui.grid.CellSheet
import hu.laurel.sqlpulse.ui.grid.ConfirmStatementDialog
import hu.laurel.sqlpulse.ui.grid.RowDetailSheet
import hu.laurel.sqlpulse.ui.grid.asText
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * The one line under the result's count that says whether its rows can be edited, and if not,
 * why. It is a line of small print rather than a banner: most results of a hand-written query are
 * read-only for a plain reason, and the reason is only wanted by the person who looks for it.
 */
@Composable
fun ResultEditStrip(state: ResultEditUiState, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val line = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)

    // Right after a write the line says so, for as long as it can be taken back.
    if (state.undoable != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
        ) {
            Text(stringResource(R.string.resultedit_applied), style = line, color = semantic.success)
            Text(
                stringResource(R.string.resultedit_undo),
                style = line.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onUndo).padding(vertical = Spacing.xs),
            )
        }
        return
    }

    val (icon, text, tint) = when (val status = state.status) {
        ResultEditStatus.None -> return
        is ResultEditStatus.Editable -> Triple(
            Icons.Default.Edit,
            stringResource(R.string.resultedit_editable, status.target.table),
            semantic.success,
        )

        is ResultEditStatus.ReadOnly -> Triple(
            Icons.Default.Lock,
            stringResource(R.string.resultedit_readonly, stringResource(status.reason.labelRes())),
            semantic.textSecondary,
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(text, style = line, color = semantic.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Everything that opens over the grid when a cell or a row is touched: the cell sheet, the row
 * sheet, the edit box, the confirmation card, and what follows a refused or conflicting write.
 * They are the table page's own components, so editing here asks for exactly what it asks for there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultEditDialogs(
    controller: ResultEditController,
    state: ResultEditUiState,
    rows: ResultTable?,
    selectedCell: CellSelection?,
    onSelectedCellChange: (CellSelection?) -> Unit,
    detailRow: Int?,
    onDetailRowChange: (Int?) -> Unit,
    onCopy: (String) -> Unit,
) {
    var editingCell by remember { mutableStateOf<CellSelection?>(null) }
    val target = (state.status as? ResultEditStatus.Editable)?.target

    selectedCell?.let { selection ->
        val columnIndex = rows?.columns?.indexOfFirst { it === selection.column }
            ?.takeIf { it >= 0 } ?: rows?.columns?.indexOf(selection.column) ?: -1
        // A column that is generated, or a BLOB shown only as its size, has nothing to type into.
        val writable = target?.columns?.getOrNull(columnIndex) != null && selection.value !is CellValue.Blob
        CellSheet(
            column = selection.column,
            value = selection.value,
            canEdit = writable,
            editBlockedReason = (state.status as? ResultEditStatus.ReadOnly)?.let {
                stringResource(R.string.resultedit_readonly, stringResource(it.reason.labelRes()))
            },
            onCopy = onCopy,
            onEdit = {
                editingCell = selection
                onSelectedCellChange(null)
            },
            onDismiss = { onSelectedCellChange(null) },
        )
    }

    editingCell?.let { selection ->
        val columnIndex = rows?.columns?.indexOfFirst { it === selection.column }
            ?.takeIf { it >= 0 } ?: rows?.columns?.indexOf(selection.column) ?: -1
        CellEditDialog(
            columnLabel = selection.column.label,
            initialValue = if (selection.value is CellValue.Null) null else selection.value.asText(),
            // The table's own type knows an enum's values; the result set only says "string".
            editor = ColumnEditors.of(target?.types?.getOrNull(columnIndex) ?: selection.column.typeName),
            onConfirm = { newValue ->
                controller.prepareCellEdit(selection.rowIndex, columnIndex, newValue)
                editingCell = null
            },
            onDismiss = { editingCell = null },
        )
    }

    detailRow?.let { rowIndex ->
        if (rows != null) {
            RowDetailSheet(
                columns = rows.columns,
                row = rows.rows.getOrElse(rowIndex) { emptyList() },
                canDelete = state.canEdit,
                onCopy = onCopy,
                onDelete = {
                    controller.prepareRowDelete(rowIndex)
                    onDetailRowChange(null)
                },
                onDismiss = { onDetailRowChange(null) },
            )
        }
    }

    state.pendingEdit?.let { edit ->
        val destructive = edit.kind == EditKind.DELETE
        ConfirmStatementDialog(
            title = stringResource(if (destructive) R.string.confirm_delete_title else R.string.confirm_update_title),
            statement = edit.preview,
            destructive = destructive,
            // §7.6: on a production connection, deleting means typing the table name.
            requireTableName = state.table.takeIf { destructive && state.isProduction },
            onConfirm = controller::confirmEdit,
            onDismiss = controller::dismissEdit,
            subtitle = stringResource(R.string.environment_production_short).uppercase().takeIf { state.isProduction },
        )
    }

    state.conflict?.let { conflict ->
        BasicAlertDialog(onDismissRequest = controller::dismissConflict) {
            DialogCard {
                DialogHeading(title = stringResource(R.string.conflict_title))
                Text(
                    if (conflict.rowExists) {
                        stringResource(R.string.conflict_body, conflict.currentValue ?: "NULL")
                    } else {
                        stringResource(R.string.conflict_row_gone)
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = LocalSemanticColors.current.textSecondary,
                )
                DialogButtons(
                    cancelLabel = stringResource(R.string.cancel),
                    onCancel = controller::dismissConflict,
                    // Only offered while there is still a row to write to.
                    actionLabel = stringResource(R.string.conflict_overwrite),
                    onAction = controller::overwriteConflict,
                    enabled = conflict.rowExists,
                )
            }
        }
    }

    state.error?.let { message ->
        BasicAlertDialog(onDismissRequest = controller::dismissError) {
            DialogCard {
                DialogHeading(title = stringResource(R.string.resultedit_error_title))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = LocalSemanticColors.current.textSecondary,
                )
                DialogButtons(
                    cancelLabel = stringResource(R.string.resultedit_close),
                    onCancel = controller::dismissError,
                    actionLabel = stringResource(R.string.resultedit_close),
                    onAction = controller::dismissError,
                    enabled = true,
                )
            }
        }
    }
}

private fun NotEditableReason.labelRes(): Int = when (this) {
    NotEditableReason.NOT_SELECT -> R.string.resultedit_reason_not_select
    NotEditableReason.MULTIPLE_STATEMENTS -> R.string.resultedit_reason_multiple_statements
    NotEditableReason.CTE -> R.string.resultedit_reason_cte
    NotEditableReason.UNION -> R.string.resultedit_reason_union
    NotEditableReason.JOIN -> R.string.resultedit_reason_join
    NotEditableReason.SUBQUERY_IN_FROM -> R.string.resultedit_reason_subquery_in_from
    NotEditableReason.DISTINCT -> R.string.resultedit_reason_distinct
    NotEditableReason.GROUP_BY -> R.string.resultedit_reason_group_by
    NotEditableReason.HAVING -> R.string.resultedit_reason_having
    NotEditableReason.AGGREGATE -> R.string.resultedit_reason_aggregate
    NotEditableReason.WINDOW -> R.string.resultedit_reason_window
    NotEditableReason.EXPRESSION -> R.string.resultedit_reason_expression
    NotEditableReason.NO_TABLE -> R.string.resultedit_reason_no_table
    NotEditableReason.UNSUPPORTED -> R.string.resultedit_reason_unsupported
    NotEditableReason.NO_DATABASE -> R.string.resultedit_reason_no_database
    NotEditableReason.UNKNOWN_TABLE -> R.string.resultedit_reason_unknown_table
    NotEditableReason.UNKNOWN_COLUMN -> R.string.resultedit_reason_unknown_column
    NotEditableReason.COLUMN_MISMATCH -> R.string.resultedit_reason_column_mismatch
    NotEditableReason.NO_KEY -> R.string.resultedit_reason_no_key
    NotEditableReason.KEY_NOT_SELECTED -> R.string.resultedit_reason_key_not_selected
    NotEditableReason.WRITES_LOCKED -> R.string.resultedit_reason_writes_locked
}
