package hu.laurel.sqlpulse.data.sql.plan

import hu.laurel.sqlpulse.data.backup.JsonException
import hu.laurel.sqlpulse.data.backup.JsonValue
import hu.laurel.sqlpulse.data.sql.ExplainJson
import hu.laurel.sqlpulse.data.sql.ExplainNodeKind
import hu.laurel.sqlpulse.data.sql.ExplainPlan
import hu.laurel.sqlpulse.data.sql.ExplainPlanFlag
import hu.laurel.sqlpulse.data.sql.ExplainPlanNode
import hu.laurel.sqlpulse.data.sql.ExplainPlanResult
import hu.laurel.sqlpulse.data.sql.ExplainUnavailable
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine

/**
 * PostgreSQL's `EXPLAIN (FORMAT JSON)`: a one-element array whose `Plan` is a tree of nodes, each
 * with `Plans` as children.
 *
 * Only the estimate is ever asked for. `EXPLAIN ANALYZE` runs the statement, and an editor that
 * explains a `DELETE` must not delete anything — so there are no actual times here to read, and
 * none are looked for.
 *
 * Costs: PostgreSQL's `Total Cost` is cumulative (a node's figure includes everything below it),
 * and a plan read to find the step to change needs the step's own share. It is the total minus the
 * children's totals, never below zero: a nested loop's inner side is costed per pass, so the
 * subtraction can overshoot, and a negative cost would draw a bar backwards.
 */
object PostgresPlanReader : PlanReader {

    override val engine = DatabaseEngine.POSTGRESQL

    override fun read(table: ResultTable): ExplainPlanResult {
        val text = PlanReaders.singleText(table, "[")
            ?: return ExplainPlanResult.Unavailable(ExplainUnavailable.UNSUPPORTED)
        return of(text)
    }

    fun of(text: String): ExplainPlanResult {
        val json = try {
            JsonValue.parse(text)
        } catch (e: JsonException) {
            return ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_JSON)
        }
        val plan = ((json as? JsonValue.Arr)?.items?.firstOrNull() as? JsonValue.Obj)?.obj("Plan")
            ?: return ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_A_PLAN)

        // The same shape MySQL's plan has: a block that carries the whole query's cost over the
        // steps. The expensive-branch walk relies on it — it always descends from the root, and
        // a root that is itself a step would never be the one picked.
        val root = ExplainPlanNode(
            id = "0",
            kind = ExplainNodeKind.QUERY_BLOCK,
            label = "query",
            cost = plan.decimal("Total Cost"),
            children = listOf(node(plan, "0.0")),
        )
        val all = root.flatten()
        val hasCost = all.any { it.cost != null }
        return ExplainPlanResult.Parsed(
            ExplainPlan(
                root = root,
                totalCost = plan.decimal("Total Cost"),
                hasCostInfo = hasCost,
                notes = ExplainJson.notesOf(all),
                expensiveBranch = ExplainJson.expensiveBranch(root, hasCost),
                engine = DatabaseEngine.POSTGRESQL,
            ),
        )
    }

    private fun node(plan: JsonValue.Obj, id: String): ExplainPlanNode {
        val relationship = plan.text("Parent Relationship")
        // A subplan is the one place a node is attached to its parent by something other than
        // "this is my input": it is a subquery the parent runs, once (InitPlan) or per row
        // (SubPlan). Wrapped, the plan shows it as the subquery it is with the real node under it.
        return if (relationship == "InitPlan" || relationship == "SubPlan") {
            ExplainPlanNode(
                id = id,
                kind = ExplainNodeKind.SUBQUERY,
                label = plan.text("Subplan Name") ?: relationship,
                flags = if (relationship == "SubPlan") setOf(ExplainPlanFlag.DEPENDENT) else emptySet(),
                children = listOf(build(plan, "$id.0")),
            )
        } else {
            build(plan, id)
        }
    }

    private fun build(plan: JsonValue.Obj, id: String): ExplainPlanNode {
        val type = plan.text("Node Type") ?: "?"
        val relation = plan.text("Relation Name")
        val index = plan.text("Index Name")
        val children = (plan.fields["Plans"] as? JsonValue.Arr)?.items.orEmpty()
            .filterIsInstance<JsonValue.Obj>()

        val builtChildren = children.mapIndexed { i, child -> node(child, "$id.$i") }
        val flags = mutableSetOf<ExplainPlanFlag>()
        val kind: ExplainNodeKind
        val label: String
        var accessType: String? = null

        when {
            type == "Seq Scan" -> {
                kind = ExplainNodeKind.TABLE
                label = relationLabel(plan, relation)
                accessType = type
                // Every row of the table is read and no index took part, the PostgreSQL spelling
                // of MySQL's access type ALL.
                flags += ExplainPlanFlag.FULL_TABLE_SCAN
                flags += ExplainPlanFlag.NO_INDEX
            }

            type == "Index Only Scan" -> {
                kind = ExplainNodeKind.TABLE
                label = relationLabel(plan, relation)
                accessType = type
                flags += ExplainPlanFlag.COVERING_INDEX
            }

            type == "Index Scan" || type == "Bitmap Heap Scan" || type == "Tid Scan" ||
                type == "Tid Range Scan" || type == "Sample Scan" -> {
                kind = if (relation != null) ExplainNodeKind.TABLE else ExplainNodeKind.OPERATION
                label = if (relation != null) relationLabel(plan, relation) else type
                accessType = type
            }

            type == "Bitmap Index Scan" -> {
                // Has an index but no relation: the heap scan above it names the table.
                kind = ExplainNodeKind.OPERATION
                label = type
                accessType = type
            }

            type == "Function Scan" || type == "Table Function Scan" -> {
                kind = ExplainNodeKind.OPERATION
                label = "$type ${plan.text("Function Name") ?: plan.text("Alias") ?: ""}".trim()
            }

            type == "CTE Scan" || type == "Subquery Scan" || type == "Values Scan" ||
                type == "WorkTable Scan" || type == "Named Tuplestore Scan" -> {
                kind = ExplainNodeKind.OPERATION
                label = "$type ${plan.text("CTE Name") ?: plan.text("Alias") ?: ""}".trim()
            }

            type == "Nested Loop" -> {
                kind = ExplainNodeKind.NESTED_LOOP
                label = joinLabel("nested loop", plan)
            }

            type.endsWith(" Join") -> {
                kind = ExplainNodeKind.JOIN
                label = joinLabel(type.lowercase(), plan)
            }

            type == "Sort" || type == "Incremental Sort" -> {
                kind = ExplainNodeKind.ORDERING
                val key = plan.strings("Sort Key").joinToString(", ")
                label = if (key.isEmpty()) type else "$type: $key"
                flags += ExplainPlanFlag.FILESORT
            }

            type == "Aggregate" || type == "GroupAggregate" || type == "HashAggregate" ||
                type == "MixedAggregate" -> {
                kind = ExplainNodeKind.GROUPING
                val strategy = plan.text("Strategy")
                val key = plan.strings("Group Key").joinToString(", ")
                label = buildString {
                    append(type)
                    if (strategy != null) append(" ($strategy)")
                    if (key.isNotEmpty()) append(": ").append(key)
                }
            }

            type == "Unique" -> {
                kind = ExplainNodeKind.DUPLICATES_REMOVAL
                label = type
            }

            type == "Append" || type == "Merge Append" || type == "Recursive Union" || type == "SetOp" -> {
                kind = ExplainNodeKind.UNION
                label = type
            }

            type == "Materialize" || type == "Memoize" -> {
                kind = ExplainNodeKind.MATERIALISED
                label = type
            }

            else -> {
                // Hash, Limit, Gather, Result, LockRows, ProjectSet, WindowAgg …: steps with
                // nothing of their own to show but their name and numbers.
                kind = ExplainNodeKind.OPERATION
                label = type
            }
        }

        val totalCost = plan.decimal("Total Cost")
        // PostgreSQL's costs are running totals: this step's own share is what is left after
        // taking its inputs out.
        val childTotals = children.sumOf { it.decimal("Total Cost") ?: 0.0 }
        val cost = totalCost?.let { (it - childTotals).coerceAtLeast(0.0) }

        val conditions = listOfNotNull(
            plan.text("Index Cond")?.let { "Index Cond: $it" },
            plan.text("Recheck Cond")?.let { "Recheck Cond: $it" },
            plan.text("Hash Cond")?.let { "Hash Cond: $it" },
            plan.text("Merge Cond")?.let { "Merge Cond: $it" },
            plan.text("Join Filter")?.let { "Join Filter: $it" },
            plan.text("Filter")?.let { "Filter: $it" },
        )

        return ExplainPlanNode(
            id = id,
            kind = kind,
            label = label,
            accessType = accessType,
            // A bitmap heap scan has no index of its own: the Bitmap Index Scan under it does, and
            // "no index" on the table row would say the opposite of what the plan does.
            usedKey = index ?: if (type == "Bitmap Heap Scan") bitmapIndex(plan) else null,
            rowsExamined = plan.decimal("Plan Rows")?.toLong(),
            cost = cost,
            attachedCondition = conditions.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            flags = flags,
            children = builtChildren,
        )
    }

    /** The first index named by a Bitmap Index Scan anywhere below [plan] (BitmapAnd/BitmapOr nest them). */
    private fun bitmapIndex(plan: JsonValue.Obj): String? =
        (plan.fields["Plans"] as? JsonValue.Arr)?.items.orEmpty().filterIsInstance<JsonValue.Obj>().firstNotNullOfOrNull {
            it.text("Index Name") ?: bitmapIndex(it)
        }

    /** `orders`, or `orders o` when the query gave the table another name. */
    private fun relationLabel(plan: JsonValue.Obj, relation: String?): String {
        val schema = plan.text("Schema")
        val name = relation ?: "?"
        val qualified = if (schema != null && schema != "public") "$schema.$name" else name
        val alias = plan.text("Alias")
        return if (alias != null && alias != name) "$qualified $alias" else qualified
    }

    private fun joinLabel(name: String, plan: JsonValue.Obj): String {
        val joinType = plan.text("Join Type")
        return if (joinType != null && joinType != "Inner") "$name ($joinType)" else name
    }

    private fun JsonValue.Obj.obj(name: String): JsonValue.Obj? = fields[name] as? JsonValue.Obj

    private fun JsonValue.Obj.text(name: String): String? =
        (fields[name] as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() }

    private fun JsonValue.Obj.decimal(name: String): Double? = when (val value = fields[name]) {
        is JsonValue.Num -> value.text.toDoubleOrNull()
        is JsonValue.Str -> value.value.toDoubleOrNull()
        else -> null
    }

    private fun JsonValue.Obj.strings(name: String): List<String> =
        (fields[name] as? JsonValue.Arr)?.items.orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }
}
