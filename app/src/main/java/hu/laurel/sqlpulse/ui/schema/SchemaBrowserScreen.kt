package hu.laurel.sqlpulse.ui.schema

import android.icu.text.CompactDecimalFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.schema.CacheAgeUnit
import hu.laurel.sqlpulse.data.schema.ObjectKind
import hu.laurel.sqlpulse.data.schema.SchemaCache
import hu.laurel.sqlpulse.data.schema.SchemaCacheRepository
import hu.laurel.sqlpulse.data.schema.SchemaRoutine
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.schema.TableKind
import hu.laurel.sqlpulse.data.schema.formatByteSize
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.ui.components.ConnectionTitle
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.components.isWideWindow
import hu.laurel.sqlpulse.ui.copyToClipboard
import hu.laurel.sqlpulse.ui.explain
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

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
    onOpenPulse: () -> Unit,
    onOpenMap: () -> Unit,
    viewModel: SchemaBrowserViewModel = hiltViewModel(),
    cacheViewModel: SchemaCacheMarkerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current

    // Which connection's cache the marker is reading. A lost session still knows its connection,
    // which is exactly the case the marker exists for.
    val connectionId = when (val session = state.session) {
        is SqlSessionState.Ready -> session.connection.id
        is SqlSessionState.Lost -> session.connection.id
        else -> null
    }
    val capturedAt by cacheViewModel.capturedAt.collectAsStateWithLifecycle()
    LaunchedEffect(connectionId, state.selectedDatabase) {
        cacheViewModel.watch(connectionId, state.selectedDatabase)
    }

    SchemaBrowserContent(
        state = state,
        capturedAt = capturedAt,
        actions = SchemaBrowserActions(
            onBack = onBack,
            onOpenTable = onOpenTable,
            onOpenQuery = onOpenQuery,
            onOpenServer = onOpenServer,
            onOpenPulse = onOpenPulse,
            onOpenMap = onOpenMap,
            onRefresh = viewModel::refresh,
            onSelectDatabase = viewModel::selectDatabase,
            onSelectObjectKind = viewModel::selectObjectKind,
            onFilter = viewModel::setFilter,
            onShowRoutine = viewModel::showRoutine,
            onDismissRoutine = viewModel::dismissRoutine,
        ),
    )
}

/** Everything the schema browser can ask for, so it can be drawn without its ViewModels. */
data class SchemaBrowserActions(
    val onBack: () -> Unit = {},
    val onOpenTable: (database: String, table: String) -> Unit = { _, _ -> },
    val onOpenQuery: () -> Unit = {},
    val onOpenServer: () -> Unit = {},
    val onOpenPulse: () -> Unit = {},
    val onOpenMap: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelectDatabase: (String) -> Unit = {},
    val onSelectObjectKind: (ObjectKind) -> Unit = {},
    val onFilter: (String) -> Unit = {},
    val onShowRoutine: (SchemaRoutine) -> Unit = {},
    val onDismissRoutine: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaBrowserContent(
    state: SchemaBrowserUiState,
    capturedAt: Long?,
    actions: SchemaBrowserActions,
) {
    val semantic = LocalSemanticColors.current
    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = {
                    val connection = when (val session = state.session) {
                        is SqlSessionState.Ready -> session.connection
                        is SqlSessionState.Lost -> session.connection
                        else -> null
                    }
                    ConnectionTitle(
                        name = connection?.name ?: stringResource(R.string.schema_title),
                        environment = ConnectionEnvironment.fromName(connection?.environment),
                        database = state.selectedDatabase,
                        databases = state.databases,
                        onSelectDatabase = actions.onSelectDatabase,
                        placeholder = stringResource(R.string.query_no_database),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = actions.onOpenMap) {
                        Icon(
                            Icons.Default.AccountTree,
                            contentDescription = stringResource(R.string.map_title),
                        )
                    }
                    IconButton(onClick = actions.onOpenPulse) {
                        Icon(
                            Icons.Default.MonitorHeart,
                            contentDescription = stringResource(R.string.pulse_title),
                        )
                    }
                    IconButton(onClick = actions.onOpenServer) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = stringResource(R.string.server_title),
                        )
                    }
                    IconButton(onClick = actions.onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
        // Writing a query is what one comes to a schema for, so it is the one big button.
        floatingActionButton = {
            if (state.session is SqlSessionState.Ready) {
                ExtendedFloatingActionButton(
                    onClick = actions.onOpenQuery,
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    text = { Text(stringResource(R.string.query_title)) },
                    shape = Shapes.card,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Above everything, never inside the list: a marker that scrolls away is a marker
            // that was not read.
            SchemaCacheMarker(
                live = state.session is SqlSessionState.Ready,
                capturedAt = capturedAt,
                onRefresh = actions.onRefresh,
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    // Without a session the placeholder is the whole screen — unless the cache
                    // has something, in which case the tables are browsable and the marker above
                    // is what says they are not live.
                    state.session !is SqlSessionState.Ready && state.databases.isEmpty() ->
                        SessionPlaceholder(state.session, actions.onBack)

                    else -> SchemaBody(
                        wide = isWideWindow(),
                        databases = state.databases,
                        selectedDatabase = state.selectedDatabase,
                        onSelectDatabase = actions.onSelectDatabase,
                    ) {
                        OutlinedTextField(
                            value = state.filter,
                            onValueChange = actions.onFilter,
                            placeholder = { Text(stringResource(R.string.schema_search)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = if (state.filter.isNotEmpty()) {
                                {
                                    IconButton(onClick = { actions.onFilter("") }) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.schema_clear_filter))
                                    }
                                }
                            } else {
                                null
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.l),
                        )

                        LazyRow(
                            contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.m),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        ) {
                            items(ObjectKind.entries) { kind ->
                                FilterChip(
                                    selected = kind == state.objectKind,
                                    onClick = { actions.onSelectObjectKind(kind) },
                                    label = { Text(stringResource(kind.labelRes())) },
                                )
                            }
                        }
                        HorizontalDivider(color = semantic.hairline)

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
                                onAction = { actions.onFilter("") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                when (state.objectKind) {
                                    ObjectKind.TABLES, ObjectKind.VIEWS ->
                                        items(state.visibleTables, key = { "${it.database}.${it.name}" }) { table ->
                                            TableRow(table) { actions.onOpenTable(table.database, table.name) }
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
                                                onClick = { actions.onShowRoutine(routine) },
                                            )
                                            HorizontalDivider(color = semantic.hairline)
                                        }

                                    ObjectKind.TRIGGERS ->
                                        items(state.visibleTriggers, key = { it.name }) { trigger ->
                                            ObjectRow(
                                                name = trigger.name,
                                                detail = "${trigger.timing} ${trigger.event}",
                                                trailing = trigger.table,
                                                onClick = { actions.onOpenTable(state.selectedDatabase.orEmpty(), trigger.table) },
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
                        onDismiss = actions.onDismissRoutine,
                    )
                }
            }
        }
    }
}

/**
 * The line that says this schema came from the cache, and when it was taken (§11).
 *
 * It is shown whenever there is no live session and something was captured for this database.
 * Nothing about the lists below it changes to say so — a list looks the same whatever produced
 * it, which is precisely why this bar has to be here and has to be at the top, above the search
 * box and outside the scrolling area. The one mistake this feature could make is a stored schema
 * read as what the server says right now.
 *
 * The capture time is given twice on purpose: "3 hours ago" is what tells the reader whether to
 * trust it, and the date and time is what lets them check. The refresh action asks the browser
 * for the schema again, which is what a reconnect does with it.
 */
@Composable
private fun SchemaCacheMarker(
    live: Boolean,
    capturedAt: Long?,
    onRefresh: () -> Unit,
) {
    if (live || capturedAt == null) return
    val semantic = LocalSemanticColors.current
    val age = SchemaCache.age(capturedAt, System.currentTimeMillis())
    val stale = SchemaCache.isStale(capturedAt, System.currentTimeMillis())
    val ago = when (age.unit) {
        CacheAgeUnit.JUST_NOW -> stringResource(R.string.cache_taken_just_now)
        CacheAgeUnit.MINUTES -> pluralStringResource(R.plurals.cache_taken_minutes, age.count, age.count)
        CacheAgeUnit.HOURS -> pluralStringResource(R.plurals.cache_taken_hours, age.count, age.count)
        CacheAgeUnit.DAYS -> pluralStringResource(R.plurals.cache_taken_days, age.count, age.count)
    }
    val taken = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(capturedAt))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            // Announced when it appears: a reader who is not looking at the top of the screen has
            // no other way of learning that what they are reading is not live.
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = Shapes.card,
        color = semantic.surfaceRaised,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = if (stale) semantic.warning else semantic.textSecondary,
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                Text(
                    text = stringResource(R.string.cache_badge),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (stale) semantic.warning else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.cache_not_live),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
                Text(
                    text = stringResource(R.string.cache_taken, ago, taken),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
                if (stale) {
                    Text(
                        text = stringResource(R.string.cache_stale),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.warning,
                    )
                }
            }
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.cache_refresh))
            }
        }
    }
}

/**
 * When this database's schema was last captured, and nothing else.
 *
 * Its own view model rather than a field on [SchemaBrowserUiState]: the marker is about the
 * cache, not about the browser's state machine, and it has to keep working in the states where
 * that state machine has given up — a lost session clears the lists but the marker is exactly
 * what should appear then. Keeping it separate also means the browser's state class does not
 * grow a field that every screen showing a schema would then have to remember to set.
 */
@HiltViewModel
class SchemaCacheMarkerViewModel @Inject constructor(
    private val cache: SchemaCacheRepository,
) : ViewModel() {

    private val target = MutableStateFlow<Pair<Long, String>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val capturedAt: StateFlow<Long?> = target
        .flatMapLatest { watched ->
            if (watched == null) {
                flowOf(null)
            } else {
                cache.observeTablesCapturedAt(watched.first, watched.second)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Follows the connection and database on screen; null while there is neither. */
    fun watch(connectionId: Long?, database: String?) {
        target.value = if (connectionId != null && database != null) connectionId to database else null
    }

    private companion object {
        /** Long enough to survive a rotation without dropping the query and asking again. */
        const val STOP_TIMEOUT_MS = 5_000L
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
        // On a phone the database is picked in the title, so the list starts right away.
        Column(modifier = Modifier.fillMaxSize().padding(top = Spacing.xs)) {
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
    val view = table.kind == TableKind.VIEW
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Spacing.l, end = Spacing.m, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Icon(
            if (view) Icons.Default.Visibility else Icons.Default.TableChart,
            contentDescription = null,
            tint = semantic.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(table.name, style = MonoStyles.cell.copy(fontSize = 14.sp))
            // Engine under the name, the way the design reads a table: what it is, then how big.
            val detail = listOfNotNull(
                table.engine?.takeIf { !view },
                table.comment?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = semantic.textSecondary)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (view) {
                    stringResource(R.string.schema_view)
                } else {
                    table.approximateRows?.let { "~" + compactCount(it) }.orEmpty()
                },
                style = MonoStyles.cellNumber.copy(fontSize = 12.sp),
                color = if (view) semantic.textSecondary else semantic.cellNumber,
            )
            // Size only for real tables: a view has none.
            table.totalBytes?.takeIf { !view }?.let {
                Text(
                    formatByteSize(it),
                    style = MonoStyles.cellNumber.copy(fontSize = 11.sp),
                    color = semantic.textSecondary,
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = semantic.textSecondary.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp),
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

/**
 * A row count as the list shows it: exact with grouping below a hundred thousand, then shortened
 * the way the locale shortens ("1,2 M"). The tilde in front says it is InnoDB's estimate.
 */
private fun compactCount(count: Long): String =
    if (count < 100_000) {
        NumberFormat.getIntegerInstance().format(count)
    } else {
        CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT).format(count)
    }
