package hu.laurel.sqlpulse.data.sql.plan

import hu.laurel.sqlpulse.data.sql.dialect.SqlServerDialect
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The guarantee behind SQL Server's EXPLAIN: a connection never goes back to the pool in SHOWPLAN
 * mode. Checked with a fake connection so the failure paths — a block that throws, a reset that
 * fails — can be driven, which a real server will not do on request.
 */
class PlanModeTest {

    private fun connection(failOnOff: Boolean = false): Pair<Connection, MutableList<String>> {
        val sent = mutableListOf<String>()
        val connection = mockk<Connection>(relaxed = true)
        val statement = mockk<Statement>(relaxed = true)
        every { connection.createStatement() } returns statement
        every { statement.execute(any<String>()) } answers {
            val sql = firstArg<String>()
            sent += sql
            if (failOnOff && sql.endsWith("OFF")) throw SQLException("connection reset")
            true
        }
        return connection to sent
    }

    @Test
    fun `plan mode is switched on, the block runs, and it is switched off`() {
        val (connection, sent) = connection()
        val result = SqlServerDialect.inPlanMode(connection) { sent += "block"; 42 }
        assertEquals(42, result)
        assertEquals(listOf("SET SHOWPLAN_XML ON", "block", "SET SHOWPLAN_XML OFF"), sent)
        verify(exactly = 0) { connection.close() }
    }

    @Test
    fun `a block that throws still resets the connection`() {
        val (connection, sent) = connection()
        try {
            SqlServerDialect.inPlanMode(connection) { throw SQLException("Invalid object name") }
            fail("the failure was swallowed")
        } catch (e: SQLException) {
            assertEquals("Invalid object name", e.message)
        }
        assertEquals(listOf("SET SHOWPLAN_XML ON", "SET SHOWPLAN_XML OFF"), sent)
        verify(exactly = 0) { connection.close() }
    }

    @Test
    fun `a reset that fails closes the connection so the pool cannot lend it again`() {
        val (connection, _) = connection(failOnOff = true)
        val result = SqlServerDialect.inPlanMode(connection) { "plan" }
        assertEquals("plan", result)
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `a failed reset after a failed block closes it and reports the block's failure`() {
        val (connection, _) = connection(failOnOff = true)
        try {
            SqlServerDialect.inPlanMode(connection) { throw SQLException("boom") }
            fail("the failure was swallowed")
        } catch (e: SQLException) {
            assertEquals("boom", e.message)
        }
        verifyOrder { connection.close() }
    }

    @Test
    fun `a plan mode that cannot even be entered still resets`() {
        val sent = mutableListOf<String>()
        val connection = mockk<Connection>(relaxed = true)
        val statement = mockk<Statement>(relaxed = true)
        every { connection.createStatement() } returns statement
        every { statement.execute(any<String>()) } answers {
            sent += firstArg<String>()
            if (firstArg<String>().endsWith("ON")) throw SQLException("no permission")
            true
        }
        try {
            SqlServerDialect.inPlanMode(connection) { fail("the block ran without plan mode") }
        } catch (e: SQLException) {
            assertEquals("no permission", e.message)
        }
        // Whether the ON half-happened is unknowable from here, so it is switched off regardless.
        assertEquals(listOf("SET SHOWPLAN_XML ON", "SET SHOWPLAN_XML OFF"), sent)
    }
}
