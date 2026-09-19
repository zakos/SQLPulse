package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaSvgTest {

    private val graph = SchemaLayout.build(
        tables = listOf("orders", "customers", "log"),
        edges = listOf(GraphEdge(from = "orders", to = "customers", columns = listOf("customer_id"))),
        rows = mapOf("orders" to 1200L),
    )

    private val svg = SchemaSvg.render(graph, "webshop")

    @Test
    fun `it is an SVG document with the database in its title`() {
        assertTrue(svg.startsWith("<?xml"))
        assertTrue(svg.contains("""<svg xmlns="http://www.w3.org/2000/svg""""))
        assertTrue(svg.contains("<title>webshop</title>"))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
    }

    @Test
    fun `every table gets a box with its name`() {
        listOf("orders", "customers", "log").forEach {
            assertTrue(it, svg.contains(">$it<") || svg.contains(">$it ·"))
        }
        assertEquals(3, Regex("<rect [^>]*rx=").findAll(svg).count())
    }

    @Test
    fun `a row count rides along with the name where there is one`() {
        assertTrue(svg.contains("orders · 1200"))
    }

    @Test
    fun `a foreign key is an arrow from the child up to the parent`() {
        val line = Regex("""<line [^>]*marker-end="url\(#arrow\)"/>""").find(svg)?.value
        assertTrue(line != null)
        // The child sits a row below its parent, so the arrow runs upwards: y2 above y1.
        val y1 = Regex("""y1="(-?[0-9.]+)"""").find(line!!)!!.groupValues[1].toFloat()
        val y2 = Regex("""y2="(-?[0-9.]+)"""").find(line)!!.groupValues[1].toFloat()
        assertTrue("$y1 -> $y2", y2 < y1)
    }

    @Test
    fun `a guessed link is dashed, a declared one is not`() {
        val guessed = SchemaSvg.render(
            SchemaLayout.build(
                tables = listOf("a", "b"),
                edges = listOf(GraphEdge(from = "a", to = "b", guessed = true)),
            ),
            "db",
        )
        assertTrue(guessed.contains("stroke-dasharray"))
        assertFalse(svg.contains("stroke-dasharray"))
    }

    @Test
    fun `a table referencing itself gets a loop, not a line of no length`() {
        val loop = SchemaSvg.render(
            SchemaLayout.build(
                tables = listOf("employee"),
                edges = listOf(GraphEdge(from = "employee", to = "employee", columns = listOf("boss_id"))),
            ),
            "hr",
        )
        assertTrue(loop.contains("<path d=\"M "))
        assertFalse(loop.contains("<line"))
    }

    @Test
    fun `an edge naming a table the map does not hold is left out`() {
        // SchemaLayout drops those already; the renderer must not fall over if one survives.
        val dangling = SchemaGraph(
            nodes = listOf(GraphNode("a", level = 0, order = 0)),
            edges = listOf(GraphEdge(from = "a", to = "elsewhere")),
        )
        val rendered = SchemaSvg.render(dangling, "db")
        assertFalse(rendered.contains("<line"))
        assertTrue(rendered.contains(">a<"))
    }

    @Test
    fun `the canvas is big enough for every box`() {
        val width = Regex("""width="([0-9.]+)"""").find(svg)!!.groupValues[1].toFloat()
        val height = Regex("""height="([0-9.]+)"""").find(svg)!!.groupValues[1].toFloat()
        val rightmost = graph.nodes.maxOf { MapGeometry.x(it.order) } + MapGeometry.NODE_WIDTH
        val lowest = graph.nodes.maxOf { MapGeometry.y(it.level) } + MapGeometry.NODE_HEIGHT
        assertTrue(width >= rightmost)
        assertTrue(height >= lowest)
    }

    @Test
    fun `a name with a bracket in it cannot break the document`() {
        val odd = SchemaSvg.render(
            SchemaLayout.build(tables = listOf("a<b>&c"), edges = emptyList()),
            "db",
        )
        assertTrue(odd.contains("a&lt;b&gt;&amp;c"))
        assertFalse(odd.contains("<b>"))
    }

    @Test
    fun `an empty schema is still a valid document`() {
        val empty = SchemaSvg.render(SchemaGraph(), "db")
        assertTrue(empty.contains("<svg"))
        assertTrue(empty.trimEnd().endsWith("</svg>"))
    }

    @Test
    fun `the same schema always renders the same file`() {
        assertEquals(svg, SchemaSvg.render(graph, "webshop"))
    }
}
