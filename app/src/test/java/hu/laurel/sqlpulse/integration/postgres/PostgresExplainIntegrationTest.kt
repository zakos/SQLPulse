package hu.laurel.sqlpulse.integration.postgres

import hu.laurel.sqlpulse.data.sql.ExplainNodeKind
import hu.laurel.sqlpulse.data.sql.ExplainNote
import hu.laurel.sqlpulse.data.sql.ExplainPlanFlag
import hu.laurel.sqlpulse.data.sql.dialect.PostgresDialect
import hu.laurel.sqlpulse.integration.PlanIntegration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * `EXPLAIN (FORMAT JSON)` against a real PostgreSQL, through the executor and the reader the plan
 * screen uses. Skipped without `SQLPULSE_TEST_POSTGRES_URL`.
 */
class PostgresExplainIntegrationTest {

    private var fixture: PostgresFixture? = null
    private val dialect = PostgresDialect

    @Before
    fun setUp() {
        assumeTrue("no ${PostgresTestServer.URL_VARIABLE}", PostgresTestServer.configOrNull() != null)
        fixture = PostgresFixture().also { f ->
            f.execute(
                "CREATE TABLE ${f.t("customer")} (id serial PRIMARY KEY, name text NOT NULL)",
                "CREATE TABLE ${f.t("orders")} (id serial PRIMARY KEY, customer_id int NOT NULL REFERENCES ${f.t("customer")}(id), " +
                    "placed date, total numeric(10,2))",
                "CREATE INDEX orders_customer ON ${f.t("orders")} (customer_id)",
                "INSERT INTO ${f.t("customer")} (name) SELECT 'c' || g FROM generate_series(1, 1000) g",
                "INSERT INTO ${f.t("orders")} (customer_id, placed, total) " +
                    "SELECT 1 + (g % 1000), date '2026-01-01' + (g % 300), g % 500 FROM generate_series(1, 50000) g",
                "ANALYZE ${f.t("customer")}",
                "ANALYZE ${f.t("orders")}",
            )
        }
    }

    @After
    fun tearDown() {
        fixture?.close()
    }

    private val f get() = fixture!!

    @Test
    fun `a selective lookup uses an index and a filter on an unindexed column is a full scan`() {
        val executor = PlanIntegration.executor(f.manager)

        val lookup = PlanIntegration.plan(executor, dialect, "SELECT * FROM ${f.t("orders")} WHERE customer_id = 5")
        val scan = lookup.root.flatten().first { it.kind == ExplainNodeKind.TABLE }
        assertEquals("orders", scan.label)
        assertEquals("orders_customer", scan.usedKey)
        assertTrue(ExplainPlanFlag.FULL_TABLE_SCAN !in scan.flags)
        assertTrue(lookup.notes.toString(), ExplainNote.FULL_TABLE_SCAN !in lookup.notes)
        assertTrue(lookup.hasCostInfo)
        assertNotNull(lookup.totalCost)

        val full = PlanIntegration.plan(executor, dialect, "SELECT * FROM ${f.t("orders")} WHERE total > 100")
        val seq = full.root.flatten().first { it.kind == ExplainNodeKind.TABLE }
        assertEquals("Seq Scan", seq.accessType)
        assertTrue(ExplainPlanFlag.FULL_TABLE_SCAN in seq.flags)
        assertTrue(ExplainNote.FULL_TABLE_SCAN in full.notes)
        assertTrue(ExplainNote.NO_INDEX in full.notes)
        assertTrue(seq.attachedCondition!!.contains("total"))
    }

    @Test
    fun `a join with grouping and a sort has a heaviest step and costs that add up to the whole`() {
        val executor = PlanIntegration.executor(f.manager)
        val plan = PlanIntegration.plan(
            executor,
            dialect,
            "SELECT c.name, sum(o.total) AS t FROM ${f.t("customer")} c JOIN ${f.t("orders")} o ON o.customer_id = c.id " +
                "WHERE o.placed > date '2026-06-01' GROUP BY c.name ORDER BY t DESC",
        )
        val nodes = plan.root.flatten()
        assertTrue(nodes.any { it.kind == ExplainNodeKind.ORDERING })
        assertTrue(nodes.any { it.kind == ExplainNodeKind.GROUPING })
        assertTrue(nodes.any { it.kind == ExplainNodeKind.JOIN || it.kind == ExplainNodeKind.NESTED_LOOP })
        assertNotNull(plan.expensiveNodeId)
        // Own costs are never negative, and nowhere near more than the plan's total.
        nodes.forEach { node -> node.cost?.let { assertTrue("${node.label}: $it", it >= 0.0) } }
        val own = nodes.filter { it.kind != ExplainNodeKind.QUERY_BLOCK }.sumOf { it.cost ?: 0.0 }
        assertTrue("own costs $own vs total ${plan.totalCost}", own <= plan.totalCost!! * 1.001)
    }

    @Test
    fun `explaining a DELETE plans it and deletes nothing`() {
        val executor = PlanIntegration.executor(f.manager)
        val before = f.scalar("SELECT count(*) FROM ${f.t("orders")}")
        PlanIntegration.plan(executor, dialect, "DELETE FROM ${f.t("orders")} WHERE total > 100")
        assertEquals(before, f.scalar("SELECT count(*) FROM ${f.t("orders")}"))
        assertFalse(dialect.explain("DELETE FROM t").contains("analyze", ignoreCase = true))
    }
}
