package hu.laurel.sqlpulse.data.sql

/**
 * Shows what is inside a BLOB without pretending to open it.
 *
 * A BLOB can be anything: a JPEG, a PDF, a gzip stream, or text somebody stored in a binary
 * column. The app cannot display most of those, and guessing wrong is worse than saying nothing —
 * so it either shows the text, when the bytes really are text, or a hex dump, which is readable
 * for any content and lies about none of it.
 */
object BlobPreview {

    /** Bytes per line of the dump. Sixteen is what every hex viewer uses, and it fits a phone. */
    private const val LINE = 16

    /**
     * The bytes as text, or null when they are not valid UTF-8.
     *
     * A control character other than tab, newline or carriage return means binary: text files do
     * not contain them, and a NUL in the middle is the clearest sign that this is not text.
     */
    fun asText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        val text = bytes.toString(Charsets.UTF_8)
        // The replacement character appears where the decoder gave up, unless it was really stored.
        if (text.contains('�') && !bytes.contains(0xEF.toByte())) return null
        if (text.any { it.isISOControl() && it != '\t' && it != '\n' && it != '\r' }) return null
        return text
    }

    /**
     * A classic hex dump: offset, sixteen bytes, then those bytes as characters.
     *
     * Anything unprintable shows as a dot in the right-hand column — that column is for
     * recognising a header or a fragment of text, not for reading the content.
     */
    fun hexDump(bytes: ByteArray): String = bytes.asIterable()
        .chunked(LINE)
        .withIndex()
        .joinToString("\n") { (lineIndex, chunk) ->
            val offset = "%08x".format(lineIndex * LINE)
            val hex = chunk.joinToString(" ") { "%02x".format(it) }.padEnd(LINE * 3 - 1)
            val ascii = chunk.joinToString("") { byte ->
                val c = byte.toInt().toChar()
                if (c.code in 0x20..0x7E) c.toString() else "."
            }
            "$offset  $hex  $ascii"
        }

    /**
     * What is worth showing for these bytes: the text if they are text, otherwise the hex dump.
     *
     * @param truncated true when more bytes were left on the server than were read.
     */
    fun of(bytes: ByteArray, truncated: Boolean): Preview {
        val text = asText(bytes)
        return Preview(
            content = text ?: hexDump(bytes),
            isText = text != null,
            truncated = truncated,
        )
    }

    data class Preview(val content: String, val isText: Boolean, val truncated: Boolean)
}
