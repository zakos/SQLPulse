package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowStatementsTest {

    @Test
    fun `picoseconds are shown in a readable unit`() {
        assertEquals("<1 ns", SlowStatements.formatPicos(500))
        assertEquals("1.50 µs", SlowStatements.formatPicos(1_500_000))
        assertEquals("2.50 ms", SlowStatements.formatPicos(2_500_000_000))
        assertEquals("120 ms", SlowStatements.formatPicos(120_000_000_000))
        assertEquals("1.23 s", SlowStatements.formatPicos(1_234_000_000_000))
        assertEquals("12.0 s", SlowStatements.formatPicos(12_000_000_000_000))
        assertEquals("2m 5s", SlowStatements.formatPicos(125_000_000_000_000))
        assertEquals("3h 1m", SlowStatements.formatPicos(10_860_000_000_000_000))
    }

    @Test
    fun `query orders by the chosen column and limits`() {
        val total = SlowStatements.query(SlowSort.TOTAL)
        assertTrue(total.contains("ORDER BY SUM_TIMER_WAIT DESC"))
        assertTrue(total.contains("LIMIT 25"))
        assertTrue(total.contains("DIGEST_TEXT IS NOT NULL"))
        assertTrue(SlowStatements.query(SlowSort.AVERAGE).contains("ORDER BY AVG_TIMER_WAIT DESC"))
        assertTrue(SlowStatements.query(SlowSort.COUNT).contains("ORDER BY COUNT_STAR DESC"))
        assertTrue(SlowStatements.query(SlowSort.COUNT, limit = 5000).contains("LIMIT 100"))
    }

    @Test
    fun `support by server version`() {
        fun ok(v: String) = SlowStatements.supported(ServerVersion.parse(v))
        assertTrue(ok("8.0.39"))
        assertTrue(ok("5.7.44-log"))
        assertTrue(ok("5.6.51"))
        assertFalse(ok("5.5.62"))
        assertTrue(ok("10.11.6-MariaDB-1:10.11.6+maria~ubu2204"))
        assertTrue(ok("10.5.0-MariaDB"))
        assertFalse(ok("10.4.32-MariaDB"))
        assertTrue(ok("5.5.5-10.6.12-MariaDB"))
        assertTrue(ok("weird"))
    }

    @Test
    fun `counters survive unsigned overflow and junk`() {
        assertEquals(42L, SlowStatements.parseCounter("42"))
        assertEquals(Long.MAX_VALUE, SlowStatements.parseCounter("18446744073709551615"))
        assertEquals(0L, SlowStatements.parseCounter(null))
        assertEquals(0L, SlowStatements.parseCounter("x"))
    }

    @Test
    fun `error codes are classified`() {
        assertTrue(SlowStatements.isAccessDenied(1142))
        assertTrue(SlowStatements.isMissingTable(1146))
        assertFalse(SlowStatements.isAccessDenied(1146))
    }

    @Test
    fun `mariadb version prefix is stripped`() {
        val version = ServerVersion.parse("5.5.5-10.6.12-MariaDB")
        assertTrue(version.mariaDb)
        assertEquals(10, version.major)
        assertEquals(6, version.minor)
    }
}
