package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Test

class SqlParameterBindingTest {

    @Test
    fun `named parameters become question marks in order`() {
        val bound = SqlGuards.bindParameters("SELECT * FROM t WHERE a = :from AND b = :to")

        assertEquals("SELECT * FROM t WHERE a = ? AND b = ?", bound.sql)
        assertEquals(listOf("from", "to"), bound.parameterOrder)
    }

    @Test
    fun `a repeated parameter is bound once per occurrence`() {
        val bound = SqlGuards.bindParameters("SELECT * FROM t WHERE a = :id OR b = :id")

        assertEquals("SELECT * FROM t WHERE a = ? OR b = ?", bound.sql)
        assertEquals(listOf("id", "id"), bound.parameterOrder)
    }

    @Test
    fun `a colon inside a string literal is left alone`() {
        val bound = SqlGuards.bindParameters("SELECT * FROM t WHERE at = '12:30'")

        assertEquals("SELECT * FROM t WHERE at = '12:30'", bound.sql)
        assertEquals(emptyList<String>(), bound.parameterOrder)
    }

    @Test
    fun `a double colon cast is not a parameter`() {
        val bound = SqlGuards.bindParameters("SELECT value::text FROM t")

        assertEquals("SELECT value::text FROM t", bound.sql)
        assertEquals(emptyList<String>(), bound.parameterOrder)
    }

    @Test
    fun `a parameter inside a comment is left alone`() {
        val bound = SqlGuards.bindParameters("SELECT 1 -- :not_a_param\nFROM t WHERE a = :real")

        assertEquals("SELECT 1 -- :not_a_param\nFROM t WHERE a = ?", bound.sql)
        assertEquals(listOf("real"), bound.parameterOrder)
    }

    @Test
    fun `a parameter inside a quoted identifier is left alone`() {
        val bound = SqlGuards.bindParameters("SELECT `a:b` FROM t")

        assertEquals("SELECT `a:b` FROM t", bound.sql)
        assertEquals(emptyList<String>(), bound.parameterOrder)
    }

    @Test
    fun `sql without parameters is returned unchanged`() {
        val sql = "SELECT * FROM t WHERE a = 1"

        assertEquals(sql, SqlGuards.bindParameters(sql).sql)
    }
}
