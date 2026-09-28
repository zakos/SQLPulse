package hu.laurel.sqlpulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.ui.query.SqlVisualTransformation
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * The card every confirmation is drawn on: a raised surface with 24dp corners, and a red outline
 * where the thing being confirmed is [danger]ous. It is the whole dialog, so the same
 * card can be drawn on its own in a screenshot, where a real dialog window cannot be.
 */
@Composable
fun DialogCard(
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (danger) Modifier.border(1.dp, LocalSemanticColors.current.production, shape) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** A dialog's title: the question, and — for a dangerous one — a red mark and where it applies. */
@Composable
fun DialogHeading(title: String, subtitle: String? = null, danger: Boolean = false) {
    val semantic = LocalSemanticColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        if (danger) {
            Box(
                modifier = Modifier.size(40.dp).background(semantic.production.copy(alpha = 0.16f), Shapes.button),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = semantic.production, modifier = Modifier.size(22.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                    color = if (danger) semantic.production else semantic.textSecondary,
                )
            }
        }
    }
}

/**
 * A statement in a code block, as the confirmations show it: highlighted, and — for a write —
 * broken over the lines the design uses, with the condition that picks the rows tinted amber
 * and marked at its edge. Which rows a statement touches is the one thing to check before
 * confirming, so it is the one thing that is drawn differently.
 */
@Composable
fun SqlBlock(statement: String, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val highlight = SqlVisualTransformation(
        plain = MaterialTheme.colorScheme.onSurface,
        keyword = MaterialTheme.colorScheme.primary,
        string = semantic.success,
        number = semantic.cellNumber,
        comment = semantic.cellNull,
        identifier = semantic.cellDate,
        parameter = semantic.warning,
    )
    val lines = if ('\n' in statement) statement.lines() else StatementLayout.lines(statement)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, Shapes.button)
            .border(1.dp, semantic.hairline, Shapes.button)
            .padding(vertical = 12.dp),
    ) {
        lines.forEach { line ->
            val condition = StatementLayout.isCondition(line)
            Text(
                text = highlight.filter(AnnotatedString(line)).text,
                style = MonoStyles.cell.copy(lineHeight = 22.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (condition) {
                            Modifier
                                .background(semantic.warning.copy(alpha = 0.14f))
                                .drawBehind { drawRect(semantic.warning, size = Size(3.dp.toPx(), size.height)) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 14.dp),
            )
        }
    }
}

/** The two buttons at the foot of a dialog: the way out in white on an outline, and the action. */
@Composable
fun DialogButtons(
    cancelLabel: String,
    onCancel: () -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    enabled: Boolean,
    danger: Boolean = false,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
    ) {
        androidx.compose.material3.OutlinedButton(
            onClick = onCancel,
            shape = Shapes.button,
            modifier = Modifier.height(48.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) { Text(cancelLabel) }
        androidx.compose.material3.Button(
            onClick = onAction,
            enabled = enabled,
            shape = Shapes.button,
            modifier = Modifier.height(48.dp).weight(1f, fill = false),
            colors = if (danger) {
                androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = semantic.danger,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                androidx.compose.material3.ButtonDefaults.buttonColors()
            },
        ) {
            actionIcon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = Spacing.s))
            }
            Text(actionLabel)
        }
    }
}

/** A quiet line under a dialog's content: an icon and a sentence, in the secondary colour. */
@Composable
fun DialogNote(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal),
            color = LocalSemanticColors.current.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
