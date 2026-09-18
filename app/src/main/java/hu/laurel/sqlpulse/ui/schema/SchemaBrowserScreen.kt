package hu.laurel.sqlpulse.ui.schema

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.ObjectKind
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.schema.TableKind
import hu.laurel.sqlpulse.data.schema.formatByteSize
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.components.isWideWindow
import hu.laurel.sqlpulse.ui.copyToClipboard
import hu.laurel.sqlpulse.ui.explain
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
    onOpenQuery: () -> Unit,
    onOpenServer: () -> Unit,
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
                    IconButton(onClick = onOpenServer) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = stringResource(R.string.server_title),
                        )
                    }
                    IconButton(onClick = onOpenQuery) {
                        Icon(Icons.Default.Code, contentDescription = stringResource(R.string.query_title))
                    }
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

                else -> SchemaBody(
                    wide = isWideWindow(),
                    databases = state.databases,
                    selectedDatabase = state.selectedDatabase,
                    onSelectDatabase = viewModel::selectDatabase,
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.l),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        items(ObjectKind.entries) { kind ->
                            FilterChip(
                                selected = kind == state.objectKind,
                                onClick = { viewModel.selectObjectKind(kind) },
                                label = { Text(stringResource(kind.labelRes())) },
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

                    if (state.isEmpty) {
                        EmptyState(
                            title = stringResource(state.objectKind.emptyTitleRes()),
                            body = stringResource(R.string.schema_no_tables_body),
                            actionLabel = stringResource(R.string.schema_clear_filter),
                            onAction = { viewModel.setFilter("") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            when (state.objectKind) {
                                ObjectKind.TABLES, ObjectKind.VIEWS ->
                                    items(state.visibleTables, key = { "${it.database}.${it.name}" }) { table ->
                                        TableRow(table) { onOpenTable(table.database, table.name) }
                                        HorizontalDivider(color = semantic.hairline)
                                    }

                                ObjectKind.ROUTINES ->
                                    items(state.visibleRoutines, key = { "${it.kind}.${it.name}" }) { routine ->
                                        ObjectRow(
                                            name = routine.name,
                                            detail = routine.comment,
                                            trailing = routine.returns
                                                ?.let { stringResource(R.string.schema_returns, it) }
                                                ?: stringResource(R.string.schema_procedure),
                                            onClick = { viewModel.showRoutine(routine) },
                                        )
                                        HorizontalDivider(color = semantic.hairline)
                                    }

                                ObjectKind.TRIGGERS ->
                                    items(state.visibleTriggers, key = { it.name }) { trigger ->
                                        ObjectRow(
                                            name = trigger.name,
                                            detail = "${trigger.timing} ${trigger.event}",
                                            trailing = trigger.table,
                                            onClick = { onOpenTable(state.selectedDatabase.orEmpty(), trigger.table) },
                                        )
                                        HorizontalDivider(color = semantic.hairline)
                                    }

                                ObjectKind.EVENTS ->
                                    items(state.visibleEvents, key = { it.name }) { event ->
                                        ObjectRow(
                                            name = event.name,
                                            detail = event.schedule,
                                            trailing = event.status,
                                            onClick = null,
                                        )
                                        HorizontalDivider(color = semantic.hairline)
                                    }
                            }
                        }
                    }
                }
            }

            state.routineDefinition?.let { definition ->
                val context = LocalContext.current
                RoutineSheet(
                    definition = definition,
                    onCopy = context::copyToClipboard,
                    onDismiss = viewModel::dismissRoutine,
                )
            }
        }
    }
}

/**
 * One column on a phone, two where there is room (research summary, §2.0).
 *
 * Wide, the databases move out of the chip row into a column of their own and stay visible while
 * the tables are read, which is the whole point of the extra width: switching database no longer
 * means scrolling a row of chips back into view.
 */
@Composable
private fun SchemaBody(
    wide: Boolean,
    databases: List<String>,
    selectedDatabase: String?,
    onSelectDatabase: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (wide) {
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.width(DATABASE_COLUMN_WIDTH).fillMaxHeight()) {
                items(databases) { database ->
                    DatabaseRow(
                        name = database,
                        selected = database == selectedDatabase,
                        onClick = { onSelectDatabase(database) },
                    )
                }
            }
            VerticalDivider()
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), content = content)
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                items(databases) { database ->
                    FilterChip(
                        selected = database == selectedDatabase,
                        onClick = { onSelectDatabase(database) },
                        label = { Text(database) },
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun DatabaseRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Text(
        text = name,
        style = MonoStyles.cell,
        color = if (selected) MaterialTheme.colorScheme.primary else semantic.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
    )
}

/** The routine's `SHOW CREATE`, or a line saying the body is not visible to this user. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineSheet(
    definition: RoutineDefinition,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.l)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = definition.routine.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (definition.sql.isNotBlank()) {
                    IconButton(onClick = { onCopy(definition.sql) }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.ddl_copy),
                        )
                    }
                }
            }
            if (definition.sql.isBlank()) {
                Text(
                    text = stringResource(R.string.schema_routine_hidden),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalSemanticColors.current.textSecondary,
                )
            } else {
                Text(
                    text = definition.sql,
                    style = MonoStyles.cell,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@StringRes
private fun ObjectKind.labelRes(): Int = when (this) {
    ObjectKind.TABLES -> R.string.schema_tables
    ObjectKind.VIEWS -> R.string.schema_views
    ObjectKind.ROUTINES -> R.string.schema_routines
    ObjectKind.TRIGGERS -> R.string.schema_triggers
    ObjectKind.EVENTS -> R.string.schema_events
}

@StringRes
private fun ObjectKind.emptyTitleRes(): Int = when (this) {
    ObjectKind.TABLES -> R.string.schema_no_tables_title
    ObjectKind.VIEWS -> R.string.schema_no_views_title
    ObjectKind.ROUTINES -> R.string.schema_no_routines_title
    ObjectKind.TRIGGERS -> R.string.schema_no_triggers_title
    ObjectKind.EVENTS -> R.string.schema_no_events_title
}

/** One line for a routine, trigger or event: name, what it does, and where it lives. */
@Composable
private fun ObjectRow(name: String, detail: String?, trailing: String?, onClick: (() -> Unit)?) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MonoStyles.cell)
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = semantic.textSecondary)
            }
        }
        trailing?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = semantic.textSecondary)
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
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (table.kind == TableKind.VIEW) {
                    stringResource(R.string.schema_view)
                } else {
                    table.approximateRows?.let { stringResource(R.string.schema_rows_approx, it) }.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
            // Engine and size only for real tables: a view has neither.
            val storage = listOfNotNull(
                table.engine?.takeIf { table.kind == TableKind.TABLE },
                table.totalBytes?.takeIf { table.kind == TableKind.TABLE }?.let { formatByteSize(it) },
            ).joinToString(" · ")
            if (storage.isNotBlank()) {
                Text(
                    storage,
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }
        }
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
            body = session.failure?.let { LocalContext.current.explain(it) } ?: session.message,
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

/** Wide enough for a database name, narrow enough to leave the tables the room. */
private val DATABASE_COLUMN_WIDTH = 200.dp
