package hu.laurel.sqlpulse.integration.sqlserver

import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ExplainNodeKind
import hu.laurel.sqlpulse.data.sql.ExplainNote
import hu.laurel.sqlpulse.data.sql.ExplainPlanFlag
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.dialect.SqlServerDialect
import hu.laurel.sqlpulse.integration.IntegrationSessions
import hu.laurel.sqlpulse.integration.PlanIntegration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * SQL Server's estimated plan (`SET SHOWPLAN_XML ON`) through the executor, against a real server.
 * Skipped without `SQLPULSE_TEST_MSSQL_URL`. The point of most tests here is what the plan mode
 * must never do: run the statement, or stay switched on for the next query of the pool.
 */
class SqlServerExplainIntegrationTest {

    private val dialect = SqlServerDialect
    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession
    private val ns = "sp_plan_${System.nanoTime()}"

    private fun q(name: String) = dialect.quoteIdentifier(name)
    private fun t(name: String) = dialect.qualify(ns, name)

    @Before
    fun connect() {
        val found = SqlServerTestServer.configOrNull()
        assumeTrue("no ${SqlServerTestServer.URL_VARIABLE}", found != null)
        config = found!!
        session = SqlServerTestServer.open(config)
        session.use { c ->
            c.execute("CREATE SCHEMA ${q(ns)}")
            c.execute("CREATE TABLE ${t("customer")} (id INT IDENTITY(1,1) PRIMARY KEY, name NVARCHAR(50) NOT NULL)")
            c.execute(
                "CREATE TABLE ${t("orders")} (id INT IDENTITY(1,1) PRIMARY KEY, customer_id INT NOT NULL, " +
                    "placed DATE, total DECIMAL(10,2), note VARCHAR(100))",
            )
            c.execute("CREATE INDEX orders_customer ON ${t("orders")} (customer_id)")
            c.execute(
                "INSERT INTO ${t("customer")} (name) SELECT TOP 1000 'c' + CAST(ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS VARCHAR(10)) " +
                    "FROM sys.all_objects a CROSS JOIN sys.all_objects b",
            )
            c.execute(
                "INSERT INTO ${t("orders")} (customer_id, placed, total, note) SELECT TOP 20000 " +
                    "1 + (ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) % 1000), " +
                    "DATEADD(day, ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) % 300, '2026-01-01'), " +
                    "ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) % 500, 'n' FROM sys.all_objects a CROSS JOIN sys.all_objects b",
            )
            c.execute("UPDATE STATISTICS ${t("customer")}")
            c.execute("UPDATE STATISTICS ${t("orders")}")
        }
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) {
            runCatching {
                session.use { c ->
                    c.execute("DROP TABLE IF EXISTS ${t("orders")}")
                    c.execute("DROP TABLE IF EXISTS ${t("customer")}")
                    c.execute("DROP SCHEMA IF EXISTS ${q(ns)}")
                }
            }
            session.close()
        }
    }

    private val executor get() = PlanIntegration.executor(IntegrationSessions.manager(session, config.database, dialect = dialect))

    @Test
    fun `a seek, a key lookup and a scan with a missing index are told apart`() {
        val ex = executor

        val seek = PlanIntegration.plan(ex, dialect, "SELECT id FROM ${t("orders")} WHERE customer_id = 5")
        val seekNode = seek.root.flatten().first { it.kind == ExplainNodeKind.TABLE }
        assertEquals("Index Seek", seekNode.accessType)
        assertEquals("orders_customer", seekNode.usedKey)
        assertTrue(seek.notes.toString(), seek.notes.isEmpty())

        // The note column is not in the narrow index: every found row costs a second look-up.
        val lookup = PlanIntegration.plan(ex, dialect, "SELECT note FROM ${t("orders")} WHERE customer_id = 5")
        assertTrue(lookup.root.flatten().any { ExplainPlanFlag.KEY_LOOKUP in it.flags })
        assertTrue(ExplainNote.KEY_LOOKUP in lookup.notes)

        val scan = PlanIntegration.plan(ex, dialect, "SELECT note FROM ${t("orders")} WHERE placed = '2026-03-03'")
        assertTrue(ExplainNote.FULL_TABLE_SCAN in scan.notes)
        val hint = scan.missingIndexes.single()
        assertEquals("orders", hint.table)
        assertEquals(listOf("placed"), hint.equalityColumns)
        assertEquals(listOf("note"), hint.includeColumns)
        assertTrue(hint.impactPercent!! > 0.0)
    }

    @Test
    fun `a join with grouping and a sort has a heaviest step`() {
        val plan = PlanIntegration.plan(
            executor,
            dialect,
            "SELECT c.name, SUM(o.total) AS t FROM ${t("customer")} c JOIN ${t("orders")} o ON o.customer_id = c.id " +
                "WHERE o.placed > '2026-06-01' GROUP BY c.name ORDER BY t DESC",
        )
        val nodes = plan.root.flatten()
        assertTrue(nodes.any { it.kind == ExplainNodeKind.ORDERING })
        assertTrue(nodes.any { it.kind == ExplainNodeKind.GROUPING })
        assertTrue(nodes.any { it.kind == ExplainNodeKind.JOIN || it.kind == ExplainNodeKind.NESTED_LOOP })
        assertTrue(plan.expensiveNodeId != null)
        assertTrue(plan.hasCostInfo)
    }

    @Test
    fun `a plan request for a write plans it and changes nothing`() {
        // The editor refuses to explain a write (only a query is explained), so this goes below it:
        // what the plan mode itself guarantees, on a connection from the real pool.
        val before = scalar("SELECT COUNT(*) FROM ${t("orders")}")
        session.use { c ->
            dialect.inPlanMode(c) {
                c.createStatement().use { s ->
                    s.execute("DELETE FROM ${t("orders")} WHERE total > 100")
                    s.resultSet.use { r -> assertTrue(r.next()); assertTrue(r.getString(1).startsWith("<ShowPlanXML")) }
                }
                c.createStatement().use { s ->
                    s.execute("UPDATE ${t("orders")} SET note = 'x' WHERE customer_id = 5")
                }
            }
        }
        assertEquals(before, scalar("SELECT COUNT(*) FROM ${t("orders")}"))
        assertEquals("0", scalar("SELECT COUNT(*) FROM ${t("orders")} WHERE note = 'x'"))
    }

    @Test
    fun `typed parameters are written into the plan request as literals`() {
        val ex = executor
        val plan = PlanIntegration.plan(
            ex,
            dialect,
            "SELECT id FROM ${t("orders")} WHERE customer_id = :customer AND note = :note",
            parameters = mapOf(
                "customer" to hu.laurel.sqlpulse.data.sql.ParameterValue("5", hu.laurel.sqlpulse.data.sql.ParameterType.NUMBER),
                "note" to hu.laurel.sqlpulse.data.sql.ParameterValue("it's"),
            ),
        )
        assertTrue(plan.root.flatten().any { it.kind == ExplainNodeKind.TABLE })
    }

    @Test
    fun `no pooled connection is left in plan mode`() {
        val ex = executor
        // More explains than the pool has connections (three), each followed by an ordinary
        // query that would answer with an XML plan if its connection were still in SHOWPLAN mode.
        repeat(7) {
            PlanIntegration.plan(ex, dialect, "SELECT id FROM ${t("orders")} WHERE customer_id = 5")
            val outcome = runBlocking {
                ex.run(connectionId = 7, sql = "SELECT COUNT(*) AS n FROM ${t("orders")}", readOnly = false)
            }
            assertEquals(CellValue.Number("20000"), outcome.table.rows.single().single())
        }
    }

    @Test
    fun `a statement that fails to plan still leaves the connection usable`() {
        val ex = executor
        try {
            runBlocking {
                ex.run(connectionId = 7, sql = dialect.explain("SELECT * FROM ${t("no_such_table")}"), readOnly = false)
            }
            org.junit.Assert.fail("planning a missing table should fail")
        } catch (e: java.sql.SQLException) {
            // Expected: the server cannot plan it.
        }
        val outcome = runBlocking {
            ex.run(connectionId = 7, sql = "SELECT 1 AS one", readOnly = false)
        }
        assertEquals(CellValue.Number("1"), outcome.table.rows.single().single())
    }

    private fun scalar(sql: String): String? = session.use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> if (r.next()) r.getString(1) else null } }
    }
}
