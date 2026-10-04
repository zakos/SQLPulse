package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.schema.ReplicationHealth
import hu.laurel.sqlpulse.data.schema.ReplicationReport
import hu.laurel.sqlpulse.data.schema.ServerRepository
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.schema.SlowStatement
import hu.laurel.sqlpulse.data.schema.SlowStatements
import hu.laurel.sqlpulse.data.schema.SlowStatementsReport
import hu.laurel.sqlpulse.data.schema.ThreadState
import hu.laurel.sqlpulse.data.schema.quoteIdentifier
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SqlSession
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The replication panel and the slowest-statements panel on a real server (see [TestServer]).
 *
 * Whether the performance schema is on is a property of the server under test, so the two
 * slow-statement tests are each other's complement: one runs where it is off, the other where it is
 * on, and CI's stock MySQL 8 image is the second. The replica test needs a replica, which the
 * workflow's single-container servers are not; it names one through [REPLICA_VARIABLE] and is
 * skipped without it.
 */
class ServerOpsIntegrationTest {

    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession
    private lateinit var server: ServerRepository

    private val stamp = System.nanoTime()
    private val database = "sqlpulse_it_ops_$stamp"
    private val table = "marker_$stamp"

    @Before
    fun connect() {
        val found = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlSession(config)
        server = ServerRepository(IntegrationSessions.manager(session, config.database))
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) {
            runCatching { session.use { it.execute("DROP DATABASE IF EXISTS ${q(database)}") } }
        }
    }

    // --- replication -------------------------------------------------------------------------

    @Test
    fun `a server that is not a replica says so instead of failing`() {
        // The suite's server is a plain one by construction: the replica of the tests below is
        // reached through its own variable.
        val report = runBlocking { server.replication() }

        assertTrue("expected NotReplica, got $report", report === ReplicationReport.NotReplica)
    }

    @Test
    fun `a running replica is reported with its threads, lag and positions`() {
        val replica = replicaConfigOrSkip()
        val replicaSession = SqlSession(replica)
        val repository = ServerRepository(IntegrationSessions.manager(replicaSession, replica.database))

        val report = runBlocking { repository.replication() }

        assertTrue("expected channels, got $report", report is ReplicationReport.Channels)
        val channel = (report as ReplicationReport.Channels).channels.single()
        assertEquals(ThreadState.RUNNING, channel.ioThread)
        assertEquals(ThreadState.RUNNING, channel.sqlThread)
        assertNotNull(channel.sourceHost)
        assertNotNull(channel.sourcePort)
        assertNotNull("a healthy replica reports its lag as a number", channel.lagSeconds)
        assertTrue("lag: ${channel.lagSeconds}", channel.lagSeconds!! >= 0)
        assertEquals(0L, channel.sqlDelaySeconds)
        assertNull(channel.lastError)
        assertTrue("positions are read from the status row", channel.hasPositions)
        assertTrue(report.raw.rows.size == 1)
        assertTrue(
            "health: ${channel.health}, lag ${channel.lagSeconds}",
            channel.health == ReplicationHealth.OK || channel.health == ReplicationHealth.LAGGING,
        )
    }

    @Test
    fun `a stopped SQL thread is reported as stopped`() {
        val replica = replicaConfigOrSkip()
        val replicaSession = SqlSession(replica)
        val repository = ServerRepository(IntegrationSessions.manager(replicaSession, replica.database))
        try {
            replicaSession.use { it.execute("STOP SLAVE SQL_THREAD") }

            val report = runBlocking { repository.replication() }

            val channel = (report as ReplicationReport.Channels).channels.single()
            assertEquals(ThreadState.RUNNING, channel.ioThread)
            assertEquals(ThreadState.STOPPED, channel.sqlThread)
            assertEquals(ReplicationHealth.STOPPED, channel.health)
        } finally {
            // The replica is shared with the next test; leave it the way it was found.
            runCatching { replicaSession.use { it.execute("START SLAVE SQL_THREAD") } }
        }
    }

    // --- slow statements ---------------------------------------------------------------------

    @Test
    fun `slow statements report the performance schema as off when it is`() {
        assumeTrue("the performance schema is on here", !performanceSchemaOn())

        val report = runBlocking { server.slowStatements(SlowSort.TOTAL) }

        assertTrue("expected PerformanceSchemaOff, got $report", report === SlowStatementsReport.PerformanceSchemaOff)
    }

    @Test
    fun `slow statements are listed and sorted for every sort order when the performance schema is on`() {
        assumeTrue("the performance schema is off here", performanceSchemaOn())
        // Statements of our own, with a marker in their text so they can be told from everything
        // else the server has digested; the digest normalises literals, not table names.
        val slow = "SELECT SLEEP(1) FROM ${q(database)}.${q(table)} LIMIT 1"
        val frequent = "SELECT COUNT(*) FROM ${q(database)}.${q(table)} WHERE id > "
        session.use { connection ->
            connection.execute("CREATE DATABASE ${q(database)}")
            connection.execute("CREATE TABLE ${q(database)}.${q(table)} (id INT PRIMARY KEY)")
            connection.execute("INSERT INTO ${q(database)}.${q(table)} VALUES (1)")
            connection.createStatement().use { it.executeQuery(slow).close() }
            repeat(300) { connection.createStatement().use { s -> s.executeQuery(frequent + it).close() } }
        }

        val reports = SlowSort.entries.associateWith { sort ->
            val report = runBlocking { server.slowStatements(sort) }
            assertTrue("$sort: expected rows, got $report", report is SlowStatementsReport.Rows)
            assertEquals(sort, (report as SlowStatementsReport.Rows).sort)
            report.statements
        }

        reports.forEach { (sort, rows) ->
            assertTrue("$sort: at most ${SlowStatements.LIMIT} rows", rows.size in 1..SlowStatements.LIMIT)
            // Every row has text: the bucket for what did not fit in the table is left out.
            assertTrue(rows.all { it.digestText.isNotBlank() })
            val metric = metric(sort)
            assertEquals("$sort: sorted descending", rows.map(metric).sortedDescending(), rows.map(metric))
            // Total is count times average, to within the integer rounding of the server.
            rows.forEach { assertTrue(it.totalPicos >= it.avgPicos) }
        }

        // Relative order of our own two statements, wherever the server's other traffic lets them
        // into the top rows. Each must be present where it dominates the metric.
        fun List<SlowStatement>.ours(text: String) = firstOrNull { it.digestText.contains(table) && it.digestText.contains(text) }
        val slowRow = reports.getValue(SlowSort.AVERAGE).ours("SLEEP")
        assertNotNull("the one-second statement leads the averages", slowRow)
        assertTrue("average: ${slowRow!!.avgPicos}", slowRow.avgPicos >= 1_000_000_000_000L)
        // A second and a hair: shown in seconds with two decimals, nothing like raw picoseconds.
        val shown = SlowStatements.formatPicos(slowRow.avgPicos)
        assertTrue(shown, shown.matches(Regex("""1\.\d\d s""")))
        assertEquals(1L, slowRow.count)
        assertEquals(config.database, slowRow.schema)

        val totalSlow = reports.getValue(SlowSort.TOTAL).ours("SLEEP")
        assertNotNull("the one-second statement is among the costliest", totalSlow)

        val frequentRow = reports.getValue(SlowSort.COUNT).ours("COUNT")
        assertNotNull("the 300-times statement leads the counts", frequentRow)
        assertEquals(300L, frequentRow!!.count)
        // Same digest for every literal: 300 runs, one row.
        assertEquals(1, reports.getValue(SlowSort.COUNT).count { it.digestText.contains(table) && it.digestText.contains("COUNT") })
        assertNotNull(frequentRow.firstSeen)
        assertNotNull(frequentRow.lastSeen)
        assertTrue(SlowStatements.formatPicos(frequentRow.totalPicos).matches(Regex("""<1 ns|[\d.]+ (ns|µs|ms|s)|\d+ h \d+ m|\d+ m \d+ s""")))
        assertEquals(300L, frequentRow.rowsSent)
    }

    private fun metric(sort: SlowSort): (SlowStatement) -> Long = when (sort) {
        SlowSort.TOTAL -> { it -> it.totalPicos }
        SlowSort.AVERAGE -> { it -> it.avgPicos }
        SlowSort.COUNT -> { it -> it.count }
    }

    private fun performanceSchemaOn(): Boolean = session.use { it.scalar("SELECT @@performance_schema") } == "1"

    private fun replicaConfigOrSkip(): JdbcConfig {
        val url = System.getenv(REPLICA_VARIABLE)?.takeIf { it.isNotBlank() }
        assumeTrue("no $REPLICA_VARIABLE, so there is no replica to look at", url != null)
        val address = url!!.substringAfter("://").substringBefore('/')
        return config.copy(
            host = address.substringBefore(':'),
            port = address.substringAfter(':', "3306").toInt(),
            database = address0(url).ifBlank { config.database },
        )
    }

    private fun address0(url: String) = url.substringAfter("://").substringAfter('/', "").substringBefore('?')

    private fun q(name: String) = quoteIdentifier(name)

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.scalar(sql: String): String =
        createStatement().use { statement -> statement.executeQuery(sql).use { it.next(); it.getString(1) } }

    private companion object {
        /** `jdbc:mysql://host:port/database` of a server that replicates from another one. */
        const val REPLICA_VARIABLE = "SQLPULSE_TEST_MYSQL_REPLICA_URL"
    }
}
