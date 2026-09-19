package hu.laurel.sqlpulse.data.export

import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A result written as an Excel workbook, by hand.
 *
 * CSV is what this app has always offered, and CSV is where a phone export usually dies: the
 * recipient double-taps it, Excel guesses the separator from its locale, and a Hungarian machine
 * reads a comma-separated file as one column. An xlsx says what each value is, so nothing is
 * guessed at the other end.
 *
 * Written by hand rather than with a library because the whole format needed here is five small
 * XML documents in a zip. Apache POI is several megabytes of jar for a phone and brings its own
 * XML stack; this is two hundred lines with no dependency at all.
 *
 * There is no shared string table: every text cell carries its own value inline. That is larger on
 * disk and simpler to be sure of — a shared table that gets an index wrong produces a file which
 * opens and shows the wrong words, which is the worst kind of wrong.
 */
object Xlsx {

    /** The bytes of a one-sheet workbook holding [table]. */
    fun workbook(table: ResultTable, sheetName: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.write("[Content_Types].xml", CONTENT_TYPES)
            zip.write("_rels/.rels", ROOT_RELS)
            zip.write("xl/workbook.xml", workbookXml(sheetName))
            zip.write("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            zip.write("xl/styles.xml", STYLES)
            zip.write("xl/worksheets/sheet1.xml", sheetXml(table))
        }
        return bytes.toByteArray()
    }

    /**
     * Excel's rules for a sheet name, applied rather than hoped for.
     *
     * A name with a slash or a bracket in it, or one over 31 characters, makes the whole file
     * unopenable — and a table called `order/detail` is not an unusual name.
     */
    fun sheetName(raw: String): String {
        val cleaned = raw.filterNot { it in FORBIDDEN_IN_NAME || it.isISOControl() }
            .trim()
            .take(MAX_SHEET_NAME)
        return cleaned.ifBlank { "Sheet1" }
    }

    /** A1, B1 ... Z1, AA1: the column letters Excel numbers its columns with. */
    fun columnName(index: Int): String {
        var remaining = index
        val name = StringBuilder()
        do {
            name.insert(0, ('A' + remaining % 26))
            remaining = remaining / 26 - 1
        } while (remaining >= 0)
        return name.toString()
    }

    private fun ZipOutputStream.write(path: String, content: String) {
        // A fixed timestamp, so the same result always produces the same bytes. A file that
        // differs from itself cannot be compared, and a test that hashes it would be a clock test.
        putNextEntry(ZipEntry(path).apply { time = FIXED_TIME })
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun workbookXml(sheetName: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${escape(sheetName)}" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    private fun sheetXml(table: ResultTable): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append(columnsXml(table))
        // The header stays put while the rows scroll, which is the first thing anybody does to a
        // spreadsheet of query output by hand.
        append("""<sheetViews><sheetView workbookViewId="0" tabSelected="1">""")
        append("""<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""")
        append("""</sheetView></sheetViews>""")
        append("<sheetData>")

        append("""<row r="1">""")
        table.columns.forEachIndexed { index, column ->
            append(textCell(columnName(index) + "1", column.label, style = HEADER_STYLE))
        }
        append("</row>")

        table.rows.forEachIndexed { rowIndex, row ->
            val number = rowIndex + 2
            append("""<row r="$number">""")
            row.forEachIndexed { index, cell ->
                val reference = columnName(index) + number
                append(cellXml(reference, cell))
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    /**
     * Column widths from the content, in Excel's unit of "characters of the default font".
     *
     * Not cosmetic: a column narrower than its number shows `#####`, which reads as an error in
     * the data rather than as a column that needs dragging.
     */
    private fun columnsXml(table: ResultTable): String {
        if (table.columns.isEmpty()) return ""
        return buildString {
            append("<cols>")
            table.columns.forEachIndexed { index, column ->
                val widest = table.rows.asSequence()
                    .mapNotNull { it.getOrNull(index) }
                    .maxOfOrNull { plain(it).length } ?: 0
                val width = (maxOf(column.label.length, widest) + WIDTH_PADDING)
                    .coerceIn(MIN_WIDTH, MAX_WIDTH)
                append("""<col min="${index + 1}" max="${index + 1}" width="$width" customWidth="1"/>""")
            }
            append("</cols>")
        }
    }

    /**
     * One cell, typed.
     *
     * A number goes in as a number, so the spreadsheet can add it up; everything else goes in as
     * text, including dates. A date written as text is a date the recipient may have to convert;
     * a date written as Excel's serial number and misread by one day is a wrong answer nobody
     * notices, and MySQL's zero dates and out-of-range timestamps have no serial number at all.
     */
    private fun cellXml(reference: String, cell: CellValue): String = when (cell) {
        // An empty cell, not a zero and not the word NULL: both of those are values, and a
        // spreadsheet that averages a column would take them for one.
        CellValue.Null -> ""

        is CellValue.Number -> when {
            // A value the driver called a number but Excel would not (an out-of-range decimal,
            // say) stays text rather than becoming a cell Excel refuses to open the file over.
            isNumeric(cell.value) -> """<c r="$reference"><v>${cell.value}</v></c>"""
            else -> textCell(reference, cell.value)
        }

        is CellValue.Bool -> """<c r="$reference" t="b"><v>${if (cell.value) 1 else 0}</v></c>"""
        is CellValue.Text -> textCell(reference, cell.value)
        is CellValue.Date -> textCell(reference, cell.value)
        // The grid never holds a BLOB's contents, so neither can the file: its size is all there
        // is to say, exactly as the grid says it.
        is CellValue.Blob -> textCell(reference, formatBytes(cell.sizeBytes))
    }

    private fun textCell(reference: String, text: String, style: Int? = null): String {
        val styleAttribute = style?.let { """ s="$it"""" }.orEmpty()
        return """<c r="$reference"$styleAttribute t="inlineStr"><is><t xml:space="preserve">""" +
            escape(text) + "</t></is></c>"
    }

    private fun plain(cell: CellValue): String = when (cell) {
        CellValue.Null -> ""
        is CellValue.Text -> cell.value
        is CellValue.Number -> cell.value
        is CellValue.Date -> cell.value
        is CellValue.Bool -> if (cell.value) "1" else "0"
        is CellValue.Blob -> formatBytes(cell.sizeBytes)
    }

    private fun formatBytes(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${size / (1024 * 1024)} MB"
    }

    private fun isNumeric(text: String): Boolean =
        text.isNotBlank() && text.toDoubleOrNull()?.isFinite() == true

    /**
     * XML escaping, plus the control characters the format simply cannot carry.
     *
     * A 0x00 from a binary column would make the file unopenable rather than merely odd, so it is
     * dropped here instead of being discovered by the recipient.
     */
    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { character ->
            when {
                character == '&' -> append("&amp;")
                character == '<' -> append("&lt;")
                character == '>' -> append("&gt;")
                character == '"' -> append("&quot;")
                character == '\'' -> append("&apos;")
                character == '\n' || character == '\t' || character == '\r' -> append(character)
                character.isISOControl() -> Unit
                else -> append(character)
            }
        }
    }

    private const val HEADER_STYLE = 1
    private const val MAX_SHEET_NAME = 31
    private const val WIDTH_PADDING = 2
    private const val MIN_WIDTH = 8
    private const val MAX_WIDTH = 60
    private val FORBIDDEN_IN_NAME = charArrayOf('[', ']', ':', '*', '?', '/', '\\')

    /** 1 January 1980, the earliest a zip entry can claim. */
    private const val FIXED_TIME = 315_532_800_000L

    private val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    /** Two fonts and two formats: the plain one, and the bold one the header row uses. */
    private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="1"><fill><patternFill patternType="none"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
</styleSheet>"""
}
