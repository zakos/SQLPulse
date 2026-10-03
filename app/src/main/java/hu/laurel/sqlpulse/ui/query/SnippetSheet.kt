package hu.laurel.sqlpulse.ui.query

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.query.BuiltInSnippets
import hu.laurel.sqlpulse.data.query.Snippet
import hu.laurel.sqlpulse.data.query.SnippetEngine
import hu.laurel.sqlpulse.ui.components.DialogButtons
import hu.laurel.sqlpulse.ui.components.LabeledField
import hu.laurel.sqlpulse.ui.components.SectionCaption
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/** A snippet being created or changed. [id] is null for a new one. */
data class SnippetDraft(val id: String?, val name: String, val body: String)

/**
 * The snippet picker: the user's own snippets and the built-in templates, searchable, in a bottom
 * sheet over the editor. Tapping one inserts it and closes the sheet.
 *
 * [selectedText] is what the editor has selected (or all of it when nothing is), offered as the
 * starting point for a new snippet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetSheet(
    snippets: List<Snippet>,
    selectedText: String,
    hasSelection: Boolean,
    onInsert: (Snippet) -> Unit,
    onSave: (id: String?, name: String, body: String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = Shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        SnippetSheetContent(
            snippets = snippets,
            selectedText = selectedText,
            hasSelection = hasSelection,
            onInsert = {
                onInsert(it)
                onDismiss()
            },
            onSave = onSave,
            onDelete = onDelete,
        )
    }
}

/** The sheet's content, apart from its window, so a screenshot can draw it. */
@Composable
fun SnippetSheetContent(
    snippets: List<Snippet>,
    selectedText: String,
    hasSelection: Boolean,
    onInsert: (Snippet) -> Unit,
    onSave: (id: String?, name: String, body: String) -> Unit,
    onDelete: (String) -> Unit,
    initialQuery: String = "",
    initialDraft: SnippetDraft? = null,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var draft by remember { mutableStateOf(initialDraft) }
    var deleting by remember { mutableStateOf<String?>(null) }

    val editing = draft
    if (editing != null) {
        SnippetEditor(
            draft = editing,
            onChange = { draft = it },
            onCancel = { draft = null },
            onSave = {
                onSave(editing.id, editing.name, editing.body)
                draft = null
            },
        )
        return
    }

    val mine = SnippetEngine.search(snippets, query)
    val builtIn = SnippetEngine.search(BuiltInSnippets.ALL, query)
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(R.string.snippets_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        LabeledField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.snippets_search)) },
        )
        if (selectedText.isNotBlank()) {
            OutlinedButton(
                onClick = { draft = SnippetDraft(null, "", selectedText) },
                shape = Shapes.button,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(
                        if (hasSelection) R.string.snippets_save_selection else R.string.snippets_save_current,
                    ),
                    modifier = Modifier.padding(start = Spacing.s),
                )
            }
        }
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { SectionCaption(stringResource(R.string.snippets_mine), modifier = Modifier.padding(vertical = Spacing.s)) }
            if (mine.isEmpty()) {
                item {
                    Text(
                        stringResource(if (snippets.isEmpty()) R.string.snippets_empty_mine else R.string.snippets_none_found),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.textSecondary,
                        modifier = Modifier.padding(bottom = Spacing.s),
                    )
                }
            }
            items(mine, key = { it.id }) { snippet ->
                if (deleting == snippet.id) {
                    DeleteConfirm(
                        name = snippet.name,
                        onConfirm = {
                            deleting = null
                            onDelete(snippet.id)
                        },
                        onCancel = { deleting = null },
                    )
                } else {
                    SnippetRow(
                        snippet = snippet,
                        onClick = { onInsert(snippet) },
                        onEdit = { draft = SnippetDraft(snippet.id, snippet.name, snippet.body) },
                        onDelete = { deleting = snippet.id },
                    )
                }
            }
            item {
                SectionCaption(stringResource(R.string.snippets_builtin), modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.s))
            }
            items(builtIn, key = { it.id }) { snippet ->
                SnippetRow(snippet = snippet, onClick = { onInsert(snippet) })
            }
            item {
                Text(
                    stringResource(R.string.snippets_builtin_note),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = semantic.textSecondary,
                    modifier = Modifier.padding(top = Spacing.m),
                )
            }
        }
    }
}

@Composable
private fun SnippetRow(
    snippet: Snippet,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Shapes.button)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.s, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                snippet.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            // The look of the thing, not all of it: the first line is what tells two apart.
            Text(
                SnippetEngine.expand(snippet.body).text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                style = MonoStyles.cell.copy(fontSize = 12.sp),
                color = semantic.textSecondary,
                maxLines = 1,
            )
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.snippets_edit), tint = semantic.textSecondary)
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.snippets_delete), tint = semantic.textSecondary)
            }
        }
    }
}

@Composable
private fun DeleteConfirm(name: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Shapes.button)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(stringResource(R.string.snippets_delete_confirm, name), style = MaterialTheme.typography.bodyMedium)
        DialogButtons(
            cancelLabel = stringResource(R.string.cancel),
            onCancel = onCancel,
            actionLabel = stringResource(R.string.snippets_delete_yes),
            onAction = onConfirm,
            enabled = true,
            danger = true,
        )
    }
}

@Composable
private fun SnippetEditor(
    draft: SnippetDraft,
    onChange: (SnippetDraft) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(if (draft.id == null) R.string.snippets_save_current else R.string.snippets_edit),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        LabeledField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = { Text(stringResource(R.string.snippets_name)) },
        )
        LabeledField(
            value = draft.body,
            onValueChange = { onChange(draft.copy(body = it)) },
            label = { Text(stringResource(R.string.snippets_text)) },
            singleLine = false,
            mono = true,
            modifier = Modifier.heightIn(max = 280.dp),
        )
        Text(
            stringResource(R.string.snippets_placeholder_hint),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = LocalSemanticColors.current.textSecondary,
        )
        DialogButtons(
            cancelLabel = stringResource(R.string.cancel),
            onCancel = onCancel,
            actionLabel = stringResource(R.string.snippets_save),
            onAction = onSave,
            enabled = draft.name.isNotBlank() && draft.body.isNotBlank(),
        )
    }
}
