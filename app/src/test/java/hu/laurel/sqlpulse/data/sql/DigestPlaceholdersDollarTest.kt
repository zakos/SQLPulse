package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Test

class DigestPlaceholdersDollarTest {

    @Test
    fun `numbered dollar parameters become named ones and the same number stays the same name`() {
        val converted = DigestPlaceholders.toNamed("SELECT * FROM t WHERE a = \$1 AND b = \$2 OR c = \$1", dollarNumbered = true)
        assertEquals("SELECT * FROM t WHERE a = :p1 AND b = :p2 OR c = :p1", converted.sql)
        assertEquals(3, converted.count)
    }

    @Test
    fun `a question mark is an operator for PostgreSQL and is left alone`() {
        val converted = DigestPlaceholders.toNamed("SELECT data ? 'key' FROM t WHERE id = \$1", dollarNumbered = true)
        assertEquals("SELECT data ? 'key' FROM t WHERE id = :p1", converted.sql)
    }

    @Test
    fun `a dollar sign inside a string or without digits is not a parameter`() {
        val converted = DigestPlaceholders.toNamed("SELECT '\$1', \$\$x\$\$ FROM t", dollarNumbered = true)
        assertEquals("SELECT '\$1', \$\$x\$\$ FROM t", converted.sql)
        assertEquals(0, converted.count)
    }

    @Test
    fun `the default still reads question marks`() {
        assertEquals("SELECT :p1", DigestPlaceholders.toNamed("SELECT ?").sql)
    }
}
