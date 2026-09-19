package hu.laurel.sqlpulse.data.csv

import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader
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
    /**
     * A reader that hands out one character per call, so every possible split of the input is
     * exercised at once: a buffer boundary inside a value, inside a quoted field, and — the one
     * that used to need a look at the next character — between the two quotes of an escaped one.
     */
    private fun meanReader(text: String): Reader = object : Reader() {
        private val source = StringReader(text)
        override fun read(cbuf: CharArray, off: Int, len: Int): Int =
            source.read(cbuf, off, if (len > 0) 1 else 0)

        override fun close() = source.close()
    }

    private fun streamRows(reader: Reader, separator: Char = ','): Pair<List<String>, List<List<String?>>> {
        var header: List<String> = emptyList()
        val rows = mutableListOf<List<String?>>()
        CsvParser.stream(reader, separator, onHeader = { header = it }, onRow = { rows += it })
        return header to rows
    }

    @Test
    fun `a record split across buffer boundaries is still one record`() {
        // Longer than the parser's own buffer, so the split is real rather than arranged.
        val padding = "x".repeat(20_000)
        val text = "id,note\n1,$padding\n2,Anna\n"
        val (header, rows) = streamRows(StringReader(text))
        assertEquals(listOf("id", "note"), header)
        assertEquals(listOf<String?>("1", padding), rows[0])
        assertEquals(listOf<String?>("2", "Anna"), rows[1])
    }

    @Test
    fun `quoting survives a buffer boundary falling anywhere, including inside an escaped quote`() {
        val text = "id,note\n1,\"say \"\"hi\"\", and\nthen go\"\n2,\"\"\n"
        val (_, rows) = streamRows(meanReader(text))
        assertEquals(listOf<String?>("1", "say \"hi\", and\nthen go"), rows[0])
        assertEquals(listOf<String?>("2", ""), rows[1])
    }

    @Test
    fun `a quoted field containing newlines is one field, not several records`() {
        val text = "id,note\n1,\"line1\nline2\nline3\"\n2,plain\n"
        val (_, rows) = streamRows(StringReader(text))
        assertEquals(2, rows.size)
        assertEquals(listOf<String?>("1", "line1\nline2\nline3"), rows[0])
    }

    @Test
    fun `a CRLF file streams without carriage returns and without empty rows`() {
        val text = "id,name\r\n1,Anna\r\n2,Béla\r\n"
        val (header, rows) = streamRows(meanReader(text))
        assertEquals(listOf("id", "name"), header)
        assertEquals(listOf(listOf<String?>("1", "Anna"), listOf<String?>("2", "Béla")), rows)
    }

    @Test
    fun `the separator is guessed from a stream without consuming it`() {
        val reader = BufferedReader(StringReader("id;name\n1;Anna\n"))
        assertEquals(';', CsvParser.guessSeparator(reader))
        // The guess read ahead and gave the characters back, so the header is still there.
        val (header, rows) = streamRows(reader, ';')
        assertEquals(listOf("id", "name"), header)
        assertEquals(listOf(listOf<String?>("1", "Anna")), rows)
    }

    @Test
    fun `streaming and parsing a string agree, malformed rows included`() {
        val text = "id,name\n1,Anna\n2\n3,Cecil\n"
        val table = CsvParser.parse(text)
        val (header, rows) = streamRows(meanReader(text))
        assertEquals(table.header, header)
        assertEquals(table.rows, rows)
        assertEquals(1, table.malformedRows)
    }
}
