package hu.laurel.sqlpulse.data.sql.plan

import hu.laurel.sqlpulse.data.sql.CellValue
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
 * SQLite's `EXPLAIN QUERY PLAN`: rows of `(id, parent, notused, detail)`, where `parent` is the
 * `id` of the row a step hangs under (0 for the top level) and `detail` is a sentence such as
 * `SEARCH orders USING INDEX orders_customer (customer_id=?)`.
 *
 * SQLite reports no costs and no row estimates, so the tree has neither: the cost bar and the
 * "heaviest step" mark do not appear (nothing to measure them by), and what the plan can say is
 * the one distinction that matters for SQLite performance — `SCAN` reads the whole table, `SEARCH`
 * goes through an index or the rowid — plus the temporary b-trees it builds for sorting.
 *
 * The `detail` text is not a stable format; SQLite documents it as being for people. It is read
 * leniently — a line that matches nothing is still shown, as a step with its text for a label.
 */
object SqlitePlanReader : PlanReader {

    override val engine = DatabaseEngine.SQLITE

    private val COLUMNS = listOf("id", "parent", "detail")

    /** True for an `EXPLAIN QUERY PLAN` result, however the user came to run it. */
    fun recognises(table: ResultTable): Boolean {
        val labels = table.columns.map { it.label.lowercase() }
        return COLUMNS.all { it in labels } && table.columns.size == 4
    }

    override fun read(table: ResultTable): ExplainPlanResult {
        if (!recognises(table)) return ExplainPlanResult.Unavailable(ExplainUnavailable.UNSUPPORTED)
        val labels = table.columns.map { it.label.lowercase() }
        val idColumn = labels.indexOf("id")
        val parentColumn = labels.indexOf("parent")
        val detailColumn = labels.indexOf("detail")
        val rows = table.rows.mapNotNull { row ->
            val id = row.getOrNull(idColumn)?.asLong() ?: return@mapNotNull null
            val parent = row.getOrNull(parentColumn)?.asLong() ?: 0L
            val detail = (row.getOrNull(detailColumn) as? CellValue.Text)?.value ?: return@mapNotNull null
            Step(id, parent, detail.trim())
        }
        if (rows.isEmpty()) return ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_A_PLAN)
        return of(rows)
    }

    /** One row of the result, before it is placed in the tree. */
    class Step(val id: Long, val parent: Long, val detail: String)

    fun of(steps: List<Step>): ExplainPlanResult {
        val known = steps.map { it.id }.toSet()
        fun build(step: Step, id: String): ExplainPlanNode {
            val children = steps.filter { it.parent == step.id && it !== step }
            return node(step.detail, id).copy(
                children = children.mapIndexed { i, child -> build(child, "$id.$i") },
            )
        }

        // A step whose parent is not in the result (0, or an id SQLite did not print) is a top
        // level step: a plan is never refused because its numbering has a gap.
        val tops = steps.filter { it.parent == 0L || it.parent !in known }
        val root = ExplainPlanNode(
            id = "0",
            kind = ExplainNodeKind.QUERY_BLOCK,
            label = "query plan",
            children = tops.mapIndexed { i, step -> build(step, "0.$i") },
        )
        val all = root.flatten()
        return ExplainPlanResult.Parsed(
            ExplainPlan(
                root = root,
                totalCost = null,
                hasCostInfo = false,
                notes = ExplainJson.notesOf(all),
                expensiveBranch = emptyList(),
                engine = DatabaseEngine.SQLITE,
            ),
        )
    }

    private val SCAN_OR_SEARCH = Regex(
        "^(SCAN|SEARCH)\\s+(?:TABLE\\s+)?(.+?)(?:\\s+USING\\s+(.*))?$",
        RegexOption.IGNORE_CASE,
    )

    /** `INDEX name (cols=?)`, `COVERING INDEX name`, `INTEGER PRIMARY KEY (rowid=?)`, `AUTOMATIC COVERING INDEX (…)`. */
    private val USING = Regex(
        "^(?:(AUTOMATIC)\\s+)?(?:(COVERING)\\s+)?(?:INDEX\\s*([^\\s(]*)|INTEGER PRIMARY KEY|PRIMARY KEY)\\s*(\\(.*\\))?",
        RegexOption.IGNORE_CASE,
    )

    private fun node(detail: String, id: String): ExplainPlanNode {
        val scan = SCAN_OR_SEARCH.matchEntire(detail)
        if (scan != null) {
            val access = scan.groupValues[1].uppercase()
            // "orders AS o" keeps the alias; a subquery or constant row is not a table at all.
            val name = scan.groupValues[2].trim()
            val using = scan.groupValues[3]
            val parsed = USING.find(using)
            val automatic = parsed?.groupValues?.get(1)?.isNotEmpty() == true
            val covering = parsed?.groupValues?.get(2)?.isNotEmpty() == true
            val indexName = parsed?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }
            val condition = parsed?.groupValues?.get(4)?.takeIf { it.isNotEmpty() }
            val byRowid = using.startsWith("INTEGER PRIMARY KEY", ignoreCase = true) ||
                using.startsWith("PRIMARY KEY", ignoreCase = true)

            val flags = mutableSetOf<ExplainPlanFlag>()
            if (access == "SCAN") {
                if (indexName != null) {
                    // A scan of an index is the whole index, which is cheaper than the table but
                    // still everything.
                    flags += ExplainPlanFlag.FULL_INDEX_SCAN
                } else {
                    flags += ExplainPlanFlag.FULL_TABLE_SCAN
                    flags += ExplainPlanFlag.NO_INDEX
                }
            }
            if (covering) flags += ExplainPlanFlag.COVERING_INDEX
            if (automatic) flags += ExplainPlanFlag.AUTO_INDEX
            val isTable = !name.startsWith("SUBQUERY", ignoreCase = true) &&
                !name.startsWith("CONSTANT", ignoreCase = true)
            return ExplainPlanNode(
                id = id,
                kind = if (isTable) ExplainNodeKind.TABLE else ExplainNodeKind.OPERATION,
                label = name,
                accessType = access,
                usedKey = when {
                    automatic -> "automatic index"
                    indexName != null -> indexName
                    byRowid -> "rowid"
                    else -> null
                },
                attachedCondition = condition,
                flags = flags,
            )
        }

        val upper = detail.uppercase()
        return when {
            upper.startsWith("USE TEMP B-TREE FOR") -> {
                val purpose = upper.removePrefix("USE TEMP B-TREE FOR").trim()
                val (kind, flag) = when {
                    purpose.startsWith("ORDER BY") -> ExplainNodeKind.ORDERING to ExplainPlanFlag.FILESORT
                    purpose.startsWith("GROUP BY") -> ExplainNodeKind.GROUPING to ExplainPlanFlag.TEMPORARY_TABLE
                    purpose.contains("DISTINCT") -> ExplainNodeKind.DUPLICATES_REMOVAL to ExplainPlanFlag.TEMPORARY_TABLE
                    else -> ExplainNodeKind.OPERATION to ExplainPlanFlag.TEMPORARY_TABLE
                }
                ExplainPlanNode(id = id, kind = kind, label = detail, flags = setOf(flag))
            }

            upper.contains("SUBQUERY") && !upper.startsWith("MATERIALIZE") -> {
                // LIST SUBQUERY 1, SCALAR SUBQUERY 2, CORRELATED SCALAR SUBQUERY 3: only the
                // correlated one runs again for every row of the query around it.
                val flags = if (upper.startsWith("CORRELATED")) setOf(ExplainPlanFlag.DEPENDENT) else emptySet()
                ExplainPlanNode(id = id, kind = ExplainNodeKind.SUBQUERY, label = detail, flags = flags)
            }

            upper.startsWith("MATERIALIZE") || upper.startsWith("CO-ROUTINE") ->
                ExplainPlanNode(id = id, kind = ExplainNodeKind.MATERIALISED, label = detail)

            upper.contains("COMPOUND") || upper.startsWith("UNION") || upper.startsWith("MERGE (") ||
                upper.startsWith("LEFT-MOST") ->
                ExplainPlanNode(id = id, kind = ExplainNodeKind.UNION, label = detail)

            else -> ExplainPlanNode(id = id, kind = ExplainNodeKind.OPERATION, label = detail)
        }
    }

    private fun CellValue.asLong(): Long? = when (this) {
        is CellValue.Number -> value.toDoubleOrNull()?.toLong()
        is CellValue.Text -> value.toLongOrNull()
        else -> null
    }
}
