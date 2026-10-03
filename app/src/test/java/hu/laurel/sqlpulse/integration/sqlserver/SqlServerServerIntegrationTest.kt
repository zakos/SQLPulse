package hu.laurel.sqlpulse.integration.sqlserver

import hu.laurel.sqlpulse.data.schema.MetricId
import hu.laurel.sqlpulse.data.schema.ReplicationReport
import hu.laurel.sqlpulse.data.schema.ServerRepository
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.schema.SlowStatementsReport
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.dialect.KillAction
import hu.laurel.sqlpulse.data.sql.dialect.MissingPrivilegeException
import hu.laurel.sqlpulse.data.sql.dialect.OwnSessionException
import hu.laurel.sqlpulse.data.sql.dialect.SqlServerDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlServerServerCatalog
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
 * The Server and Pulse screens' questions against a real SQL Server (see [SqlServerTestServer]):
 * requests seen from a second session and ended with KILL, sleeping sessions hidden, open
 * transactions, blocking chains, availability groups (absent on the container), the query-stats
 * view, logins and their permissions, performance counters, and the account that lacks VIEW
 * SERVER STATE getting a named permission instead of a failure.
 */
class SqlServerServerIntegrationTest {

    private val dialect = SqlServerDialect
    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession
    private lateinit var server: ServerRepository
    private val others = mutableListOf<Connection>()
    private val logins = mutableListOf<String>()

    private val stamp = System.nanoTime()
    private val ns = "sp_it_srv_$stamp"

    @Before
    fun connect() {
        val found = SqlServerTestServer.configOrNull()
        assumeTrue("no ${SqlServerTestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlServerTestServer.open(config)
        server = ServerRepository(IntegrationSessions.manager(session, config.database, dialect = dialect))
        session.use { it.execute("CREATE SCHEMA ${dialect.quoteIdentifier(ns)}") }
    }

    @After
    fun disconnect() {
        others.forEach { runCatching { it.close() } }
        if (!this::session.isInitialized) return
        runCatching {
            session.use { c ->
                logins.forEach { c.execute("IF EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'$it') DROP LOGIN [$it]") }
                c.execute("IF OBJECT_ID(N'$ns.locks') IS NOT NULL DROP TABLE ${dialect.qualify(ns, "locks")}")
                c.execute("IF OBJECT_ID(N'$ns.marker_$stamp') IS NOT NULL DROP TABLE ${dialect.qualify(ns, "marker_$stamp")}")
                c.execute("DROP SCHEMA IF EXISTS ${dialect.quoteIdentifier(ns)}")
            }
        }
        session.close()
    }

    private fun other(
        user: String = config.user,
        password: String? = config.password,
        database: String = config.database,
    ): Connection {
        val c = DriverManager.getConnection(
            "jdbc:sqlserver://${config.host}:${config.port};databaseName=$database;encrypt=true;trustServerCertificate=true",
            user,
            password,
        )
        others += c
        return c
    }

    private fun spidOf(c: Connection): Long =
        c.createStatement().use { s -> s.executeQuery("SELECT @@SPID").use { it.next(); it.getLong(1) } }

    private fun ResultTable.column(name: String) = columns.indexOfFirst { it.label.equals(name, ignoreCase = true) }

    private fun ResultTable.rowOf(id: Long, idColumn: String = "Id"): List<CellValue>? {
        val index = column(idColumn)
        return rows.firstOrNull { (it[index] as? CellValue.Number)?.value?.toLongOrNull() == id }
    }

    private fun ResultTable.text(row: List<CellValue>, name: String): String? =
        (row[column(name)] as? CellValue.Text)?.value ?: (row[column(name)] as? CellValue.Number)?.value

    private fun eventually(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(150)
        }
        throw AssertionError("timed out waiting for $what")
    }

    // ------------------------------------------------------------------ running requests

    @Test
    fun `a request running in another session is listed and KILL ends its session`() {
        val other = other()
        val spid = spidOf(other)
        var failure: SQLException? = null
        val worker = thread {
            try {
                other.createStatement().use { it.execute("WAITFOR DELAY '00:01:00'") }
            } catch (e: SQLException) {
                failure = e
            }
        }
        eventually("the waiting request to show up") { runBlocking { server.processList() }.rowOf(spid) != null }
        val table = runBlocking { server.processList() }
        val row = table.rowOf(spid)!!
        assertTrue(table.text(row, "Info")!!.contains("WAITFOR"))
        assertEquals(config.database, table.text(row, "db"))
        assertEquals(config.user, table.text(row, "User"))

        assertTrue(runBlocking { server.stop(spid, KillAction.TERMINATE) })
        worker.join(15_000)
        assertNotNull("the killed session's statement failed", failure)
        eventually("the connection to be gone") { !other.isValid(2) }
    }

    @Test
    fun `T-SQL cannot cancel from another session so only terminating is accepted`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { server.stop(55, KillAction.CANCEL) } }
    }

    @Test
    fun `sleeping sessions are hidden unless asked for`() {
        val other = other()
        // The id first: asking for it would otherwise be the session's last statement.
        val spid = spidOf(other)
        other.createStatement().use { it.execute("SELECT 1 AS sleeping_marker") }
        assertNull(runBlocking { server.processList(includeIdle = false) }.rowOf(spid))
        val all = runBlocking { server.processList(includeIdle = true) }
        val row = all.rowOf(spid)!!
        assertTrue(all.text(row, "State")!!.startsWith("sleeping"))
        // A sleeping session shows the last statement it ran.
        assertTrue(all.text(row, "Info")!!.contains("sleeping_marker"))
    }

    @Test
    fun `this app's own session is never in the list and cannot be killed`() {
        session.use { c ->
            val own = spidOf(c)
            assertNull(SqlServerServerCatalog.processList(c, includeIdle = true, limit = 500).rowOf(own))
            assertThrows(OwnSessionException::class.java) { SqlServerServerCatalog.stop(c, own, KillAction.TERMINATE) }
        }
    }

    @Test
    fun `a session that is already gone answers false instead of failing`() {
        assertFalse(runBlocking { server.stop(32_000, KillAction.TERMINATE) })
        assertFalse(runBlocking { server.stop(0, KillAction.TERMINATE) })
    }

    // ------------------------------------------------------------------ transactions and blocking

    @Test
    fun `a transaction left open shows up as idle in transaction`() {
        val other = other()
        other.createStatement().use { it.execute("BEGIN TRANSACTION") }
        val spid = spidOf(other)
        try {
            val table = runBlocking { server.transactions() }
            val row = table.rowOf(spid)!!
            assertEquals("idle in transaction", table.text(row, "State"))
            assertNotNull(table.text(row, "Seconds"))
            assertEquals(config.database, table.text(row, "db"))
        } finally {
            other.createStatement().use { it.execute("ROLLBACK") }
        }
        eventually("the transaction to be gone") { runBlocking { server.transactions() }.rowOf(spid) == null }
    }

    @Test
    fun `a blocked request is paired with the session blocking it`() {
        session.use { it.execute("CREATE TABLE ${dialect.qualify(ns, "locks")} (id INT PRIMARY KEY, v INT)") }
        session.use { it.execute("INSERT INTO ${dialect.qualify(ns, "locks")} VALUES (1, 0)") }
        val holder = other()
        holder.createStatement().use { it.execute("BEGIN TRANSACTION; UPDATE ${dialect.qualify(ns, "locks")} SET v = 1 WHERE id = 1") }
        val waiter = other()
        // Both ids before the waiter blocks: its connection is busy while the statement waits.
        val holderSpid = spidOf(holder)
        val waiterSpid = spidOf(waiter)
        val worker = thread {
            runCatching { waiter.createStatement().use { it.execute("UPDATE ${dialect.qualify(ns, "locks")} SET v = 2 WHERE id = 1") } }
        }
        try {
            eventually("the blocking to show up") { runBlocking { server.lockWaits() }.rowOf(waiterSpid, "WaitingId") != null }
            val table = runBlocking { server.lockWaits() }
            val row = table.rowOf(waiterSpid, "WaitingId")!!
            assertEquals(holderSpid.toString(), table.text(row, "BlockingId"))
            assertTrue(table.text(row, "WaitType")!!.startsWith("LCK_M_"))
            assertTrue(table.text(row, "WaitingQuery")!!.contains("UPDATE"))
        } finally {
            holder.createStatement().use { it.execute("ROLLBACK") }
            worker.join(15_000)
        }
    }

    // ------------------------------------------------------------------ replication

    @Test
    fun `a server without availability groups is not a replica`() {
        assertEquals(ReplicationReport.NotReplica, runBlocking { server.replication() })
    }

    // ------------------------------------------------------------------ query stats

    @Test
    fun `the slow panel lists statements from the query stats with their numbers`() {
        // The query store tells statements apart by their tree, not by aliases, so the marker is
        // a table of this run's own schema: nothing from an earlier run is counted with it.
        val marker = "marker_$stamp"
        session.use { it.execute("CREATE TABLE ${dialect.qualify(ns, marker)} (a INT)") }
        session.use { it.execute("INSERT INTO ${dialect.qualify(ns, marker)} VALUES (1)") }
        val c = other()
        repeat(3) {
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT_BIG(*) AS n FROM ${dialect.qualify(ns, marker)} m CROSS JOIN sys.all_columns a CROSS JOIN sys.all_columns b").use { it.next() }
            }
        }
        var found: hu.laurel.sqlpulse.data.schema.SlowStatement? = null
        eventually("the statement to reach the query stats") {
            // A wide page: on a server other people use, a three-run statement is not in its top 25.
            val report = session.use { SqlServerServerCatalog.slowStatements(it, SlowSort.TOTAL, 1_000) } as SlowStatementsReport.Rows
            found = report.statements.firstOrNull { it.digestText.contains(marker) }
            found != null
        }
        val statement = found!!
        assertEquals(3L, statement.count)
        assertTrue("total time is real", statement.totalPicos > 0)
        assertTrue(statement.avgPicos in 1..statement.totalPicos)
        assertEquals(3L, statement.rowsSent)
        assertNull(statement.rowsExamined)
        assertTrue("logical reads were counted", statement.blocksRead!! > 0)
        assertFalse(statement.dollarPlaceholders)
        assertNotNull(statement.lastSeen)
        for (sort in SlowSort.entries) {
            assertEquals(sort, (runBlocking { server.slowStatements(sort) } as SlowStatementsReport.Rows).sort)
        }
    }

    // ------------------------------------------------------------------ logins, overview, pulse

    @Test
    fun `logins are listed read-only and their permissions are written out`() {
        val users = runBlocking { server.users() }
        val sa = users.rows.first { users.text(it, "Account") == "sa" }
        assertEquals("SQL_LOGIN", users.text(sa, "Kind"))
        assertTrue(users.rows.any { users.text(it, "Account") == "sysadmin" && users.text(it, "Kind") == "SERVER_ROLE" })
        assertTrue(users.rows.none { users.text(it, "Account")!!.startsWith("##") })

        val grants = runBlocking { server.grants("sa") }
        assertTrue(grants.contains("ALTER SERVER ROLE [sysadmin] ADD MEMBER [sa];"))
        assertTrue(grants.any { it.startsWith("GRANT CONNECT SQL TO [sa]") })
        assertEquals(emptyList<String>(), runBlocking { server.grants("no_such_login_$stamp") })
    }

    @Test
    fun `the overview names the version, edition and uptime`() {
        val facts = runBlocking { server.overview() }.associate { it.label to it.value }
        assertTrue(facts["version"]!!.startsWith("1"))
        assertNotNull(facts["edition"])
        assertEquals(config.database, facts["database"])
        assertEquals(config.user, facts["current_user_name"])
        assertTrue(facts.containsKey("uptime"))
        assertTrue(facts.containsKey("connections"))
        assertFalse(facts.containsKey("JDBC driver"))
    }

    @Test
    fun `two samples make a pulse with real numbers`() {
        val first = runBlocking { server.sample() }
        for (key in listOf("batch_requests", "user_connections", "page_life_expectancy", "cache_hit", "cache_hit_base", "transactions", "blocked")) {
            assertNotNull("$key was sampled", first.value(key))
        }
        repeat(5) { session.use { it.execute("SELECT 1") } }
        Thread.sleep(1_100)
        val second = runBlocking { server.sample() }
        val tiles = server.pulse.readings(first, second)
        val batches = tiles.first { it.id == MetricId.MS_BATCH_REQUESTS }
        assertNotNull(batches.value)
        assertTrue(batches.value!! > 0.0)
        val hit = tiles.first { it.id == MetricId.MS_CACHE_HIT }.value
        assertNotNull(hit)
        assertTrue(hit!! in 0.0..1.0)
        assertTrue(tiles.first { it.id == MetricId.MS_USER_CONNECTIONS }.display.toInt() >= 1)
    }

    // ------------------------------------------------------------------ an account without VIEW SERVER STATE

    @Test
    fun `an account without VIEW SERVER STATE gets a named permission instead of a failure`() {
        val login = "sp_it_plain_$stamp"
        val password = "Plain_Pw1!Strong"
        logins += login
        session.use { it.execute("CREATE LOGIN [$login] WITH PASSWORD = N'$password', CHECK_POLICY = OFF") }
        // master has an enabled guest user, which is how a login with no mapping can connect at all.
        val plain = other(login, password, database = "master")

        val processes = assertThrows(MissingPrivilegeException::class.java) {
            SqlServerServerCatalog.processList(plain, includeIdle = false, limit = 100)
        }
        assertEquals("VIEW SERVER STATE", processes.privilege)
        assertThrows(MissingPrivilegeException::class.java) { SqlServerServerCatalog.transactions(plain, 100) }
        assertThrows(MissingPrivilegeException::class.java) { SqlServerServerCatalog.lockWaits(plain, 100) }
        assertThrows(MissingPrivilegeException::class.java) { SqlServerServerCatalog.sample(plain) }
        assertEquals(SlowStatementsReport.NoPrivilege, SqlServerServerCatalog.slowStatements(plain, SlowSort.TOTAL))
        // The overview still answers, with a shorter card.
        val facts = SqlServerServerCatalog.overview(plain).associate { it.label to it.value }
        assertTrue(facts.containsKey("version"))
        assertFalse(facts.containsKey("uptime"))
        // And it may not kill: that needs ALTER ANY CONNECTION.
        val victim = other()
        val failure = assertThrows(SQLException::class.java) {
            SqlServerServerCatalog.stop(plain, spidOf(victim), KillAction.TERMINATE)
        }
        assertTrue(failure.message!!.isNotBlank())
        assertTrue(victim.isValid(5))
    }
}
