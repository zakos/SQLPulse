package hu.laurel.sqlpulse.data.export

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultSerializerTest {

    private fun table(vararg rows: List<CellValue>) = ResultTable(
        columns = listOf(
            ColumnMeta("id", CellType.NUMBER, "INT", "orders"),
            ColumnMeta("note", CellType.TEXT, "VARCHAR", "orders"),
        ),
        rows = rows.toList(),
    )

    @Test
    fun `csv starts with the header row`() {
        val csv = ResultSerializer.toCsv(table(listOf(CellValue.Number("1"), CellValue.Text("ok"))))

        assertEquals("id,note\r\n1,ok\r\n", csv)
    }

    @Test
    fun `csv quotes fields containing a comma a quote or a newline`() {
        val csv = ResultSerializer.toCsv(
            table(
                listOf(CellValue.Number("1"), CellValue.Text("a,b")),
                listOf(CellValue.Number("2"), CellValue.Text("say \"hi\"")),
                listOf(CellValue.Number("3"), CellValue.Text("line\nbreak")),
            ),
        )

        assertTrue(csv.contains("\"a,b\""))
        assertTrue(csv.contains("\"say \"\"hi\"\"\""))
        assertTrue(csv.contains("\"line\nbreak\""))
    }

    @Test
    fun `a csv null is an empty unquoted field, unlike an empty string`() {
        val csv = ResultSerializer.toCsv(
            table(
                listOf(CellValue.Number("1"), CellValue.Null),
                listOf(CellValue.Number("2"), CellValue.Text("")),
            ),
        )

        assertEquals("id,note\r\n1,\r\n2,\r\n", csv)
    }

    @Test
    fun `json renders one object per row`() {
        val json = ResultSerializer.toJson(
            table(listOf(CellValue.Number("1"), CellValue.Text("ok"))),
        )

        assertEquals("""[{"id":1,"note":"ok"}]""", json)
    }

    @Test
    fun `json writes null for a null cell`() {
        val json = ResultSerializer.toJson(table(listOf(CellValue.Number("1"), CellValue.Null)))

        assertEquals("""[{"id":1,"note":null}]""", json)
    }

    @Test
    fun `json escapes quotes backslashes and control characters`() {
        val json = ResultSerializer.toJson(
            table(listOf(CellValue.Number("1"), CellValue.Text("a\"b\\c\nd"))),
        )

        assertEquals("""[{"id":1,"note":"a\"b\\c\nd"}]""", json)
    }

    @Test
    fun `a number that is not really numeric is quoted rather than breaking the json`() {
        val json = ResultSerializer.toJson(
            table(listOf(CellValue.Number("1e999"), CellValue.Text("x"))),
        )

        assertEquals("""[{"id":"1e999","note":"x"}]""", json)
    }

    @Test
    fun `a blob exports its size, not its contents`() {
        val csv = ResultSerializer.toCsv(table(listOf(CellValue.Number("1"), CellValue.Blob(2048))))

        assertTrue(csv.contains("[BLOB 2048 B]"))
    }

    @Test
    fun `an empty result still produces a header and a valid json array`() {
        assertEquals("id,note\r\n", ResultSerializer.toCsv(table()))
        assertEquals("[]", ResultSerializer.toJson(table()))
    }

    @Test
    fun `TSV escapes what would otherwise become another column or row`() {
        val single = ResultTable(
            columns = listOf(ColumnMeta("note", CellType.TEXT, "VARCHAR", "orders")),
            rows = listOf(
                listOf(CellValue.Text("a\tb")),
                listOf(CellValue.Text("line1\nline2")),
                listOf(CellValue.Null),
            ),
        )
        assertEquals(
            "note\na\\tb\nline1\\nline2\n\\N\n",
            ResultSerializer.toTsv(single),
        )
    }

    @Test
    fun `INSERT statements quote strings and leave numbers alone`() {
        val rows = table(listOf(CellValue.Number("7"), CellValue.Text("O'Brien")))
        assertEquals(
            "INSERT INTO `orders` (`id`, `note`) VALUES (7, 'O\\'Brien');",
            ResultSerializer.toSqlInserts(rows, "orders"),
        )
    }

    @Test
    fun `a NULL stays NULL and a BLOB does not become its size`() {
        val rows = table(listOf(CellValue.Null, CellValue.Blob(2048)))
        // Inserting "[BLOB 2048 B]" would put a lie into the target table.
        assertEquals(
            "INSERT INTO `t` (`id`, `note`) VALUES (NULL, NULL);",
            ResultSerializer.toSqlInserts(rows, "t"),
        )
    }

    @Test
    fun `without a table name the driver's own answer is used`() {
        val rows = table(listOf(CellValue.Number("1"), CellValue.Text("x")))
        assertTrue(ResultSerializer.toSqlInserts(rows, null).startsWith("INSERT INTO `orders`"))
    }
}
