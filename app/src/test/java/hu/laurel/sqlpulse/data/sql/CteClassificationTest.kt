package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A `WITH` prelude hides what the statement does, and MySQL 8 allows a write behind one. */
class CteClassificationTest {

    @Test
    fun `an update behind a common table expression is a write`() {
        assertEquals(
            StatementKind.WRITE,
            SqlGuards.classify("WITH x AS (SELECT id FROM orders) UPDATE t JOIN x ON t.id = x.id SET t.paid = 1"),
        )
    }

    @Test
    fun `a delete behind a common table expression is a write`() {
        assertEquals(
            StatementKind.WRITE,
            SqlGuards.classify("WITH stale AS (SELECT id FROM s) DELETE FROM t WHERE id IN (SELECT id FROM stale)"),
        )
    }

    @Test
    fun `a select behind a common table expression is still a read`() {
        assertEquals(
            StatementKind.READ,
            SqlGuards.classify("WITH x AS (SELECT 1) SELECT * FROM x"),
        )
    }

    @Test
    fun `several common table expressions are walked in turn`() {
        assertEquals(
            StatementKind.WRITE,
            SqlGuards.classify("WITH a AS (SELECT 1), b AS (SELECT 2) DELETE FROM t WHERE id = 1"),
        )
        assertEquals(
            StatementKind.READ,
            SqlGuards.classify("WITH a AS (SELECT 1), b AS (SELECT 2) SELECT * FROM a, b"),
        )
    }

    @Test
    fun `a nested body does not end the prelude early`() {
        assertEquals(
            StatementKind.WRITE,
            SqlGuards.classify(
                "WITH x AS (SELECT id FROM (SELECT id FROM y WHERE id > 0) z) UPDATE t SET a = 1 WHERE id = 2",
            ),
        )
    }

    @Test
    fun `a recursive prelude with a column list is understood`() {
        assertEquals(
            StatementKind.WRITE,
            SqlGuards.classify(
                "WITH RECURSIVE tree (id, parent) AS (SELECT id, parent FROM n) " +
                    "DELETE FROM n WHERE id IN (SELECT id FROM tree)",
            ),
        )
    }

    @Test
    fun `a materialisation hint does not confuse the prelude`() {
        assertEquals(
            StatementKind.READ,
            SqlGuards.classify("WITH x AS NOT MATERIALIZED (SELECT 1) SELECT * FROM x"),
        )
    }

    @Test
    fun `the word update inside a string or a comment is not the statement`() {
        assertEquals(
            StatementKind.READ,
            SqlGuards.classify("WITH x AS (SELECT 'update t set a = 1' AS note) SELECT * FROM x"),
        )
        assertEquals(
            StatementKind.READ,
            SqlGuards.classify("WITH x AS (SELECT 1 /* update t set a = 1 */) SELECT * FROM x"),
        )
        assertEquals(
            StatementKind.READ,
            SqlGuards.classify("WITH x AS (\n  -- delete from t\n  SELECT 1\n) SELECT * FROM x"),
        )
    }

    @Test
    fun `a write inside a body belongs to nobody`() {
        // The body is parenthesised, so a word scan that ignored depth would read this as a write.
        assertEquals(
            StatementKind.READ,
            SqlGuards.classify("WITH x AS (SELECT id FROM updates) SELECT * FROM x"),
        )
    }

    @Test
    fun `a limit is not appended to a write hiding behind a prelude`() {
        val result = SqlGuards.applyDefaultLimit("WITH x AS (SELECT 1) UPDATE t SET a = 1 WHERE id = 2")

        assertFalse(result.limitAdded)
    }

    @Test
    fun `a where inside a body does not guard the write that follows`() {
        assertTrue(
            SqlGuards.isUnguardedWrite("WITH x AS (SELECT id FROM s WHERE id > 0) UPDATE t SET a = 1"),
        )
        assertFalse(
            SqlGuards.isUnguardedWrite("WITH x AS (SELECT id FROM s WHERE id > 0) UPDATE t SET a = 1 WHERE id = 1"),
        )
    }
}
