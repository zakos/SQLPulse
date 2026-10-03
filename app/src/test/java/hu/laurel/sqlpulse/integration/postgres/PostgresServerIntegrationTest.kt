package hu.laurel.sqlpulse.integration.postgres

import hu.laurel.sqlpulse.data.schema.MetricId
import hu.laurel.sqlpulse.data.schema.PostgresPulse
import hu.laurel.sqlpulse.data.schema.ReplicationReport
import hu.laurel.sqlpulse.data.schema.ServerRepository
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.schema.SlowStatementsReport
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.dialect.KillAction
import hu.laurel.sqlpulse.data.sql.dialect.OwnSessionException
import hu.laurel.sqlpulse.data.sql.dialect.PostgresDialect
import hu.laurel.sqlpulse.data.sql.dialect.PostgresServerCatalog
import hu.laurel.sqlpulse.integration.IntegrationSessions
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The Server and Pulse screens' questions against a real PostgreSQL (see [PostgresTestServer]):
 * running statements seen from a second session and cancelled or terminated, idle sessions
 * hidden, open transactions, lock waits, replication on a plain primary, users and grants,
 * pg_stat_statements both missing and loaded, and two Pulse samples.
 *
 * `pg_stat_statements` needs `shared_preload_libraries`, so the "present" case reads a second
 * server named by [PRELOAD_VARIABLE] (a cluster started with the library preloaded) and is
 * skipped without it; "missing" and "installed but not loaded" run in a throwaway database.
 */
class PostgresServerIntegrationTest {

    private lateinit var fixture: PostgresFixture
    private lateinit var server: ServerRepository
    private val others = mutableListOf<Connection>()
    private val roles = mutableListOf<String>()

    @Before
    fun connect() {
        fixture = PostgresFixture()
        server = ServerRepository(fixture.manager)
    }

    @After
    fun disconnect() {
        others.forEach { runCatching { it.close() } }
        if (this::fixture.isInitialized) {
            roles.forEach { runCatching { fixture.session.use { c -> c.execute("DROP ROLE IF EXISTS ${fixture.q(it)}") } } }
            fixture.close()
        }
    }

    private fun other(user: String = fixture.config.user, password: String? = fixture.config.password): Connection {
        val c = DriverManager.getConnection(
            "jdbc:postgresql://${fixture.config.host}:${fixture.config.port}/${fixture.config.database}",
            user,
            password,
        )
        others += c
        return c
    }

    private fun pidOf(c: Connection): Long =
        c.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { it.next(); it.getLong(1) } }

    private fun ResultTable.column(name: String) = columns.indexOfFirst { it.label.equals(name, ignoreCase = true) }

    private fun ResultTable.rowOf(id: Long, idColumn: String = "Id"): List<hu.laurel.sqlpulse.data.sql.CellValue>? {
        val index = column(idColumn)
        return rows.firstOrNull { (it[index] as? hu.laurel.sqlpulse.data.sql.CellValue.Number)?.value?.toLongOrNull() == id }
    }

    private fun ResultTable.text(row: List<hu.laurel.sqlpulse.data.sql.CellValue>, name: String): String? =
        (row[column(name)] as? hu.laurel.sqlpulse.data.sql.CellValue.Text)?.value
            ?: (row[column(name)] as? hu.laurel.sqlpulse.data.sql.CellValue.Number)?.value

    private fun eventually(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    // ------------------------------------------------------------------ running statements

    @Test
    fun `a statement running in another session is listed and cancelling it leaves the session alive`() {
        val other = other()
        val pid = pidOf(other)
        var failure: SQLException? = null
        val worker = thread {
            try {
                other.createStatement().use { it.execute("SELECT pg_sleep(60)") }
            } catch (e: SQLException) {
                failure = e
            }
        }
        eventually("the sleeping statement to show up") {
            runBlocking { server.processList() }.rowOf(pid) != null
        }
        val table = runBlocking { server.processList() }
        val row = table.rowOf(pid)!!
        assertTrue(table.text(row, "Info")!!.contains("pg_sleep"))
        assertTrue(table.text(row, "State")!!.startsWith("active"))
        assertEquals(fixture.config.database, table.text(row, "db"))

        assertTrue(runBlocking { server.stop(pid, KillAction.CANCEL) })
        worker.join(10_000)
        // query_canceled
        assertEquals("57014", failure?.sqlState)
        // The session itself was not ended, which is what separates cancel from terminate.
        assertTrue(other.isValid(5))
    }

    @Test
    fun `terminating a session ends its connection`() {
        val other = other()
        val pid = pidOf(other)
        assertTrue(runBlocking { server.stop(pid, KillAction.TERMINATE) })
        eventually("the connection to be gone") { !other.isValid(2) }
    }

    @Test
    fun `idle sessions are hidden unless asked for`() {
        val other = other()
        other.createStatement().use { it.execute("SELECT 1") }
        val pid = pidOf(other)
        assertNull(runBlocking { server.processList(includeIdle = false) }.rowOf(pid))
        val all = runBlocking { server.processList(includeIdle = true) }
        assertEquals("idle", all.text(all.rowOf(pid)!!, "State"))
    }

    @Test
    fun `this app's own session is never in the list and cannot be ended`() {
        fixture.use { c ->
            val own = pidOf(c)
            assertNull(PostgresServerCatalog.processList(c, includeIdle = true, limit = 500).rowOf(own))
            assertThrows(OwnSessionException::class.java) { PostgresServerCatalog.stop(c, own, KillAction.TERMINATE) }
            assertThrows(OwnSessionException::class.java) { PostgresServerCatalog.stop(c, own, KillAction.CANCEL) }
        }
    }

    @Test
    fun `a session that is already gone answers false instead of failing`() {
        assertFalse(runBlocking { server.stop(2_000_000_000L, KillAction.CANCEL) })
        assertFalse(runBlocking { server.stop(0, KillAction.CANCEL) })
    }

    // ------------------------------------------------------------------ transactions and locks

    @Test
    fun `a transaction left open shows up as idle in transaction`() {
        val other = other()
        other.autoCommit = false
        other.createStatement().use { it.execute("SELECT 1") }
        val pid = pidOf(other)
        val table = runBlocking { server.transactions() }
        val row = table.rowOf(pid)!!
        assertEquals("idle in transaction", table.text(row, "State"))
        assertNotNull(table.text(row, "Seconds"))
        assertTrue(table.text(row, "Query")!!.contains("SELECT"))
        other.rollback()
        eventually("the transaction to be gone") { runBlocking { server.transactions() }.rowOf(pid) == null }
    }

    @Test
    fun `a session waiting for a row lock is paired with the one holding it`() {
        fixture.execute(
            "CREATE TABLE ${fixture.t("locks")} (id int PRIMARY KEY, v int)",
            "INSERT INTO ${fixture.t("locks")} VALUES (1, 0)",
        )
        val holder = other()
        holder.autoCommit = false
        holder.createStatement().use { it.execute("UPDATE ${fixture.t("locks")} SET v = 1 WHERE id = 1") }
        val waiter = other()
        waiter.autoCommit = false
        // Both ids before the waiter blocks: its connection is busy while the statement waits.
        val holderPid = pidOf(holder)
        val waiterPid = pidOf(waiter)
        val worker = thread {
            runCatching { waiter.createStatement().use { it.execute("UPDATE ${fixture.t("locks")} SET v = 2 WHERE id = 1") } }
        }
        try {
            eventually("the wait to show up") {
                runBlocking { server.lockWaits() }.rowOf(waiterPid, "WaitingId") != null
            }
            val table = runBlocking { server.lockWaits() }
            val row = table.rowOf(waiterPid, "WaitingId")!!
            assertEquals(holderPid.toString(), table.text(row, "BlockingId"))
            assertTrue(table.text(row, "WaitingQuery")!!.contains("UPDATE"))
        } finally {
            holder.rollback()
            worker.join(10_000)
            waiter.rollback()
        }
    }

    // ------------------------------------------------------------------ replication

    @Test
    fun `a primary with no standby is not a replica`() {
        assertEquals(ReplicationReport.NotReplica, runBlocking { server.replication() })
    }

    // ------------------------------------------------------------------ pg_stat_statements

    private fun inThrowawayDatabase(block: (ServerRepository, Connection) -> Unit) {
        val name = "sqlpulse_it_srv_${System.nanoTime()}"
        fixture.session.use { it.execute("CREATE DATABASE ${fixture.q(name)}") }
        val session = SqlSession(fixture.config.copy(database = name))
        try {
            val repo = ServerRepository(IntegrationSessions.manager(session, "public", dialect = PostgresDialect))
            session.use { block(repo, it) }
        } finally {
            session.close()
            fixture.session.use { it.execute("DROP DATABASE IF EXISTS ${fixture.q(name)} WITH (FORCE)") }
        }
    }

    @Test
    fun `without the extension the slow panel says it is not installed`() {
        inThrowawayDatabase { repo, _ ->
            assertEquals(SlowStatementsReport.ExtensionMissing, runBlocking { repo.slowStatements(SlowSort.TOTAL) })
        }
    }

    @Test
    fun `an extension that was created but never preloaded is told apart from a missing one`() {
        // Only meaningful where the library is not preloaded, which is how the main test server runs.
        val preloaded = fixture.scalar("SHOW shared_preload_libraries").orEmpty().contains("pg_stat_statements")
        assumeTrue("pg_stat_statements is preloaded on this server", !preloaded)
        inThrowawayDatabase { repo, c ->
            c.execute("CREATE EXTENSION pg_stat_statements")
            assertEquals(SlowStatementsReport.ExtensionNotLoaded, runBlocking { repo.slowStatements(SlowSort.TOTAL) })
        }
    }

    @Test
    fun `with the extension preloaded the slow panel lists statements with their numbers`() {
        val url = System.getenv(PRELOAD_VARIABLE)?.takeIf { it.isNotBlank() }
        assumeTrue("no $PRELOAD_VARIABLE, so there is no server with pg_stat_statements preloaded", url != null)
        val address = url!!.substringAfter("://")
        val config: JdbcConfig = fixture.config.copy(
            host = address.substringBefore(':'),
            port = address.substringAfter(':').substringBefore('/').toInt(),
            database = address.substringAfter('/').substringBefore('?'),
        )
        val session = SqlSession(config)
        try {
            val repo = ServerRepository(IntegrationSessions.manager(session, "public", dialect = PostgresDialect))
            session.use { c ->
                c.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
                // pg_stat_statements ignores column aliases when it tells statements apart, so the
                // marker is a table of its own: the same statement with other literals is one entry.
                val marker = "sqlpulse_marker_${System.nanoTime()}"
                c.execute("CREATE TABLE public.$marker (a int)")
                c.execute("INSERT INTO public.$marker VALUES (1)")
                try {
                    repeat(3) {
                        c.createStatement().use { s ->
                            s.executeQuery("SELECT pg_sleep(0.05), a FROM public.$marker WHERE a < ${it + 10}").use { r -> r.next() }
                        }
                    }
                    val report = runBlocking { repo.slowStatements(SlowSort.TOTAL) } as SlowStatementsReport.Rows
                    // The normalised text has the literal replaced by $n.
                    val statement = report.statements.first { it.digestText.contains(marker) }
                    assertTrue(statement.digestText.contains("a < \$2"))
                    assertEquals(3L, statement.count)
                    assertTrue("total time is real (>= 3 x 50 ms)", statement.totalPicos >= 150_000_000_000L)
                    assertTrue(statement.avgPicos in 40_000_000_000L..5_000_000_000_000L)
                    assertEquals(3L, statement.rowsSent)
                    assertTrue(statement.dollarPlaceholders)
                    assertNull(statement.rowsExamined)
                    assertNotNull(statement.blocksRead)
                    assertEquals(
                        SlowSort.COUNT,
                        (runBlocking { repo.slowStatements(SlowSort.COUNT) } as SlowStatementsReport.Rows).sort,
                    )
                } finally {
                    c.execute("DROP TABLE IF EXISTS public.$marker")
                }
            }
        } finally {
            session.close()
        }
    }

    // ------------------------------------------------------------------ users, grants, overview, pulse

    @Test
    fun `roles are listed read-only and a role's grants are written out`() {
        val role = "sqlpulse_it_role_${System.nanoTime()}"
        val group = "sqlpulse_it_group_${System.nanoTime()}"
        roles += role
        roles += group
        fixture.execute(
            "CREATE ROLE ${fixture.q(group)} NOLOGIN",
            "CREATE ROLE ${fixture.q(role)} LOGIN CREATEDB CONNECTION LIMIT 3",
            "GRANT ${fixture.q(group)} TO ${fixture.q(role)}",
            "GRANT USAGE ON SCHEMA ${fixture.q(fixture.schema)} TO ${fixture.q(role)}",
        )
        val users = runBlocking { server.users() }
        val row = users.rows.first { users.text(it, "Account") == role }
        assertEquals("login", users.text(row, "Kind"))
        assertTrue(users.rows.any { users.text(it, "Account") == "postgres" })
        // The predefined pg_* roles are noise.
        assertTrue(users.rows.none { users.text(it, "Account")!!.startsWith("pg_") })

        val grants = runBlocking { server.grants(role) }
        assertTrue(grants.first().startsWith("CREATE ROLE \"$role\" WITH LOGIN CREATEDB"))
        assertTrue(grants.first().contains("CONNECTION LIMIT 3"))
        assertTrue(grants.contains("GRANT \"$group\" TO \"$role\";"))
        assertTrue(grants.any { it.startsWith("GRANT USAGE") && it.contains("ON SCHEMA \"${fixture.schema}\"") })
        assertTrue(grants.any { it.contains("ON DATABASE") && it.contains("CONNECT") })
        assertEquals(emptyList<String>(), runBlocking { server.grants("no_such_role_${System.nanoTime()}") })
    }

    @Test
    fun `the overview names the version, uptime and connection state`() {
        val facts = runBlocking { server.overview() }.associate { it.label to it.value }
        assertTrue(facts["version"]!!.startsWith("1"))
        assertEquals(fixture.config.database, facts["database"])
        assertEquals("primary", facts["role"])
        assertTrue(facts.containsKey("uptime"))
        assertTrue(facts["max_connections"]!!.toInt() > 0)
        assertTrue(facts.containsKey("Ssl_version"))
        assertFalse(facts.containsKey("JDBC driver"))
    }

    @Test
    fun `two samples make a pulse with real numbers`() {
        val first = runBlocking { server.sample() }
        assertTrue(first.uptimeSeconds >= 0)
        assertTrue(first.value("connections")!! >= 1)
        assertTrue(PostgresPulse.COUNTERS.all { first.value(it) != null })
        // Something to count between the samples.
        repeat(5) { fixture.scalar("SELECT 1") }
        Thread.sleep(1_100)
        val second = runBlocking { server.sample() }
        val tiles = server.pulse.readings(first, second)
        val commits = tiles.first { it.id == MetricId.PG_COMMITS }
        assertNotNull(commits.value)
        assertTrue(commits.value!! > 0.0)
        assertNotNull(tiles.first { it.id == MetricId.PG_CACHE_HIT }.value)
        assertTrue(tiles.none { it.id == MetricId.REPLICATION_LAG })
    }

    // ------------------------------------------------------------------ an ordinary account

    @Test
    fun `an account without monitoring rights sees little and may not end other people's sessions`() {
        val role = "sqlpulse_it_plain_${System.nanoTime()}"
        roles += role
        fixture.execute("CREATE ROLE ${fixture.q(role)} LOGIN PASSWORD 'plain-pw'")
        val victim = other()
        victim.createStatement().use { it.execute("SELECT 1") }
        val victimPid = pidOf(victim)
        val plain = other(role, "plain-pw")
        // Replication details are for superusers and pg_read_all_stats, and the panel says so.
        PostgresServerCatalog.replication(plain).let { assertEquals(ReplicationReport.NoPrivilege, it) }
        // Other people's sessions are listed without their text, and cannot be signalled.
        val table = PostgresServerCatalog.processList(plain, includeIdle = true, limit = 500)
        val row = table.rowOf(victimPid)
        if (row != null) assertTrue(table.text(row, "Info") == null || table.text(row, "Info")!!.contains("insufficient privilege"))
        val failure = assertThrows(SQLException::class.java) {
            PostgresServerCatalog.stop(plain, victimPid, KillAction.TERMINATE)
        }
        assertEquals("42501", failure.sqlState)
        assertTrue(victim.isValid(5))
    }

    companion object {
        const val PRELOAD_VARIABLE = "SQLPULSE_TEST_POSTGRES_PRELOAD_URL"
    }
}
