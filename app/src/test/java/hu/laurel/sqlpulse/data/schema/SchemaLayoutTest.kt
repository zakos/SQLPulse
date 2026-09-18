package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaLayoutTest {

    private fun edge(from: String, to: String) = GraphEdge(from, to, listOf("id"))

    @Test
    fun `a child sits below the table it references`() {
        val graph = SchemaLayout.build(
            tables = listOf("order_item", "orders", "customer"),
            edges = listOf(edge("orders", "customer"), edge("order_item", "orders")),
        )
        assertEquals(0, graph.node("customer")!!.level)
        assertEquals(1, graph.node("orders")!!.level)
        assertEquals(2, graph.node("order_item")!!.level)
    }

    @Test
    fun `a table goes below the deepest parent it has, not the first`() {
        val graph = SchemaLayout.build(
            tables = listOf("a", "b", "c", "d"),
            edges = listOf(edge("b", "a"), edge("c", "b"), edge("d", "a"), edge("d", "c")),
        )
        assertEquals(3, graph.node("d")!!.level)
    }

    @Test
    fun `two tables referencing each other do not hang the layout`() {
        val graph = SchemaLayout.build(
            tables = listOf("left", "right"),
            edges = listOf(edge("left", "right"), edge("right", "left")),
        )
        assertEquals(2, graph.nodes.size)
        assertEquals(2, graph.edges.size)
    }

    @Test
    fun `a longer cycle also terminates`() {
        val graph = SchemaLayout.build(
            tables = listOf("a", "b", "c"),
            edges = listOf(edge("a", "b"), edge("b", "c"), edge("c", "a")),
        )
        assertEquals(3, graph.nodes.size)
    }

    @Test
    fun `a table referencing itself is drawn but ranks nothing`() {
        val graph = SchemaLayout.build(
            tables = listOf("employee"),
            edges = listOf(edge("employee", "employee")),
        )
        assertEquals(0, graph.node("employee")!!.level)
        assertTrue(graph.edges.single().isSelfReference)
    }

    @Test
    fun `a link to another schema is dropped rather than drawn into nothing`() {
        val graph = SchemaLayout.build(
            tables = listOf("orders"),
            edges = listOf(edge("orders", "other_schema_customer")),
        )
        assertTrue(graph.edges.isEmpty())
        // With no link left, the table belongs with the unconnected ones.
        assertFalse(graph.node("orders")!!.connected)
    }

    @Test
    fun `tables no foreign key touches are laid out under the graph in rows`() {
        val graph = SchemaLayout.build(
            tables = listOf("orders", "customer", "log", "config", "cache", "flags", "audit"),
            edges = listOf(edge("orders", "customer")),
        )
        val loose = graph.nodes.filterNot { it.connected }
        assertEquals(listOf("audit", "cache", "config", "flags", "log"), loose.map { it.table })
        // Below the deepest connected row, three to a row for five tables.
        assertEquals(2, loose.first().level)
        assertEquals(listOf(0, 1, 2, 0, 1), loose.map { it.order })
        assertEquals(3, loose.last().level)
    }

    @Test
    fun `a schema with no links at all becomes a squarish block, not a tall column`() {
        val tables = (1..64).map { "t%02d".format(it) }
        val graph = SchemaLayout.build(tables, emptyList())
        assertEquals(8, SchemaLayout.isolatedColumns(64))
        // 64 tables in rows of 8: eight rows, not sixty-four.
        assertEquals(7, graph.nodes.maxOf { it.level })
        assertTrue(graph.nodes.none { it.connected })
    }

    @Test
    fun `the block never gets narrower than three or wider than eight`() {
        assertEquals(3, SchemaLayout.isolatedColumns(1))
        assertEquals(3, SchemaLayout.isolatedColumns(9))
        assertEquals(4, SchemaLayout.isolatedColumns(16))
        assertEquals(8, SchemaLayout.isolatedColumns(400))
    }

    @Test
    fun `the same schema always draws the same map`() {
        val tables = listOf("c", "a", "b")
        val edges = listOf(edge("b", "a"), edge("c", "a"))
        val first = SchemaLayout.build(tables, edges)
        val second = SchemaLayout.build(tables.reversed(), edges.reversed())
        assertEquals(first.nodes, second.nodes)
    }

    @Test
    fun `duplicate links are drawn once`() {
        val graph = SchemaLayout.build(
            tables = listOf("a", "b"),
            edges = listOf(edge("b", "a"), edge("b", "a")),
        )
        assertEquals(1, graph.edges.size)
    }

    @Test
    fun `an empty schema is empty rather than a crash`() {
        val graph = SchemaLayout.build(emptyList(), emptyList())
        assertTrue(graph.isEmpty)
        assertFalse(graph.hasLinks)
        assertNull(graph.node("anything"))
    }

    @Test
    fun `row counts ride along for the labels`() {
        val graph = SchemaLayout.build(
            tables = listOf("orders"),
            edges = emptyList(),
            rows = mapOf("orders" to 1_200L),
        )
        assertEquals(1_200L, graph.node("orders")!!.rows)
    }
}
