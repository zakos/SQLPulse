package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Test

class DigestPlaceholdersTest {

    @Test
    fun `each question mark becomes a numbered named parameter`() {
        val result = DigestPlaceholders.toNamed("SELECT * FROM `t` WHERE `a` = ? AND `b` > ? LIMIT ?")
        assertEquals("SELECT * FROM `t` WHERE `a` = :p1 AND `b` > :p2 LIMIT :p3", result.sql)
        assertEquals(3, result.count)
    }

    @Test
    fun `the converted text is understood by the parameter parser`() {
        val result = DigestPlaceholders.toNamed("UPDATE t SET a = ? WHERE id = ?")
        assertEquals(listOf("p1", "p2"), SqlGuards.parameters(result.sql))
    }

    @Test
    fun `question marks inside literals are left alone`() {
        val sql = "SELECT 'what?', \"why?\", `odd?name`, ? FROM t"
        val result = DigestPlaceholders.toNamed(sql)
        assertEquals("SELECT 'what?', \"why?\", `odd?name`, :p1 FROM t", result.sql)
        assertEquals(1, result.count)
    }

    @Test
    fun `an escaped quote does not end the literal early`() {
        val result = DigestPlaceholders.toNamed("SELECT 'it\\'s ?', ? FROM t")
        assertEquals("SELECT 'it\\'s ?', :p1 FROM t", result.sql)
    }

    @Test
    fun `question marks inside comments are left alone`() {
        val sql = "SELECT ? -- really?\nFROM t /* maybe? */ WHERE a = ? # why?\n"
        val result = DigestPlaceholders.toNamed(sql)
        assertEquals("SELECT :p1 -- really?\nFROM t /* maybe? */ WHERE a = :p2 # why?\n", result.sql)
        assertEquals(2, result.count)
    }

    @Test
    fun `a name the text already uses is not reused`() {
        val result = DigestPlaceholders.toNamed("SELECT :p1, ?, ?")
        assertEquals("SELECT :p1, :p2, :p3", result.sql)
    }

    @Test
    fun `text without placeholders comes back unchanged`() {
        val sql = "SELECT 1 FROM dual"
        val result = DigestPlaceholders.toNamed(sql)
        assertEquals(sql, result.sql)
        assertEquals(0, result.count)
    }

    @Test
    fun `an unterminated literal does not hang or lose text`() {
        val result = DigestPlaceholders.toNamed("SELECT ?, 'open ?")
        assertEquals("SELECT :p1, 'open ?", result.sql)
    }
}
