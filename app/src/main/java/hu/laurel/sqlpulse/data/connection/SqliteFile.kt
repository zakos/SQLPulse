package hu.laurel.sqlpulse.data.connection

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * What a SQLite database file looks like from the outside: the 100-byte header every one starts
 * with. Pure JVM, so the copy-in can be tested without a phone.
 *
 * Checking the header before anything is kept is the whole defence against the commonest mistake —
 * picking a `.db` file that is not SQLite (a SQLCipher file, a Realm file, a zip renamed) — which
 * would otherwise be saved as a connection and fail later with "file is not a database" far from
 * the moment the user chose it.
 */
object SqliteFile {

    /** `SQLite format 3` followed by a NUL: the first 16 bytes of every database file. */
    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    private const val HEADER_BYTES = 100
    private const val BUFFER_BYTES = 64 * 1024

    /** What the first bytes of a file say. */
    sealed interface Header {
        /** A SQLite database. [walMode] is the file's own journal mode flag (bytes 18 and 19). */
        data class Valid(val walMode: Boolean, val pageSize: Int) : Header

        /** Fewer than sixteen bytes: an empty file, which SQLite would treat as a new database. */
        data object Empty : Header

        /** Anything else: not SQLite, or encrypted so that it looks like noise. */
        data object NotSqlite : Header
    }

    fun inspect(head: ByteArray): Header {
        if (head.size < MAGIC.size) return if (head.isEmpty()) Header.Empty else Header.NotSqlite
        if (!MAGIC.indices.all { head[it] == MAGIC[it] }) return Header.NotSqlite
        // The page size is a big-endian 16-bit number where 1 stands for 65536.
        val pageSize = if (head.size >= 18) {
            ((head[16].toInt() and 0xFF) shl 8 or (head[17].toInt() and 0xFF)).let { if (it == 1) 65_536 else it }
        } else {
            0
        }
        // Versions 1 and 2 are the rollback journal and WAL. A WAL file keeps recent changes in a
        // `-wal` file next to it, which a copy of the main file alone does not carry.
        val wal = head.size >= 20 && head[18].toInt() == 2 && head[19].toInt() == 2
        return Header.Valid(walMode = wal, pageSize = pageSize)
    }

    /** The header of an existing file, or null when it cannot be read. */
    fun inspect(file: File): Header? = try {
        file.inputStream().use { input -> inspect(readHead(input)) }
    } catch (e: IOException) {
        null
    }

    /** The file that was copied in. */
    data class Copied(val bytes: Long, val walMode: Boolean)

    class NotSqliteException(val empty: Boolean) :
        IOException(if (empty) "the file is empty" else "the file is not a SQLite database")

    /**
     * Copies [input] to [target], refusing a stream that does not start like a SQLite database
     * before a byte of it is written. A partial copy is deleted, so [target] either holds a complete
     * file or does not exist.
     */
    fun copyValidated(input: InputStream, target: File): Copied {
        val head = readHead(input)
        val header = inspect(head)
        if (header !is Header.Valid) throw NotSqliteException(empty = header == Header.Empty)
        var written = 0L
        try {
            java.io.FileOutputStream(target).use { output ->
                output.write(head)
                written += head.size
                written += pump(input, output)
                // The copy is the user's data from now on; make sure it is on disk before the
                // connection that points at it is saved.
                output.flush()
                output.fd.sync()
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        return Copied(written, header.walMode)
    }

    /** Up to the first [HEADER_BYTES] bytes of [input]; fewer only at the end of the stream. */
    private fun readHead(input: InputStream): ByteArray {
        val buffer = ByteArray(HEADER_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return buffer.copyOf(filled)
    }

    private fun pump(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            output.write(buffer, 0, read)
            total += read
        }
    }
}
