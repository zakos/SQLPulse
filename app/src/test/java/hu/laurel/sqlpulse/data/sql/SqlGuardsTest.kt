package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlGuardsTest {

    @Test
    fun `reads writes and everything else are told apart`() {
        assertEquals(StatementKind.READ, SqlGuards.classify("SELECT 1"))
        assertEquals(StatementKind.READ, SqlGuards.classify("  show tables "))
        assertEquals(StatementKind.READ, SqlGuards.classify("WITH x AS (SELECT 1) SELECT * FROM x"))
        assertEquals(StatementKind.WRITE, SqlGuards.classify("UPDATE t SET a = 1"))
        assertEquals(StatementKind.WRITE, SqlGuards.classify("delete from t where id = 3"))
        assertEquals(StatementKind.OTHER, SqlGuards.classify("DROP TABLE t"))
        assertEquals(StatementKind.OTHER, SqlGuards.classify(""))
    }

    @Test
    fun `a leading comment does not hide the statement kind`() {
        assertEquals(StatementKind.WRITE, SqlGuards.classify("-- harmless\nDELETE FROM t"))
        assertEquals(StatementKind.WRITE, SqlGuards.classify("/* nothing to see */ UPDATE t SET a = 1"))
    }

    @Test
    fun `a select without a limit gets one`() {
        val result = SqlGuards.applyDefaultLimit("SELECT * FROM orders")

        assertTrue(result.limitAdded)
        assertEquals("SELECT * FROM orders LIMIT 500", result.sql)
    }

    @Test
    fun `an explicit limit always wins`() {
        val result = SqlGuards.applyDefaultLimit("SELECT * FROM orders LIMIT 10")

        assertFalse(result.limitAdded)
        assertEquals("SELECT * FROM orders LIMIT 10", result.sql)
    }

    @Test
    fun `a column named limit is not mistaken for a limit clause`() {
        val result = SqlGuards.applyDefaultLimit("SELECT `limit` FROM accounts")

        assertTrue(result.limitAdded)
    }

    @Test
    fun `the word limit inside a string literal does not count`() {
        val result = SqlGuards.applyDefaultLimit("SELECT * FROM logs WHERE msg = 'limit 10'")

        assertTrue(result.limitAdded)
        assertEquals("SELECT * FROM logs WHERE msg = 'limit 10' LIMIT 500", result.sql)
    }

    @Test
    fun `writes are never given a limit`() {
        val result = SqlGuards.applyDefaultLimit("UPDATE t SET a = 1 WHERE id = 2")

        assertFalse(result.limitAdded)
    }

    @Test
    fun `show statements are left alone`() {
        assertFalse(SqlGuards.applyDefaultLimit("SHOW TABLES").limitAdded)
    }

    @Test
    fun `a trailing semicolon is dropped before the limit is appended`() {
        assertEquals("SELECT 1 LIMIT 500", SqlGuards.applyDefaultLimit("SELECT 1;").sql)
    }

    @Test
    fun `named parameters are collected in order and deduplicated`() {
        val parameters = SqlGuards.parameters(
            "SELECT * FROM t WHERE a = :from AND b = :to AND c = :from",
        )

        assertEquals(listOf("from", "to"), parameters)
    }

    @Test
    fun `a parameter inside a string literal is not a parameter`() {
        assertEquals(emptyList<String>(), SqlGuards.parameters("SELECT ':nope' FROM t"))
    }

    @Test
    fun `stripping removes comments and literal contents`() {
        val stripped = SqlGuards.strip("SELECT 'a--b' /* x */ FROM t -- tail")

        assertFalse(stripped.contains("a--b"))
        assertFalse(stripped.contains("tail"))
        assertTrue(stripped.contains("FROM t"))
    }

    @Test
    fun `an escaped quote does not end the literal early`() {
        val stripped = SqlGuards.strip("SELECT 'it\\'s fine' FROM t")

        assertTrue(stripped.contains("FROM t"))
        assertFalse(stripped.contains("fine"))
    }
}
