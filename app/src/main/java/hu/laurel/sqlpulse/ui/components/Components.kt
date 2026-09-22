package hu.laurel.sqlpulse.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.sp
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
 * The stepped connection indicator (§8): key, SSH, MySQL, schema, joined by a line that fills as
 * the steps complete. A finished step carries a tick and a failed one a cross, so the state is
 * readable without telling green from red; the concrete error goes underneath, in the caller.
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
    val idle = semantic.textSecondary.copy(alpha = 0.45f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, step ->
            val color = when {
                step == failed -> semantic.danger
                step in completed -> semantic.success
                step == current -> semantic.warning
                else -> idle
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .then(
                            if (step == current) {
                                Modifier.border(4.dp, color.copy(alpha = 0.2f), CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .border(2.dp, color, CircleShape)
                        .background(
                            if (step in completed || step == failed) color else Color.Transparent,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val mark = when {
                        step == failed -> Icons.Default.Close
                        step in completed -> Icons.Default.Check
                        else -> null
                    }
                    if (mark != null) {
                        Icon(
                            mark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.background,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                Text(
                    text = labels(step),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        step == failed -> semantic.danger
                        step in completed || step == current -> MaterialTheme.colorScheme.onSurface
                        else -> semantic.textSecondary
                    },
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (index < steps.lastIndex) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            if (step in completed) semantic.success else semantic.hairline,
                            RoundedCornerShape(1.dp),
                        ),
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

/**
 * Left-edge colour bar of a connection card; red for production, and it cannot be hidden (§8).
 * It runs the card's full height, so the row it sits in needs an intrinsic height.
 */
@Composable
fun ColorRail(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(4.dp)
            .fillMaxHeight()
            .background(color),
    )
}

/**
 * A small tag in capitals — ÉLES, TLS, CSAK OLVASÁS — on a tinted ground of its own colour.
 * Short on purpose: it names a fact about the thing next to it, it does not explain it.
 */
@Composable
fun InfoBadge(text: String, color: Color, modifier: Modifier = Modifier, container: Color = color.copy(alpha = 0.15f)) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = modifier
            .background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Section caption in small capitals, the way every grouped screen in §8 heads its parts. */
@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
        color = LocalSemanticColors.current.textSecondary,
        modifier = modifier,
    )
}
