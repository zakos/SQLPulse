package hu.laurel.sqlpulse.data.sql.dialect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteChecksTest {

    @Test
    fun `column and table checks are found with their names`() {
        val sql = """
            CREATE TABLE orders (
                id INTEGER PRIMARY KEY,
                qty INTEGER CHECK (qty > 0),
                total REAL,
                CONSTRAINT total_nonneg CHECK (total >= 0 AND (total < 1e9)),
                "state" TEXT CHECK("state" IN ('new', 'paid'))
            )
        """.trimIndent()
        val checks = SqliteChecks.parse(sql)
        assertEquals(listOf("check_1", "total_nonneg", "check_2"), checks.map { it.name })
        assertEquals("qty > 0", checks[0].expression)
        assertEquals("total >= 0 AND (total < 1e9)", checks[1].expression)
        assertEquals("\"state\" IN ('new', 'paid')", checks[2].expression)
    }

    @Test
    fun `the word check inside a string, a name or a comment is not a constraint`() {
        val sql = """
            CREATE TABLE t (
                a TEXT DEFAULT 'check (x)', -- check (y)
                "check" INTEGER, /* check (z) */
                b INTEGER
            )
        """.trimIndent()
        assertTrue(SqliteChecks.parse(sql).isEmpty())
    }

    @Test
    fun `a quoted constraint name is unquoted`() {
        val checks = SqliteChecks.parse("CREATE TABLE t (a INT, CONSTRAINT \"Positive A\" CHECK (a > 0))")
        assertEquals("Positive A", checks.single().name)
    }

    @Test
    fun `text that is not a create table gives nothing`() {
        assertTrue(SqliteChecks.parse("").isEmpty())
        assertTrue(SqliteChecks.parse("CREATE VIEW v AS SELECT 1").isEmpty())
    }
}
