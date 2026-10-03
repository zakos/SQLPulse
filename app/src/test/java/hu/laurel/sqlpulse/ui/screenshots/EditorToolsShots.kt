package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.data.query.KeyBar
import hu.laurel.sqlpulse.data.query.KeyBarConfig
import hu.laurel.sqlpulse.data.query.Snippet
import hu.laurel.sqlpulse.ui.query.QueryEditorContent
import hu.laurel.sqlpulse.ui.query.QueryEditorUiState
import hu.laurel.sqlpulse.ui.query.QueryTab
import hu.laurel.sqlpulse.ui.query.SnippetDraft
import hu.laurel.sqlpulse.ui.query.SnippetSheetContent
import hu.laurel.sqlpulse.ui.settings.KeyBarController
import hu.laurel.sqlpulse.ui.settings.KeyBarSettingsContent
import hu.laurel.sqlpulse.ui.theme.Shapes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

/** The editor's new tools: undo and redo, the longer key bar, snippets, and the key bar's settings. */
class EditorToolsShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private val mine = listOf(
        Snippet("u1", "Nyitott számlák", "SELECT *\nFROM invoices\nWHERE status = 'open'\nORDER BY issued_at DESC"),
        Snippet("u2", "Napi darabszám", "SELECT DATE(created_at), COUNT(*)\nFROM {{table}}\nGROUP BY 1"),
    )

    @Test
    fun editorWithUndoAndKeyBar() = paparazzi.screen {
        QueryEditorContent(
            onBack = {},
            viewModel = FakeQueryController(
                QueryEditorUiState(
                    tabs = listOf(
                        QueryTab(id = 1, title = "napi bevétel", sql = QueryEditorShots.SQL, database = "billing"),
                    ),
                    activeTabId = 1,
                    databases = listOf("billing", "shop"),
                    connectionName = "Számlázó",
                    canUndo = true,
                    canRedo = false,
                ),
            ),
        )
    }

    @Test
    fun snippetSheet() = paparazzi.screen {
        SheetOver { SnippetSheetContent(mine, "SELECT 1", false, {}, { _, _, _ -> }, {}) }
    }

    @Test
    fun snippetSheetSearch() = paparazzi.screen {
        SheetOver { SnippetSheetContent(mine, "SELECT 1", false, {}, { _, _, _ -> }, {}, initialQuery = "join") }
    }

    @Test
    fun snippetEditor() = paparazzi.screen {
        SheetOver {
            SnippetSheetContent(
                mine, "", false, {}, { _, _, _ -> }, {},
                initialDraft = SnippetDraft(null, "Napi darabszám", mine[1].body),
            )
        }
    }

    @Test
    fun keyBarSettings() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DesignPhone.copy(screenHeight = 2200))
        paparazzi.screen {
            KeyBarSettingsContent(
                onBack = {},
                viewModel = object : KeyBarController {
                    override val config: StateFlow<KeyBarConfig> = MutableStateFlow(
                        KeyBar.setShown(KeyBar.move(KeyBarConfig.DEFAULT, "LIMIT", -3), "NOT", false),
                    )
                    override fun move(id: String, delta: Int) = Unit
                    override fun setShown(id: String, shown: Boolean) = Unit
                    override fun reset() = Unit
                },
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun SheetOver(content: @androidx.compose.runtime.Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            QueryEditorContent(
                onBack = {},
                viewModel = FakeQueryController(
                    QueryEditorUiState(
                        tabs = listOf(QueryTab(id = 1, title = "napi bevétel", sql = "SELECT 1", database = "billing")),
                        activeTabId = 1,
                        connectionName = "Számlázó",
                    ),
                ),
            )
            Box(Modifier.fillMaxSize().background(Color(0xA0050608)))
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = Shapes.sheet,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .background(Color(0x24FFFFFF), RoundedCornerShape(2.dp)),
                    )
                    content()
                }
            }
        }
    }
}
