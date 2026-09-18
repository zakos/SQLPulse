package hu.laurel.sqlpulse.data.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTimeoutsTest {

    @Test
    fun `a whole number of seconds in range is taken`() {
        assertEquals(1, ConnectionTimeouts.parse("1"))
        assertEquals(45, ConnectionTimeouts.parse(" 45 "))
        assertEquals(ConnectionTimeouts.MAX_SECONDS, ConnectionTimeouts.parse("3600"))
    }

    @Test
    fun `nothing else is`() {
        // Zero is the dangerous one: in JDBC it means "wait forever".
        assertNull(ConnectionTimeouts.parse("0"))
        assertNull(ConnectionTimeouts.parse("-5"))
        assertNull(ConnectionTimeouts.parse("3601"))
        assertNull(ConnectionTimeouts.parse(""))
        assertNull(ConnectionTimeouts.parse("30s"))
        assertNull(ConnectionTimeouts.parse("1.5"))
        assertNull(ConnectionTimeouts.parse("99999999999999"))
    }

    @Test
    fun `the field says which of the two it is`() {
        assertTrue(ConnectionTimeouts.isValid("10"))
        assertFalse(ConnectionTimeouts.isValid(""))
    }

    @Test
    fun `a stored number out of range falls back to the default`() {
        assertEquals(30, ConnectionTimeouts.sane(30, ConnectionTimeouts.DEFAULT_QUERY_SECONDS))
        assertEquals(30, ConnectionTimeouts.sane(0, ConnectionTimeouts.DEFAULT_QUERY_SECONDS))
        assertEquals(30, ConnectionTimeouts.sane(-1, ConnectionTimeouts.DEFAULT_QUERY_SECONDS))
        assertEquals(10, ConnectionTimeouts.sane(9_000, ConnectionTimeouts.DEFAULT_CONNECT_SECONDS))
    }

    @Test
    fun `the driver gets milliseconds`() {
        assertEquals(10_000, ConnectionTimeouts.connectMillis(10))
        assertEquals(10_000, ConnectionTimeouts.connectMillis(0))
    }

    @Test
    fun `the socket is given longer than the query`() {
        // Otherwise the socket drops first and a query that ran over its time looks like the
        // network failing, instead of the server reporting that it killed the statement.
        assertTrue(ConnectionTimeouts.socketMillis(30) > 30_000)
        assertTrue(ConnectionTimeouts.socketMillis(0) > ConnectionTimeouts.DEFAULT_QUERY_SECONDS * 1_000)
        // Even the longest allowed timeout stays well inside an Int of milliseconds.
        assertTrue(ConnectionTimeouts.socketMillis(ConnectionTimeouts.MAX_SECONDS) > 0)
    }
}
