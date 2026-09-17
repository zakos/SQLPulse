package hu.laurel.sqlpulse.ui.schema

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.schema.TableKind
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * Schema browser (§7.3): databases as chips, tables in a searchable list, one tap into the table
 * page. On a phone the tree is flat rather than nested — two levels are all MySQL has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaBrowserScreen(
    onBack: () -> Unit,
    onOpenTable: (database: String, table: String) -> Unit,
    viewModel: SchemaBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.schema_title))
                        (state.session as? SqlSessionState.Ready)?.let { ready ->
                            Text(
                                text = ready.connection.name +
                                    (ready.serverVersion?.let { " · MySQL $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.textSecondary,
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
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.session !is SqlSessionState.Ready -> SessionPlaceholder(state.session, onBack)

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        items(state.databases) { database ->
                            FilterChip(
                                selected = database == state.selectedDatabase,
                                onClick = { viewModel.selectDatabase(database) },
                                label = { Text(database) },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.filter,
                        onValueChange = viewModel::setFilter,
                        label = { Text(stringResource(R.string.schema_search)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.l),
                    )

                    state.error?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(Spacing.l),
                        )
                    }

                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.padding(Spacing.l))
                    }

                    val tables = state.visibleTables
                    if (tables.isEmpty() && !state.loading) {
                        EmptyState(
                            title = stringResource(R.string.schema_no_tables_title),
                            body = stringResource(R.string.schema_no_tables_body),
                            actionLabel = stringResource(R.string.schema_clear_filter),
                            onAction = { viewModel.setFilter("") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(tables, key = { "${it.database}.${it.name}" }) { table ->
                                TableRow(table) { onOpenTable(table.database, table.name) }
                                HorizontalDivider(color = semantic.hairline)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableRow(table: SchemaTable, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(table.name, style = MonoStyles.cell)
            table.comment?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = semantic.textSecondary)
            }
        }
        Text(
            text = if (table.kind == TableKind.VIEW) {
                stringResource(R.string.schema_view)
            } else {
                table.approximateRows?.let { stringResource(R.string.schema_rows_approx, it) }.orEmpty()
            },
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
}

@Composable
private fun SessionPlaceholder(session: SqlSessionState, onBack: () -> Unit) {
    when (session) {
        is SqlSessionState.Opening -> Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        is SqlSessionState.Failed -> EmptyState(
            title = stringResource(R.string.schema_no_session_title),
            body = session.message,
            actionLabel = stringResource(R.string.cancel),
            onAction = onBack,
            modifier = Modifier.fillMaxSize(),
        )

        else -> EmptyState(
            title = stringResource(R.string.schema_no_session_title),
            body = stringResource(R.string.schema_no_session_body),
            actionLabel = stringResource(R.string.cancel),
            onAction = onBack,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
