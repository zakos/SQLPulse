package hu.laurel.sqlpulse.ui.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.RedundantIndex
import hu.laurel.sqlpulse.data.schema.StorageFormat
import hu.laurel.sqlpulse.data.schema.StorageSnapshot
import hu.laurel.sqlpulse.data.schema.StorageSort
import hu.laurel.sqlpulse.data.schema.StorageSource
import hu.laurel.sqlpulse.data.schema.StorageTable
import hu.laurel.sqlpulse.data.schema.StorageTotals
import hu.laurel.sqlpulse.data.schema.UnavailableKind
import hu.laurel.sqlpulse.data.schema.UnusedIndexes
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.components.InfoBadge
import hu.laurel.sqlpulse.ui.components.SectionCaption
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors
import java.util.Locale

/** Table and index sizes of the current database, and the indexes that earn their space least. */
@Composable
fun StorageScreen(
    onBack: () -> Unit,
    onOpenTable: (database: String, table: String) -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    StorageScreenContent(onBack = onBack, onOpenTable = onOpenTable, viewModel = viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreenContent(
    onBack: () -> Unit,
    onOpenTable: (database: String, table: String) -> Unit,
    viewModel: StorageController,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = {
                    Column {
                        Text(stringResource(R.string.storage_title), maxLines = 1)
                        state.database?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = semantic.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (state.connected) {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val snapshot = state.snapshot
        when {
            !state.connected -> EmptyState(
                title = stringResource(R.string.storage_no_session_title),
                body = stringResource(R.string.schema_no_session_body),
                actionLabel = stringResource(R.string.cancel),
                onAction = onBack,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            state.database == null -> EmptyState(
                title = stringResource(R.string.storage_no_database_title),
                body = stringResource(R.string.storage_no_database_body),
                actionLabel = stringResource(R.string.cancel),
                onAction = onBack,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            snapshot == null -> Column(modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.l)) {
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.loading) CircularProgressIndicator()
            }

            else -> StorageList(
                state = state,
                snapshot = snapshot,
                locale = locale,
                controller = viewModel,
                onOpenTable = onOpenTable,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun StorageList(
    state: StorageUiState,
    snapshot: StorageSnapshot,
    locale: Locale,
    controller: StorageController,
    onOpenTable: (database: String, table: String) -> Unit,
    modifier: Modifier,
) {
    val semantic = LocalSemanticColors.current
    val tables = state.tables
    val largest = tables.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1) ?: 1
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = Spacing.xl)) {
        item { Totals(StorageTotals.of(snapshot.tables), locale) }

        item {
            Text(
                stringResource(R.string.storage_estimate_note),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                items(StorageSort.entries) { sort ->
                    FilterChip(
                        selected = sort == state.sort,
                        onClick = { controller.setSort(sort) },
                        label = { Text(stringResource(sort.labelRes())) },
                    )
                }
            }
        }

        state.error?.let {
            item {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
        }

        if (!snapshot.indexSizesAvailable) {
            item {
                Text(
                    stringResource(R.string.storage_index_sizes_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
        }

        if (tables.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.storage_empty_title),
                    body = stringResource(R.string.storage_empty_body),
                    actionLabel = stringResource(R.string.refresh),
                    onAction = controller::refresh,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            items(tables, key = { it.name }) { table ->
                TableRow(
                    table = table,
                    largest = largest,
                    locale = locale,
                    onClick = { onOpenTable(snapshot.database, table.name) },
                )
            }
        }

        item { UnusedSection(snapshot.unused, snapshot.uptimeSeconds) }
        item { RedundantSection(snapshot.redundant) }

        item {
            Text(
                stringResource(R.string.storage_ddl_note),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            )
        }
    }
}

@Composable
private fun Totals(totals: StorageTotals, locale: Locale) {
    HairlineCard(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Column(
            modifier = Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionCaption(stringResource(R.string.storage_title))
                    Text(
                        StorageFormat.bytes(totals.totalBytes, locale),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Text(
                    pluralStringResource(R.plurals.storage_table_count, totals.tableCount, totals.tableCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalSemanticColors.current.textSecondary,
                )
            }
            SizeBar(totals.dataBytes, totals.indexBytes, fraction = 1f)
            Row(modifier = Modifier.fillMaxWidth()) {
                Stat(stringResource(R.string.storage_total_data), totals.dataBytes, dataColor(), locale, Modifier.weight(1f))
                Stat(stringResource(R.string.storage_total_index), totals.indexBytes, indexColor(), locale, Modifier.weight(1f))
                Stat(
                    stringResource(R.string.storage_total_free),
                    totals.freeBytes,
                    LocalSemanticColors.current.textSecondary,
                    locale,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, bytes: Long, swatch: Color, locale: Locale, modifier: Modifier) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(swatch, RoundedCornerShape(2.dp)))
            Text(label, style = MaterialTheme.typography.labelMedium, color = LocalSemanticColors.current.textSecondary)
        }
        Text(StorageFormat.bytes(bytes, locale), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun dataColor() = MaterialTheme.colorScheme.primary

@Composable
private fun indexColor() = MaterialTheme.colorScheme.tertiary

/**
 * A bar whose length is [fraction] of the available width and whose two segments are data and
 * indexes. The length compares tables with each other; the split says what the space is made of.
 */
@Composable
private fun SizeBar(dataBytes: Long, indexBytes: Long, fraction: Float) {
    val total = (dataBytes + indexBytes).coerceAtLeast(1)
    val track = LocalSemanticColors.current.hairline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(track.copy(alpha = 0.35f), RoundedCornerShape(4.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .height(8.dp)
                .background(Color.Transparent, RoundedCornerShape(4.dp)),
        ) {
            val dataWeight = dataBytes.toFloat() / total
            val indexWeight = indexBytes.toFloat() / total
            if (dataWeight > 0f) {
                Box(Modifier.weight(dataWeight).height(8.dp).background(dataColor(), RoundedCornerShape(4.dp)))
            }
            if (indexWeight > 0f) {
                Box(Modifier.weight(indexWeight).height(8.dp).background(indexColor(), RoundedCornerShape(4.dp)))
            }
            // A table with nothing in it still gets a sliver, or the bar looks like a missing row.
            if (dataWeight == 0f && indexWeight == 0f) Box(Modifier.weight(1f).height(8.dp))
        }
    }
}

@Composable
private fun TableRow(table: StorageTable, largest: Long, locale: Locale, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                table.name,
                style = MonoStyles.cell,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (table.autoIncrementWarning) {
                InfoBadge(
                    stringResource(
                        R.string.storage_ai_warning,
                        StorageFormat.percent(table.autoIncrementUsage ?: 0.0, locale),
                    ),
                    semantic.warning,
                )
            }
            Text(StorageFormat.bytes(table.totalBytes, locale), style = MaterialTheme.typography.titleSmall)
        }
        SizeBar(table.dataBytes, table.indexBytes, fraction = table.totalBytes.toFloat() / largest)
        Text(
            buildString {
                append(StorageFormat.bytes(table.dataBytes, locale)).append(" + ")
                append(StorageFormat.bytes(table.indexBytes, locale))
                if (table.freeBytes > 0) {
                    append(" · ").append(stringResource(R.string.storage_free_space, StorageFormat.bytes(table.freeBytes, locale)))
                }
                table.rowsEstimate?.let {
                    append(" · ").append(stringResource(R.string.storage_rows_estimate, StorageFormat.count(it, locale)))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
        val facts = listOfNotNull(
            table.engine,
            table.rowFormat,
            table.updateTime?.take(10)?.let { stringResource(R.string.storage_updated, it) },
        ).joinToString(" · ")
        if (facts.isNotEmpty()) {
            Text(facts, style = MaterialTheme.typography.bodySmall, color = semantic.textSecondary)
        }
        if (table.indexes.isNotEmpty()) {
            Text(
                table.indexes.take(3).joinToString("  ") { "${it.index} ${StorageFormat.bytes(it.bytes, locale)}" },
                style = MonoStyles.cell.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                color = semantic.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UnusedSection(source: StorageSource<UnusedIndexes>, uptimeSeconds: Long?) {
    val semantic = LocalSemanticColors.current
    SectionBlock(stringResource(R.string.storage_unused_title)) {
        when (source) {
            is StorageSource.Unavailable -> Unavailable(source)
            is StorageSource.Loaded -> {
                Text(
                    when {
                        source.value.fromUserstat -> stringResource(R.string.storage_unused_note_userstat)
                        uptimeSeconds != null ->
                            stringResource(R.string.storage_unused_note, uptimeText(uptimeSeconds))
                        else -> stringResource(R.string.storage_unused_note_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
                if (source.value.indexes.isEmpty()) {
                    Text(stringResource(R.string.storage_unused_none), style = MaterialTheme.typography.bodyMedium)
                }
                source.value.indexes.forEach {
                    Text("${it.table}.${it.index}", style = MonoStyles.cell, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RedundantSection(source: StorageSource<List<RedundantIndex>>) {
    val semantic = LocalSemanticColors.current
    SectionBlock(stringResource(R.string.storage_redundant_title)) {
        when (source) {
            is StorageSource.Unavailable -> Unavailable(source)
            is StorageSource.Loaded -> {
                if (source.value.isEmpty()) {
                    Text(stringResource(R.string.storage_redundant_none), style = MaterialTheme.typography.bodyMedium)
                }
                source.value.forEach {
                    Column {
                        Text("${it.table}.${it.index} (${it.columns})", style = MonoStyles.cell)
                        Text(
                            stringResource(R.string.storage_redundant_covered, "${it.coveredBy} (${it.coveredByColumns})"),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionBlock(title: String, content: @Composable () -> Unit) {
    HairlineCard(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SectionCaption(title)
            content()
        }
    }
}

@Composable
private fun Unavailable(source: StorageSource.Unavailable) {
    Text(
        when (source.kind) {
            UnavailableKind.NOT_ON_SERVER -> stringResource(R.string.storage_unavailable_not_on_server)
            UnavailableKind.USERSTAT_OFF -> stringResource(R.string.storage_unavailable_userstat)
            UnavailableKind.PERFORMANCE_SCHEMA_OFF -> stringResource(R.string.storage_unavailable_perfschema)
            UnavailableKind.FAILED -> stringResource(R.string.storage_unavailable_failed, source.detail.orEmpty())
        },
        style = MaterialTheme.typography.bodyMedium,
        color = LocalSemanticColors.current.textSecondary,
    )
}

@Composable
private fun uptimeText(seconds: Long): String {
    val parts = StorageFormat.uptime(seconds)
    return when {
        parts.days > 0 -> stringResource(R.string.storage_uptime_days, parts.days, parts.hours)
        parts.hours > 0 -> stringResource(R.string.storage_uptime_hours, parts.hours, parts.minutes)
        else -> stringResource(R.string.storage_uptime_minutes, parts.minutes)
    }
}

private fun StorageSort.labelRes() = when (this) {
    StorageSort.TOTAL -> R.string.storage_sort_total
    StorageSort.ROWS -> R.string.storage_sort_rows
    StorageSort.FREE -> R.string.storage_sort_free
    StorageSort.NAME -> R.string.storage_sort_name
}
