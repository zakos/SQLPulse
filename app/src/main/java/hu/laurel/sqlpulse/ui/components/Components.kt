package hu.laurel.sqlpulse.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.ssh.ConnectStep
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * State dot (§8). Colour is never the only signal: the label next to it carries the same
 * information for colour-blind users.
 */
@Composable
fun StatusDot(color: Color, label: String, pulsing: Boolean = false, modifier: Modifier = Modifier) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "status-pulse")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "status-pulse-alpha",
        ).value
    } else {
        1f
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha)
                // The label right next to it already says this; don't read the dot twice.
                .clearAndSetSemantics { },
            shape = CircleShape,
            color = color,
        ) {}
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalSemanticColors.current.textSecondary,
            modifier = Modifier.padding(start = Spacing.s),
        )
    }
}

/**
 * The four-dot stepped connection indicator (§8): key, SSH, MySQL, schema. The failing step turns
 * red and the concrete error is shown underneath.
 */
@Composable
fun StepIndicator(
    steps: List<ConnectStep>,
    completed: Set<ConnectStep>,
    current: ConnectStep?,
    failed: ConnectStep?,
    labels: @Composable (ConnectStep) -> String,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEach { step ->
            val color = when {
                step == failed -> semantic.danger
                step in completed -> semantic.success
                step == current -> semantic.warning
                else -> semantic.textSecondary.copy(alpha = 0.3f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
                Text(
                    text = labels(step),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step == failed) semantic.danger else semantic.textSecondary,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
    }
}

/** A card with the hairline border the spec asks for instead of a shadow (§8). */
@Composable
fun HairlineCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, LocalSemanticColors.current.hairline, Shapes.card),
        shape = Shapes.card,
        color = MaterialTheme.colorScheme.surface,
        content = content,
    )
}

/** §8: every empty state explains itself and offers exactly one action. No bare "No data". */
@Composable
fun EmptyState(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalSemanticColors.current.textSecondary,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAction, shape = Shapes.button) { Text(actionLabel) }
    }
}

/** Left-edge colour bar of a connection card; red for production, and it cannot be hidden (§8). */
@Composable
fun ColorRail(color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .size(width = 3.dp, height = 56.dp)
            .background(color),
        color = color,
    ) {}
}
