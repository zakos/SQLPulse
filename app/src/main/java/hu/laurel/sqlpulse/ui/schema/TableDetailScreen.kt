package hu.laurel.sqlpulse.ui.schema

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.ForeignKey
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.SchemaIndex
import hu.laurel.sqlpulse.ui.grid.ResultGrid
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing

/** Table page (§7.3): Data, Structure and DDL. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableDetailScreen(
    onBack: () -> Unit,
    onOpenTable: (database: String, table: String) -> Unit,
    viewModel: TableDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val semantic = LocalSemanticColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.table, style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.database,
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.textSecondary,
                        )
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.tab.ordinal) {
                TableTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == state.tab,
                        onClick = { viewModel.select(tab) },
                        text = {
                            Text(
                                stringResource(
                                    when (tab) {
                                        TableTab.DATA -> R.string.tab_data
                                        TableTab.STRUCTURE -> R.string.tab_structure
                                        TableTab.DDL -> R.string.tab_ddl
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Spacing.l),
                )
            }

            if (state.loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(Spacing.l)) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            when (state.tab) {
                TableTab.DATA -> state.preview?.let { ResultGrid(it, modifier = Modifier.fillMaxSize()) }

                TableTab.STRUCTURE -> state.structure?.let { structure ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(structure.columns, key = { it.name }) { column ->
                            ColumnRow(column)
                            HorizontalDivider(color = semantic.hairline)
                        }
                        if (structure.indexes.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.structure_indexes)) }
                            items(structure.indexes, key = { it.name }) { IndexRow(it) }
                        }
                        if (structure.foreignKeys.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.structure_foreign_keys)) }
                            items(structure.foreignKeys, key = { it.constraintName + it.column }) { fk ->
                                // §7.3: touching a foreign key jumps to the referenced table.
                                ForeignKeyRow(fk) { onOpenTable(fk.referencedDatabase, fk.referencedTable) }
                            }
                        }
                        if (structure.primaryKey.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.structure_no_primary_key),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = semantic.warning,
                                    modifier = Modifier.padding(Spacing.l),
                                )
                            }
                        }
                    }
                }

                TableTab.DDL -> state.ddl?.let { ddl ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            IconButton(onClick = { context.copyToClipboard(ddl) }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.ddl_copy),
                                )
                            }
                        }
                        Text(
                            text = ddl,
                            style = MonoStyles.cell,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(Spacing.l),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.l, bottom = Spacing.s),
    )
}

@Composable
private fun ColumnRow(column: SchemaColumn) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(column.name, style = MonoStyles.cell)
                if (column.isPrimaryKey) {
                    Text(
                        stringResource(R.string.structure_primary_key),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.success,
                    )
                }
            }
            Text(
                text = buildString {
                    append(column.typeName)
                    if (!column.nullable) append(" · NOT NULL")
                    column.defaultValue?.let { append(" · DEFAULT $it") }
                    column.extra?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
        }
    }
}

@Composable
private fun IndexRow(index: SchemaIndex) {
    val semantic = LocalSemanticColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Text(index.name, style = MonoStyles.cell)
        Text(
            text = index.columns.joinToString(", ") +
                if (index.unique) " · UNIQUE" else "",
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
}

@Composable
private fun ForeignKeyRow(foreignKey: ForeignKey, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        Text(foreignKey.column, style = MonoStyles.cell)
        Text(
            text = "→ ${foreignKey.referencedTable}.${foreignKey.referencedColumn}",
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
        )
    }
}

private fun Context.copyToClipboard(text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("sqlpulse", text))
}
