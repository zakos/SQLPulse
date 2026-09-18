package hu.laurel.sqlpulse.data.csv

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvParserTest {

    @Test
    fun `the first line is the header`() {
        val table = CsvParser.parse("id,name\n1,Anna\n2,Béla\n")
        assertEquals(listOf("id", "name"), table.header)
        assertEquals(listOf(listOf("1", "Anna"), listOf("2", "Béla")), table.rows)
    }

    @Test
    fun `an empty field is NULL and a pair of quotes is an empty string`() {
        val table = CsvParser.parse("id,note\n1,\n2,\"\"\n")
        assertEquals(listOf<String?>("1", null), table.rows[0])
        assertEquals(listOf<String?>("2", ""), table.rows[1])
    }

    @Test
    fun `a quoted field may contain the separator, a newline and quotes`() {
        val table = CsvParser.parse("id,note\n1,\"a,b\"\n2,\"line1\nline2\"\n3,\"say \"\"hi\"\"\"\n")
        assertEquals(listOf<String?>("1", "a,b"), table.rows[0])
        assertEquals(listOf<String?>("2", "line1\nline2"), table.rows[1])
        assertEquals(listOf<String?>("3", "say \"hi\""), table.rows[2])
    }

    @Test
    fun `CRLF line endings are not part of the value`() {
        val table = CsvParser.parse("id,name\r\n1,Anna\r\n")
        assertEquals(listOf<String?>("1", "Anna"), table.rows[0])
    }

    @Test
    fun `the separator is guessed from the header`() {
        assertEquals(';', CsvParser.guessSeparator("id;name;city\n1;Anna;Győr\n"))
        assertEquals('\t', CsvParser.guessSeparator("id\tname\n1\tAnna\n"))
        // A comma inside a value must not outvote the real separator.
        assertEquals(';', CsvParser.guessSeparator("id;name\n1;\"Anna, Béla\"\n"))
    }

    @Test
    fun `a row with the wrong number of fields is counted, not guessed at`() {
        val table = CsvParser.parse("id,name\n1,Anna\n2\n3,Cecil,extra\n")
        assertEquals(2, table.malformedRows)
        assertEquals(listOf(listOf<String?>("1", "Anna")), table.rows)
    }

    @Test
    fun `a trailing newline does not make an empty row`() {
        assertEquals(1, CsvParser.parse("id\n1\n").rows.size)
        assertEquals(1, CsvParser.parse("id\n1").rows.size)
    }

    @Test
    fun `empty text is an empty table`() {
        val table = CsvParser.parse("")
        assertEquals(emptyList<String>(), table.header)
        assertEquals(emptyList<List<String?>>(), table.rows)
    }
}
