package hu.laurel.sqlpulse.data.sql.plan

import hu.laurel.sqlpulse.data.sql.ExplainJson
import hu.laurel.sqlpulse.data.sql.ExplainNodeKind
import hu.laurel.sqlpulse.data.sql.ExplainPlan
import hu.laurel.sqlpulse.data.sql.ExplainPlanFlag
import hu.laurel.sqlpulse.data.sql.ExplainPlanNode
import hu.laurel.sqlpulse.data.sql.ExplainPlanResult
import hu.laurel.sqlpulse.data.sql.ExplainUnavailable
import hu.laurel.sqlpulse.data.sql.MissingIndexHint
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * SQL Server's estimated plan, `SET SHOWPLAN_XML ON`: a `ShowPlanXML` document whose `QueryPlan`
 * holds one `RelOp` per operator, nested the way the data flows (a join's inputs are RelOps inside
 * the join's own element).
 *
 * The parser is `javax.xml.parsers`, which Android and the JVM both have, so the reader is
 * unit-tested on the JVM against plans captured from a real server. It is hardened against
 * entities because the document comes from a server the app does not control: a plan has no use
 * for a DTD, so none is read.
 *
 * Costs: `EstimatedTotalSubtreeCost` is cumulative, like PostgreSQL's total. A step's own cost is
 * what remains after its inputs, never below zero (the inner side of a nested loop is costed per
 * execution, so the difference can overshoot).
 *
 * The `MissingIndexes` element is passed on as [MissingIndexHint]s and nothing more: the app
 * shows what the optimiser wished for and never creates an index (specification §2).
 */
object SqlServerPlanReader : PlanReader {

    override val engine = DatabaseEngine.SQLSERVER

    override fun read(table: ResultTable): ExplainPlanResult {
        val text = PlanReaders.singleText(table, "<")
            ?: return ExplainPlanResult.Unavailable(ExplainUnavailable.UNSUPPORTED)
        return of(text)
    }

    fun of(text: String): ExplainPlanResult {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            // Every one of these can be unsupported by a given parser (Android's is not Xerces);
            // a parser that refuses a hardening switch is still given the document, because the
            // refusal means the feature the switch turns off does not exist there either.
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            ).forEach { (feature, value) -> runCatching { factory.setFeature(feature, value) } }
            runCatching { factory.isXIncludeAware = false }
            runCatching { factory.setExpandEntityReferences(false) }
            factory.newDocumentBuilder().parse(InputSource(StringReader(text.trim())))
        } catch (e: Exception) {
            return ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_JSON)
        }
        val root = document.documentElement
        if (root == null || root.localName != "ShowPlanXML") {
            return ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_A_PLAN)
        }

        val statements = root.descendants("StmtSimple").filter { it.firstDescendant("RelOp") != null }
        if (statements.isEmpty()) return ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_A_PLAN)

        val blocks = statements.mapIndexed { i, statement -> statementNode(statement, if (statements.size == 1) "0" else "0.$i") }
        val tree = if (blocks.size == 1) {
            blocks.first()
        } else {
            ExplainPlanNode(id = "0", kind = ExplainNodeKind.QUERY_BLOCK, label = "batch", children = blocks)
        }
        val all = tree.flatten()
        val hasCost = all.any { it.cost != null }
        return ExplainPlanResult.Parsed(
            ExplainPlan(
                root = tree,
                totalCost = statements.first().number("StatementSubTreeCost"),
                hasCostInfo = hasCost,
                notes = ExplainJson.notesOf(all),
                expensiveBranch = ExplainJson.expensiveBranch(tree, hasCost),
                engine = DatabaseEngine.SQLSERVER,
                missingIndexes = statements.flatMap { it.descendants("MissingIndexGroup") }.map(::missingIndex),
            ),
        )
    }

    private fun statementNode(statement: Element, id: String): ExplainPlanNode {
        val plan = statement.firstDescendant("QueryPlan")
        val relOps = plan?.relOpsBelow().orEmpty()
        return ExplainPlanNode(
            id = id,
            kind = ExplainNodeKind.QUERY_BLOCK,
            label = statement.attribute("StatementType")?.lowercase() ?: "statement",
            cost = statement.number("StatementSubTreeCost"),
            children = relOps.mapIndexed { i, relOp -> relOpNode(relOp, "$id.$i") },
        )
    }

    private fun relOpNode(relOp: Element, id: String): ExplainPlanNode {
        val physical = relOp.attribute("PhysicalOp") ?: "?"
        val logical = relOp.attribute("LogicalOp") ?: physical
        val subtree = relOp.number("EstimatedTotalSubtreeCost")
        val children = relOp.relOpsBelow()
        val childTotals = children.sumOf { it.number("EstimatedTotalSubtreeCost") ?: 0.0 }

        val scan = relOp.ownDescendants().firstOrNull { it.localName == "IndexScan" || it.localName == "TableScan" }
        val objectElement = relOp.ownDescendants().firstOrNull { it.localName == "Object" }
        val table = objectElement?.attribute("Table")?.unbracket()
        val index = objectElement?.attribute("Index")?.unbracket()
        val lookup = physical == "Key Lookup" || physical == "RID Lookup" || scan?.attribute("Lookup") == "1"

        val flags = mutableSetOf<ExplainPlanFlag>()
        val kind: ExplainNodeKind
        val label: String
        var accessType: String? = null

        when {
            objectElement != null && table != null &&
                (physical.contains("Scan") || physical.contains("Seek") || lookup) -> {
                kind = ExplainNodeKind.TABLE
                label = table
                accessType = if (lookup) "Key Lookup" else physical
                when (physical) {
                    // The base table is read from start to finish: SQL Server's access type ALL.
                    // A clustered index *is* the table, so scanning it narrows nothing.
                    "Table Scan", "Clustered Index Scan" -> {
                        flags += ExplainPlanFlag.FULL_TABLE_SCAN
                        flags += ExplainPlanFlag.NO_INDEX
                    }

                    "Index Scan" -> flags += ExplainPlanFlag.FULL_INDEX_SCAN
                }
                if (lookup) flags += ExplainPlanFlag.KEY_LOOKUP
            }

            physical == "Nested Loops" -> {
                kind = ExplainNodeKind.NESTED_LOOP
                label = joinLabel("nested loops", logical)
            }

            logical.endsWith("Join") || physical == "Merge Join" || physical == "Adaptive Join" -> {
                kind = ExplainNodeKind.JOIN
                label = joinLabel(physical.lowercase(), logical)
            }

            logical == "Distinct Sort" || logical == "Distinct" || logical == "Flow Distinct" -> {
                kind = ExplainNodeKind.DUPLICATES_REMOVAL
                label = physical
                if (physical.contains("Sort")) flags += ExplainPlanFlag.FILESORT
            }

            physical.contains("Aggregate") || logical.contains("Aggregate") -> {
                kind = ExplainNodeKind.GROUPING
                label = physical
            }

            physical.contains("Sort") -> {
                kind = ExplainNodeKind.ORDERING
                val order = relOp.ownDescendants().filter { it.localName == "OrderByColumn" }.mapNotNull { column ->
                    column.firstDescendant("ColumnReference")?.attribute("Column")?.let { name ->
                        if (column.attribute("Ascending") == "0") "$name DESC" else name
                    }
                }
                label = if (order.isEmpty()) physical else "$physical: ${order.joinToString(", ")}"
                flags += ExplainPlanFlag.FILESORT
            }

            physical == "Concatenation" -> {
                kind = ExplainNodeKind.UNION
                label = physical
            }

            physical.contains("Spool") -> {
                kind = ExplainNodeKind.MATERIALISED
                label = physical
            }

            else -> {
                kind = ExplainNodeKind.OPERATION
                label = physical
            }
        }

        // The predicate the step evaluates, in the optimiser's own spelling. A seek's range is
        // under SeekPredicates, a scan's filter under Predicate; whichever the step has.
        // On a join the predicate is what pairs the rows up; on anything else it is a filter.
        val predicateLabel = if (kind == ExplainNodeKind.JOIN || kind == ExplainNodeKind.NESTED_LOOP) "Join" else "Filter"
        val conditions = listOfNotNull(
            relOp.ownDescendants().firstOrNull { it.localName == "SeekPredicates" || it.localName == "SeekPredicateNew" }
                ?.firstDescendant("ScalarOperator")?.attribute("ScalarString")?.let { "Seek: $it" },
            relOp.ownDescendants().firstOrNull { it.localName == "Predicate" || it.localName == "ProbeResidual" || it.localName == "Residual" }
                ?.firstDescendant("ScalarOperator")?.attribute("ScalarString")?.let { "$predicateLabel: $it" },
        )

        val read = relOp.number("EstimatedRowsRead")
        val estimate = relOp.number("EstimateRows")
        return ExplainPlanNode(
            id = id,
            kind = kind,
            label = label,
            accessType = accessType,
            usedKey = index.takeIf { kind == ExplainNodeKind.TABLE },
            // A scan reads more rows than it returns; every other step reads what it returns.
            rowsExamined = (read ?: estimate)?.toLong(),
            rowsProduced = if (read != null) estimate?.toLong() else null,
            cost = subtree?.let { (it - childTotals).coerceAtLeast(0.0) },
            attachedCondition = conditions.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            flags = flags,
            children = children.mapIndexed { i, child -> relOpNode(child, "$id.$i") },
        )
    }

    private fun joinLabel(name: String, logical: String): String =
        if (logical == "Inner Join") name else "$name ($logical)"

    private fun missingIndex(group: Element): MissingIndexHint {
        val index = group.firstDescendant("MissingIndex")
        fun columns(usage: String): List<String> = index?.descendants("ColumnGroup").orEmpty()
            .filter { it.attribute("Usage") == usage }
            .flatMap { it.descendants("Column") }
            .mapNotNull { it.attribute("Name")?.unbracket() }
        return MissingIndexHint(
            table = index?.attribute("Table")?.unbracket() ?: "?",
            equalityColumns = columns("EQUALITY"),
            inequalityColumns = columns("INEQUALITY"),
            includeColumns = columns("INCLUDE"),
            impactPercent = group.number("Impact"),
        )
    }

    /* ---- DOM helpers ---------------------------------------------------------------------- */

    private fun Element.attribute(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

    private fun Element.number(name: String): Double? = attribute(name)?.toDoubleOrNull()

    /** `[dbo]` to `dbo`; names are shown the way a person writes them, not the way T-SQL quotes them. */
    private fun String.unbracket(): String =
        if (startsWith("[") && endsWith("]")) substring(1, length - 1).replace("]]", "]") else this

    private fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        var child: Node? = firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) out += child as Element
            child = child.nextSibling
        }
        return out
    }

    /** Every element below this one with [name], in document order. */
    private fun Element.descendants(name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(element: Element) {
            element.childElements().forEach { child ->
                if (child.localName == name) out += child
                walk(child)
            }
        }
        walk(this)
        return out
    }

    private fun Element.firstDescendant(name: String): Element? = descendants(name).firstOrNull()

    /**
     * The elements below this one, stopping at nested `RelOp`s: what belongs to this operator
     * and not to one of its inputs.
     */
    private fun Element.ownDescendants(): List<Element> {
        val out = ArrayList<Element>()
        fun walk(element: Element) {
            element.childElements().forEach { child ->
                if (child.localName == "RelOp") return@forEach
                out += child
                walk(child)
            }
        }
        walk(this)
        return out
    }

    /**
     * The operators directly below this element: the nested `RelOp`s that have no other `RelOp`
     * between them and it. A join's two inputs, or a scalar subquery's plan.
     */
    private fun Element.relOpsBelow(): List<Element> {
        val out = ArrayList<Element>()
        fun walk(element: Element) {
            element.childElements().forEach { child ->
                if (child.localName == "RelOp") out += child else walk(child)
            }
        }
        walk(this)
        return out
    }
}
