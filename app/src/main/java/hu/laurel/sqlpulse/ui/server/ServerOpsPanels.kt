package hu.laurel.sqlpulse.ui.server

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.ReplicationChannel
import hu.laurel.sqlpulse.data.schema.ReplicationHealth
import hu.laurel.sqlpulse.data.schema.ReplicationReport
import hu.laurel.sqlpulse.data.schema.ReplicationStatus
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.schema.SlowStatement
import hu.laurel.sqlpulse.data.schema.SlowStatements
import hu.laurel.sqlpulse.data.schema.SlowStatementsReport
import hu.laurel.sqlpulse.data.schema.StorageFormat
import hu.laurel.sqlpulse.data.sql.DigestPlaceholders
import hu.laurel.sqlpulse.data.schema.ThreadState
import hu.laurel.sqlpulse.ui.appLocale
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.components.InfoBadge
import hu.laurel.sqlpulse.ui.components.SectionCaption
import hu.laurel.sqlpulse.ui.copyToClipboard
import hu.laurel.sqlpulse.ui.durationUnits
import hu.laurel.sqlpulse.ui.query.SqlVisualTransformation
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.ui.engine.LocalEngineFeatures
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

// The two read-only operations panels of the server screen: replication cards and the slowest
// statements. Kept out of ServerScreen.kt, which is already long.

private val ListPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.xl)

@Composable
private fun healthColor(health: ReplicationHealth): Color {
    val semantic = LocalSemanticColors.current
    return when (health) {
        ReplicationHealth.OK -> semantic.success
        ReplicationHealth.LAGGING -> semantic.warning
        ReplicationHealth.FAR_BEHIND, ReplicationHealth.STOPPED, ReplicationHealth.ERROR -> semantic.danger
        ReplicationHealth.UNKNOWN -> semantic.textSecondary
    }
}

@StringRes
private fun ReplicationHealth.labelRes(): Int = when (this) {
    ReplicationHealth.OK -> R.string.repl_health_ok
    ReplicationHealth.LAGGING -> R.string.repl_health_lagging
    ReplicationHealth.FAR_BEHIND -> R.string.repl_health_far_behind
    ReplicationHealth.STOPPED -> R.string.repl_health_stopped
    ReplicationHealth.ERROR -> R.string.repl_health_error
    ReplicationHealth.UNKNOWN -> R.string.repl_health_unknown
}

@StringRes
private fun ThreadState.labelRes(): Int = when (this) {
    ThreadState.RUNNING -> R.string.repl_thread_running
    ThreadState.CONNECTING -> R.string.repl_thread_connecting
    ThreadState.STOPPED -> R.string.repl_thread_stopped
    ThreadState.UNKNOWN -> R.string.repl_thread_unknown
}

/** Cards for the "not a replica" and "not allowed" answers, and for each channel. */
@Composable
fun ReplicationPanel(
    report: ReplicationReport,
    raw: Boolean,
    onRawChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    rawContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = LocalEngineFeatures.current.engine
    when (report) {
        ReplicationReport.NotReplica -> EmptyState(
            title = stringResource(R.string.repl_not_replica_title),
            body = stringResource(
                if (engine == DatabaseEngine.SQLSERVER) R.string.repl_not_replica_mssql_body else R.string.repl_not_replica_body,
            ),
            actionLabel = stringResource(R.string.refresh),
            onAction = onRefresh,
            modifier = modifier.fillMaxWidth(),
        )

        ReplicationReport.NoPrivilege -> EmptyState(
            title = stringResource(R.string.repl_no_privilege_title),
            body = stringResource(
                when (engine) {
                    DatabaseEngine.POSTGRESQL -> R.string.repl_no_privilege_pg_body
                    DatabaseEngine.SQLSERVER -> R.string.repl_no_privilege_mssql_body
                    else -> R.string.repl_no_privilege_body
                },
            ),
            actionLabel = stringResource(R.string.refresh),
            onAction = onRefresh,
            modifier = modifier.fillMaxWidth(),
        )

        is ReplicationReport.Channels -> Column(modifier) {
            Row(
                modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                // The cards leave some columns out (binlog filters, SSL options, …); the raw
                // table has every one of them.
                FilterChip(
                    selected = raw,
                    onClick = { onRawChange(!raw) },
                    label = { Text(stringResource(R.string.repl_raw)) },
                )
            }
            if (raw) {
                rawContent()
            } else {
                LazyColumn(
                    contentPadding = ListPadding,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(report.channels) { channel -> ReplicationCard(channel) }
                }
            }
        }
    }
}

@Composable
private fun ReplicationCard(channel: ReplicationChannel) {
    val semantic = LocalSemanticColors.current
    val health = channel.health
    val colour = healthColor(health)
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, Shapes.card)
            .border(1.dp, if (health == ReplicationHealth.OK) semantic.hairline else colour, Shapes.card)
            .padding(horizontal = 14.dp, vertical = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            InfoBadge(stringResource(health.labelRes()), colour)
            Text(
                channel.name.ifEmpty { stringResource(R.string.repl_default_channel) },
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    channel.lagSeconds?.let { ReplicationStatus.formatSeconds(it, durationUnits(), appLocale()) }
                        ?: stringResource(R.string.repl_lag_unknown),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (channel.lagSeconds == null) semantic.textSecondary else colour,
                )
                Text(
                    stringResource(R.string.repl_lag),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }
            val source = listOfNotNull(
                channel.sourceHost?.let { host -> channel.sourcePort?.let { "$host:$it" } ?: host },
            ).firstOrNull()
            if (source != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        source,
                        style = MonoStyles.cell,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            stringResource(if (channel.peerIsDownstream) R.string.repl_standby else R.string.repl_source),
                            channel.sourceUser,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.textSecondary,
                    )
                }
            }
        }

        // PostgreSQL and SQL Server have no IO/SQL thread pair; their state is a word and a few figures.
        if (channel.showThreads) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ThreadChip(stringResource(R.string.repl_io_thread), channel.ioThread)
                ThreadChip(stringResource(R.string.repl_sql_thread), channel.sqlThread)
            }
        }
        if (channel.details.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                channel.details.forEach { fact ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        Text(
                            fact.label,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = semantic.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            fact.value,
                            style = MonoStyles.cell.copy(fontSize = 12.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        channel.sqlState?.takeIf { channel.showThreads }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = semantic.textSecondary) }
        if (channel.sqlDelaySeconds > 0) {
            Text(
                stringResource(R.string.repl_delay_note, channel.sqlDelaySeconds),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
        }

        channel.lastError?.let { error ->
            SectionCaption(stringResource(R.string.repl_last_error))
            // Selectable: an error text is what gets pasted into a ticket or a search.
            SelectionContainer {
                Text(
                    error,
                    style = MonoStyles.cell.copy(fontSize = 12.sp),
                    color = semantic.danger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = Spacing.s),
                )
            }
        }

        if (channel.hasPositions) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = Spacing.xs),
            ) {
                Text(
                    stringResource(R.string.repl_positions),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
                )
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    PositionLine(R.string.repl_retrieved_gtid, channel.retrievedGtidSet)
                    PositionLine(R.string.repl_executed_gtid, channel.executedGtidSet)
                    PositionLine(
                        R.string.repl_source_log,
                        listOfNotNull(channel.sourceLogFile, channel.readSourceLogPos?.toString()).joinToString(" : ")
                            .ifEmpty { null },
                    )
                    PositionLine(
                        R.string.repl_relay_log,
                        listOfNotNull(channel.relayLogFile, channel.relayLogPos?.toString()).joinToString(" : ")
                            .ifEmpty { null },
                    )
                    PositionLine(R.string.repl_exec_pos, channel.execSourceLogPos?.toString())
                }
            }
        }
    }
}

@Composable
private fun ThreadChip(label: String, state: ThreadState) {
    val semantic = LocalSemanticColors.current
    val colour = when (state) {
        ThreadState.RUNNING -> semantic.success
        ThreadState.CONNECTING -> semantic.warning
        ThreadState.STOPPED -> semantic.danger
        ThreadState.UNKNOWN -> semantic.textSecondary
    }
    InfoBadge("$label · ${stringResource(state.labelRes())}", colour)
}

@Composable
private fun PositionLine(@StringRes label: Int, value: String?) {
    if (value == null) return
    Column {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = LocalSemanticColors.current.textSecondary,
        )
        SelectionContainer { Text(value, style = MonoStyles.cell.copy(fontSize = 12.sp)) }
    }
}

@StringRes
private fun SlowSort.labelRes(): Int = when (this) {
    SlowSort.TOTAL -> R.string.slow_sort_total
    SlowSort.AVERAGE -> R.string.slow_sort_average
    SlowSort.COUNT -> R.string.slow_sort_count
}

/** The slowest statements, or the reason there are none to show. */
@Composable
fun SlowPanel(
    report: SlowStatementsReport,
    sort: SlowSort,
    onSort: (SlowSort) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenInEditor: (String) -> Unit = {},
) {
    val engine = LocalEngineFeatures.current.engine
    when (report) {
        SlowStatementsReport.ExtensionMissing -> SlowEmpty(R.string.slow_ext_missing_title, R.string.slow_ext_missing_body, onRefresh, modifier)
        SlowStatementsReport.ExtensionNotLoaded -> SlowEmpty(R.string.slow_ext_not_loaded_title, R.string.slow_ext_not_loaded_body, onRefresh, modifier)
        SlowStatementsReport.PerformanceSchemaOff -> SlowEmpty(R.string.slow_off_title, R.string.slow_off_body, onRefresh, modifier)
        SlowStatementsReport.NoPrivilege -> SlowEmpty(
            R.string.slow_no_privilege_title,
            if (engine == DatabaseEngine.SQLSERVER) R.string.slow_no_privilege_mssql_body else R.string.slow_no_privilege_body,
            onRefresh,
            modifier,
        )
        SlowStatementsReport.Unsupported -> SlowEmpty(R.string.slow_unsupported_title, R.string.slow_unsupported_body, onRefresh, modifier)
        is SlowStatementsReport.Rows -> Column(modifier) {
            Row(
                modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                SlowSort.entries.forEach { option ->
                    FilterChip(
                        selected = option == sort,
                        onClick = { onSort(option) },
                        label = { Text(stringResource(option.labelRes())) },
                    )
                }
            }
            if (report.statements.isEmpty()) {
                SlowEmpty(R.string.slow_empty_title, R.string.slow_empty_body, onRefresh, Modifier)
            } else {
                val semantic = LocalSemanticColors.current
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
                    contentPadding = ListPadding,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            stringResource(
                                if (report.statements.firstOrNull()?.dollarPlaceholders == true) R.string.slow_hint_dollar else R.string.slow_hint,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.textSecondary,
                        )
                    }
                    items(report.statements) { statement -> SlowCard(statement, highlight, onOpenInEditor) }
                }
            }
        }
    }
}

@Composable
private fun SlowEmpty(@StringRes title: Int, @StringRes body: Int, onRefresh: () -> Unit, modifier: Modifier) {
    EmptyState(
        title = stringResource(title),
        body = stringResource(body),
        actionLabel = stringResource(R.string.refresh),
        onAction = onRefresh,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SlowCard(statement: SlowStatement, highlight: SqlVisualTransformation, onOpenInEditor: (String) -> Unit) {
    val semantic = LocalSemanticColors.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    // The app's language for the grouping and the duration words: "1 204 futás", "2 ó 20 p".
    val locale = appLocale()
    val units = durationUnits()
    fun count(value: Long) = StorageFormat.count(value, locale)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, Shapes.card)
            .border(1.dp, semantic.hairline, Shapes.card)
            // Open, never run: a digest has its values replaced by ?, which become :p1, :p2 … so
            // the editor's own parameter dialog asks for them, and running it unasked against a
            // production server is not something a list tap should do.
            .clickable { onOpenInEditor(DigestPlaceholders.toNamed(statement.digestText, statement.dollarPlaceholders).sql) }
            .padding(horizontal = 14.dp, vertical = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    SlowStatements.formatPicos(statement.totalPicos, locale, units),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    stringResource(R.string.slow_total),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    SlowStatements.formatPicos(statement.avgPicos, locale, units),
                    style = MonoStyles.cell.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    stringResource(R.string.slow_avg),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }
        }
        Text(
            highlight.filter(AnnotatedString(statement.digestText)).text,
            style = MonoStyles.cell.copy(fontSize = 12.sp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = Spacing.s),
        )
        Text(
            listOfNotNull(
                statement.schema,
                stringResource(R.string.slow_calls, count(statement.count)),
                // MySQL counts rows examined and sent; the others only the rows returned, and say how much they read.
                statement.rowsExamined?.let {
                    stringResource(R.string.slow_rows, count(it), count(statement.rowsSent))
                } ?: stringResource(R.string.slow_rows_sent, count(statement.rowsSent)),
                statement.blocksRead?.let { stringResource(R.string.slow_blocks, count(it)) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = semantic.textSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (statement.noIndexUsed > 0) {
                InfoBadge(stringResource(R.string.slow_no_index, count(statement.noIndexUsed)), semantic.warning)
            }
            // PostgreSQL does not say when a statement was first or last seen.
            val seen = if (statement.firstSeen == null && statement.lastSeen == null) {
                ""
            } else {
                stringResource(R.string.slow_seen, shortTime(statement.firstSeen), shortTime(statement.lastSeen))
            }
            Text(
                seen,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = semantic.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (copied) {
                Text(
                    stringResource(R.string.slow_copied),
                    style = MaterialTheme.typography.labelSmall,
                    color = semantic.success,
                )
            }
            // Copying stays one tap away, as the secondary action.
            IconButton(
                onClick = {
                    context.copyToClipboard(statement.digestText)
                    copied = true
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.slow_copy),
                    tint = semantic.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** `2026-09-30 12:04:11.123456` → `09-30 12:04`; the year and seconds are noise on a phone. */
private fun shortTime(value: String?): String =
    value?.takeIf { it.length >= 16 }?.substring(5, 16) ?: value.orEmpty()
