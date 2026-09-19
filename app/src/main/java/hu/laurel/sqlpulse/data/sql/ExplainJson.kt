package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.backup.JsonException
import hu.laurel.sqlpulse.data.backup.JsonValue

/**
 * What a node in the plan is. The tabular `EXPLAIN` has one row per table and nothing else; the
 * JSON plan also names the steps the optimiser puts around those tables, and those steps are
 * usually where the time goes — a sort of a million rows costs more than reading them did.
 */
enum class ExplainNodeKind {
    /** One `SELECT`. The root of a plan, and the root of every subquery inside it. */
    QUERY_BLOCK,

    /** A table (or a derived table) being read. */
    TABLE,

    /** Tables joined one inside the other: the first is driven, the rest are looked up per row. */
    NESTED_LOOP,

    /** `ORDER BY`, whether it is satisfied by an index or by sorting afterwards. */
    ORDERING,

    /** `GROUP BY`, likewise. */
    GROUPING,

    /** `DISTINCT`, which MySQL reports as a step of its own. */
    DUPLICATES_REMOVAL,

    /** `UNION`, with one child per branch. */
    UNION,

    /** A subquery: correlated, in the select list, or attached to a condition. */
    SUBQUERY,

    /** A subquery whose result is written to a temporary table and then read back. */
    MATERIALISED,
}

/** A property of one step worth marking, beyond what its numbers already say. */
enum class ExplainPlanFlag {
    /** `access_type = ALL`: every row of the table is read. */
    FULL_TABLE_SCAN,

    /** `access_type = index`: the whole index is walked instead of the whole table. */
    FULL_INDEX_SCAN,

    /** No index was chosen for this table. */
    NO_INDEX,

    /** The index alone answers the query; the table itself is never touched. */
    COVERING_INDEX,

    /** The rows are sorted after being read. */
    FILESORT,

    /** An intermediate table is built. */
    TEMPORARY_TABLE,

    /** Rows of the outer table are buffered because the inner one has no usable index. */
    JOIN_BUFFER,

    /** The subquery is re-run for each row of the query around it. */
    DEPENDENT,
}

/**
 * One step of the plan.
 *
 * [id] is the node's position in the tree ("0.1.0"), not anything the server said. The tree is
 * rebuilt from scratch on every parse, so the path is stable for as long as the plan is on screen,
 * which is all the UI needs it for: remembering which branches are open, and knowing whether this
 * node is on the expensive branch.
 */
data class ExplainPlanNode(
    val id: String,
    val kind: ExplainNodeKind,
    /** The table's name, or the operation's, exactly as the server spelled it. */
    val label: String,
    val accessType: String? = null,
    val usedKey: String? = null,
    val possibleKeys: List<String> = emptyList(),
    /** How many rows this step reads per pass through it. */
    val rowsExamined: Long? = null,
    /** How many of them survive into the step above. */
    val rowsProduced: Long? = null,
    /** `filtered`, in percent: how much of what was read the condition keeps. */
    val filteredPercent: Double? = null,
    /**
     * What this step costs on its own, in the optimiser's units.
     *
     * Its own, not the running total: a plan is read to find the one step to change, and a
     * cumulative figure hides that step behind whatever follows it.
     */
    val cost: Double? = null,
    /** The `WHERE` fragment MySQL evaluates here, as the server rewrote it. */
    val attachedCondition: String? = null,
    /** What the server says instead of a plan, e.g. "no matching row in const table". */
    val message: String? = null,
    val flags: Set<ExplainPlanFlag> = emptySet(),
    val children: List<ExplainPlanNode> = emptyList(),
) {
    /** This node and everything under it, parents before children. */
    fun flatten(): List<ExplainPlanNode> = buildList {
        add(this@ExplainPlanNode)
        children.forEach { addAll(it.flatten()) }
    }
}

/**
 * A parsed `EXPLAIN FORMAT=JSON` plan.
 *
 * [notes] is the same short list of warnings the tabular reading produces, so a plan shown either
 * way says the same things about itself and the wording stays in one place.
 */
data class ExplainPlan(
    val root: ExplainPlanNode,
    /** The whole query's cost, when the server reported one. */
    val totalCost: Double?,
    /** False for a plan with no `cost_info` anywhere: MariaDB, and MySQL with the flag off. */
    val hasCostInfo: Boolean,
    val notes: List<ExplainNote>,
    /**
     * The ids of the nodes from the root down to the step that dominates the plan.
     *
     * Empty when nothing dominates — a plan of one node, or one where every step is free. A
     * highlight that is always on highlights nothing.
     */
    val expensiveBranch: List<String>,
) {
    /** The step at the end of the expensive branch: the one actually worth looking at. */
    val expensiveNodeId: String? get() = expensiveBranch.lastOrNull()
}

/** The outcome of trying to read a plan as JSON. */
sealed interface ExplainPlanResult {

    data class Parsed(val plan: ExplainPlan) : ExplainPlanResult

    /** No tree to show; the caller falls back to [ExplainAdvice] over the tabular plan. */
    data class Unavailable(val reason: ExplainUnavailable) : ExplainPlanResult
}

/** Why there is no tree. All of them end in the same place: show the tabular plan instead. */
enum class ExplainUnavailable {
    /** The server refused `EXPLAIN FORMAT=JSON`, or there was no text to read. */
    UNSUPPORTED,

    /** The text is not JSON at all. */
    NOT_JSON,

    /** Valid JSON, but not a query plan — no `query_block` anywhere in it. */
    NOT_A_PLAN,
}

/**
 * Reads `EXPLAIN FORMAT=JSON` into a tree.
 *
 * Why bother when [ExplainAdvice] already reads the tabular plan: the table flattens the plan into
 * one row per table and drops the shape. It cannot say which table drives the join, what the
 * optimiser attached where, or that the sort — not the scan — is what costs. The JSON keeps all of
 * that, and MySQL has produced it since 5.6.
 *
 * Nothing here is required to succeed. MariaDB words the same plan differently, older servers do
 * not understand the syntax at all, and a future version may add a step this code has never seen.
 * So every field is optional, unknown keys are ignored rather than refused — the opposite of the
 * strictness [hu.laurel.sqlpulse.data.backup.JsonValue] applies to a backup file, because here a
 * half-understood plan is still worth showing and a backup is not — and anything that cannot be
 * read at all comes back as [ExplainPlanResult.Unavailable] so the caller can show the table.
 */
object ExplainJson {

    /** Same threshold as the tabular reading: above this many rows a step is worth a look. */
    private const val MANY_ROWS = 100_000L

    fun of(text: String?): ExplainPlanResult {
        if (text.isNullOrBlank()) return ExplainPlanResult.Unavailable(ExplainUnavailable.UNSUPPORTED)
        val json = try {
            JsonValue.parse(text)
        } catch (e: JsonException) {
            return ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_JSON)
        }
        val root = (json as? JsonValue.Obj)
            ?: return ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_A_PLAN)
        val block = root.obj("query_block")
            ?: return ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_A_PLAN)

        val tree = blockNode(block, "0")
        val all = tree.flatten()
        val hasCost = all.any { it.cost != null }
        return ExplainPlanResult.Parsed(
            ExplainPlan(
                root = tree,
                totalCost = block.obj("cost_info")?.decimal("query_cost"),
                hasCostInfo = hasCost,
                notes = notesOf(all),
                expensiveBranch = expensiveBranch(tree, hasCost),
            ),
        )
    }

    /**
     * True when the server's complaint is that it does not know this syntax.
     *
     * `FORMAT=JSON` arrived in MySQL 5.6. Before that the word `FORMAT` is a syntax error, and a
     * syntax error is the one failure where retrying the plain `EXPLAIN` makes sense: anything
     * else — no such table, no privilege — would fail identically the second time.
     */
    fun isUnsupported(errorCode: Int, message: String?): Boolean {
        if (errorCode == 1064 || errorCode == 1149) return true
        val text = message?.lowercase() ?: return false
        return text.contains("syntax") && text.contains("format")
    }

    /* ---- the tree ------------------------------------------------------------------------- */

    /**
     * A `query_block` and everything hanging off it.
     *
     * A block holds at most one of `table`, `nested_loop`, `ordering_operation` and friends, but
     * which one varies and they nest into each other in whatever order the optimiser needed, so
     * the children are collected by looking for all of the shapes rather than by expecting one.
     */
    private fun blockNode(block: JsonValue.Obj, id: String): ExplainPlanNode {
        val selectId = block.decimal("select_id")?.toLong()
        return ExplainPlanNode(
            id = id,
            kind = ExplainNodeKind.QUERY_BLOCK,
            label = if (selectId != null) "select #$selectId" else "select",
            cost = block.obj("cost_info")?.decimal("query_cost"),
            message = block.text("message"),
            children = childrenOf(block, id),
        )
    }

    /**
     * The steps inside a container, in the order MySQL wrote them.
     *
     * The container may be a query block, a nested loop element, or an operation such as
     * `ordering_operation` — they all carry the same set of possible child keys, which is why one
     * function reads all of them.
     */
    private fun childrenOf(container: JsonValue.Obj, id: String): List<ExplainPlanNode> {
        val children = mutableListOf<ExplainPlanNode>()
        fun next(): String = "$id.${children.size}"

        container.fields.forEach { (key, value) ->
            when {
                key == "table" -> (value as? JsonValue.Obj)?.let { children += tableNode(it, next()) }

                key == "nested_loop" -> (value as? JsonValue.Arr)?.let { loop ->
                    children += nestedLoopNode(loop.items, next())
                }

                key == "ordering_operation" -> (value as? JsonValue.Obj)?.let {
                    children += operationNode(it, next(), ExplainNodeKind.ORDERING, "order by")
                }

                key == "grouping_operation" -> (value as? JsonValue.Obj)?.let {
                    children += operationNode(it, next(), ExplainNodeKind.GROUPING, "group by")
                }

                key == "duplicates_removal" -> (value as? JsonValue.Obj)?.let {
                    children += operationNode(it, next(), ExplainNodeKind.DUPLICATES_REMOVAL, "distinct")
                }

                key == "union_result" -> (value as? JsonValue.Obj)?.let {
                    children += unionNode(it, next())
                }

                key == "materialized_from_subquery" -> (value as? JsonValue.Obj)?.let {
                    children += operationNode(it, next(), ExplainNodeKind.MATERIALISED, "materialised")
                }

                // select_list_subqueries, group_by_subqueries, attached_subqueries and the rest:
                // every one of them is an array of blocks, and there is no use in naming them
                // separately when a future server may add another.
                key.endsWith("subqueries") -> (value as? JsonValue.Arr)?.items?.forEach { item ->
                    (item as? JsonValue.Obj)?.let { children += subqueryNode(it, next()) }
                }

                // A block nested directly, which is what a materialised subquery looks like
                // inside the table that reads it.
                key == "query_block" -> (value as? JsonValue.Obj)?.let {
                    children += blockNode(it, next())
                }
            }
        }
        return children
    }

    private fun nestedLoopNode(items: List<JsonValue>, id: String): ExplainPlanNode {
        val children = mutableListOf<ExplainPlanNode>()
        items.forEach { item ->
            val element = item as? JsonValue.Obj ?: return@forEach
            // Each element is normally {"table": {...}}, but the optimiser may put an operation
            // there instead, so it goes through the same reader as anything else. The element
            // itself is not a step and gets no node: it would be one wrapper per joined table,
            // all of them empty.
            childrenOf(element, id).forEach { child ->
                children += child.reparent("$id.${children.size}")
            }
        }
        return ExplainPlanNode(
            id = id,
            kind = ExplainNodeKind.NESTED_LOOP,
            label = "nested loop",
            children = children,
        )
    }

    private fun operationNode(
        obj: JsonValue.Obj,
        id: String,
        kind: ExplainNodeKind,
        label: String,
    ): ExplainPlanNode {
        val flags = mutableSetOf<ExplainPlanFlag>()
        if (obj.flag("using_filesort")) flags += ExplainPlanFlag.FILESORT
        if (obj.flag("using_temporary_table")) flags += ExplainPlanFlag.TEMPORARY_TABLE
        return ExplainPlanNode(
            id = id,
            kind = kind,
            label = label,
            cost = obj.obj("cost_info")?.decimal("sort_cost"),
            flags = flags,
            children = childrenOf(obj, id),
        )
    }

    private fun unionNode(obj: JsonValue.Obj, id: String): ExplainPlanNode {
        val children = mutableListOf<ExplainPlanNode>()
        (obj.fields["query_specifications"] as? JsonValue.Arr)?.items?.forEach { item ->
            (item as? JsonValue.Obj)?.let { children += subqueryNode(it, "$id.${children.size}") }
        }
        val temporary = if (obj.flag("using_temporary_table")) {
            setOf(ExplainPlanFlag.TEMPORARY_TABLE)
        } else {
            emptySet()
        }
        return ExplainPlanNode(
            id = id,
            kind = ExplainNodeKind.UNION,
            label = "union",
            flags = temporary,
            children = children,
        )
    }

    /**
     * A subquery holder: `{"dependent": true, "cacheable": false, "query_block": {...}}`.
     *
     * The holder itself is dropped and its block takes its place, with the dependence carried
     * onto the block — one node instead of two, and "runs once per row" is a property of the
     * subquery rather than a step of its own.
     */
    private fun subqueryNode(holder: JsonValue.Obj, id: String): ExplainPlanNode {
        val block = holder.obj("query_block")
        val dependent = holder.flag("dependent")
        val node = if (block != null) blockNode(block, id) else {
            ExplainPlanNode(id = id, kind = ExplainNodeKind.SUBQUERY, label = "subquery")
        }
        return node.copy(
            kind = ExplainNodeKind.SUBQUERY,
            flags = if (dependent) node.flags + ExplainPlanFlag.DEPENDENT else node.flags,
        )
    }

    private fun tableNode(table: JsonValue.Obj, id: String): ExplainPlanNode {
        val accessType = table.text("access_type")
        val key = table.text("key")
        val flags = mutableSetOf<ExplainPlanFlag>()
        when (accessType?.lowercase()) {
            "all" -> flags += ExplainPlanFlag.FULL_TABLE_SCAN
            "index" -> flags += ExplainPlanFlag.FULL_INDEX_SCAN
        }
        if (key == null) flags += ExplainPlanFlag.NO_INDEX
        if (table.flag("using_index")) flags += ExplainPlanFlag.COVERING_INDEX
        if (table.flag("using_filesort")) flags += ExplainPlanFlag.FILESORT
        if (table.flag("using_temporary_table")) flags += ExplainPlanFlag.TEMPORARY_TABLE
        if (table.text("using_join_buffer") != null) flags += ExplainPlanFlag.JOIN_BUFFER

        // read_cost plus eval_cost is what this table costs on its own. prefix_cost would be the
        // obvious field, but it includes everything joined before it, so the last table of a join
        // always carries the largest number and the table actually responsible never stands out.
        // A derived table reports query_cost instead and nothing else, so that is the fallback.
        val cost = table.obj("cost_info")?.let { info ->
            val read = info.decimal("read_cost")
            val eval = info.decimal("eval_cost")
            when {
                read != null || eval != null -> (read ?: 0.0) + (eval ?: 0.0)
                else -> info.decimal("prefix_cost") ?: info.decimal("query_cost")
            }
        }

        return ExplainPlanNode(
            id = id,
            kind = ExplainNodeKind.TABLE,
            label = table.text("table_name") ?: "?",
            accessType = accessType,
            usedKey = key,
            possibleKeys = table.strings("possible_keys"),
            rowsExamined = table.decimal("rows_examined_per_scan")?.toLong()
                ?: table.decimal("rows")?.toLong(),
            rowsProduced = table.decimal("rows_produced_per_join")?.toLong(),
            filteredPercent = table.decimal("filtered"),
            cost = cost,
            attachedCondition = table.text("attached_condition"),
            message = table.text("message"),
            flags = flags,
            children = childrenOf(table, id),
        )
    }

    /** Rebuilds a node's id, and its children's, under a new parent path. */
    private fun ExplainPlanNode.reparent(id: String): ExplainPlanNode = copy(
        id = id,
        children = children.mapIndexed { index, child -> child.reparent("$id.$index") },
    )

    /* ---- reading the plan ----------------------------------------------------------------- */

    /**
     * The same warnings [ExplainAdvice] draws from the table, drawn from the tree.
     *
     * Deliberately the same short list and the same enum: the JSON plan knows more, but a second
     * vocabulary of warnings would mean two sets of translated sentences saying the same thing,
     * and the extra detail is already visible in the tree itself.
     */
    private fun notesOf(nodes: List<ExplainPlanNode>): List<ExplainNote> {
        val notes = linkedSetOf<ExplainNote>()
        nodes.forEach { node ->
            if (ExplainPlanFlag.FULL_TABLE_SCAN in node.flags) notes += ExplainNote.FULL_TABLE_SCAN
            if (ExplainPlanFlag.FULL_INDEX_SCAN in node.flags) notes += ExplainNote.FULL_INDEX_SCAN
            // Only a table can be missing an index; an operation never has one to begin with.
            if (node.kind == ExplainNodeKind.TABLE && ExplainPlanFlag.NO_INDEX in node.flags) {
                notes += ExplainNote.NO_INDEX
            }
            if (ExplainPlanFlag.FILESORT in node.flags) notes += ExplainNote.FILESORT
            if (ExplainPlanFlag.TEMPORARY_TABLE in node.flags) notes += ExplainNote.TEMPORARY_TABLE
            val rows = node.rowsExamined
            if (rows != null && rows >= MANY_ROWS) notes += ExplainNote.MANY_ROWS
        }
        return notes.toList()
    }

    /**
     * The path from the root to the step that dominates the plan.
     *
     * Cost is the measure when the server gave one, because it already accounts for rows, row
     * width and whether the read is sequential. When there is no cost anywhere — MariaDB, or
     * `optimizer_cost_model` turned off — rows examined is the fallback: cruder, but it is the
     * number that makes a full scan of a large table stand out, which is the case worth seeing.
     *
     * Returns nothing when the winner is not a real winner: a plan of a single step, or one where
     * every step measures zero. A highlight that is always on carries no information.
     */
    private fun expensiveBranch(root: ExplainPlanNode, hasCost: Boolean): List<String> {
        fun weight(node: ExplainPlanNode): Double =
            if (hasCost) node.cost ?: 0.0 else (node.rowsExamined?.toDouble() ?: 0.0)

        // The heaviest single step anywhere in this subtree. Max rather than sum: MySQL's
        // prefix_cost is already cumulative, so adding it up would count the same work twice.
        fun peak(node: ExplainPlanNode): Double =
            maxOf(weight(node), node.children.maxOfOrNull { peak(it) } ?: 0.0)

        if (peak(root) <= 0.0 || root.flatten().size < 2) return emptyList()

        val path = mutableListOf(root.id)
        var node = root
        while (node.children.isNotEmpty()) {
            val heaviest = node.children.maxByOrNull { peak(it) } ?: break
            // Stop where the branch below is lighter than this step itself: this is the step that
            // costs, not the ones under it.
            if (peak(heaviest) <= weight(node) && node !== root) break
            path += heaviest.id
            node = heaviest
        }
        return path
    }

    /* ---- readers -------------------------------------------------------------------------- */

    /*
     * Lenient by design, unlike the readers in the backup codec: a key that is missing, null, or
     * of a type this version of MySQL did not use last year is an absent value, never an error.
     * A plan shown with one field blank is useful; a plan refused because of one field is not.
     */

    private fun JsonValue.Obj.obj(name: String): JsonValue.Obj? = fields[name] as? JsonValue.Obj

    private fun JsonValue.Obj.text(name: String): String? =
        (fields[name] as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() }

    private fun JsonValue.Obj.flag(name: String): Boolean = when (val value = fields[name]) {
        is JsonValue.Bool -> value.value
        // MariaDB writes some of these as "true"/"false" text rather than as JSON booleans.
        is JsonValue.Str -> value.value.equals("true", ignoreCase = true)
        else -> false
    }

    /**
     * A number, whether the server wrote it as a JSON number or as a string.
     *
     * MySQL quotes the cost figures ("query_cost": "1.20") and the filtered percentage but not the
     * row counts, and which of them are quoted has changed between versions.
     */
    private fun JsonValue.Obj.decimal(name: String): Double? = when (val value = fields[name]) {
        is JsonValue.Num -> value.text.toDoubleOrNull()
        is JsonValue.Str -> value.value.toDoubleOrNull()
        else -> null
    }

    private fun JsonValue.Obj.strings(name: String): List<String> =
        (fields[name] as? JsonValue.Arr)?.items.orEmpty()
            .mapNotNull { (it as? JsonValue.Str)?.value }
}
