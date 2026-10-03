package hu.laurel.sqlpulse.integration.sqlite

import hu.laurel.sqlpulse.data.connection.WriteUnlockStore
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.sql.ExplainNodeKind
import hu.laurel.sqlpulse.data.sql.ExplainNote
import hu.laurel.sqlpulse.data.sql.ExplainPlanFlag
import hu.laurel.sqlpulse.data.sql.NotEditableReason
import hu.laurel.sqlpulse.data.sql.ResultEditabilities
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.RowEditor
import hu.laurel.sqlpulse.data.sql.WriteGate
import hu.laurel.sqlpulse.data.sql.dialect.EngineFeature
import hu.laurel.sqlpulse.data.sql.dialect.SqliteDialect
import hu.laurel.sqlpulse.integration.IntegrationSessions
import hu.laurel.sqlpulse.integration.PlanIntegration
import hu.laurel.sqlpulse.integration.sqlite.SqliteFixture.scalar
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `EXPLAIN QUERY PLAN` drawn as a tree, and the result of a typed SELECT edited in place, both on
 * a real SQLite file (the fixture of [SqliteFixture]). Always runs: there is no server.
 */
class SqliteExplainIntegrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dialect = SqliteDialect
    private lateinit var file: File
    private lateinit var session: hu.laurel.sqlpulse.data.sql.SqlSession
    private lateinit var manager: hu.laurel.sqlpulse.data.sql.SqlSessionManager

    @Before
    fun open() {
        file = SqliteFixture.create(folder.newFile("explain.sqlite"))
        session = SqliteFixture.session(file)
        manager = IntegrationSessions.manager(session, "main", dialect = dialect)
    }

    @After
    fun close() {
        session.close()
    }

    // ------------------------------------------------------------------ EXPLAIN

    @Test
    fun `the dialect offers the plan tree and editable results`() {
        assertTrue(dialect.supports(EngineFeature.EXPLAIN))
        assertTrue(dialect.supports(EngineFeature.EDITABLE_RESULTS))
    }

    @Test
    fun `a lookup through an index is a search and a scan of a bare table is flagged`() {
        val executor = PlanIntegration.executor(manager)

        val search = PlanIntegration.plan(executor, dialect, "SELECT * FROM orders WHERE placed = '2026-01-01'")
        val node = search.root.flatten().first { it.kind == ExplainNodeKind.TABLE }
        assertEquals("SEARCH", node.accessType)
        assertEquals("orders", node.label)
        assertEquals("orders_placed", node.usedKey)
        assertTrue(search.notes.toString(), search.notes.isEmpty())
        assertTrue(!search.hasCostInfo)

        val scan = PlanIntegration.plan(executor, dialect, "SELECT * FROM tag WHERE weight > 1")
        val scanned = scan.root.flatten().first { it.kind == ExplainNodeKind.TABLE }
        assertEquals("SCAN", scanned.accessType)
        assertTrue(ExplainPlanFlag.FULL_TABLE_SCAN in scanned.flags)
        assertTrue(ExplainNote.FULL_TABLE_SCAN in scan.notes)
    }

    @Test
    fun `a join with a sort and a subquery builds a tree with temp b-trees and subquery steps`() {
        val executor = PlanIntegration.executor(manager)
        val plan = PlanIntegration.plan(
            executor,
            dialect,
            "SELECT c.name, SUM(o.total) FROM customer c JOIN orders o ON o.customer_id = c.id " +
                "WHERE c.id IN (SELECT customer_id FROM orders WHERE total > 10) GROUP BY c.name ORDER BY 2 DESC",
        )
        val nodes = plan.root.flatten()
        assertTrue(nodes.toString(), nodes.any { it.kind == ExplainNodeKind.GROUPING || it.kind == ExplainNodeKind.ORDERING })
        assertTrue(nodes.any { it.accessType == "SCAN" || it.accessType == "SEARCH" })
        // Nothing was executed to get here: the explained SELECT left the data alone.
        assertEquals("3", session.use { it.scalar("SELECT COUNT(*) FROM orders") })
    }

    @Test
    fun `explaining a write plans it and changes nothing`() {
        val executor = PlanIntegration.executor(manager)
        PlanIntegration.plan(executor, dialect, "SELECT 1")
        // EXPLAIN QUERY PLAN of a DELETE is a plan, never a delete.
        val before = session.use { it.scalar("SELECT COUNT(*) FROM tag") }
        session.use { connection ->
            connection.createStatement().use { it.executeQuery(dialect.explain("DELETE FROM tag WHERE weight = 1")).close() }
        }
        assertEquals(before, session.use { it.scalar("SELECT COUNT(*) FROM tag") })
    }

    // ------------------------------------------------------------------ editable results

    private fun verdict(sql: String): ResultEditability {
        val editable = dialect.resultEditability(sql) as? ResultEditability.Editable
            ?: return dialect.resultEditability(sql)
        val schema = SchemaRepository(manager)
        val structure = runBlocking { schema.structure(editable.database ?: "main", editable.table) }
        val labels = session.use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery(sql).use { r -> (1..r.metaData.columnCount).map { r.metaData.getColumnLabel(it) } }
            }
        }
        return ResultEditabilities.confirm(editable, "main", labels, structure)
    }

    @Test
    fun `a select of a keyed table is editable, and the edit changes that cell only`() {
        val sql = "SELECT name AS who, id FROM customer ORDER BY id"
        val target = (verdict(sql) as ResultEditability.Confirmed).target
        assertEquals("customer", target.table)
        assertEquals("main", target.database)
        assertEquals(listOf<String?>("name", "id"), target.columns)
        assertEquals(mapOf("id" to 1), target.key)

        val editor = RowEditor(manager, WriteGate(manager, WriteUnlockStore()), io.mockk.mockk(relaxed = true))
        val edit = editor.prepareUpdate(
            database = target.database,
            table = target.table,
            key = mapOf("id" to "2"),
            column = "name",
            oldValue = "Béla",
            newValue = "Béla Bartók",
        )
        assertEquals(1, runBlocking { editor.execute(edit) })
        assertEquals("Béla Bartók", session.use { it.scalar("SELECT name FROM customer WHERE id = 2") })
        assertEquals("Ada", session.use { it.scalar("SELECT name FROM customer WHERE id = 1") })
        assertEquals("50% off", session.use { it.scalar("SELECT name FROM customer WHERE id = 3") })
    }

    @Test
    fun `quoted names and a schema-qualified table are read the SQLite way`() {
        listOf(
            "SELECT \"id\", \"name\" FROM \"customer\"",
            "SELECT `id`, [name] FROM main.customer",
            "SELECT k, v FROM kv",
        ).forEach { sql -> assertTrue("$sql: ${verdict(sql)}", verdict(sql) is ResultEditability.Confirmed) }
    }

    @Test
    fun `a table with no primary key, a missing key and a join are shown read-only`() {
        assertEquals(ResultEditability.NotEditable(NotEditableReason.NO_KEY), verdict("SELECT * FROM tag"))
        assertEquals(ResultEditability.NotEditable(NotEditableReason.KEY_NOT_SELECTED), verdict("SELECT name FROM customer"))
        assertEquals(
            ResultEditability.NotEditable(NotEditableReason.JOIN),
            dialect.resultEditability("SELECT c.name FROM customer c JOIN orders o ON o.customer_id = c.id"),
        )
        assertEquals(
            ResultEditability.NotEditable(NotEditableReason.EXPRESSION),
            dialect.resultEditability("SELECT id, upper(name) FROM customer"),
        )
        assertEquals(ResultEditability.NotEditable(NotEditableReason.CTE), dialect.resultEditability("WITH x AS (SELECT 1) SELECT * FROM x"))
    }

    @Test
    fun `a view has no key and so no edit`() {
        assertEquals(ResultEditability.NotEditable(NotEditableReason.NO_KEY), verdict("SELECT * FROM big_orders"))
    }
}
