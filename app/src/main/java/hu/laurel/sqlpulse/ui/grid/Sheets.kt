package hu.laurel.sqlpulse.ui.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * The bottom sheet a cell opens (§7.5): the whole value in monospace, the column name and type
 * above it, Copy and — where the row can be identified — Edit below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellSheet(
    column: ColumnMeta,
    value: CellValue,
    canEdit: Boolean,
    editBlockedReason: String?,
    onCopy: (String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        shape = Shapes.sheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(column.label, style = MaterialTheme.typography.titleMedium)
            Text(column.typeName, style = MaterialTheme.typography.bodySmall, color = semantic.textSecondary)

            Text(
                text = value.asText(),
                style = MonoStyles.cell,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            )

            editBlockedReason?.takeIf { !canEdit }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = semantic.warning)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(onClick = { onCopy(value.asText()) }, shape = Shapes.button) {
                    Text(stringResource(R.string.cell_copy))
                }
                if (canEdit) {
                    Button(onClick = onEdit, shape = Shapes.button) {
                        Text(stringResource(R.string.cell_edit))
                    }
                }
            }
        }
    }
}

/**
 * Row detail (§7.5): every column under one another as label-value pairs, which is what makes a
 * wide table usable in one hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowDetailSheet(
    columns: List<ColumnMeta>,
    row: List<CellValue>,
    canDelete: Boolean,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        shape = Shapes.sheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            columns.forEachIndexed { index, column ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                    Text(
                        text = column.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.textSecondary,
                    )
                    Text(row.getOrNull(index)?.asText().orEmpty(), style = MonoStyles.cell)
                }
                HorizontalDivider(color = semantic.hairline)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(
                    onClick = {
                        onCopy(
                            columns.mapIndexed { index, column ->
                                "${column.label}: ${row.getOrNull(index)?.asText().orEmpty()}"
                            }.joinToString("\n"),
                        )
                    },
                    shape = Shapes.button,
                ) { Text(stringResource(R.string.cell_copy)) }

                if (canDelete) {
                    OutlinedButton(onClick = onDelete, shape = Shapes.button) {
                        Text(stringResource(R.string.row_delete))
                    }
                }
            }
        }
    }
}
