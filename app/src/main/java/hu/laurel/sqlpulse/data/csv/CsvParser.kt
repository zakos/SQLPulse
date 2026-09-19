package hu.laurel.sqlpulse.data.csv

import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader

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
 *
 * The parser works off a [Reader] in fixed-size buffers and hands each record to the caller as it
 * is completed, so a file is never held in memory in one piece. The string-taking [parse] is the
 * same machine over a [StringReader]: one implementation, so the file and the test agree.
 */
object CsvParser {

    /** The separators worth guessing between; the caller can also name one. */
    private val CANDIDATES = listOf(',', ';', '\t')

    /** Characters per read. Small enough to be nothing on a phone, large enough to not thrash. */
    private const val BUFFER = 8 * 1024

    /** How far into a stream the separator may be guessed from; a header is far shorter. */
    private const val LOOKAHEAD = 64 * 1024

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

    /**
     * The same guess taken from the head of a stream, which is left where it was found.
     *
     * A [BufferedReader] is asked for because the guess has to read ahead and then give those
     * characters back; the mark is the size of the read, so the reader can always honour it.
     */
    fun guessSeparator(reader: BufferedReader, lookahead: Int = LOOKAHEAD): Char {
        reader.mark(lookahead)
        val head = CharArray(lookahead)
        var filled = 0
        while (filled < lookahead) {
            val read = reader.read(head, filled, lookahead - filled)
            if (read < 0) break
            filled += read
        }
        reader.reset()
        return guessSeparator(String(head, 0, filled))
    }

    fun parse(text: String, separator: Char = guessSeparator(text)): CsvTable {
        val rows = mutableListOf<List<String?>>()
        var header: List<String> = emptyList()
        val malformed = stream(
            reader = StringReader(text),
            separator = separator,
            onHeader = { header = it },
            onRow = { rows += it },
        )
        return CsvTable(header, rows, malformed)
    }

    /**
     * Reads the stream record by record.
     *
     * [onHeader] is called once, with the first record, before any row; [onRow] then sees every
     * record that has as many fields as the header, in file order. The return value counts the
     * records that had a different number of fields — those are not guessed at, and not passed on.
     *
     * Nothing beyond the current buffer and the current record is held here, so what the import
     * costs in memory is what the caller chooses to keep.
     */
    fun stream(
        reader: Reader,
        separator: Char,
        onHeader: (List<String>) -> Unit = {},
        onRow: (List<String?>) -> Unit,
    ): Int {
        var header: List<String>? = null
        var malformed = 0
        val assembler = Records(separator) { record ->
            val known = header
            if (known == null) {
                header = record.map { it.orEmpty().trim() }.also(onHeader)
            } else if (record.size != known.size) {
                malformed++
            } else {
                onRow(record)
            }
        }

        val buffer = CharArray(BUFFER)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            assembler.feed(buffer, read)
        }
        assembler.finish()
        // A file with nothing in it has no header either, and the caller is told so by never
        // having been handed one.
        if (header == null) onHeader(emptyList())
        return malformed
    }

    /**
     * The record machine, one character at a time.
     *
     * Character by character rather than by line because a record is not a line: a quoted field
     * may hold newlines, and a buffer boundary may fall anywhere — including between the two
     * quotes of an escaped one, which is why a quote seen inside a quoted field is remembered
     * ([pendingQuote]) instead of being decided on with a look at the next character.
     */
    private class Records(
        private val separator: Char,
        private val onRecord: (List<String?>) -> Unit,
    ) {
        private val field = StringBuilder()
        private var record = mutableListOf<String?>()
        private var quoted = false
        private var wasQuoted = false
        private var pendingQuote = false

        fun feed(buffer: CharArray, length: Int) {
            for (index in 0 until length) accept(buffer[index])
        }

        fun finish() {
            // A quote at the very end of the file closes its field; there is nothing to double.
            if (pendingQuote) {
                pendingQuote = false
                quoted = false
            }
            if (field.isNotEmpty() || record.isNotEmpty() || wasQuoted) endRecord()
        }

        private fun accept(c: Char) {
            if (pendingQuote) {
                pendingQuote = false
                if (c == '"') {
                    field.append('"')
                    return
                }
                // Not doubled, so that quote ended the quoted field; this character is ordinary.
                quoted = false
            }
            when {
                quoted && c == '"' -> pendingQuote = true
                c == '"' -> {
                    quoted = true
                    wasQuoted = true
                }

                quoted -> field.append(c)
                c == separator -> endField()
                c == '\n' -> endRecord()
                c == '\r' -> Unit // A CRLF ends the record on the \n.
                else -> field.append(c)
            }
        }

        private fun endField() {
            // An unquoted empty field is NULL; "" is an empty string.
            record += if (field.isEmpty() && !wasQuoted) null else field.toString()
            field.setLength(0)
            wasQuoted = false
        }

        private fun endRecord() {
            endField()
            // A line of nothing at all is not a record; a line of empty fields is.
            if (record.size > 1 || record.firstOrNull() != null) onRecord(record)
            record = mutableListOf()
        }
    }

    private fun splitLine(line: String, separator: Char): List<String?> {
        var first: List<String?>? = null
        Records(separator) { record -> if (first == null) first = record }
            .apply { feed(line.toCharArray(), line.length) }
            .finish()
        return first ?: emptyList()
    }
}
