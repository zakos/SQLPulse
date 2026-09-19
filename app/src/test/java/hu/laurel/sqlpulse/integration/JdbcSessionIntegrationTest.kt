package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.JdbcDriverKind
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Connecting, choosing a driver and reading results — against a real server (see [TestServer]).
 *
 * The project has hundreds of unit tests and, until these, nothing that had ever spoken MySQL:
 * every one of the things below is something only a server can be wrong about. What is exercised
 * is the production code — [SqlSession] opens the connection, [ResultTable] reads the rows — not
 * a test-only copy of it.
 */
class JdbcSessionIntegrationTest {

    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession

    @Before
    fun connect() {
        val found = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlSession(config)
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) session.close()
    }

    @Test
    fun `a session opens a connection to the server and it is alive`() {
        session.use { connection ->
            assertTrue(connection.isValid(5))
            assertEquals(config.database, connection.catalog)
        }
    }

    @Test
    fun `the driver choice settles on the modern driver and the whole pool then uses it`() {
        session.use { it.createStatement().use { statement -> statement.execute("SELECT 1") } }
        assertEquals(JdbcDriverKind.MODERN, session.settled)

        // A second borrow must not try both drivers again: whichever one worked is the one the
        // rest of the pool opens with, and that is what `settled` is for.
        session.use { it.createStatement().use { statement -> statement.execute("SELECT 1") } }
        assertEquals(JdbcDriverKind.MODERN, session.settled)
    }

    @Test
    fun `the driver the fallback falls back to reaches the same server`() {
        // The fallback itself cannot be provoked here — it needs a pre-5_5_3 server that refuses
        // utf8mb4 — so what is checked is its precondition: the legacy driver, used for exactly
        // those servers, logs in and answers. Which refusals trigger it is covered by
        // SqlFailuresTest.
        assumeTrue(
            "the legacy driver cannot log in to this server's default authentication",
            TestServer.legacyDriverConfigured(),
        )
        TestServer.connectWith(com.mysql.jdbc.Driver(), "mysql", config).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT VERSION()").use { rows ->
                    assertTrue(rows.next())
                    assertNotNull(rows.getString(1))
                }
            }
        }
    }

    @Test
    fun `a SELECT comes back with its columns typed the way the grid needs them`() {
        val table = session.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT 42 AS n, 'szöveg' AS t, NULL AS nothing",
                ).use { rows -> ResultTable.from(rows, maxRows = 10) }
            }
        }

        assertEquals(listOf("n", "t", "nothing"), table.columns.map { it.label })
        assertEquals(CellType.NUMBER, table.columns[0].type)
        assertEquals(CellValue.Number("42"), table.rows[0][0])
        // Not ASCII on purpose: the whole point of the modern driver's utf8mb4 handshake.
        assertEquals(CellValue.Text("szöveg"), table.rows[0][1])
        assertEquals(CellValue.Null, table.rows[0][2])
    }

    @Test
    fun `a parameterised query binds its value instead of pasting it into the statement`() {
        // A value that would end the string and start a second statement if it were interpolated.
        val hostile = "O'Brien'; SELECT 1 -- "
        val echoed = session.use { connection ->
            connection.prepareStatement("SELECT ? AS value").use { statement ->
                statement.setString(1, hostile)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    rows.getString(1)
                }
            }
        }
        assertEquals(hostile, echoed)
    }

    @Test
    fun `the pool hands out a limited number of connections and takes them back`() {
        val taken = (1..SqlSession.MAX_CONNECTIONS).map { session.take() }
        assertEquals(SqlSession.MAX_CONNECTIONS, taken.distinct().size)
        taken.forEach { session.giveBack(it) }

        // Returned connections are reused rather than reopened, which is what keeps a tunnel from
        // being asked for a fourth forward.
        session.use { connection -> assertTrue(connection.isValid(5)) }
    }
}
