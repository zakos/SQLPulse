package hu.laurel.sqlpulse.ui.connections

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import hu.laurel.sqlpulse.ui.theme.SqlPulseTheme
import org.junit.Rule
import org.junit.Test

/**
 * The editor's refusal to save an incomplete connection, driven through real Compose input.
 *
 * What is under test is [ConnectionForm.canSave] as the screen uses it: Save stays disabled until
 * the connection could actually be opened, because a half-filled connection saved now is a
 * failure much later, with nothing on screen to explain it.
 *
 * The screen itself takes its state from a `hiltViewModel`, which would need a Hilt test
 * application and an activity to host it — more scaffolding than this skeleton is for. So the
 * same widgets are wired to the same form here; when the editor is given a stateless overload,
 * this test should compose that instead.
 *
 * Test names are camel case rather than the project's backticked sentences: a method name with
 * spaces is rejected by the runtime below API 30, and this module's minimum is 28.
 */
class ConnectionFormUiTest {

    @get:Rule
    val compose = createComposeRule()

    /** Without the tunnel the form is about the database alone, which keeps the test to the point. */
    private fun setContent() = compose.setContent {
        var form by remember { mutableStateOf(ConnectionForm(useSsh = false)) }
        SqlPulseTheme {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { form = form.copy(name = it) },
                    label = { Text("Name") },
                )
                OutlinedTextField(
                    value = form.dbHost,
                    onValueChange = { form = form.copy(dbHost = it) },
                    label = { Text("Host") },
                )
                OutlinedTextField(
                    value = form.dbPort,
                    onValueChange = { form = form.copy(dbPort = it) },
                    label = { Text("Port") },
                )
                Button(onClick = {}, enabled = form.canSave) { Text("Save") }
            }
        }
    }

    @Test
    fun saveIsRefusedUntilTheConnectionIsNamed() {
        setContent()

        // A connection with no name is one nobody can find again in the list.
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNodeWithText("Name").performTextInput("Staging")
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun saveIsRefusedWhileTheAddressIsIncomplete() {
        setContent()
        compose.onNodeWithText("Name").performTextInput("Staging")

        // A half-deleted port is not a port, and must not be quietly replaced by a default.
        compose.onNodeWithText("Port").performTextClearance()
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNodeWithText("Port").performTextInput("3307")
        compose.onNodeWithText("Save").assertIsEnabled()

        compose.onNodeWithText("Host").performTextClearance()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }
}
