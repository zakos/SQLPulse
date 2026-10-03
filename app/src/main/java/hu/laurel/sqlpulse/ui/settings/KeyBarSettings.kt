package hu.laurel.sqlpulse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.query.EditorPrefsRepository
import hu.laurel.sqlpulse.data.query.KeyAction
import hu.laurel.sqlpulse.data.query.KeyBar
import hu.laurel.sqlpulse.data.query.KeyBarConfig
import hu.laurel.sqlpulse.data.query.KeyBarItem
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the key bar settings read and ask for; the interface lets a screenshot draw them. */
interface KeyBarController {
    val config: StateFlow<KeyBarConfig>
    fun move(id: String, delta: Int)
    fun setShown(id: String, shown: Boolean)
    fun reset()
}

@HiltViewModel
class KeyBarViewModel @Inject constructor(
    private val prefs: EditorPrefsRepository,
) : ViewModel(), KeyBarController {

    override val config: StateFlow<KeyBarConfig> = prefs.keyBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeyBarConfig.DEFAULT)

    override fun move(id: String, delta: Int) = change { KeyBar.move(it, id, delta) }

    override fun setShown(id: String, shown: Boolean) = change { KeyBar.setShown(it, id, shown) }

    override fun reset() {
        viewModelScope.launch { prefs.resetKeyBar() }
    }

    private fun change(block: (KeyBarConfig) -> KeyBarConfig) {
        viewModelScope.launch { prefs.setKeyBar(block(config.value)) }
    }
}

/** Settings → Editor key bar: reorder, hide and show the keys above the keyboard. */
@Composable
fun KeyBarSettingsScreen(onBack: () -> Unit, viewModel: KeyBarViewModel = hiltViewModel()) {
    KeyBarSettingsContent(onBack = onBack, viewModel = viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyBarSettingsContent(onBack: () -> Unit, viewModel: KeyBarController) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current
    val entries = KeyBar.entries(config)
    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = { Text(stringResource(R.string.keybar_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(
                    modifier = Modifier.padding(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    Text(
                        stringResource(R.string.keybar_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = semantic.textSecondary,
                    )
                    OutlinedButton(
                        onClick = viewModel::reset,
                        enabled = config != KeyBarConfig.DEFAULT,
                        shape = Shapes.button,
                    ) { Text(stringResource(R.string.keybar_reset)) }
                }
                HorizontalDivider(color = semantic.hairline)
            }
            items(entries.size, key = { entries[it].first.id }) { index ->
                val (item, shown) = entries[index]
                KeyRow(
                    item = item,
                    shown = shown,
                    canMoveUp = index > 0,
                    canMoveDown = index < entries.lastIndex,
                    onShown = { viewModel.setShown(item.id, it) },
                    onMove = { viewModel.move(item.id, it) },
                )
            }
        }
    }
}

@Composable
private fun KeyRow(
    item: KeyBarItem,
    shown: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onShown: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    val name = keyName(item)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = Spacing.l, end = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            // The key as it looks on the bar, so the list is a picture of the row.
            Box(
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .heightIn(min = 36.dp)
                    .background(semantic.surfaceRaised, RoundedCornerShape(10.dp))
                    .padding(horizontal = Spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                KeyFace(item, dimmed = !shown)
            }
            Text(
                text = if (item.label == null) name else "",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.keybar_move_up, name))
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.keybar_move_down, name))
            }
            Switch(
                checked = shown,
                onCheckedChange = onShown,
                modifier = Modifier.padding(end = Spacing.s).size(width = 52.dp, height = 32.dp),
            )
        }
        HorizontalDivider(color = semantic.hairline)
    }
}

/** What is printed on a key: its text, or an icon for the three that are not text. */
@Composable
fun KeyFace(item: KeyBarItem, dimmed: Boolean = false, modifier: Modifier = Modifier) {
    val color = if (dimmed) LocalSemanticColors.current.textSecondary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
    when (val action = item.action) {
        is KeyAction.Insert -> Text(action.text, style = MonoStyles.cell.copy(fontSize = 14.sp), color = color, modifier = modifier)
        KeyAction.Undo -> Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = color, modifier = modifier.size(20.dp))
        KeyAction.Redo -> Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = null, tint = color, modifier = modifier.size(20.dp))
        KeyAction.Snippets -> Icon(Icons.Default.Code, contentDescription = null, tint = color, modifier = modifier.size(20.dp))
    }
}

/** The key's name for a screen reader and for the rows that have no text on the key itself. */
@Composable
fun keyName(item: KeyBarItem): String = when (val action = item.action) {
    is KeyAction.Insert -> action.text
    KeyAction.Undo -> stringResource(R.string.editor_undo)
    KeyAction.Redo -> stringResource(R.string.editor_redo)
    KeyAction.Snippets -> stringResource(R.string.editor_snippets)
}
