package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Test

class QuotingTest {

    @Test
    fun `an identifier is backtick-quoted and a backtick inside it is doubled`() {
        assertEquals("`orders`", quoteIdentifier("orders"))
        assertEquals("`odd``name`", quoteIdentifier("odd`name"))
    }

    @Test
    fun `a string literal escapes the quote and the backslash`() {
        assertEquals("'app'", quoteStringLiteral("app"))
        assertEquals("'O\\'Brien'", quoteStringLiteral("O'Brien"))
        assertEquals("'back\\\\slash'", quoteStringLiteral("back\\slash"))
    }

    @Test
    fun `a value that tries to end the statement stays inside its quotes`() {
        // SHOW GRANTS takes no parameters, so this is the only thing standing between an account
        // name and the rest of the statement.
        assertEquals("'x\\'; DROP TABLE t; --'", quoteStringLiteral("x'; DROP TABLE t; --"))
    }
}
