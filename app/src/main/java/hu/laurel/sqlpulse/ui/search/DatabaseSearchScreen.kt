package hu.laurel.sqlpulse.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.withStyle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.search.DatabaseSearch
import hu.laurel.sqlpulse.data.search.SearchMode
import hu.laurel.sqlpulse.data.search.SearchRow
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.components.InfoBadge
import hu.laurel.sqlpulse.ui.components.SectionCaption
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors

/**
 * "Search the whole database": type a value, see which tables and columns hold it.
 *
 * A tap on a hit opens that table; the table page cannot be pre-filtered from here, so the row's
 * key is shown on the hit to find it by.
 */
@Composable
fun DatabaseSearchScreen(
    onBack: () -> Unit,
    onOpenTable: (database: String, table: String) -> Unit,
    viewModel: DatabaseSearchViewModel = hiltViewModel(),
) {
    DatabaseSearchScreenContent(onBack = onBack, onOpenTable = onOpenTable, viewModel = viewModel)
}

/** The screen itself, drawn from whatever [DatabaseSearchController] it is handed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseSearchScreenContent(
    onBack: () -> Unit,
    onOpenTable: (database: String, table: String) -> Unit,
    viewModel: DatabaseSearchController,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = {
                    Column {
                        Text(stringResource(R.string.dbsearch_title), maxLines = 1)
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
            )
        },
    ) { padding ->
        if (!state.connected) {
            EmptyState(
                title = stringResource(R.string.dbsearch_no_session_title),
                body = stringResource(R.string.schema_no_session_body),
                actionLabel = stringResource(R.string.cancel),
                onAction = onBack,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xl),
        ) {
            item { SearchControls(state, viewModel) }

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

            if (state.running || state.searched) {
                item { Progress(state) }
            }

            if (state.results.isNotEmpty()) {
                items(state.results, key = { it.table }) { hits ->
                    TableGroup(
                        hits = hits,
                        term = state.term,
                        mode = state.mode,
                        onOpen = { onOpenTable(state.database.orEmpty(), hits.table) },
                    )
                }
            } else if (!state.running && state.searched && state.error == null) {
                item {
                    EmptyState(
                        title = stringResource(R.string.dbsearch_summary_none),
                        body = stringResource(R.string.dbsearch_none_body),
                        actionLabel = stringResource(R.string.dbsearch_mode_contains),
                        onAction = { viewModel.setMode(SearchMode.CONTAINS) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (!state.searched) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        Text(
                            stringResource(R.string.dbsearch_idle_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.dbsearch_idle_body, state.database.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = semantic.textSecondary,
                        )
                    }
                }
            }

            if (state.skipped.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        SectionCaption(stringResource(R.string.dbsearch_skipped_title, state.skipped.size))
                        state.skipped.forEach { skipped ->
                            Text(
                                text = skipped.table + (skipped.reason?.let { " — " + it.lineSequence().first() } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchControls(state: DatabaseSearchUiState, controller: DatabaseSearchController) {
    val semantic = LocalSemanticColors.current
    Column {
        OutlinedTextField(
            value = state.term,
            onValueChange = controller::setTerm,
            placeholder = { Text(stringResource(R.string.dbsearch_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (state.term.isNotEmpty() && !state.running) {
                {
                    IconButton(onClick = { controller.setTerm("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dbsearch_clear))
                    }
                }
            } else {
                null
            },
            singleLine = true,
            enabled = !state.running,
            textStyle = MonoStyles.cell,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { controller.start() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            items(SearchMode.entries) { mode ->
                FilterChip(
                    selected = mode == state.mode,
                    onClick = { controller.setMode(mode) },
                    enabled = !state.running,
                    label = {
                        Text(
                            stringResource(
                                if (mode == SearchMode.CONTAINS) R.string.dbsearch_mode_contains
                                else R.string.dbsearch_mode_exact,
                            ),
                        )
                    },
                )
            }
            items(TABLE_LIMITS) { limit ->
                FilterChip(
                    selected = limit == state.tableLimit,
                    onClick = { controller.setTableLimit(limit) },
                    enabled = !state.running,
                    label = {
                        Text(
                            if (limit == 0) stringResource(R.string.dbsearch_tables_all)
                            else stringResource(R.string.dbsearch_tables_limit, limit),
                        )
                    },
                )
            }
            item {
                FilterChip(
                    selected = state.skipLargeTables,
                    onClick = { controller.setSkipLargeTables(!state.skipLargeTables) },
                    enabled = !state.running,
                    label = { Text(stringResource(R.string.dbsearch_skip_large)) },
                )
            }
        }

        if (state.production) {
            Text(
                text = stringResource(R.string.dbsearch_production_warning),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.production,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = Spacing.s)
                    .background(semantic.production.copy(alpha = 0.12f), MaterialTheme.shapes.medium)
                    .padding(Spacing.m),
            )
        }

        val numbers = if (DatabaseSearch.isNumeric(state.term)) stringResource(R.string.dbsearch_scope_numbers) else ""
        Text(
            text = stringResource(R.string.dbsearch_scope, numbers, state.rowsPerTable),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )

        if (state.running) {
            OutlinedButton(
                onClick = controller::cancel,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
            ) { Text(stringResource(R.string.dbsearch_stop)) }
        } else {
            Button(
                onClick = controller::start,
                enabled = state.canStart,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
            ) { Text(stringResource(R.string.dbsearch_start)) }
        }
    }
}

@Composable
private fun Progress(state: DatabaseSearchUiState) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (state.running) {
            if (state.tablesTotal > 0) {
                LinearProgressIndicator(
                    progress = { state.tablesDone.toFloat() / state.tablesTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = state.currentTable?.let { stringResource(R.string.dbsearch_reading, it) }.orEmpty(),
                    style = MonoStyles.cell.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    color = semantic.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.dbsearch_progress, state.tablesDone, state.tablesTotal),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }
        }
        if (state.matchCount > 0) {
            Text(
                text = stringResource(R.string.dbsearch_summary, state.matchCount, state.results.size),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (state.notSearched > 0) {
            Text(
                text = stringResource(R.string.dbsearch_not_searched, state.notSearched),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
        }
    }
}

@Composable
private fun TableGroup(hits: TableHits, term: String, mode: SearchMode, onOpen: () -> Unit) {
    val semantic = LocalSemanticColors.current
    HairlineCard(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Column(modifier = Modifier.clickable(onClick = onOpen)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                modifier = Modifier.fillMaxWidth().padding(Spacing.m),
            ) {
                Text(
                    text = hits.table,
                    style = MonoStyles.cell.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                InfoBadge(
                    text = if (hits.capped) stringResource(R.string.dbsearch_capped, hits.rows.size)
                    else stringResource(R.string.dbsearch_rows_in_table, hits.rows.size),
                    color = if (hits.capped) semantic.warning else MaterialTheme.colorScheme.primary,
                )
            }
            hits.rows.forEach { row ->
                HorizontalDivider(color = semantic.hairline)
                HitRow(row, term, mode)
            }
        }
    }
}

@Composable
private fun HitRow(row: SearchRow, term: String, mode: SearchMode) {
    val semantic = LocalSemanticColors.current
    val highlight = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (row.key.isNotEmpty()) {
            Text(
                text = row.key.joinToString("  ") { (name, value) -> "$name = ${value ?: "NULL"}" },
                style = MonoStyles.cell.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                color = semantic.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The cells that really hold the term; if the server matched through its collation and the
        // app cannot see why (é for e), every searched cell is shown rather than none.
        val matching = row.cells.filter { DatabaseSearch.matchRange(it.second, term, mode) != null }
        (matching.ifEmpty { row.cells }).forEach { (column, value) ->
            val (text, range) = DatabaseSearch.snippet(value, DatabaseSearch.matchRange(value, term, mode))
            Text(text = column, style = MaterialTheme.typography.labelSmall, color = semantic.textSecondary)
            Text(
                text = buildAnnotatedString {
                    if (range == null) {
                        append(text)
                    } else {
                        append(text.substring(0, range.first))
                        withStyle(
                            SpanStyle(
                                background = highlight.copy(alpha = 0.28f),
                                color = highlight,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        ) { append(text.substring(range.first, range.last + 1)) }
                        append(text.substring(range.last + 1))
                    }
                },
                style = MonoStyles.cell,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val TABLE_LIMITS = listOf(50, 200, 0)
