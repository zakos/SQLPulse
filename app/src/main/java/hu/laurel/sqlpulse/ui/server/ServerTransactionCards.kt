package hu.laurel.sqlpulse.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.MetricFormat
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.components.InfoBadge
import hu.laurel.sqlpulse.ui.grid.asText
import hu.laurel.sqlpulse.ui.query.SqlVisualTransformation
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/** A transaction open this long (seconds) is drawn as the one to look at. */
private const val LONG_TRANSACTION_SECONDS = 300L

/** What PostgreSQL calls a session that began a transaction and then went quiet; SQL Server rows say the same. */
internal fun isIdleInTransaction(state: String?): Boolean =
    state?.startsWith("idle in transaction", ignoreCase = true) == true

/**
 * Open transactions as cards, for engines whose transaction list carries a `State` (PostgreSQL,
 * SQL Server). Columns read: `Id`, `User`, `State`, `Seconds`, `db`, `Query`.
 *
 * An "idle in transaction" session holds its locks while doing nothing, which is how a forgotten
 * session ends up blocking everybody else; it gets the warning colour, and the danger colour once
 * it has been open for five minutes.
 */
@Composable
internal fun TransactionCards(
    table: ResultTable,
    onKill: (Long, String) -> Unit,
    onOpenInEditor: (String) -> Unit,
    killLabel: Int = R.string.server_kill,
) {
    val semantic = LocalSemanticColors.current
    fun column(name: String) = table.columns.indexOfFirst { it.label.equals(name, ignoreCase = true) }
    val idColumn = column("Id")
    val userColumn = column("User")
    val stateColumn = column("State")
    val secondsColumn = column("Seconds")
    val dbColumn = column("db")
    val queryColumn = column("Query")
    val highlight = SqlVisualTransformation(
        plain = MaterialTheme.colorScheme.onSurface,
        keyword = MaterialTheme.colorScheme.primary,
        string = semantic.success,
        number = semantic.cellNumber,
        comment = semantic.cellNull,
        identifier = semantic.cellDate,
        parameter = semantic.warning,
    )

    LazyColumn(
        contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(table.rows) { row ->
            fun cell(index: Int) = row.getOrNull(index)?.takeIf { it !is CellValue.Null }?.asText()
            val id = cell(idColumn)?.toLongOrNull()
            val seconds = cell(secondsColumn)?.toLongOrNull() ?: 0L
            val state = cell(stateColumn)
            val idle = isIdleInTransaction(state)
            val long = seconds >= LONG_TRANSACTION_SECONDS
            val accent = when {
                idle && long -> semantic.danger
                idle -> semantic.warning
                else -> semantic.textSecondary
            }
            val query = cell(queryColumn)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, Shapes.card)
                    .border(1.dp, if (idle) accent else semantic.hairline, Shapes.card)
                    .padding(horizontal = 14.dp, vertical = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Text("#${id ?: "?"}", style = MonoStyles.cell.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        listOfNotNull(cell(userColumn), cell(dbColumn)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = semantic.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        MetricFormat.duration(seconds),
                        style = MonoStyles.cell.copy(fontWeight = FontWeight.SemiBold),
                        color = if (idle) accent else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (state != null) {
                    // The colour is never the only sign: the words are in the badge.
                    InfoBadge(state, if (idle) accent else semantic.success)
                }
                if (query != null) {
                    Text(
                        highlight.filter(AnnotatedString(query)).text,
                        style = MonoStyles.cell.copy(fontSize = 12.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = Spacing.s),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.weight(1f))
                    if (query != null) {
                        IconButton(onClick = { onOpenInEditor(query) }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.server_open_in_editor),
                                tint = semantic.textSecondary,
                            )
                        }
                    }
                    if (id != null) {
                        OutlinedButton(
                            onClick = {
                                onKill(
                                    id,
                                    table.columns.indices.filter { it != idColumn }.joinToString("\n") { index ->
                                        "${table.columns[index].label}: ${row.getOrNull(index)?.asText().orEmpty()}"
                                    },
                                )
                            },
                            shape = Shapes.button,
                            border = BorderStroke(1.dp, semantic.danger.copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = semantic.danger),
                            contentPadding = PaddingValues(horizontal = Spacing.m),
                            modifier = Modifier.height(40.dp),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(killLabel), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
