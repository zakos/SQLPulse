package hu.laurel.sqlpulse.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.net.ManualReason
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * The bar that appears when the network moved under the session (§5, §8).
 *
 * It sits above whatever screen it is placed on and never replaces it: the query in the editor and
 * the rows already fetched are still there, and they still mean something — losing the connection
 * is not a reason to throw away what the user typed or read.
 *
 * Reusable on purpose: the query editor, the schema browser and the server screen all have the
 * same problem and should not each grow their own version of this.
 */
@Composable
fun ConnectionLostBanner(
    state: SqlSessionState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lost = state as? SqlSessionState.Lost ?: return
    ConnectionLostBar(
        message = lost.explain(),
        reconnecting = lost.reconnecting,
        onReconnect = onReconnect,
        modifier = modifier,
    )
}

/** The bar itself, free of session types so a preview or another caller can drive it directly. */
@Composable
fun ConnectionLostBar(
    message: String,
    reconnecting: Boolean,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // Announced by TalkBack when it appears: a user who is not looking at the screen has
            // no other way of learning that the session went.
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = Shapes.card,
        color = semantic.surfaceRaised,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.network_connection_lost),
                    style = MaterialTheme.typography.titleSmall,
                    color = semantic.warning,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }
            if (reconnecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onReconnect, shape = Shapes.button) {
                    Text(stringResource(R.string.network_reconnect))
                }
            }
        }
    }
}

/**
 * One sentence saying what the user needs to know — and, when a transaction was open, what the
 * server has already done about it. That case is not a detail: anything they had not committed is
 * gone, and being told so is the difference between re-running the work and assuming it stuck.
 */
@Composable
private fun SqlSessionState.Lost.explain(): String = when {
    rolledBackTransaction -> stringResource(R.string.network_transaction_rolled_back)
    reconnecting -> when (val number = attempt) {
        null -> stringResource(R.string.network_reconnecting)
        else -> stringResource(R.string.network_reconnecting_attempt, number)
    }

    reason == ManualReason.WRITABLE_SESSION ->
        stringResource(R.string.network_manual_writable, connection.name)

    reason == ManualReason.STATEMENT_IN_FLIGHT ->
        stringResource(R.string.network_manual_in_flight)

    reason == ManualReason.RETRIES_EXHAUSTED -> stringResource(R.string.network_manual_exhausted)
    else -> stringResource(R.string.network_lost_body, connection.name)
}
