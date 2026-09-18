package hu.laurel.sqlpulse.data.csv

/** What a file turned out to contain. [rows] excludes the header. */
data class CsvTable(
    val header: List<String>,
    val rows: List<List<String?>>,
    /** Rows that had a different number of fields than the header. */
    val malformedRows: Int,
)

/**
 * Reads separated text: comma, semicolon or tab.
 *
 * RFC 4180 for quoting — a field may be quoted, a quote inside it is doubled, and a quoted field
 * may contain the separator and newlines. An unquoted empty field is NULL and `""` is an empty
 * string, which is the distinction MySQL's own export makes and the one that matters when the
 * column is nullable.
 */
object CsvParser {

    /** The separators worth guessing between; the caller can also name one. */
    private val CANDIDATES = listOf(',', ';', '\t')

    /**
     * The separator that splits the first line into the most fields.
     *
     * A header is what it is measured on: it is the one line guaranteed to have every column, and
     * a comma inside a data value would otherwise win the vote.
     */
    fun guessSeparator(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        return CANDIDATES.maxByOrNull { candidate ->
            splitLine(firstLine, candidate).size
        } ?: ','
    }

    fun parse(text: String, separator: Char = guessSeparator(text)): CsvTable {
        val records = records(text, separator)
        if (records.isEmpty()) return CsvTable(emptyList(), emptyList(), 0)

        val header = records.first().map { it.orEmpty().trim() }
        var malformed = 0
        val rows = records.drop(1).mapNotNull { record ->
            if (record.size != header.size) {
                malformed++
                null
            } else {
                record
            }
        }
        return CsvTable(header, rows, malformed)
    }

    /** Splits into records, keeping a newline that is inside a quoted field. */
    private fun records(text: String, separator: Char): List<List<String?>> {
        val records = mutableListOf<List<String?>>()
        val field = StringBuilder()
        var record = mutableListOf<String?>()
        var quoted = false
        var wasQuoted = false
        var index = 0

        fun endField() {
            // An unquoted empty field is NULL; "" is an empty string.
            record += if (field.isEmpty() && !wasQuoted) null else field.toString()
            field.setLength(0)
            wasQuoted = false
        }

        fun endRecord() {
            endField()
            // A line of nothing at all is not a record; a line of empty fields is.
            if (record.size > 1 || record.firstOrNull() != null) records += record
            record = mutableListOf()
        }

        while (index < text.length) {
            val c = text[index]
            when {
                quoted && c == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index += 2
                    continue
                }

                c == '"' -> {
                    quoted = !quoted
                    wasQuoted = true
                }

                quoted -> field.append(c)
                c == separator -> endField()
                c == '\n' -> endRecord()
                c == '\r' -> Unit // A CRLF ends the record on the \n.
                else -> field.append(c)
            }
            index++
        }
        if (field.isNotEmpty() || record.isNotEmpty() || wasQuoted) endRecord()
        return records
    }

    private fun splitLine(line: String, separator: Char): List<String?> =
        records(line, separator).firstOrNull() ?: emptyList()
}
