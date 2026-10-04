package hu.laurel.sqlpulse.ui.query

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import hu.laurel.sqlpulse.R
import androidx.compose.ui.res.stringResource
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing

/** The cursor line and the editor, in window pixels: all the popup needs to know about the screen. */
data class CompletionAnchor(
    val cursorX: Int,
    val cursorTop: Int,
    val cursorBottom: Int,
    /** Where the editor ends; the key bar starts right below, and the list stays above it. */
    val editorBottom: Int,
    val windowWidth: Int,
)

private val RowHeight = 40.dp
private val ListPadding = 4.dp
private val ListWidth = 280.dp

/**
 * The floating completion list, anchored under the cursor line.
 *
 * Not focusable: the keyboard stays with the editor, and the arrow keys reach the list through
 * [QueryShortcuts] instead. Tapping a row accepts it.
 */
@Composable
fun CompletionPopup(
    items: List<CompletionItem>,
    selected: Int,
    prefix: String,
    anchor: CompletionAnchor,
    onPick: (CompletionItem) -> Unit,
) {
    val density = LocalDensity.current
    val spot = remember(items.size, anchor, density) {
        with(density) {
            val rows = items.size.coerceAtMost(CompletionRanking.MAX_ROWS)
            CompletionPlacement.place(
                cursorX = anchor.cursorX,
                cursorTop = anchor.cursorTop,
                cursorBottom = anchor.cursorBottom,
                popupWidth = ListWidth.roundToPx(),
                popupHeight = (RowHeight * rows + ListPadding * 2).roundToPx(),
                editorBottom = anchor.editorBottom,
                windowWidth = anchor.windowWidth,
                margin = Spacing.s.roundToPx(),
                minHeight = (RowHeight * 2 + ListPadding * 2).roundToPx(),
            )
        }
    } ?: return

    val provider = remember(spot) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ) = IntOffset(spot.x, spot.y)
        }
    }
    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
        CompletionListCard(
            items = items,
            selected = selected,
            prefix = prefix,
            onPick = onPick,
            maxHeight = with(density) { spot.height.toDp() },
        )
    }
}

/**
 * The list itself. Drawn separately from [CompletionPopup] because Paparazzi does not capture a
 * popup window, and so the card can be placed over the editor in a screenshot.
 */
@Composable
fun CompletionListCard(
    items: List<CompletionItem>,
    selected: Int,
    prefix: String,
    onPick: (CompletionItem) -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
) {
    val semantic = LocalSemanticColors.current
    val shape = RoundedCornerShape(12.dp)
    val description = stringResource(R.string.completion_list)
    Surface(
        modifier = modifier
            .width(ListWidth)
            .semantics { contentDescription = description },
        shape = shape,
        color = semantic.surfaceRaised,
        border = BorderStroke(1.dp, semantic.hairline),
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = ListPadding)) {
            // Rows beyond what fits are dropped rather than scrolled: a floating list that
            // scrolls would fight the editor for the same swipe.
            val fits = if (maxHeight == Dp.Unspecified) items.size else
                ((maxHeight - ListPadding * 2) / RowHeight).toInt().coerceIn(1, items.size.coerceAtLeast(1))
            items.take(fits).forEachIndexed { index, item ->
                CompletionRow(item, prefix, selected = index == selected, onClick = { onPick(item) })
            }
        }
    }
}

@Composable
private fun CompletionRow(item: CompletionItem, prefix: String, selected: Boolean, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val accent = kindColor(item.kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .padding(horizontal = 4.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(accent.copy(alpha = 0.18f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(kindIcon(item.kind), contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(Spacing.s))
        Text(
            text = highlighted(item.text, prefix),
            style = MonoStyles.cell,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (item.detail != null) {
            Spacer(Modifier.width(Spacing.s))
            Text(
                text = item.detail,
                style = MonoStyles.cell.copy(fontSize = 11.sp),
                color = semantic.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** The typed prefix in bold, the rest as it is. */
private fun highlighted(text: String, prefix: String): AnnotatedString {
    val (typed, rest) = CompletionRanking.split(text, prefix)
    return buildAnnotatedString {
        if (typed.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(typed) }
        append(rest)
    }
}

@Composable
private fun kindColor(kind: CompletionKind): Color {
    val semantic = LocalSemanticColors.current
    return when (kind) {
        CompletionKind.TABLE -> MaterialTheme.colorScheme.primary
        CompletionKind.COLUMN -> semantic.cellDate
        CompletionKind.KEYWORD -> semantic.textSecondary
        CompletionKind.FUNCTION -> semantic.cellNumber
        CompletionKind.SNIPPET -> semantic.success
    }
}

private fun kindIcon(kind: CompletionKind): ImageVector = when (kind) {
    CompletionKind.TABLE -> Icons.Default.TableRows
    CompletionKind.COLUMN -> Icons.Default.ViewColumn
    CompletionKind.KEYWORD -> Icons.Default.Code
    CompletionKind.FUNCTION -> Icons.Default.Functions
    CompletionKind.SNIPPET -> Icons.Default.ContentPaste
}
