package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteImpactTest {

    @Test
    fun `an update becomes a count over the same table and where`() {
        assertEquals(
            "SELECT COUNT(*) FROM orders WHERE paid = 0 AND id > 10",
            WriteImpact.countQuery("UPDATE orders SET paid = 1 WHERE paid = 0 AND id > 10"),
        )
    }

    @Test
    fun `a delete becomes a count over the same table and where`() {
        assertEquals(
            "SELECT COUNT(*) FROM orders WHERE created_at < '2020-01-01'",
            WriteImpact.countQuery("DELETE FROM orders WHERE created_at < '2020-01-01'"),
        )
    }

    @Test
    fun `a write without a where counts the whole table`() {
        assertEquals("SELECT COUNT(*) FROM orders", WriteImpact.countQuery("UPDATE orders SET paid = 1"))
        assertEquals("SELECT COUNT(*) FROM orders", WriteImpact.countQuery("DELETE FROM orders"))
    }

    @Test
    fun `the table keeps its database prefix its backticks and its alias`() {
        assertEquals(
            "SELECT COUNT(*) FROM shop.`my orders` o WHERE o.id = 1",
            WriteImpact.countQuery("UPDATE shop.`my orders` o SET o.paid = 1 WHERE o.id = 1"),
        )
        assertEquals(
            "SELECT COUNT(*) FROM orders AS o WHERE o.id = 1",
            WriteImpact.countQuery("DELETE FROM orders AS o WHERE o.id = 1"),
        )
    }

    @Test
    fun `a trailing semicolon and stray whitespace are dropped`() {
        // The WHERE is copied over as it was written, down to its case: it is the user's text.
        assertEquals(
            "SELECT COUNT(*) FROM t where id = 1",
            WriteImpact.countQuery("  update t set a = 1 where id = 1 ;  "),
        )
    }

    @Test
    fun `named parameters survive into the count`() {
        assertEquals(
            "SELECT COUNT(*) FROM t WHERE id = :id",
            WriteImpact.countQuery("UPDATE t SET a = 1 WHERE id = :id"),
        )
    }

    @Test
    fun `a subquery in the where is kept whole`() {
        assertEquals(
            "SELECT COUNT(*) FROM t WHERE id IN (SELECT id FROM s WHERE x = 1 LIMIT 5)",
            WriteImpact.countQuery("DELETE FROM t WHERE id IN (SELECT id FROM s WHERE x = 1 LIMIT 5)"),
        )
    }

    @Test
    fun `a column whose name starts with a keyword is not mistaken for one`() {
        assertEquals(
            "SELECT COUNT(*) FROM t WHERE id = 1",
            WriteImpact.countQuery("UPDATE t SET where_at = 1, limited = 2 WHERE id = 1"),
        )
    }

    @Test
    fun `a where hidden in a string is not a where`() {
        assertEquals(
            "SELECT COUNT(*) FROM t",
            WriteImpact.countQuery("UPDATE t SET note = 'where id = 1'"),
        )
    }

    @Test
    fun `comments are dropped rather than copied into the count`() {
        assertEquals(
            "SELECT COUNT(*) FROM t WHERE id = 1",
            WriteImpact.countQuery("UPDATE /* now */ t SET a = 1 WHERE id = 1 -- careful"),
        )
    }

    @Test
    fun `an order by without a limit is left off the count`() {
        assertEquals(
            "SELECT COUNT(*) FROM t WHERE id > 1",
            WriteImpact.countQuery("DELETE FROM t WHERE id > 1 ORDER BY id"),
        )
    }

    @Test
    fun `a statement with a limit has no count`() {
        assertNull(WriteImpact.countQuery("DELETE FROM t WHERE id > 1 ORDER BY id LIMIT 10"))
        assertNull(WriteImpact.countQuery("UPDATE t SET a = 1 LIMIT 5"))
    }

    @Test
    fun `a multi table update has no count`() {
        assertNull(WriteImpact.countQuery("UPDATE a, b SET a.x = b.x WHERE a.id = b.id"))
        assertNull(WriteImpact.countQuery("UPDATE a JOIN b ON a.id = b.id SET a.x = 1"))
        assertNull(WriteImpact.countQuery("UPDATE a LEFT JOIN b ON a.id = b.id SET a.x = 1"))
    }

    @Test
    fun `a multi table delete has no count`() {
        assertNull(WriteImpact.countQuery("DELETE a FROM a JOIN b ON a.id = b.id"))
        assertNull(WriteImpact.countQuery("DELETE a, b FROM a JOIN b ON a.id = b.id"))
        assertNull(WriteImpact.countQuery("DELETE FROM a USING a, b WHERE a.id = b.id"))
    }

    @Test
    fun `a subquery in the from has no count`() {
        assertNull(WriteImpact.countQuery("UPDATE (SELECT * FROM t) x SET x.a = 1"))
        assertNull(WriteImpact.countQuery("DELETE FROM (SELECT * FROM t) x"))
    }

    @Test
    fun `a write behind a common table expression has no count`() {
        assertNull(WriteImpact.countQuery("WITH x AS (SELECT 1) UPDATE t SET a = 1 WHERE id = 1"))
    }

    @Test
    fun `statements that add rows have no count`() {
        assertNull(WriteImpact.countQuery("INSERT INTO t (a) VALUES (1)"))
        assertNull(WriteImpact.countQuery("REPLACE INTO t (a) VALUES (1)"))
        assertNull(WriteImpact.countQuery("SELECT * FROM t"))
        assertNull(WriteImpact.countQuery("TRUNCATE TABLE t"))
        assertNull(WriteImpact.countQuery(""))
    }

    @Test
    fun `two statements in one string have no count`() {
        assertNull(WriteImpact.countQuery("DELETE FROM a WHERE id = 1; DELETE FROM b WHERE id = 2"))
    }

    @Test
    fun `a partitioned target is refused rather than counted whole`() {
        assertNull(WriteImpact.countQuery("DELETE FROM t PARTITION (p0) WHERE id = 1"))
    }

    @Test
    fun `the modifiers MySQL allows before the table are skipped`() {
        assertEquals(
            "SELECT COUNT(*) FROM t WHERE id = 1",
            WriteImpact.countQuery("UPDATE LOW_PRIORITY IGNORE t SET a = 1 WHERE id = 1"),
        )
        assertEquals(
            "SELECT COUNT(*) FROM t WHERE id = 1",
            WriteImpact.countQuery("DELETE LOW_PRIORITY QUICK IGNORE FROM t WHERE id = 1"),
        )
    }

    @Test
    fun `an estimate above the ceiling is refused and one below it is not`() {
        assertTrue(AffectedRowLimit.exceeds(1_001, 1_000))
        assertFalse(AffectedRowLimit.exceeds(1_000, 1_000))
        assertFalse(AffectedRowLimit.exceeds(0, 1_000))
    }

    @Test
    fun `an unknown estimate never counts as exceeding the ceiling`() {
        assertFalse(AffectedRowLimit.exceeds(null, 1_000))
    }

    @Test
    fun `a ceiling of zero turns the check off`() {
        assertFalse(AffectedRowLimit.exceeds(1_000_000, 0))
    }
}
