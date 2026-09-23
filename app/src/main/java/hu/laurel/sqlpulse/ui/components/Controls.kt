package hu.laurel.sqlpulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * A choice between a few options drawn as the design draws it: a sunken track with the chosen
 * option lifted out of it onto the surface. Radio semantics, so TalkBack reads it as one choice.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(semantic.surfaceRaised, RoundedCornerShape(12.dp))
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp)) else Modifier)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) MaterialTheme.colorScheme.onSurface else semantic.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One line of a settings-style list: a title, an optional line under it, and whatever belongs at
 * the end — a value, a switch, a chevron. Tappable when [onClick] is given; a hairline under it.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val semantic = LocalSemanticColors.current
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = semantic.textSecondary)
                }
            }
            trailing()
        }
        if (divider) HorizontalDivider(color = semantic.hairline)
    }
}

/** The value at the end of a [ListRow], in the monospace every figure is set in. */
@Composable
fun RowValue(text: String, chevron: Boolean = false) {
    val semantic = LocalSemanticColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MonoStyles.cell.copy(fontSize = 14.sp),
            color = semantic.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (chevron) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = semantic.textSecondary.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = Spacing.xs).size(18.dp),
            )
        }
    }
}

/** A chevron alone, for a row that only leads somewhere. */
@Composable
fun RowChevron() {
    Icon(
        Icons.Default.ChevronRight,
        contentDescription = null,
        tint = LocalSemanticColors.current.textSecondary.copy(alpha = 0.6f),
        modifier = Modifier.size(18.dp),
    )
}
