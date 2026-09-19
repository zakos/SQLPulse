package hu.laurel.sqlpulse.data.schema

/**
 * The geometry the schema map is drawn with, in one place.
 *
 * The screen draws it on a canvas and this file writes it into a file; if the two disagreed, the
 * picture the user shared would not be the picture they were looking at.
 */
object MapGeometry {
    const val NODE_WIDTH = 190f
    const val NODE_HEIGHT = 44f
    const val GAP_X = 24f
    const val GAP_Y = 90f
    const val CORNER = 10f

    fun x(order: Int): Float = order * (NODE_WIDTH + GAP_X)

    fun y(level: Int): Float = level * (NODE_HEIGHT + GAP_Y)
}

/**
 * The schema map as an SVG file.
 *
 * A map that can only be looked at is a map that cannot be taken to the meeting where the schema
 * is being argued about. SVG rather than a screenshot because it stays sharp at any size and is a
 * few kilobytes of text — a phone screenshot of forty tables is unreadable, and a PNG big enough
 * not to be is several megabytes.
 *
 * Pure text, no Android types, so what it produces can be asserted on rather than eyeballed.
 */
object SchemaSvg {

    /** The map of [graph], titled with [database]. */
    fun render(graph: SchemaGraph, database: String): String {
        val width = (graph.nodes.maxOfOrNull { MapGeometry.x(it.order) } ?: 0f) +
            MapGeometry.NODE_WIDTH + MARGIN * 2
        val height = (graph.nodes.maxOfOrNull { MapGeometry.y(it.level) } ?: 0f) +
            MapGeometry.NODE_HEIGHT + MARGIN * 2 + TITLE_SPACE

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append(
                """<svg xmlns="http://www.w3.org/2000/svg" width="${number(width)}" """ +
                    """height="${number(height)}" viewBox="0 0 ${number(width)} ${number(height)}">""",
            ).append('\n')
            append("<title>").append(escape(database)).append("</title>\n")
            // A background, because an SVG with none is transparent, and transparent on a dark
            // viewer means black text nobody can read.
            append("""<rect width="100%" height="100%" fill="$BACKGROUND"/>""").append('\n')
            append(defs()).append('\n')
            append(
                """<text x="${number(MARGIN)}" y="${number(MARGIN + TITLE_SIZE)}" """ +
                    """font-family="$FONT" font-size="$TITLE_SIZE" fill="$TEXT">""",
            )
            append(escape(database)).append("</text>\n")

            // Edges first, so a line never crosses over the name of a table.
            graph.edges.forEach { edge -> edgePath(graph, edge)?.let { append(it).append('\n') } }
            graph.nodes.forEach { append(box(it)).append('\n') }
            append("</svg>\n")
        }
    }

    private fun defs(): String =
        """<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" """ +
            """markerHeight="6" orient="auto-start-reverse">""" +
            """<path d="M 0 0 L 10 5 L 0 10 z" fill="$LINE"/></marker></defs>"""

    private fun box(node: GraphNode): String {
        val left = MapGeometry.x(node.order) + MARGIN
        val top = MapGeometry.y(node.level) + MARGIN + TITLE_SPACE
        val label = node.rows?.let { "${node.table} · $it" } ?: node.table
        return buildString {
            append(
                """<rect x="${number(left)}" y="${number(top)}" width="${number(MapGeometry.NODE_WIDTH)}" """ +
                    """height="${number(MapGeometry.NODE_HEIGHT)}" rx="${number(MapGeometry.CORNER)}" """ +
                    """fill="${if (node.connected) NODE_FILL else ISOLATED_FILL}" stroke="$LINE"/>""",
            )
            append(
                """<text x="${number(left + MapGeometry.NODE_WIDTH / 2)}" """ +
                    """y="${number(top + MapGeometry.NODE_HEIGHT / 2 + LABEL_SIZE / 3)}" """ +
                    """text-anchor="middle" font-family="$FONT" font-size="$LABEL_SIZE" fill="$TEXT">""",
            )
            append(escape(label))
            append("</text>")
        }
    }

    /**
     * One arrow, from the child's top edge to the parent's bottom edge.
     *
     * A table referencing itself gets a loop on its right-hand side rather than a line of zero
     * length, which is what a straight edge between a box and itself would be.
     */
    private fun edgePath(graph: SchemaGraph, edge: GraphEdge): String? {
        val from = graph.node(edge.from) ?: return null
        val to = graph.node(edge.to) ?: return null
        val dash = if (edge.guessed) """ stroke-dasharray="6 4"""" else ""

        if (edge.isSelfReference) {
            val left = MapGeometry.x(from.order) + MARGIN
            val top = MapGeometry.y(from.level) + MARGIN + TITLE_SPACE
            val right = left + MapGeometry.NODE_WIDTH
            val middle = top + MapGeometry.NODE_HEIGHT / 2
            return """<path d="M ${number(right)} ${number(middle - LOOP / 2)} """ +
                """C ${number(right + LOOP)} ${number(middle - LOOP)}, """ +
                """${number(right + LOOP)} ${number(middle + LOOP)}, """ +
                """${number(right)} ${number(middle + LOOP / 2)}" fill="none" stroke="$LINE"$dash """ +
                """marker-end="url(#arrow)"/>"""
        }

        val childX = MapGeometry.x(from.order) + MARGIN + MapGeometry.NODE_WIDTH / 2
        val childY = MapGeometry.y(from.level) + MARGIN + TITLE_SPACE
        val parentX = MapGeometry.x(to.order) + MARGIN + MapGeometry.NODE_WIDTH / 2
        val parentY = MapGeometry.y(to.level) + MARGIN + TITLE_SPACE + MapGeometry.NODE_HEIGHT
        return """<line x1="${number(childX)}" y1="${number(childY)}" x2="${number(parentX)}" """ +
            """y2="${number(parentY)}" stroke="$LINE"$dash marker-end="url(#arrow)"/>"""
    }

    /** No trailing ".0": half the numbers in this file are whole, and shorter is easier to read. */
    private fun number(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else "%.1f".format(value)

    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { character ->
            when {
                character == '&' -> append("&amp;")
                character == '<' -> append("&lt;")
                character == '>' -> append("&gt;")
                character == '"' -> append("&quot;")
                character.isISOControl() -> Unit
                else -> append(character)
            }
        }
    }

    private const val MARGIN = 24f
    private const val TITLE_SPACE = 40f
    private const val TITLE_SIZE = 18
    private const val LABEL_SIZE = 13
    private const val LOOP = 26f
    private const val FONT = "sans-serif"
    private const val BACKGROUND = "#ffffff"
    private const val NODE_FILL = "#eef2f8"
    private const val ISOLATED_FILL = "#f5f5f5"
    private const val LINE = "#33415c"
    private const val TEXT = "#1b2430"
}
