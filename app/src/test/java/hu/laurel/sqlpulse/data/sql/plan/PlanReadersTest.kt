package hu.laurel.sqlpulse.data.sql.plan

import hu.laurel.sqlpulse.data.backup.JsonValue
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ExplainNodeKind
import hu.laurel.sqlpulse.data.sql.ExplainNote
import hu.laurel.sqlpulse.data.sql.ExplainPlan
import hu.laurel.sqlpulse.data.sql.ExplainPlanFlag
import hu.laurel.sqlpulse.data.sql.ExplainPlanNode
import hu.laurel.sqlpulse.data.sql.ExplainPlanResult
import hu.laurel.sqlpulse.data.sql.ExplainUnavailable
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.PostgresDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlServerDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqliteDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan readers of the three newer engines, against plans captured from real servers
 * (PostgreSQL 16, SQL Server 2022, SQLite) and kept under `src/test/resources/explain/`.
 */
class PlanReadersTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/explain/$name")) { "missing test resource $name" }.readText().trim()

    private fun parsed(result: ExplainPlanResult): ExplainPlan =
        (result as? ExplainPlanResult.Parsed ?: error("not parsed: $result")).plan

    private fun ExplainPlan.nodes(): List<ExplainPlanNode> = root.flatten()

    private fun ExplainPlan.heaviest(): ExplainPlanNode = nodes().first { it.id == expensiveNodeId }

    private fun oneCell(text: String) = ResultTable(
        columns = listOf(ColumnMeta("plan", CellType.TEXT, "text", null)),
        rows = listOf(listOf(CellValue.Text(text))),
    )

    // ------------------------------------------------------------------ PostgreSQL

    @Test
    fun `a postgres join with a sort names the steps and finds the heaviest`() {
        val plan = parsed(PostgresPlanReader.of(resource("pg_join_sort.json")))
        assertEquals(DatabaseEngine.POSTGRESQL, plan.engine)
        assertEquals(1225.23, plan.totalCost!!, 0.001)
        assertTrue(plan.hasCostInfo)

        val kinds = plan.nodes().map { it.kind }
        assertTrue(ExplainNodeKind.ORDERING in kinds)
        assertTrue(ExplainNodeKind.GROUPING in kinds)
        assertTrue(ExplainNodeKind.JOIN in kinds)

        val sort = plan.nodes().first { it.kind == ExplainNodeKind.ORDERING }
        assertEquals("Sort: (sum(o.total)) DESC", sort.label)
        // 1225.23 - 1172.90: the sort's own share, not the running total.
        assertEquals(52.33, sort.cost!!, 0.001)

        // The scan of the big table is the step that costs; the aliases are kept.
        val heaviest = plan.heaviest()
        assertEquals("orders o", heaviest.label)
        assertEquals("Seq Scan", heaviest.accessType)
        assertEquals(944.0, heaviest.cost!!, 0.001)
        assertEquals("Filter: (placed > '2026-06-01'::date)", heaviest.attachedCondition)
        assertEquals(24606L, heaviest.rowsExamined)
    }

    @Test
    fun `a sequential scan is a full table scan with no index`() {
        val plan = parsed(PostgresPlanReader.of(resource("pg_seq_scan.json")))
        val scan = plan.nodes().first { it.kind == ExplainNodeKind.TABLE }
        assertEquals("orders", scan.label)
        assertTrue(ExplainPlanFlag.FULL_TABLE_SCAN in scan.flags)
        assertTrue(ExplainPlanFlag.NO_INDEX in scan.flags)
        assertTrue(ExplainNote.FULL_TABLE_SCAN in plan.notes)
        assertTrue(ExplainNote.NO_INDEX in plan.notes)
    }

    @Test
    fun `an index scan names its index and raises no warning`() {
        val plan = parsed(PostgresPlanReader.of(resource("pg_pk_lookup.json")))
        val scan = plan.nodes().first { it.kind == ExplainNodeKind.TABLE }
        assertEquals("Index Scan", scan.accessType)
        assertEquals("orders_pkey", scan.usedKey)
        assertEquals("Index Cond: (id = 7)", scan.attachedCondition)
        assertEquals(1L, scan.rowsExamined)
        assertTrue(plan.notes.toString(), plan.notes.isEmpty())
    }

    @Test
    fun `a bitmap scan reports the index under it, not no index`() {
        val plan = parsed(PostgresPlanReader.of(resource("pg_bitmap_scan.json")))
        val heap = plan.nodes().first { it.kind == ExplainNodeKind.TABLE }
        assertEquals("Bitmap Heap Scan", heap.accessType)
        assertEquals("orders_customer", heap.usedKey)
        assertEquals("Recheck Cond: (customer_id = 5)", heap.attachedCondition)
        assertTrue(ExplainPlanFlag.NO_INDEX !in heap.flags)
        val bitmap = heap.children.single()
        assertEquals("orders_customer", bitmap.usedKey)
        assertEquals("Index Cond: (customer_id = 5)", bitmap.attachedCondition)
        assertTrue(plan.notes.toString(), plan.notes.isEmpty())
    }

    @Test
    fun `a postgres subplan is shown as a subquery that runs again per row`() {
        val text = """
            [{"Plan": {"Node Type": "Seq Scan", "Relation Name": "a", "Total Cost": 10.0, "Plan Rows": 5,
              "Plans": [{"Node Type": "Seq Scan", "Relation Name": "b", "Parent Relationship": "SubPlan",
                         "Subplan Name": "SubPlan 1", "Total Cost": 3.0, "Plan Rows": 1}]}}]
        """.trimIndent()
        val plan = parsed(PostgresPlanReader.of(text))
        val sub = plan.nodes().first { it.kind == ExplainNodeKind.SUBQUERY }
        assertEquals("SubPlan 1", sub.label)
        assertTrue(ExplainPlanFlag.DEPENDENT in sub.flags)
        assertEquals("b", sub.children.single().label)
    }

    @Test
    fun `postgres text that is not a plan is unavailable and never throws`() {
        assertEquals(
            ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_JSON),
            PostgresPlanReader.of("[ not json"),
        )
        assertEquals(
            ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_A_PLAN),
            PostgresPlanReader.of("[{\"Other\": 1}]"),
        )
        assertTrue(PostgresPlanReader.read(oneCell("plain text")) is ExplainPlanResult.Unavailable)
    }

    @Test
    fun `postgres never asks for ANALYZE, which would run the statement`() {
        val statement = PostgresDialect.explain("DELETE FROM t WHERE id = 1")
        assertEquals("EXPLAIN (FORMAT JSON) DELETE FROM t WHERE id = 1", statement)
        assertFalse(statement.contains("analyze", ignoreCase = true))
    }

    // ------------------------------------------------------------------ SQL Server

    @Test
    fun `a sql server join plan has joins, sorts, grouping and a heaviest scan`() {
        val plan = parsed(SqlServerPlanReader.of(resource("mssql_join_sort.xml")))
        assertEquals(DatabaseEngine.SQLSERVER, plan.engine)
        assertTrue(plan.hasCostInfo)
        assertTrue(plan.totalCost!! > 0.4)

        val nodes = plan.nodes()
        assertEquals("select", plan.root.label)
        assertTrue(nodes.any { it.kind == ExplainNodeKind.JOIN && it.label.startsWith("merge join") })
        assertTrue(nodes.any { it.kind == ExplainNodeKind.GROUPING })
        val sorts = nodes.filter { it.kind == ExplainNodeKind.ORDERING }
        assertTrue(sorts.size >= 2)
        assertTrue(sorts.all { ExplainPlanFlag.FILESORT in it.flags })

        val scan = nodes.first { it.kind == ExplainNodeKind.TABLE && it.label == "orders" }
        assertEquals("Clustered Index Scan", scan.accessType)
        assertTrue(ExplainPlanFlag.FULL_TABLE_SCAN in scan.flags)
        assertEquals(50000L, scan.rowsExamined)
        assertEquals(24617L, scan.rowsProduced)
        assertTrue(scan.attachedCondition!!.contains("placed"))
        assertTrue(ExplainNote.FULL_TABLE_SCAN in plan.notes)

        // Every step has an own cost that is not negative, and they never exceed the whole.
        nodes.forEach { node -> node.cost?.let { assertTrue("${node.label} $it", it >= 0.0) } }
    }

    @Test
    fun `an index seek that covers the query is a clean plan`() {
        val plan = parsed(SqlServerPlanReader.of(resource("mssql_seek.xml")))
        val seek = plan.nodes().first { it.kind == ExplainNodeKind.TABLE }
        assertEquals("Index Seek", seek.accessType)
        assertEquals("orders_customer", seek.usedKey)
        assertEquals(50L, seek.rowsExamined)
        assertTrue(seek.attachedCondition!!.startsWith("Seek:"))
        assertTrue(plan.notes.toString(), plan.notes.isEmpty())
        assertTrue(plan.missingIndexes.isEmpty())
    }

    @Test
    fun `a key lookup is flagged and warned about`() {
        val plan = parsed(SqlServerPlanReader.of(resource("mssql_key_lookup.xml")))
        val lookup = plan.nodes().first { ExplainPlanFlag.KEY_LOOKUP in it.flags }
        assertEquals("Key Lookup", lookup.accessType)
        assertEquals("orders", lookup.label)
        assertTrue(ExplainNote.KEY_LOOKUP in plan.notes)
        // The seek on the narrow index and the nested loop around both are there too.
        assertTrue(plan.nodes().any { it.kind == ExplainNodeKind.NESTED_LOOP })
        assertTrue(plan.nodes().any { it.accessType == "Index Seek" && it.usedKey == "orders_customer" })
    }

    @Test
    fun `the missing index element is read as text, with its columns and impact`() {
        val plan = parsed(SqlServerPlanReader.of(resource("mssql_scan_missing_index.xml")))
        val hint = plan.missingIndexes.single()
        assertEquals("orders", hint.table)
        assertEquals(listOf("placed"), hint.equalityColumns)
        assertTrue(hint.inequalityColumns.isEmpty())
        assertEquals(listOf("note"), hint.includeColumns)
        assertEquals(98.0632, hint.impactPercent!!, 0.0001)
        assertTrue(ExplainNote.FULL_TABLE_SCAN in plan.notes)
    }

    @Test
    fun `sql server xml that is not a plan is unavailable and a doctype is refused`() {
        assertTrue(SqlServerPlanReader.of("<a><b/></a>") is ExplainPlanResult.Unavailable)
        assertTrue(SqlServerPlanReader.of("not xml") is ExplainPlanResult.Unavailable)
        // An entity-bomb or external-entity document must never be expanded.
        val bomb = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a SYSTEM "file:///etc/passwd">]><ShowPlanXML>&a;</ShowPlanXML>"""
        assertTrue(SqlServerPlanReader.of(bomb) is ExplainPlanResult.Unavailable)
    }

    @Test
    fun `sql server asks for a plan with a marker and never runs the statement`() {
        val statement = SqlServerDialect.explain("DELETE FROM t WHERE id = 1")
        assertTrue(SqlServerDialect.isExplain(statement))
        assertFalse(SqlServerDialect.isExplain("DELETE FROM t WHERE id = 1"))
        // The marker is a comment, so the statement still classifies as what it really is.
        assertEquals(SqlServerDialect.classify("DELETE FROM t WHERE id = 1"), SqlServerDialect.classify(statement))
    }

    // ------------------------------------------------------------------ SQLite

    private fun sqliteTable(name: String): ResultTable {
        val rows = (JsonValue.parse(resource(name)) as JsonValue.Obj).fields.getValue("rows") as JsonValue.Arr
        return ResultTable(
            columns = listOf("id", "parent", "notused", "detail").map { ColumnMeta(it, CellType.TEXT, "", null) },
            rows = rows.items.map { row ->
                (row as JsonValue.Arr).items.map { cell ->
                    when (cell) {
                        is JsonValue.Num -> CellValue.Number(cell.text)
                        is JsonValue.Str -> CellValue.Text(cell.value)
                        else -> CellValue.Null
                    }
                }
            },
        )
    }

    @Test
    fun `sqlite scan and search are told apart and no cost is invented`() {
        val plan = parsed(SqlitePlanReader.read(sqliteTable("sqlite_join_sort.json")))
        assertEquals(DatabaseEngine.SQLITE, plan.engine)
        assertFalse(plan.hasCostInfo)
        assertNull(plan.totalCost)
        assertTrue(plan.expensiveBranch.isEmpty())
        assertTrue(plan.nodes().all { it.cost == null })

        val scan = plan.nodes().first { it.accessType == "SCAN" }
        assertEquals("o", scan.label)
        assertTrue(ExplainPlanFlag.FULL_TABLE_SCAN in scan.flags)
        val search = plan.nodes().first { it.accessType == "SEARCH" }
        assertEquals("rowid", search.usedKey)
        assertEquals("(rowid=?)", search.attachedCondition)
        assertTrue(ExplainPlanFlag.FULL_TABLE_SCAN !in search.flags)

        // The two temporary b-trees are the sort and the grouping.
        assertTrue(plan.nodes().any { it.kind == ExplainNodeKind.GROUPING })
        assertTrue(plan.nodes().any { it.kind == ExplainNodeKind.ORDERING })
        assertTrue(ExplainNote.FILESORT in plan.notes)
        assertTrue(ExplainNote.FULL_TABLE_SCAN in plan.notes)
    }

    @Test
    fun `a sqlite search through an index names the index and warns about nothing`() {
        val plan = parsed(SqlitePlanReader.read(sqliteTable("sqlite_search.json")))
        val search = plan.nodes().first { it.kind == ExplainNodeKind.TABLE }
        assertEquals("orders", search.label)
        assertEquals("orders_customer", search.usedKey)
        assertEquals("(customer_id=?)", search.attachedCondition)
        assertTrue(plan.notes.toString(), plan.notes.isEmpty())
    }

    @Test
    fun `a sqlite full scan says so`() {
        val plan = parsed(SqlitePlanReader.read(sqliteTable("sqlite_scan.json")))
        assertTrue(ExplainNote.FULL_TABLE_SCAN in plan.notes)
        assertTrue(ExplainNote.NO_INDEX in plan.notes)
    }

    @Test
    fun `sqlite subqueries nest under their parent step`() {
        val plan = parsed(SqlitePlanReader.read(sqliteTable("sqlite_subquery.json")))
        val sub = plan.nodes().first { it.kind == ExplainNodeKind.SUBQUERY }
        assertEquals("LIST SUBQUERY 1", sub.label)
        // "SCAN orders" has parent 7, the id of the subquery row.
        assertEquals("orders", sub.children.single().label)
    }

    @Test
    fun `an automatic index and an unknown line are both kept`() {
        val table = ResultTable(
            columns = listOf("id", "parent", "notused", "detail").map { ColumnMeta(it, CellType.TEXT, "", null) },
            rows = listOf(
                listOf(CellValue.Number("2"), CellValue.Number("0"), CellValue.Number("0"), CellValue.Text("SCAN a")),
                listOf(CellValue.Number("5"), CellValue.Number("0"), CellValue.Number("0"), CellValue.Text("SEARCH b USING AUTOMATIC COVERING INDEX (x=?)")),
                listOf(CellValue.Number("9"), CellValue.Number("0"), CellValue.Number("0"), CellValue.Text("SOMETHING NEW")),
            ),
        )
        val plan = parsed(SqlitePlanReader.read(table))
        assertTrue(ExplainNote.AUTOMATIC_INDEX in plan.notes)
        assertTrue(plan.nodes().any { it.label == "SOMETHING NEW" && it.kind == ExplainNodeKind.OPERATION })
    }

    @Test
    fun `a table that is not an explain result is not a sqlite plan`() {
        assertFalse(SqlitePlanReader.recognises(oneCell("x")))
        assertTrue(SqlitePlanReader.read(oneCell("x")) is ExplainPlanResult.Unavailable)
        assertEquals("EXPLAIN QUERY PLAN SELECT 1", SqliteDialect.explain("SELECT 1"))
    }

    // ------------------------------------------------------------------ advice and plan-request text

    @Test
    fun `a sequential scan of a big table also says the estimate is large`() {
        val text = """[{"Plan": {"Node Type": "Seq Scan", "Relation Name": "events", "Total Cost": 90000.0, "Plan Rows": 2500000}}]"""
        val notes = parsed(PostgresPlanReader.of(text)).notes
        assertTrue(notes.toString(), ExplainNote.FULL_TABLE_SCAN in notes && ExplainNote.MANY_ROWS in notes)
    }

    @Test
    fun `sql server literals for a plan request are escaped and typed`() {
        val d = SqlServerDialect
        assertEquals("NULL", d.planLiteral(hu.laurel.sqlpulse.data.sql.ParameterBinding.Null))
        assertEquals("N'it''s'", d.planLiteral(hu.laurel.sqlpulse.data.sql.ParameterBinding.Text("it's")))
        assertEquals("42", d.planLiteral(hu.laurel.sqlpulse.data.sql.ParameterBinding.Integer(42)))
        assertEquals("1.5", d.planLiteral(hu.laurel.sqlpulse.data.sql.ParameterBinding.Decimal(1.5)))
        assertEquals("1", d.planLiteral(hu.laurel.sqlpulse.data.sql.ParameterBinding.Bool(true)))
        assertTrue(d.plansAsPlainBatch)
        assertFalse(PostgresDialect.plansAsPlainBatch)
        val bound = hu.laurel.sqlpulse.data.sql.SqlGuards.bindParameters("SELECT '12:30', :a, :b", d.grammar) { name -> "<$name>" }
        assertEquals("SELECT '12:30', <a>, <b>", bound.sql)
        assertEquals(listOf("a", "b"), bound.parameterOrder)
    }

    // ------------------------------------------------------------------ choosing the reader

    @Test
    fun `the answer decides the reader, and MySQL's keeps its own`() {
        assertEquals(MySqlPlanReader, PlanReaders.detect(oneCell("{\"query_block\": {}}")))
        assertEquals(PostgresPlanReader, PlanReaders.detect(oneCell(resource("pg_seq_scan.json"))))
        assertEquals(SqlServerPlanReader, PlanReaders.detect(oneCell(resource("mssql_seek.xml"))))
        assertEquals(SqlitePlanReader, PlanReaders.detect(sqliteTable("sqlite_scan.json")))
        assertNull(PlanReaders.detect(oneCell("hello")))
        assertNotNull(PlanReaders.forEngine(DatabaseEngine.MYSQL))
    }

    @Test
    fun `each engine's dialect reads its own plans and only EXPLAIN-capable engines say so`() {
        assertEquals(PostgresPlanReader, PostgresDialect.planReader)
        assertEquals(SqlServerPlanReader, SqlServerDialect.planReader)
        assertEquals(SqlitePlanReader, SqliteDialect.planReader)
        listOf(PostgresDialect, SqlServerDialect, SqliteDialect).forEach { dialect ->
            assertTrue(dialect.engine.name, dialect.supports(hu.laurel.sqlpulse.data.sql.dialect.EngineFeature.EXPLAIN))
        }
    }
}
