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
import hu.laurel.sqlpulse.data.sql.CellEditor
import hu.laurel.sqlpulse.data.sql.ParameterType
import hu.laurel.sqlpulse.data.sql.ParameterValue
import hu.laurel.sqlpulse.ssh.HostKeyPrompt
import hu.laurel.sqlpulse.ui.connections.HostKeyCard
import hu.laurel.sqlpulse.ui.connections.ReadOnlyOfferCard
import hu.laurel.sqlpulse.ui.diagnostics.DiagnosticsCard
import hu.laurel.sqlpulse.ui.grid.CellEditCard
import hu.laurel.sqlpulse.ui.keys.KeyDeleteCard
import hu.laurel.sqlpulse.ui.keys.PublicKeyCard
import hu.laurel.sqlpulse.ui.query.NameCard
import hu.laurel.sqlpulse.ui.query.NoticeCard
import hu.laurel.sqlpulse.ui.query.ParameterCard
import hu.laurel.sqlpulse.ui.query.TabCloseCard
import hu.laurel.sqlpulse.ui.schema.ConflictCard
import hu.laurel.sqlpulse.ui.server.GrantsCard
import hu.laurel.sqlpulse.ui.server.KillCard
import org.junit.Rule
import org.junit.Test

/** The dialogs that were still the stock Material ones, drawn as the design's cards. */
class DialogCardShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @Composable
    private fun Over(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0xA0050608)))
            Box(Modifier.align(Alignment.Center).padding(horizontal = 16.dp)) { content() }
        }
    }

    private val first = HostKeyPrompt(
        host = "bastion.example.hu",
        port = 22,
        keyType = "ssh-ed25519",
        offeredFingerprint = "SHA256:q3Zk1b0yV8nJm4o7Hc2uXwRtLp9sEaDfGhKjNvBcM6A",
        storedFingerprint = null,
    )
    private val changed = first.copy(
        storedFingerprint = "SHA256:7hTn2PqLw0cVbX5mYdEs9uJrKa3oZfGi1NxCvBtMy4Q",
        offeredFingerprint = "SHA256:Zx9Wm3KjP1aQeRtYu6oIsDfGhLcVbNn2M8dS4wEaXq0",
    )

    @Test
    fun hostKeyFirstSeenLight() = paparazzi.screen(dark = false) { Over { HostKeyCard(first, {}, {}) } }

    @Test
    fun hostKeyFirstSeenDark() = paparazzi.screen(dark = true) { Over { HostKeyCard(first, {}, {}) } }

    @Test
    fun hostKeyChangedLight() = paparazzi.screen(dark = false) { Over { HostKeyCard(changed, {}, {}) } }

    @Test
    fun hostKeyChangedDark() = paparazzi.screen(dark = true) { Over { HostKeyCard(changed, {}, {}) } }

    private val statement = "SELECT o.id, c.name FROM orders o JOIN customers c ON c.id = o.customer_id WHERE o.status = 'open'"

    @Test
    fun killMysql() = paparazzi.screen(dark = true) {
        Over { KillCard(4821, "root@10.0.4.7 · shop · 412 s\n$statement", canCancel = true, canTerminate = true, onKill = {}, onTerminate = {}, onDismiss = {}) }
    }

    @Test
    fun terminateOnly() = paparazzi.screen(dark = false) {
        Over { KillCard(77, "app@10.0.4.9 · 35 s\n$statement", canCancel = false, canTerminate = true, onKill = {}, onTerminate = {}, onDismiss = {}) }
    }

    @Test
    fun grants() = paparazzi.screen(dark = true) {
        Over {
            GrantsCard(
                "'report'@'10.0.%'",
                "GRANT SELECT ON `shop`.* TO `report`@`10.0.%`\n\nGRANT USAGE ON *.* TO `report`@`10.0.%`",
                onClose = {},
            )
        }
    }

    @Test
    fun parameters() = paparazzi.screen(dark = true) {
        Over {
            ParameterCard(
                names = listOf("status", "since"),
                values = mapOf("status" to ParameterValue("open", ParameterType.TEXT)),
                onValue = { _, _ -> },
                onRun = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun tabClose() = paparazzi.screen(dark = true) {
        Over { TabCloseCard("UPDATE orders SET status = 'sent' WHERE id = 1204", {}, {}) }
    }

    @Test
    fun tabLimit() = paparazzi.screen(dark = false) {
        Over { NoticeCard("Tab limit reached", "At most 10 queries can stay open at a time.", "OK", {}) }
    }

    @Test
    fun favouriteName() = paparazzi.screen(dark = true) {
        Over { NameCard("Save as favourite", "Name", "Open orders", {}, {}, {}) }
    }

    @Test
    fun deleteKey() = paparazzi.screen(dark = true) {
        Over { KeyDeleteCard("bastion-ed25519", "SHA256:q3Zk1b0yV8nJm4o7Hc2uXwRtLp9sEaDfGhKjNvBcM6A", {}, {}) }
    }

    @Test
    fun deleteKeyLight() = paparazzi.screen(dark = false) {
        Over { KeyDeleteCard("bastion-ed25519", "SHA256:q3Zk1b0yV8nJm4o7Hc2uXwRtLp9sEaDfGhKjNvBcM6A", {}, {}) }
    }

    @Test
    fun publicKey() = paparazzi.screen(dark = true) {
        Over { PublicKeyCard("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIO3kq0dY1xE7oT5g2Vb9uLhZsPz0cQ4mWnXrAaBcDeFg sqlpulse", generated = true, onCopy = {}, onDismiss = {}) }
    }

    @Test
    fun conflict() = paparazzi.screen(dark = true) {
        Over { ConflictCard(rowExists = true, currentValue = "paid", onOverwrite = {}, onDismiss = {}) }
    }

    @Test
    fun readOnlyOffer() = paparazzi.screen(dark = true) { Over { ReadOnlyOfferCard({}, {}) } }

    @Test
    fun cellEdit() = paparazzi.screen(dark = true) {
        Over { CellEditCard("status", CellEditor.Choice(listOf("new", "paid", "sent")), "paid", false, {}, {}, {}, {}) }
    }

    @Test
    fun diagnostics() = paparazzi.screen(dark = false) {
        Over { DiagnosticsCard("SQLPulse 0.4.2\nAndroid 15\nEngine: MySQL 8.4", loading = false, connected = true, copied = false, onCopy = {}, onDismiss = {}) }
    }
}
