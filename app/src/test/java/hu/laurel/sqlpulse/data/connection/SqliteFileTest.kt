package hu.laurel.sqlpulse.data.connection

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.sql.DriverManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SqliteFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun realDatabase(wal: Boolean = false): File {
        val file = folder.newFile()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use {
                if (wal) it.execute("PRAGMA journal_mode=WAL")
                it.execute("CREATE TABLE t (a INTEGER PRIMARY KEY, b TEXT)")
                it.execute("INSERT INTO t (b) VALUES ('x')")
                if (wal) it.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
        return file
    }

    @Test
    fun `a real database header is recognised, with its page size and journal mode`() {
        val plain = SqliteFile.inspect(realDatabase())
        assertTrue(plain is SqliteFile.Header.Valid)
        assertFalse((plain as SqliteFile.Header.Valid).walMode)
        assertEquals(4096, plain.pageSize)

        val wal = SqliteFile.inspect(realDatabase(wal = true)) as SqliteFile.Header.Valid
        assertTrue(wal.walMode)
    }

    @Test
    fun `other files are not databases`() {
        assertEquals(SqliteFile.Header.NotSqlite, SqliteFile.inspect("PK\u0003\u0004 a zip file, not a database".toByteArray()))
        assertEquals(SqliteFile.Header.NotSqlite, SqliteFile.inspect("SQLite format 2\u0000....".toByteArray()))
        // The NUL after the "3" is part of the magic.
        assertEquals(SqliteFile.Header.NotSqlite, SqliteFile.inspect("SQLite format 3 ".toByteArray()))
        assertEquals(SqliteFile.Header.NotSqlite, SqliteFile.inspect("SQLite".toByteArray()))
        assertEquals(SqliteFile.Header.Empty, SqliteFile.inspect(ByteArray(0)))
        // An encrypted database is noise from the first byte.
        assertEquals(SqliteFile.Header.NotSqlite, SqliteFile.inspect(ByteArray(100) { (it * 31 + 7).toByte() }))
    }

    @Test
    fun `a page size of one means 65536`() {
        val head = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 1, 1, 1) + ByteArray(80)
        assertEquals(65_536, (SqliteFile.inspect(head) as SqliteFile.Header.Valid).pageSize)
    }

    @Test
    fun `copying keeps every byte and reports the size`() {
        val source = realDatabase()
        val target = folder.newFile("copy.part")
        val copied = SqliteFile.copyValidated(source.inputStream(), target)
        assertEquals(source.length(), copied.bytes)
        assertArrayEquals(source.readBytes(), target.readBytes())
        // And the copy opens.
        DriverManager.getConnection("jdbc:sqlite:${target.absolutePath}").use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT count(*) FROM t").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) } }
        }
    }

    @Test
    fun `a file that is not a database is refused before anything is written`() {
        val target = File(folder.root, "never.part")
        try {
            SqliteFile.copyValidated(ByteArrayInputStream("just some text, long enough to be more than a header".toByteArray()), target)
            fail("expected a refusal")
        } catch (e: SqliteFile.NotSqliteException) {
            assertFalse(e.empty)
        }
        assertFalse(target.exists())
    }

    @Test
    fun `an empty file is refused as empty`() {
        val target = File(folder.root, "empty.part")
        try {
            SqliteFile.copyValidated(ByteArrayInputStream(ByteArray(0)), target)
            fail("expected a refusal")
        } catch (e: SqliteFile.NotSqliteException) {
            assertTrue(e.empty)
        }
        assertFalse(target.exists())
    }

    @Test
    fun `a copy that fails half way leaves nothing behind`() {
        val database = realDatabase().readBytes()
        val breaking = object : InputStream() {
            private var position = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= database.size.coerceAtMost(150)) throw IOException("the provider went away")
                val count = minOf(length, database.size - position, 150 - position)
                System.arraycopy(database, position, buffer, offset, count)
                position += count
                return count
            }
        }
        val target = File(folder.root, "broken.part")
        try {
            SqliteFile.copyValidated(breaking, target)
            fail("expected the failure to surface")
        } catch (e: IOException) {
            assertEquals("the provider went away", e.message)
        }
        assertFalse(target.exists())
    }
}
