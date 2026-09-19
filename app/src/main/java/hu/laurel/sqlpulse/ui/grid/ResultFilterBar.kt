package hu.laurel.sqlpulse.ui.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.grid.CellCondition
import hu.laurel.sqlpulse.data.grid.FilterOperator
import hu.laurel.sqlpulse.data.grid.ResultFilter
import hu.laurel.sqlpulse.data.grid.ResultFilters
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * The search box, the conditions as chips, and the line saying what is hidden.
 *
 * It narrows the rows already loaded and never asks the server again, which is a difference the
 * user has to be able to see: "nothing matches" and "nothing matches here" are different answers,
 * and the second one is the truthful one when the query stopped at its row limit.
 */
@Composable
fun ResultFilterBar(
    table: ResultTable,
    filter: ResultFilter,
    shownRows: Int,
    onFilterChange: (ResultFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<CellCondition?>(null) }
    val semantic = LocalSemanticColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = filter.search,
            onValueChange = { onFilterChange(filter.copy(search = it)) },
            singleLine = true,
            shape = Shapes.button,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (filter.search.isNotEmpty()) {
                    IconButton(onClick = { onFilterChange(filter.copy(search = "")) }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.filter_clear_search),
                        )
                    }
                }
            },
            placeholder = { Text(stringResource(R.string.filter_search_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l, vertical = Spacing.xs),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(filter.conditions, key = { it.columnIndex }) { condition ->
                val label = table.columns.getOrNull(condition.columnIndex)?.label.orEmpty()
                InputChip(
                    selected = true,
                    onClick = { editing = condition },
                    label = {
                        Text(
                            text = "$label ${stringResource(condition.operator.labelRes())} ${condition.text}".trim(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { onFilterChange(filter.without(condition.columnIndex)) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.filter_remove_condition),
                            )
                        }
                    },
                )
            }
            item {
                AssistChip(
                    onClick = {
                        // The first column that has no condition yet: on a two-column result that
                        // is the whole choice, and on a wide one it is at least never a column the
                        // user has already narrowed.
                        val free = table.columns.indices.firstOrNull { filter.conditionOn(it) == null } ?: 0
                        table.columns.getOrNull(free)?.let { column ->
                            editing = CellCondition(free, ResultFilters.operatorsFor(column.type).first())
                        }
                    },
                    label = { Text(stringResource(R.string.filter_add_condition)) },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                )
            }
        }

        if (filter.isActive) {
            Text(
                text = stringResource(R.string.filter_shown, shownRows, table.rowCount) +
                    if (table.truncated || table.limitAdded) {
                        " · " + stringResource(R.string.filter_loaded_only)
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = semantic.warning,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
        }
    }

    editing?.let { condition ->
        ConditionSheet(
            table = table,
            condition = condition,
            onDismiss = { editing = null },
            onApply = {
                onFilterChange(filter.with(it))
                editing = null
            },
        )
    }
}

/** Column, operator, value — in that order, because each one narrows what the next may be. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConditionSheet(
    table: ResultTable,
    condition: CellCondition,
    onDismiss: () -> Unit,
    onApply: (CellCondition) -> Unit,
) {
    var draft by remember(condition.columnIndex) { mutableStateOf(condition) }
    val type = table.columns.getOrNull(draft.columnIndex)?.type
    val operators = type?.let { ResultFilters.operatorsFor(it) }.orEmpty()
    val needsText = draft.operator != FilterOperator.EMPTY && draft.operator != FilterOperator.NOT_EMPTY

    ModalBottomSheet(onDismissRequest = onDismiss, shape = Shapes.sheet) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.l).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                text = stringResource(R.string.filter_condition_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Choice(
                label = stringResource(R.string.filter_column),
                selected = table.columns.getOrNull(draft.columnIndex)?.label.orEmpty(),
                options = table.columns.indices.toList(),
                optionLabel = { table.columns[it].label },
                onSelect = { index ->
                    // The operator has to follow the column: "contains" on a BLOB is not a
                    // condition anybody can answer, so a column change resets it to a valid one.
                    val allowed = ResultFilters.operatorsFor(table.columns[index].type)
                    draft = draft.copy(
                        columnIndex = index,
                        operator = if (draft.operator in allowed) draft.operator else allowed.first(),
                    )
                },
            )

            Choice(
                label = stringResource(R.string.filter_operator),
                selected = stringResource(draft.operator.labelRes()),
                options = operators,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = { draft = draft.copy(operator = it) },
            )

            if (needsText) {
                OutlinedTextField(
                    value = draft.text,
                    onValueChange = { draft = draft.copy(text = it) },
                    singleLine = true,
                    shape = Shapes.button,
                    label = { Text(stringResource(R.string.filter_value)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { onApply(draft) },
                    shape = Shapes.button,
                ) { Text(stringResource(R.string.filter_apply)) }
            }
        }
    }
}

/**
 * A labelled menu that hands back the value itself, not the text of it.
 *
 * Matching a chosen label back to what it stands for breaks the moment two columns are called the
 * same — which a join does routinely — so the value travels and the label is only drawn.
 */
@Composable
private fun <T> Choice(
    label: String,
    selected: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalSemanticColors.current.textSecondary,
        )
        Box {
            OutlinedButton(onClick = { open = true }, shape = Shapes.button) {
                Text(text = selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

private fun FilterOperator.labelRes(): Int = when (this) {
    FilterOperator.CONTAINS -> R.string.filter_op_contains
    FilterOperator.NOT_CONTAINS -> R.string.filter_op_not_contains
    FilterOperator.EQUALS -> R.string.filter_op_equals
    FilterOperator.NOT_EQUALS -> R.string.filter_op_not_equals
    FilterOperator.GREATER -> R.string.filter_op_greater
    FilterOperator.LESS -> R.string.filter_op_less
    FilterOperator.EMPTY -> R.string.filter_op_empty
    FilterOperator.NOT_EMPTY -> R.string.filter_op_not_empty
}
