package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.data.connection.WriteBackRefusal
import hu.laurel.sqlpulse.ui.connections.WriteBackCard
import hu.laurel.sqlpulse.ui.connections.WriteBackPrompt
import org.junit.Rule
import org.junit.Test

/** The question asked when leaving a SQLite table whose copy holds changes. */
class WriteBackShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @Composable
    private fun Over(prompt: WriteBackPrompt) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0xA0050608)))
            Box(Modifier.align(Alignment.Center).padding(horizontal = 16.dp)) {
                WriteBackCard(prompt, onWrite = {}, onOverwrite = {}, onKeepLocal = {}, onDismiss = {})
            }
        }
    }

    @Test
    fun ask() = paparazzi.screen(dark = true) { Over(WriteBackPrompt.Ask("basic.db", production = false)) }

    @Test
    fun askLight() = paparazzi.screen(dark = false) { Over(WriteBackPrompt.Ask("basic.db", production = false)) }

    @Test
    fun askProduction() = paparazzi.screen(dark = true) { Over(WriteBackPrompt.Ask("basic.db", production = true)) }

    @Test
    fun originalChanged() = paparazzi.screen(dark = true) {
        Over(WriteBackPrompt.Changed("basic.db", production = false, unknown = false))
    }

    @Test
    fun originalChangedProduction() = paparazzi.screen(dark = true) {
        Over(WriteBackPrompt.Changed("basic.db", production = true, unknown = false))
    }

    @Test
    fun originalUnknown() = paparazzi.screen(dark = false) {
        Over(WriteBackPrompt.Changed("basic.db", production = false, unknown = true))
    }

    @Test
    fun transactionOpen() = paparazzi.screen(dark = true) { Over(WriteBackPrompt.Refused(WriteBackRefusal.TRANSACTION_OPEN)) }

    @Test
    fun failed() = paparazzi.screen(dark = true) { Over(WriteBackPrompt.Failed("basic.db", "read-only provider")) }
}
