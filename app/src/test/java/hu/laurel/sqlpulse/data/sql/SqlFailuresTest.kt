package hu.laurel.sqlpulse.data.sql

import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Test

class SqlFailuresTest {

    private fun kind(code: Int, state: String? = null, message: String = "") =
        SqlFailures.classify(code, state, message)

    @Test
    fun `a wrong password and a missing grant are not the same problem`() {
        assertEquals(
            SqlFailureKind.AUTHENTICATION,
            kind(1045, "28000", "Access denied for user 'app'@'10.0.0.5' (using password: YES)"),
        )
        assertEquals(
            SqlFailureKind.PRIVILEGE,
            kind(1142, "42000", "SELECT command denied to user 'app'@'10.0.0.5' for table 'orders'"),
        )
        // 1044 says the user is right and the grant is missing, despite the "access denied" wording.
        assertEquals(SqlFailureKind.PRIVILEGE, kind(1044, "42000", "Access denied for user to database"))
    }

    @Test
    fun `missing databases and missing tables are told apart`() {
        assertEquals(SqlFailureKind.UNKNOWN_DATABASE, kind(1049, "42000", "Unknown database 'shop'"))
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, kind(1146, "42S02", "Table 'shop.order' doesn't exist"))
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, kind(1054, "42S22", "Unknown column 'naem'"))
    }

    @Test
    fun `the everyday statement errors are recognised`() {
        assertEquals(SqlFailureKind.SYNTAX, kind(1064, "42000", "You have an error in your SQL syntax"))
        assertEquals(SqlFailureKind.DUPLICATE_KEY, kind(1062, "23000", "Duplicate entry '7' for key 'PRIMARY'"))
        assertEquals(SqlFailureKind.LOCK, kind(1205, "HY000", "Lock wait timeout exceeded"))
        assertEquals(SqlFailureKind.LOCK, kind(1213, "40001", "Deadlock found when trying to get lock"))
        assertEquals(SqlFailureKind.SERVER_BUSY_OR_READ_ONLY, kind(1290, "HY000", "--read-only option"))
        assertEquals(SqlFailureKind.SERVER_BUSY_OR_READ_ONLY, kind(1040, "08004", "Too many connections"))
    }

    @Test
    fun `a lock wait timeout is a lock problem, not a timeout`() {
        // Both mention a timeout, but waiting for another transaction is not the same as a
        // statement that ran too long, and the advice differs.
        assertEquals(SqlFailureKind.LOCK, kind(1205, null, "Lock wait timeout exceeded; try restarting"))
        assertEquals(SqlFailureKind.TIMEOUT, kind(0, "HYT00", "Query timed out"))
    }

    @Test
    fun `a failed TLS handshake is recognised from the text, because MySQL never speaks`() {
        assertEquals(
            SqlFailureKind.TLS,
            kind(0, "08000", "Could not connect: SSLHandshakeException: PKIX path building failed"),
        )
        assertEquals(
            SqlFailureKind.TLS,
            kind(0, null, "unable to find valid certification path to requested target"),
        )
    }

    @Test
    fun `an unreachable server is told apart from a dropped connection`() {
        assertEquals(SqlFailureKind.UNREACHABLE, kind(0, null, "Connection refused"))
        assertEquals(SqlFailureKind.UNREACHABLE, kind(0, null, "Name or service not known"))
        assertEquals(SqlFailureKind.CONNECTION_LOST, kind(0, "08S01", "Connection reset by peer"))
        assertEquals(SqlFailureKind.CONNECTION_LOST, kind(2013, null, "Lost connection during query"))
    }

    @Test
    fun `an unclassifiable error is left to the server's own wording`() {
        assertEquals(SqlFailureKind.OTHER, kind(1999, "HY000", "something new"))
        assertEquals(SqlFailureKind.OTHER, kind(0, null, ""))
    }

    @Test
    fun `the exception's own fields are carried through`() {
        val failure = SqlFailures.of(SQLException("Unknown database 'shop'", "42000", 1049))
        assertEquals(SqlFailureKind.UNKNOWN_DATABASE, failure.kind)
        assertEquals(1049, failure.errorCode)
        assertEquals("42000", failure.sqlState)
        assertEquals("Unknown database 'shop'", failure.serverMessage)
    }
}
