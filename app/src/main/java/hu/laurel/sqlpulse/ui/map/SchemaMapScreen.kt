package hu.laurel.sqlpulse.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.GraphNode
import hu.laurel.sqlpulse.data.schema.SchemaGraph
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.Mono
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors
import kotlin.math.max
import kotlin.math.min

/**
 * The schema as a picture: a box per table, an arrow per foreign key, parents above children.
 *
 * Pinch to zoom, drag to move, tap a table to see what it is linked to and to open it. The map is
 * drawn from `information_schema` alone — it reads nothing from the tables themselves, so it costs
 * the same on an empty database and on a large one.
 */
@Composable
fun SchemaMapScreen(
    onBack: () -> Unit,
    onOpenTable: (database: String, table: String) -> Unit,
    viewModel: SchemaMapViewModel = hiltViewModel(),
) {
    SchemaMapScreenContent(onBack = onBack, onOpenTable = onOpenTable, viewModel = viewModel)
}

/** The screen itself, drawn from whatever [SchemaMapController] it is handed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaMapScreenContent(
    onBack: () -> Unit,
    onOpenTable: (database: String, table: String) -> Unit,
    viewModel: SchemaMapController,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current
    val measurer = rememberTextMeasurer()

    // One graph unit per dp until the first fit has run, so the opening frame is not half size.
    val unitsPerDp = LocalDensity.current.density
    var scale by remember { mutableFloatStateOf(unitsPerDp) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Bumped to frame the map again: once when a schema arrives, and whenever "fit" is tapped.
    var fitRequest by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = {
                    Column {
                        Text(stringResource(R.string.map_title))
                        state.database?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.textSecondary,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { fitRequest++ }) {
                        Icon(
                            Icons.Default.CenterFocusStrong,
                            contentDescription = stringResource(R.string.map_fit),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.error != null -> Message(state.error.orEmpty(), semantic.danger)
                state.database == null -> Message(stringResource(R.string.map_no_session), semantic.textSecondary)
                state.graph.isEmpty -> Message(stringResource(R.string.map_empty), semantic.textSecondary)
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // Said out loud rather than left to the dashes: on a schema with no foreign
                    // keys every line on the map is a guess, and that has to be impossible to
                    // miss before anyone believes the picture.
                    if (state.guessedCount > 0 || state.hasNoDeclaredLinks) {
                        GuessBanner(
                            declared = state.declaredCount,
                            guessed = state.guessedCount,
                            showGuesses = state.showGuesses,
                            onToggle = viewModel::setShowGuesses,
                        )
                    }
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        val widthPx = with(density) { maxWidth.toPx() }
                        val heightPx = with(density) { maxHeight.toPx() }

                        // Framing happens in composition, where the size is known, rather than while
                        // drawing: a draw pass that writes state redraws itself for ever.
                        LaunchedEffect(state.graph, widthPx, heightPx, fitRequest) {
                            val bounds = state.graph.bounds()
                            scale = min(
                                widthPx / (bounds.width + NODE_WIDTH),
                                heightPx / (bounds.height + NODE_HEIGHT),
                            // The graph is laid out in dp-sized units, so at most one unit per dp:
                            // capped at 1 px the boxes came out half size on a dense screen.
                            ).coerceIn(MIN_SCALE, density.density)
                            offset = Offset(
                                (widthPx - bounds.width * scale) / 2f - bounds.left * scale,
                                with(density) { Spacing.l.toPx() },
                            )
                        }

                        val nodeColour = MaterialTheme.colorScheme.surface
                        val linkColour = semantic.textSecondary
                        val selectedColour = MaterialTheme.colorScheme.primary
                        val textColour = MaterialTheme.colorScheme.onSurface
                        val looseColour = semantic.hairline

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(state.graph) {
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        val next = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                        // Zoom towards the fingers rather than the corner, so what is
                                        // being looked at stays under them.
                                        offset = (offset - centroid) * (next / scale) + centroid + pan
                                        scale = next
                                    }
                                }
                                .pointerInput(state.graph) {
                                    detectTapGestures { tap ->
                                        val point = (tap - offset) / scale
                                        val hit = state.graph.nodes.lastOrNull { node ->
                                            node.bounds().contains(point)
                                        }
                                        viewModel.select(hit?.table)
                                    }
                                },
                        ) {
                            translate(left = offset.x, top = offset.y) {
                                // Scaled about the corner the offset is measured from. The default pivot is
                                // the canvas centre, which pushed the map off screen at any zoom but 1.
                                scale(scale, pivot = Offset.Zero) {
                                    drawGraph(
                                        graph = state.graph,
                                        selected = state.selected,
                                        measurer = measurer,
                                        nodeColour = nodeColour,
                                        looseColour = looseColour,
                                        linkColour = linkColour,
                                        selectedColour = selectedColour,
                                        textColour = textColour,
                                    )
                                }
                            }
                        }

                        state.selected?.let { table ->
                            SelectedTable(
                                table = table,
                                graph = state.graph,
                                onOpen = { onOpenTable(state.database.orEmpty(), table) },
                                onDismiss = { viewModel.select(null) },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(Spacing.l),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * What the dashed lines are, and the switch that turns them off.
 *
 * On a schema that declares no foreign keys the map would otherwise be a wall of boxes; the lines
 * come from the column names instead, and this is where that is admitted.
 */
@Composable
private fun GuessBanner(
    declared: Int,
    guessed: Int,
    showGuesses: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            text = if (declared == 0) {
                stringResource(R.string.map_no_foreign_keys, guessed)
            } else {
                stringResource(R.string.map_some_guessed, declared, guessed)
            },
            style = MaterialTheme.typography.bodySmall,
            color = semantic.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (guessed > 0) {
            Switch(checked = showGuesses, onCheckedChange = onToggle)
        }
    }
}

/**
 * What is linked to the tapped table, and the way into it.
 *
 * The counts are the reason to tap: "referenced by nine tables" is what says a table is central,
 * and it is invisible on the map once the lines are dense.
 */
@Composable
private fun SelectedTable(
    table: String,
    graph: SchemaGraph,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val parentEdges = graph.edges.filter { it.from == table && !it.isSelfReference }
    val childEdges = graph.edges.filter { it.to == table && !it.isSelfReference }
    // A guessed link is marked in the list too, not only by the dashes on the map.
    val parents = parentEdges.map { if (it.guessed) "${it.to}*" else it.to }
    val children = childEdges.map { if (it.guessed) "${it.from}*" else it.from }
    val anyGuessed = (parentEdges + childEdges).any { it.guessed }

    HairlineCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(table, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.map_references, parents.size, children.size),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
            if (parents.isNotEmpty()) {
                Text(
                    stringResource(R.string.map_parents, parents.sorted().joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }
            if (children.isNotEmpty()) {
                Text(
                    stringResource(R.string.map_children, children.sorted().joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }
            if (anyGuessed) {
                Text(
                    "* " + stringResource(R.string.map_guessed_link),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(onClick = onOpen, shape = Shapes.button) {
                    Text(stringResource(R.string.map_open_table))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun Message(text: String, colour: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = colour,
            modifier = Modifier.padding(Spacing.xl),
        )
    }
}

/** Arrows first, boxes over them: a line ending under a box reads as going into it. */
private fun DrawScope.drawGraph(
    graph: SchemaGraph,
    selected: String?,
    measurer: TextMeasurer,
    nodeColour: Color,
    looseColour: Color,
    linkColour: Color,
    selectedColour: Color,
    textColour: Color,
) {
    val positions = graph.nodes.associateBy { it.table }
    graph.edges.forEach { edge ->
        val from = positions[edge.from] ?: return@forEach
        val to = positions[edge.to] ?: return@forEach
        val touched = selected != null && (edge.from == selected || edge.to == selected)
        val colour = if (touched) selectedColour else linkColour
        val width = if (touched) 2.5f else 1.5f
        // A guess is drawn dashed and fainter. It has to be tellable from a link the server
        // actually declared without reading anything.
        val effect = if (edge.guessed) {
            PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        } else {
            null
        }
        val shade = if (edge.guessed && !touched) colour.copy(alpha = 0.55f) else colour
        if (edge.isSelfReference) {
            drawSelfLink(from, shade, width, effect)
        } else {
            drawLink(from, to, shade, width, effect)
        }
    }

    graph.nodes.forEach { node ->
        val rect = node.bounds()
        val isSelected = node.table == selected
        drawRoundRect(
            color = if (node.connected) nodeColour else nodeColour.copy(alpha = 0.6f),
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(CORNER, CORNER),
        )
        drawRoundRect(
            color = if (isSelected) selectedColour else looseColour,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(CORNER, CORNER),
            style = Stroke(width = if (isSelected) 3f else 1.5f),
        )
        // Measured inside the box, with an ellipsis: a long table name drawn at its natural
        // width runs over its neighbours, which is what turns a dense schema into a smear.
        val label = measurer.measure(
            text = node.table,
            // In graph units rather than sp: the canvas is scaled already, and sp would scale twice.
            style = TextStyle(fontFamily = Mono, fontSize = (11f / density).sp, color = textColour),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = (NODE_WIDTH - 2 * LABEL_PADDING).toInt()),
        )
        drawText(
            textLayoutResult = label,
            topLeft = Offset(
                rect.left + LABEL_PADDING,
                rect.top + (NODE_HEIGHT - label.size.height) / 2f,
            ),
        )
    }
}

/** Child bottom-up to parent, with an arrowhead where it arrives. */
private fun DrawScope.drawLink(
    from: GraphNode,
    to: GraphNode,
    colour: Color,
    width: Float,
    effect: PathEffect?,
) {
    val start = Offset(from.bounds().center.x, from.bounds().top)
    val end = Offset(to.bounds().center.x, to.bounds().bottom)
    val midY = (start.y + end.y) / 2f
    val path = Path().apply {
        moveTo(start.x, start.y)
        // A gentle S rather than a diagonal: with many links, curves stay tellable apart where
        // straight lines turn into a fan.
        cubicTo(start.x, midY, end.x, midY, end.x, end.y)
    }
    drawPath(path, color = colour, style = Stroke(width = width, pathEffect = effect))
    drawArrowHead(end, colour)
}

/** A loop out of the top of the box and back into it: a table referencing itself. */
private fun DrawScope.drawSelfLink(
    node: GraphNode,
    colour: Color,
    width: Float,
    effect: PathEffect?,
) {
    val rect = node.bounds()
    val path = Path().apply {
        moveTo(rect.right - 12f, rect.top)
        cubicTo(
            rect.right + 24f, rect.top - 28f,
            rect.right - 48f, rect.top - 28f,
            rect.right - 36f, rect.top,
        )
    }
    drawPath(path, color = colour, style = Stroke(width = width, pathEffect = effect))
    drawArrowHead(Offset(rect.right - 36f, rect.top), colour)
}

private fun DrawScope.drawArrowHead(tip: Offset, colour: Color) {
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - ARROW, tip.y + ARROW)
        lineTo(tip.x + ARROW, tip.y + ARROW)
        close()
    }
    drawPath(head, color = colour)
}

/** Where a table's box sits, in the map's own coordinates. */
private fun GraphNode.bounds(): Rect = Rect(
    offset = Offset(
        x = order * (NODE_WIDTH + GAP_X),
        y = level * (NODE_HEIGHT + GAP_Y),
    ),
    size = Size(NODE_WIDTH, NODE_HEIGHT),
)

private fun SchemaGraph.bounds(): Rect {
    if (nodes.isEmpty()) return Rect(0f, 0f, NODE_WIDTH, NODE_HEIGHT)
    val rects = nodes.map { it.bounds() }
    return Rect(
        left = rects.minOf { it.left },
        top = rects.minOf { it.top },
        right = rects.maxOf { it.right },
        bottom = rects.maxOf { it.bottom },
    )
}

private const val NODE_WIDTH = 112f
private const val NODE_HEIGHT = 36f
private const val GAP_X = 12f
private const val GAP_Y = 72f
private const val CORNER = 10f
private const val ARROW = 6f
private const val LABEL_PADDING = 10f
private const val MIN_SCALE = 0.2f
private const val MAX_SCALE = 4f
