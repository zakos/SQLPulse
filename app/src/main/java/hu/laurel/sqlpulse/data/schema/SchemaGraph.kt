package hu.laurel.sqlpulse.data.schema

/**
 * One foreign key drawn as a link: [from] (the child, which holds the column) points at [to] (the
 * parent it references). Several columns of one constraint are a single link.
 */
data class GraphEdge(
    val from: String,
    val to: String,
    val columns: List<String> = emptyList(),
    /**
     * True when no foreign key says this link exists and it was read off the column names
     * instead. Drawn dashed, and never presented as fact: plenty of older schemas — MyISAM, or
     * anything built before InnoDB was the default — have no foreign keys at all, and a map of
     * such a database would otherwise be a wall of unconnected boxes.
     */
    val guessed: Boolean = false,
) {
    /** A table referencing itself — a parent id, a tree. Drawn, but it cannot rank anything. */
    val isSelfReference: Boolean get() = from == to
}

/**
 * A table on the map.
 *
 * [level] is the row it sits in and [order] its place within that row; turning those into pixels
 * is the screen's business, not this file's.
 */
data class GraphNode(
    val table: String,
    val level: Int,
    val order: Int,
    val rows: Long? = null,
    /** False for a table no foreign key touches; those are laid out separately. */
    val connected: Boolean = true,
)

data class SchemaGraph(
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    /** True when nothing references anything: a map of a schema with no foreign keys at all. */
    val hasLinks: Boolean get() = edges.isNotEmpty()

    fun node(table: String): GraphNode? = nodes.firstOrNull { it.table == table }
}

/**
 * Arranges the tables of one database into rows (research summary, §2.0 — "what does this schema
 * look like").
 *
 * Parents above children: a table sits one row below the deepest table it references, so reading
 * top to bottom follows the foreign keys. Deterministic — the same schema always draws the same
 * map, because a map that rearranges itself on every refresh cannot be learned.
 */
object SchemaLayout {

    /** Tables nothing references are laid out below the graph, in a squarish block. */
    const val MIN_ISOLATED_COLUMNS = 3
    const val MAX_ISOLATED_COLUMNS = 8

    /**
     * How wide the block of unconnected tables should be.
     *
     * Roughly square, so a schema of a hundred unlinked tables is a block that fits the screen
     * rather than a column twenty-five rows tall that has to be zoomed out until the names are
     * unreadable — which is exactly what a real, older schema looks like.
     */
    fun isolatedColumns(count: Int): Int {
        if (count <= 0) return MIN_ISOLATED_COLUMNS
        val square = Math.ceil(Math.sqrt(count.toDouble())).toInt()
        return square.coerceIn(MIN_ISOLATED_COLUMNS, MAX_ISOLATED_COLUMNS)
    }

    /**
     * @param tables every table in the database, in any order.
     * @param edges foreign keys; links pointing outside [tables] (another schema) are dropped,
     *   because a box for a table this map cannot show would be a dead end.
     */
    fun build(
        tables: List<String>,
        edges: List<GraphEdge>,
        rows: Map<String, Long?> = emptyMap(),
    ): SchemaGraph {
        val known = tables.toSet()
        val kept = edges.filter { it.from in known && it.to in known }.distinct()

        val levels = rank(tables, kept)
        val linked = kept.flatMap { listOf(it.from, it.to) }.toSet()

        val connected = tables.filter { it in linked }
        val isolated = tables.filterNot { it in linked }.sorted()

        val nodes = mutableListOf<GraphNode>()
        connected.groupBy { levels[it] ?: 0 }
            .toSortedMap()
            .forEach { (level, inLevel) ->
                inLevel.sorted().forEachIndexed { order, table ->
                    nodes += GraphNode(table, level, order, rows[table], connected = true)
                }
            }

        // The rest go underneath in a plain grid: they have no relationship to draw, but leaving
        // them off would make the map look like half the schema does not exist.
        val firstFreeLevel = (nodes.maxOfOrNull { it.level } ?: -1) + 1
        val perRow = isolatedColumns(isolated.size)
        isolated.forEachIndexed { index, table ->
            nodes += GraphNode(
                table = table,
                level = firstFreeLevel + index / perRow,
                order = index % perRow,
                rows = rows[table],
                connected = false,
            )
        }

        return SchemaGraph(nodes.sortedWith(compareBy({ it.level }, { it.order })), kept)
    }

    /**
     * The row each table belongs to: one below the deepest table it references.
     *
     * Repeated relaxation rather than a topological sort, because a schema is allowed to have
     * cycles — two tables referencing each other is unusual but legal, and a sort would either
     * fail or loop. The pass count is bounded by the number of tables, so a cycle stops instead of
     * hanging; the tables in it simply end up a row apart.
     */
    private fun rank(tables: List<String>, edges: List<GraphEdge>): Map<String, Int> {
        val levels = tables.associateWith { 0 }.toMutableMap()
        val ranking = edges.filterNot { it.isSelfReference }
        repeat(tables.size) {
            var changed = false
            ranking.forEach { edge ->
                val parent = levels[edge.to] ?: 0
                val child = levels[edge.from] ?: 0
                if (child <= parent) {
                    levels[edge.from] = parent + 1
                    changed = true
                }
            }
            if (!changed) return levels
        }
        return levels
    }
}
