package hu.laurel.sqlpulse.ui.schemadiff

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.CacheAgeUnit
import hu.laurel.sqlpulse.data.schema.DiffField
import hu.laurel.sqlpulse.data.schema.DiffStatus
import hu.laurel.sqlpulse.data.schema.FieldChange
import hu.laurel.sqlpulse.data.schema.ItemDiff
import hu.laurel.sqlpulse.data.schema.SchemaCache
import hu.laurel.sqlpulse.data.schema.SchemaDiffResult
import hu.laurel.sqlpulse.data.schema.StructureGap
import hu.laurel.sqlpulse.data.schema.TableDiff
import hu.laurel.sqlpulse.data.schema.UncheckedTable
import hu.laurel.sqlpulse.ui.components.ColorRail
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.components.InfoBadge
import hu.laurel.sqlpulse.ui.components.SectionCaption
import hu.laurel.sqlpulse.ui.components.StatusDot
import hu.laurel.sqlpulse.ui.theme.ConnectionColor
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors

/**
 * Two databases' structures side by side — what dev has that production does not, and the other
 * way round. Read-only by design: §2 rules out DDL, so the screen says what differs and stops
 * there; it never offers the statement that would make the two the same.
 */
@Composable
fun SchemaDiffScreen(
    onBack: () -> Unit,
    viewModel: SchemaDiffViewModel = hiltViewModel(),
) {
    SchemaDiffScreenContent(onBack = onBack, viewModel = viewModel)
}

/** The screen itself, drawn from whatever [SchemaDiffController] it is handed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaDiffScreenContent(
    onBack: () -> Unit,
    viewModel: SchemaDiffController,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current
    val result = state.result

    // The first table that differs starts open: that is what the reader came for, and the rest
    // of the list is one tap each.
    var expanded by rememberSaveable(result?.tables?.firstOrNull()?.name) {
        mutableStateOf(setOfNotNull(result?.tables?.firstOrNull { it.status == DiffStatus.CHANGED }?.name))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = { Text(stringResource(R.string.schemadiff_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::swap) {
                        Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.schemadiff_swap))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.xs, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (state.connections.isEmpty()) {
                item { Note(stringResource(R.string.schemadiff_no_connections)) }
                return@LazyColumn
            }
            item(key = "side-a") { SideCard(DiffSide.A, state, viewModel) }
            item(key = "side-b") { SideCard(DiffSide.B, state, viewModel) }
            item(key = "options") {
                Options(state, viewModel)
            }
            if (result == null) {
                item(key = "waiting") { Note(stringResource(R.string.schemadiff_waiting)) }
                return@LazyColumn
            }
            item(key = "summary") { Summary(result) }
            if (result.identical) {
                item(key = "identical") {
                    Text(
                        stringResource(R.string.schemadiff_identical),
                        style = MaterialTheme.typography.bodyMedium,
                        color = semantic.success,
                    )
                }
            }
            items(result.tables, key = { "t-${it.status}-${it.name}" }) { table ->
                TableCard(
                    table = table,
                    expanded = table.name in expanded,
                    onToggle = {
                        expanded = if (table.name in expanded) expanded - table.name else expanded + table.name
                    },
                )
            }
            if (result.unchecked.isNotEmpty()) {
                item(key = "unchecked-caption") {
                    SectionCaption(
                        stringResource(R.string.schemadiff_section_unchecked),
                        modifier = Modifier.padding(top = Spacing.s),
                    )
                }
                item(key = "unchecked") { UncheckedCard(result.unchecked) }
                item(key = "unchecked-note") { Note(stringResource(R.string.schemadiff_incomplete)) }
            }
            item(key = "limits") { Note(stringResource(R.string.schemadiff_limits)) }
        }
    }
}

// --------------------------------------------------------------------------- sides

@Composable
private fun SideCard(side: DiffSide, state: SchemaDiffUiState, controller: SchemaDiffController) {
    val semantic = LocalSemanticColors.current
    val current = state.side(side)
    val connection = state.connection(current.connectionId)
    val live = state.isLive(side)

    HairlineCard {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            ColorRail(ConnectionColor.fromName(connection?.color).value)
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.m, vertical = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    SideLetter(side)
                    Picker(
                        text = connection?.name ?: stringResource(R.string.schemadiff_pick),
                        options = state.connections.map { it.id to it.name },
                        onPick = { controller.selectConnection(side, it) },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (connection != null) {
                        if (live) {
                            StatusDot(color = semantic.success, label = stringResource(R.string.schemadiff_live))
                        } else {
                            InfoBadge(stringResource(R.string.schemadiff_offline), semantic.textSecondary)
                        }
                    }
                }
                if (connection != null) {
                    if (current.databases.isEmpty() && !current.loading && current.database == null) {
                        Text(
                            stringResource(R.string.schemadiff_no_databases),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.textSecondary,
                        )
                    } else {
                        Picker(
                            text = current.database ?: stringResource(R.string.schemadiff_pick),
                            options = current.databases.map { it to it },
                            onPick = { controller.selectDatabase(side, it) },
                            style = MonoStyles.cell.copy(fontSize = 14.sp),
                        )
                    }
                }
                CaptureLines(current, state.now)
                current.progress?.let { (done, total) ->
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.schemadiff_refreshing, done, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.textSecondary,
                    )
                }
                if (current.refreshFailed) {
                    Text(
                        stringResource(R.string.schemadiff_refresh_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.danger,
                    )
                }
                // Only the side with the open session can be read again; the other one is what it is.
                if (live && current.database != null && current.progress == null) {
                    TextButton(
                        onClick = { controller.refresh(side) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.schemadiff_refresh))
                    }
                }
            }
        }
    }
}

/** When the side was captured and how much of it — the comparison is only as good as these. */
@Composable
private fun CaptureLines(current: SchemaDiffSideState, now: Long) {
    val semantic = LocalSemanticColors.current
    val capture = current.capture ?: return
    val takenAt = capture.tablesCapturedAt
    if (takenAt == null) {
        Text(
            stringResource(R.string.schemadiff_never_listed),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.warning,
        )
        return
    }
    val stale = SchemaCache.isStale(takenAt, now)
    Text(
        stringResource(R.string.schemadiff_taken, ageText(takenAt, now)) + " · " +
            stringResource(R.string.schemadiff_coverage, capture.tableCount, capture.structuresCaptured),
        style = MaterialTheme.typography.bodySmall,
        color = if (stale) semantic.warning else semantic.textSecondary,
    )
    capture.oldestStructureAt?.let { oldest ->
        Text(
            stringResource(R.string.schemadiff_oldest, ageText(oldest, now)),
            style = MaterialTheme.typography.bodySmall,
            color = if (SchemaCache.isStale(oldest, now)) semantic.warning else semantic.textSecondary,
        )
    }
}

@Composable
private fun ageText(at: Long, now: Long): String {
    val age = SchemaCache.age(at, now)
    return when (age.unit) {
        CacheAgeUnit.JUST_NOW -> stringResource(R.string.cache_taken_just_now)
        CacheAgeUnit.MINUTES -> pluralStringResource(R.plurals.cache_taken_minutes, age.count, age.count)
        CacheAgeUnit.HOURS -> pluralStringResource(R.plurals.cache_taken_hours, age.count, age.count)
        CacheAgeUnit.DAYS -> pluralStringResource(R.plurals.cache_taken_days, age.count, age.count)
    }
}

/** The letter in a tinted square that the summary and every row refer back to. */
@Composable
private fun SideLetter(side: DiffSide) {
    val colour = side.colour()
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(colour.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(side.name, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = colour)
    }
}

/** A value with a drop-down arrow that opens the list it was chosen from. */
@Composable
private fun <T> Picker(
    text: String,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = options.isNotEmpty()) { open = true }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = LocalSemanticColors.current.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun Options(state: SchemaDiffUiState, controller: SchemaDiffController) {
    val options = state.options
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        FilterChip(
            selected = options.ignoreIdentifierCase,
            onClick = { controller.setOptions(options.copy(ignoreIdentifierCase = !options.ignoreIdentifierCase)) },
            label = { Text(stringResource(R.string.schemadiff_opt_case)) },
        )
        FilterChip(
            selected = options.ignoreCharset,
            onClick = { controller.setOptions(options.copy(ignoreCharset = !options.ignoreCharset)) },
            label = { Text(stringResource(R.string.schemadiff_opt_charset)) },
        )
        FilterChip(
            selected = options.ignoreComments,
            onClick = { controller.setOptions(options.copy(ignoreComments = !options.ignoreComments)) },
            label = { Text(stringResource(R.string.schemadiff_opt_comments)) },
        )
        FilterChip(
            selected = options.compareColumnOrder,
            onClick = { controller.setOptions(options.copy(compareColumnOrder = !options.compareColumnOrder)) },
            label = { Text(stringResource(R.string.schemadiff_opt_order)) },
        )
    }
}

// --------------------------------------------------------------------------- result

@Composable
private fun Summary(result: SchemaDiffResult) {
    val semantic = LocalSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        // Each count carries its word as well as its colour.
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Stat("${result.onlyACount}", stringResource(R.string.schemadiff_stat_only_a), DiffStatus.ONLY_A.colour(), Modifier.weight(1f))
            Stat("${result.changedCount}", stringResource(R.string.schemadiff_stat_changed), DiffStatus.CHANGED.colour(), Modifier.weight(1f))
            Stat("${result.onlyBCount}", stringResource(R.string.schemadiff_stat_only_b), DiffStatus.ONLY_B.colour(), Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.schemadiff_rest, result.identicalCount, result.unchecked.size),
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
}

@Composable
private fun Stat(value: String, label: String, colour: Color, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, Shapes.button)
            .border(1.dp, semantic.hairline, Shapes.button)
            .padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = colour,
        )
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = semantic.textSecondary)
    }
}

@Composable
private fun TableCard(table: TableDiff, expanded: Boolean, onToggle: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val expandable = table.status == DiffStatus.CHANGED
    HairlineCard {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (expandable) Modifier.clickable(onClick = onToggle) else Modifier)
                    .padding(horizontal = Spacing.m, vertical = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                StatusMark(table.status)
                Text(
                    table.name,
                    style = MonoStyles.cell.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (table.view) InfoBadge(stringResource(R.string.schemadiff_view), semantic.textSecondary)
                if (expandable) {
                    if (table.itemCount > 0) InfoBadge("${table.itemCount}", DiffStatus.CHANGED.colour())
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = semantic.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (table.gap != StructureGap.NONE) {
                Text(
                    stringResource(table.gap.label()),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.warning,
                    modifier = Modifier.padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.s),
                )
            }
            if (expandable && expanded) {
                HorizontalDivider(color = semantic.hairline)
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    if (table.changes.isNotEmpty()) {
                        Section(R.string.schemadiff_section_table)
                        table.changes.forEach { Change(it) }
                    }
                    ItemSection(R.string.schemadiff_section_columns, table.columns)
                    ItemSection(R.string.schemadiff_section_indexes, table.indexes)
                    ItemSection(R.string.schemadiff_section_keys, table.foreignKeys)
                }
            }
        }
    }
}

@Composable
private fun Section(@StringRes title: Int) {
    SectionCaption(stringResource(title), modifier = Modifier.padding(top = Spacing.xs))
}

@Composable
private fun ItemSection(@StringRes title: Int, items: List<ItemDiff>) {
    if (items.isEmpty()) return
    Section(title)
    items.forEach { ItemRow(it) }
}

@Composable
private fun ItemRow(item: ItemDiff) {
    val semantic = LocalSemanticColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        StatusMark(item.status, small = true)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = MonoStyles.cell.copy(fontWeight = FontWeight.Medium))
            item.summary?.let {
                Text(it, style = MonoStyles.cell.copy(fontSize = 12.sp), color = semantic.textSecondary)
            }
            item.changes.forEach { Change(it) }
        }
    }
}

/** One property as each side has it: the label, then A's value over B's. */
@Composable
private fun Change(change: FieldChange) {
    val semantic = LocalSemanticColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            stringResource(change.field.label()),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = semantic.textSecondary,
            modifier = Modifier.width(76.dp),
        )
        // A definition is too long to show twice; that it differs is the whole message.
        if (change.field != DiffField.DEFINITION) {
            Column(modifier = Modifier.weight(1f)) {
                SideValue(DiffSide.A, change.a)
                SideValue(DiffSide.B, change.b)
            }
        }
    }
}

@Composable
private fun SideValue(side: DiffSide, value: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(side.name, style = MonoStyles.cell.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = side.colour())
        Text(
            value ?: "—",
            style = MonoStyles.cell.copy(fontSize = 12.sp),
            color = if (value == null) LocalSemanticColors.current.textSecondary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The status as a letter in its colour — A, B, or ≠ — with the words for TalkBack. */
@Composable
private fun StatusMark(status: DiffStatus, small: Boolean = false) {
    val colour = status.colour()
    val description = stringResource(
        when (status) {
            DiffStatus.ONLY_A -> R.string.schemadiff_status_only_a
            DiffStatus.ONLY_B -> R.string.schemadiff_status_only_b
            DiffStatus.CHANGED -> R.string.schemadiff_status_changed
        },
    )
    Box(
        modifier = Modifier
            .size(if (small) 18.dp else 22.dp)
            .background(colour.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when (status) {
                DiffStatus.ONLY_A -> "A"
                DiffStatus.ONLY_B -> "B"
                DiffStatus.CHANGED -> "≠"
            },
            style = MonoStyles.cell.copy(fontSize = if (small) 11.sp else 12.sp, fontWeight = FontWeight.Bold),
            color = colour,
        )
    }
}

@Composable
private fun UncheckedCard(tables: List<UncheckedTable>) {
    val semantic = LocalSemanticColors.current
    HairlineCard {
        Column(modifier = Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            tables.forEach { table ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(
                        table.name,
                        style = MonoStyles.cell,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(table.gap.label()),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = semantic.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = LocalSemanticColors.current.textSecondary)
}

// --------------------------------------------------------------------------- labels and colours

/**
 * A is green and B is red, the way the snapshot diff colours added and removed rows: read A as
 * the newer side (typically dev) and B as what it is held against (typically production).
 */
@Composable
private fun DiffStatus.colour(): Color = when (this) {
    DiffStatus.ONLY_A -> LocalSemanticColors.current.success
    DiffStatus.ONLY_B -> LocalSemanticColors.current.danger
    DiffStatus.CHANGED -> LocalSemanticColors.current.warning
}

@Composable
private fun DiffSide.colour(): Color = when (this) {
    DiffSide.A -> DiffStatus.ONLY_A.colour()
    DiffSide.B -> DiffStatus.ONLY_B.colour()
}

@StringRes
private fun StructureGap.label(): Int = when (this) {
    StructureGap.MISSING_A -> R.string.schemadiff_gap_a
    StructureGap.MISSING_B -> R.string.schemadiff_gap_b
    StructureGap.MISSING_BOTH, StructureGap.NONE -> R.string.schemadiff_gap_both
}

@StringRes
private fun DiffField.label(): Int = when (this) {
    DiffField.NAME -> R.string.schemadiff_field_name
    DiffField.KIND -> R.string.schemadiff_field_kind
    DiffField.ENGINE -> R.string.schemadiff_field_engine
    DiffField.COLLATION -> R.string.schemadiff_field_collation
    DiffField.COMMENT -> R.string.schemadiff_field_comment
    DiffField.TYPE -> R.string.schemadiff_field_type
    DiffField.NULLABLE -> R.string.schemadiff_field_nullable
    DiffField.DEFAULT -> R.string.schemadiff_field_default
    DiffField.EXTRA -> R.string.schemadiff_field_extra
    DiffField.POSITION -> R.string.schemadiff_field_position
    DiffField.UNIQUE -> R.string.schemadiff_field_unique
    DiffField.COLUMNS -> R.string.schemadiff_field_columns
    DiffField.REFERENCES -> R.string.schemadiff_field_references
    DiffField.DEFINITION -> R.string.schemadiff_field_definition
}
