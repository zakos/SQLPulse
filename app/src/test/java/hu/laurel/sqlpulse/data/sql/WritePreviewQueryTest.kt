package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WritePreviewQueryTest {

    private fun preview(sql: String, limit: Int = 20) = WriteImpact.previewQuery(sql, limit)

    @Test
    fun `an update shows the old row and the new value beside it`() {
        val query = preview("UPDATE orders SET paid = 1 WHERE id > 10")!!
        assertEquals(
            "SELECT *, (1) AS `paid (new)` FROM orders WHERE id > 10 LIMIT 20",
            query.sql,
        )
        assertEquals(WriteKind.UPDATE, query.kind)
        assertEquals(listOf("paid"), query.changedColumns)
    }

    @Test
    fun `a delete shows the rows it would remove`() {
        val query = preview("DELETE FROM orders WHERE created_at < '2020-01-01'", limit = 5)!!
        assertEquals("SELECT * FROM orders WHERE created_at < '2020-01-01' LIMIT 5", query.sql)
        assertEquals(WriteKind.DELETE, query.kind)
        assertEquals(emptyList<String>(), query.changedColumns)
    }

    @Test
    fun `a write without a where previews the whole table, limited`() {
        assertEquals("SELECT * FROM t LIMIT 20", preview("DELETE FROM t")!!.sql)
        assertEquals("SELECT *, ('x') AS `a (new)` FROM t LIMIT 20", preview("UPDATE t SET a = 'x'")!!.sql)
    }

    @Test
    fun `several assignments keep their order`() {
        val query = preview("UPDATE t SET a = 1, b = b + 1, c = 'z' WHERE id = 1")!!
        assertEquals(
            "SELECT *, (1) AS `a (new)`, (b + 1) AS `b (new)`, ('z') AS `c (new)` FROM t WHERE id = 1 LIMIT 20",
            query.sql,
        )
        assertEquals(listOf("a", "b", "c"), query.changedColumns)
    }

    @Test
    fun `commas inside parentheses do not split the set list`() {
        val query = preview("UPDATE t SET name = CONCAT(first, ', ', last), n = IF(a > 1, 2, 3) WHERE id = 1")!!
        assertEquals(listOf("name", "n"), query.changedColumns)
        assertEquals(
            "SELECT *, (CONCAT(first, ', ', last)) AS `name (new)`, (IF(a > 1, 2, 3)) AS `n (new)` FROM t WHERE id = 1 LIMIT 20",
            query.sql,
        )
    }

    @Test
    fun `nested parentheses are balanced`() {
        val query = preview("UPDATE t SET a = COALESCE((SELECT MAX(x) FROM s WHERE y = 1), 0), b = 2")!!
        assertEquals(listOf("a", "b"), query.changedColumns)
        assertEquals(
            "SELECT *, (COALESCE((SELECT MAX(x) FROM s WHERE y = 1), 0)) AS `a (new)`, (2) AS `b (new)` FROM t LIMIT 20",
            query.sql,
        )
    }

    @Test
    fun `strings containing commas, equals signs and keywords stay whole`() {
        val query = preview("UPDATE t SET note = 'a, b = c where x', other = \"q, r\" WHERE id = 1")!!
        assertEquals(listOf("note", "other"), query.changedColumns)
        assertEquals(
            "SELECT *, ('a, b = c where x') AS `note (new)`, (\"q, r\") AS `other (new)` FROM t WHERE id = 1 LIMIT 20",
            query.sql,
        )
    }

    @Test
    fun `an escaped quote does not end a string early`() {
        val query = preview("UPDATE t SET a = 'it\\'s, fine', b = 'x''y, z' WHERE id = 1")!!
        assertEquals(listOf("a", "b"), query.changedColumns)
    }

    @Test
    fun `a backquoted column with odd characters is unquoted and requoted`() {
        val query = preview("UPDATE t SET `my col` = 1, `we``ird, =` = 2")!!
        assertEquals(listOf("my col", "we`ird, ="), query.changedColumns)
        assertEquals(
            "SELECT *, (1) AS `my col (new)`, (2) AS `we``ird, = (new)` FROM t LIMIT 20",
            query.sql,
        )
    }

    @Test
    fun `an alias and a qualified target name the column`() {
        val query = preview("UPDATE t x SET x.a=1, x.`b`=2 WHERE x.id=3")!!
        assertEquals(listOf("a", "b"), query.changedColumns)
        assertEquals(
            "SELECT *, (1) AS `a (new)`, (2) AS `b (new)` FROM t x WHERE x.id=3 LIMIT 20",
            query.sql,
        )
        val schema = preview("UPDATE shop.`my orders` AS o SET shop.`my orders`.a = 1")!!
        assertEquals(listOf("a"), schema.changedColumns)
    }

    @Test
    fun `comments are dropped and an order by is not carried over`() {
        val query = preview("UPDATE /* now */ t SET a = /* one */ 1 -- why\n WHERE id = 1 ORDER BY id")!!
        assertEquals("SELECT *, (1) AS `a (new)` FROM t WHERE id = 1 LIMIT 20", query.sql)
    }

    @Test
    fun `a trailing semicolon and modifiers are fine`() {
        assertEquals(
            "SELECT *, (1) AS `a (new)` FROM t where id = 1 LIMIT 20",
            preview("  update low_priority ignore t set a = 1 where id = 1 ;")!!.sql,
        )
        assertEquals(
            "SELECT * FROM t WHERE id = 1 LIMIT 20",
            preview("DELETE QUICK FROM t WHERE id = 1")!!.sql,
        )
    }

    @Test
    fun `named parameters pass through for the executor to bind`() {
        assertEquals(
            "SELECT *, (:v) AS `a (new)` FROM t WHERE id = :id LIMIT 20",
            preview("UPDATE t SET a = :v WHERE id = :id")!!.sql,
        )
    }

    @Test
    fun `column names that look like keywords are not mistaken for them`() {
        val query = preview("UPDATE t SET where_at = 1, `limit` = 2, set_by = 'x' WHERE id = 1")!!
        assertEquals(listOf("where_at", "limit", "set_by"), query.changedColumns)
    }

    @Test
    fun `it refuses what the count refuses`() {
        listOf(
            "INSERT INTO t (a) VALUES (1)",
            "REPLACE INTO t (a) VALUES (1)",
            "SELECT * FROM t",
            "",
            "WITH x AS (SELECT 1) UPDATE t SET a = 1 WHERE id = 1",
            "UPDATE a, b SET a.x = b.x WHERE a.id = b.id",
            "UPDATE a JOIN b ON a.id = b.id SET a.x = 1",
            "UPDATE (SELECT * FROM t) x SET x.a = 1",
            "DELETE a FROM a JOIN b ON a.id = b.id",
            "DELETE FROM a USING a, b WHERE a.id = b.id",
            "UPDATE t SET a = 1 LIMIT 5",
            "DELETE FROM t WHERE id > 1 ORDER BY id LIMIT 10",
            "DELETE FROM a WHERE id = 1; DELETE FROM b WHERE id = 2",
            "UPDATE t SET a = 1; UPDATE t SET b = 2",
        ).forEach { assertNull(it, preview(it)) }
    }

    @Test
    fun `a semicolon inside a string is not a second statement`() {
        assertNotNull(preview("UPDATE t SET a = 'x; y' WHERE id = 1"))
    }

    @Test
    fun `a set list it cannot read is refused`() {
        assertNull(preview("UPDATE t SET WHERE id = 1"))
        assertNull(preview("UPDATE t SET a WHERE id = 1"))
        assertNull(preview("UPDATE t SET (a, b) = (1, 2)"))
        assertNull(preview("UPDATE t SET a = 1,, b = 2"))
        assertNull(preview("UPDATE t SET a = , b = 2"))
        assertNull(preview("UPDATE t SET a b = 1"))
    }

    @Test
    fun `a column assigned twice is refused`() {
        assertNull(preview("UPDATE t SET a = 1, A = 2"))
        assertNull(preview("UPDATE t SET a = 1, t.a = 2"))
    }

    @Test
    fun `an expression that reads an earlier assignment is refused`() {
        // MySQL would evaluate b with the NEW a; the preview only has the old row.
        assertNull(preview("UPDATE t SET a = a + 1, b = a"))
        assertNull(preview("UPDATE t SET a = 1, b = `a` * 2"))
        assertNull(preview("UPDATE t SET a = 1, b = t.A + 1"))
        // Reading a later column, or naming the column inside a string, is fine.
        assertNotNull(preview("UPDATE t SET a = b, b = 2"))
        assertNotNull(preview("UPDATE t SET a = 1, b = 'a'"))
        assertNotNull(preview("UPDATE t SET a = a + 1, b = c"))
    }

    @Test
    fun `an expression with a side effect is refused`() {
        assertNull(preview("UPDATE t SET a = NEXTVAL(seq)"))
        assertNull(preview("UPDATE t SET a = SLEEP(10)"))
        assertNull(preview("UPDATE t SET a = (@n := @n + 1)"))
        assertNull(preview("UPDATE t SET a = GET_LOCK('x', 1)"))
        // The same word inside a string is only text.
        assertNotNull(preview("UPDATE t SET a = 'sleep'"))
    }

    @Test
    fun `a non-positive limit gives no preview`() {
        assertNull(preview("DELETE FROM t", limit = 0))
    }

    @Test
    fun `the preview is always a single select`() {
        listOf(
            "UPDATE t SET a = 1 WHERE id = 1",
            "DELETE FROM t WHERE id = 1",
            "UPDATE t SET a = 'x', b = (SELECT 1) WHERE note = 'drop; table'",
        ).forEach {
            val sql = preview(it)!!.sql
            assertEquals(it, true, sql.startsWith("SELECT "))
            assertEquals(it, false, sql.contains("update", ignoreCase = true) && !it.contains("'"))
        }
    }
}
