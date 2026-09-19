package hu.laurel.sqlpulse.data.export

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxTest {

    private fun column(label: String, type: CellType) = ColumnMeta(label, type, label, "t")

    private val table = ResultTable(
        columns = listOf(
            column("id", CellType.NUMBER),
            column("name", CellType.TEXT),
            column("created", CellType.DATE),
            column("ok", CellType.BOOLEAN),
            column("photo", CellType.BLOB),
        ),
        rows = listOf(
            listOf(
                CellValue.Number("42"),
                CellValue.Text("Kovács & Társa <Kft>"),
                CellValue.Date("2026-01-03 10:00:00"),
                CellValue.Bool(true),
                CellValue.Blob(2048),
            ),
            listOf(
                CellValue.Number("-3.5"),
                CellValue.Null,
                CellValue.Null,
                CellValue.Bool(false),
                CellValue.Null,
            ),
        ),
    )

    private fun entries(bytes: ByteArray): Map<String, String> {
        val found = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                found[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return found
    }

    private fun sheet(table: ResultTable = this.table): String =
        entries(Xlsx.workbook(table, "data")).getValue("xl/worksheets/sheet1.xml")

    @Test
    fun `the workbook holds the parts a reader needs to open it`() {
        val parts = entries(Xlsx.workbook(table, "data")).keys
        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/styles.xml",
                "xl/worksheets/sheet1.xml",
            ),
            parts,
        )
    }

    @Test
    fun `a number is a number, so the spreadsheet can add it up`() {
        // No t= attribute at all is xlsx for "this is a number".
        assertTrue(sheet().contains("""<c r="A2"><v>42</v></c>"""))
        assertTrue(sheet().contains("""<c r="A3"><v>-3.5</v></c>"""))
    }

    @Test
    fun `text is escaped, not pasted into the XML`() {
        val xml = sheet()
        assertTrue(xml.contains("Kovács &amp; Társa &lt;Kft&gt;"))
        assertFalse(xml.contains("<Kft>"))
    }

    @Test
    fun `NULL is an empty cell, not a zero and not the word`() {
        val xml = sheet()
        assertFalse(xml.contains("""r="B3""""))
        assertFalse(xml.contains("NULL"))
    }

    @Test
    fun `a boolean is a boolean and a BLOB is its size`() {
        assertTrue(sheet().contains("""<c r="D2" t="b"><v>1</v></c>"""))
        assertTrue(sheet().contains("""<c r="D3" t="b"><v>0</v></c>"""))
        assertTrue(sheet().contains("2 KB"))
    }

    @Test
    fun `a date stays text, because a wrong serial number is a wrong answer`() {
        assertTrue(sheet().contains("2026-01-03 10:00:00"))
    }

    @Test
    fun `a number the format cannot hold stays text rather than breaking the file`() {
        val odd = table.copy(
            rows = listOf(listOf(CellValue.Number("12,5"), CellValue.Null, CellValue.Null, CellValue.Bool(true), CellValue.Null)),
        )
        assertTrue(sheet(odd).contains("""<c r="A2" t="inlineStr"><is><t xml:space="preserve">12,5"""))
    }

    @Test
    fun `the header row is bold and frozen`() {
        val xml = sheet()
        assertTrue(xml.contains("""<c r="A1" s="1" t="inlineStr"><is><t xml:space="preserve">id"""))
        assertTrue(xml.contains("""ySplit="1""""))
    }

    @Test
    fun `a control character is dropped rather than making the file unopenable`() {
        val binary = table.copy(
            rows = listOf(listOf(CellValue.Null, CellValue.Text("a\u0000b"), CellValue.Null, CellValue.Bool(true), CellValue.Null)),
        )
        val xml = sheet(binary)
        assertTrue(xml.contains("ab"))
        assertFalse(xml.contains("\u0000"))
    }

    @Test
    fun `a sheet name Excel would refuse is made into one it accepts`() {
        assertEquals("orderdetail", Xlsx.sheetName("order/detail"))
        assertEquals("Sheet1", Xlsx.sheetName("   "))
        assertEquals(31, Xlsx.sheetName("x".repeat(40)).length)
        assertEquals("HIVASOK", Xlsx.sheetName("HIVASOK"))
    }

    @Test
    fun `columns are lettered the way Excel letters them`() {
        assertEquals("A", Xlsx.columnName(0))
        assertEquals("Z", Xlsx.columnName(25))
        assertEquals("AA", Xlsx.columnName(26))
        assertEquals("AB", Xlsx.columnName(27))
        assertEquals("BA", Xlsx.columnName(52))
    }

    @Test
    fun `the same result always produces the same bytes`() {
        assertTrue(
            Xlsx.workbook(table, "data").contentEquals(Xlsx.workbook(table, "data")),
        )
    }

    @Test
    fun `an empty result is still a workbook`() {
        val empty = ResultTable(columns = emptyList(), rows = emptyList())
        val xml = sheet(empty)
        assertTrue(xml.contains("<sheetData>"))
        assertTrue(xml.contains("""<row r="1">"""))
    }
}
