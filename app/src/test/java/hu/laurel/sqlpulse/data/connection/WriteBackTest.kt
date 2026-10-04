package hu.laurel.sqlpulse.data.connection

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A temp file standing in for the SAF document: same contract, no Android. */
private class FileSource(
    val file: File,
    var reportTime: Boolean = true,
    var failOnOpen: Boolean = false,
    /** Truncate after this many bytes and then fail, like a provider that dies mid-write. */
    var failAfterBytes: Long? = null,
    var corruptHead: Boolean = false,
) : SourceFile {
    override fun stat() = SourceStat(file.length(), if (reportTime) file.lastModified() else null)

    override fun openOutput(): OutputStream {
        if (failOnOpen) throw IOException("read-only provider")
        val out = file.outputStream()
        val limit = failAfterBytes
        return object : OutputStream() {
            var written = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (limit != null && written + len > limit) {
                    out.write(b, off, (limit - written).toInt())
                    out.close()
                    throw IOException("provider went away")
                }
                out.write(if (corruptHead) b.copyOf().also { it[0] = 0 } else b, off, len)
                written += len
            }
            override fun close() = out.close()
        }
    }

    override fun openInput(): InputStream = file.inputStream()
}

class WriteBackTest {

    private lateinit var dir: File
    private lateinit var original: File
    private lateinit var copy: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("writeback").toFile()
        original = File(dir, "basic.db")
        copy = File(dir, "7.sqlite")
        connect(original).use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
                it.execute("INSERT INTO t VALUES (1, 'one')")
            }
        }
        original.copyTo(copy)
        // Back-date both so a later "now" write is certain to change the modification time.
        original.setLastModified(1_000_000)
        copy.setLastModified(1_000_000)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun connect(file: File) = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    private fun rows(file: File): Int = connect(file).use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM t").use { it.next(); it.getInt(1) } }
    }

    private fun baseline(source: FileSource) = FileSource(original).stat().let {
        WriteBackBaseline(it!!.size, it.lastModified, copy.length(), copy.lastModified())
    }

    private fun editCopy() {
        connect(copy).use { c -> c.createStatement().use { it.execute("INSERT INTO t VALUES (2, 'two')") } }
        copy.setLastModified(2_000_000)
    }

    private fun state(): CopyState = CopyState(copy.length(), copy.lastModified())

    // ---- dirty decision

    @Test
    fun `a copy matching its baseline is clean`() {
        val base = baseline(FileSource(original))
        assertFalse(WriteBackPolicy.isDirty(base, state()))
    }

    @Test
    fun `a write to the copy makes it dirty even after a restart`() {
        val base = baseline(FileSource(original))
        editCopy()
        // Only the files are consulted: nothing in memory records the write.
        assertTrue(WriteBackPolicy.isDirty(WriteBackBaseline.decode(base.encode()), state()))
    }

    @Test
    fun `same size but newer modification time is dirty`() {
        val base = baseline(FileSource(original))
        copy.setLastModified(5_000_000)
        assertTrue(WriteBackPolicy.isDirty(base, state()))
    }

    @Test
    fun `a leftover wal file counts as changes`() {
        val base = baseline(FileSource(original))
        assertTrue(WriteBackPolicy.isDirty(base, state().copy(walBytes = 4096)))
    }

    @Test
    fun `no baseline or no copy is never dirty`() {
        assertFalse(WriteBackPolicy.isDirty(null, state()))
        assertFalse(WriteBackPolicy.isDirty(baseline(FileSource(original)), null))
    }

    @Test
    fun `baseline survives encoding and rejects garbage`() {
        val b = WriteBackBaseline(10, null, 20, 30)
        assertEquals(b, WriteBackBaseline.decode(b.encode()))
        assertNull(WriteBackBaseline.decode("nonsense"))
        assertNull(WriteBackBaseline.decode(""))
    }

    // ---- original compared with baseline

    @Test
    fun `original state compares size and time`() {
        val b = WriteBackBaseline(100, 5, 1, 1)
        assertEquals(OriginalState.UNCHANGED, WriteBackPolicy.originalState(b, SourceStat(100, 5)))
        assertEquals(OriginalState.CHANGED, WriteBackPolicy.originalState(b, SourceStat(101, 5)))
        assertEquals(OriginalState.CHANGED, WriteBackPolicy.originalState(b, SourceStat(100, 6)))
        assertEquals(OriginalState.UNKNOWN, WriteBackPolicy.originalState(b, null))
        // A provider that does not report a time cannot prove the file is unchanged.
        assertEquals(OriginalState.UNKNOWN, WriteBackPolicy.originalState(b, SourceStat(100, null)))
        assertEquals(OriginalState.UNKNOWN, WriteBackPolicy.originalState(b.copy(sourceModified = null), SourceStat(100, 5)))
        // But a known mismatch is still a change.
        assertEquals(OriginalState.CHANGED, WriteBackPolicy.originalState(b, SourceStat(7, null)))
    }

    // ---- refusal rules

    @Test
    fun `refusals are reported in a fixed order`() {
        val base = baseline(FileSource(original))
        val dirty = state().copy(lastModified = 9)
        assertEquals(WriteBackRefusal.NO_BASELINE, WriteBackPolicy.refusal(null, dirty, true, false))
        assertEquals(WriteBackRefusal.NO_PERMISSION, WriteBackPolicy.refusal(base, dirty, false, false))
        assertEquals(WriteBackRefusal.TRANSACTION_OPEN, WriteBackPolicy.refusal(base, dirty, true, true))
        assertEquals(WriteBackRefusal.NOTHING_TO_WRITE, WriteBackPolicy.refusal(base, state(), true, false))
        assertNull(WriteBackPolicy.refusal(base, dirty, true, false))
    }

    // ---- the write itself

    @Test
    fun `writes the copy over an unchanged original and moves the baseline`() {
        val source = FileSource(original)
        val base = baseline(source)
        editCopy()

        val outcome = WriteBack.perform(copy, source, base, confirmedOverwrite = false)

        val done = outcome as WriteBackOutcome.Done
        assertEquals(2, rows(original))
        assertEquals(copy.length(), done.bytes)
        // No longer dirty against the new baseline, and the next write sees the original unchanged.
        assertFalse(WriteBackPolicy.isDirty(done.baseline, state()))
        assertEquals(OriginalState.UNCHANGED, WriteBackPolicy.originalState(done.baseline, source.stat()))
    }

    @Test
    fun `a changed original needs confirmation and is left alone until then`() {
        val source = FileSource(original)
        val base = baseline(source)
        editCopy()
        connect(original).use { c -> c.createStatement().use { it.execute("INSERT INTO t VALUES (9, 'theirs')") } }
        original.setLastModified(3_000_000)

        val first = WriteBack.perform(copy, source, base, confirmedOverwrite = false)

        assertEquals(WriteBackOutcome.NeedsConfirmation(OriginalState.CHANGED), first)
        assertEquals(2, rows(original))
        assertEquals(true, connect(original).use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT 1 FROM t WHERE id = 9").use { it.next() } }
        })

        val second = WriteBack.perform(copy, source, base, confirmedOverwrite = true)
        assertTrue(second is WriteBackOutcome.Done)
        assertEquals(2, rows(original))
        assertEquals(false, connect(original).use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT 1 FROM t WHERE id = 9").use { it.next() } }
        })
    }

    @Test
    fun `an unreadable modification time asks before overwriting`() {
        val source = FileSource(original, reportTime = false)
        val base = baseline(source)
        editCopy()
        assertEquals(
            WriteBackOutcome.NeedsConfirmation(OriginalState.UNKNOWN),
            WriteBack.perform(copy, source, base, confirmedOverwrite = false),
        )
    }

    @Test
    fun `a provider that refuses writing fails and leaves the copy dirty`() {
        val source = FileSource(original, failOnOpen = true)
        val base = baseline(source)
        editCopy()

        val outcome = WriteBack.perform(copy, source, base, confirmedOverwrite = false)

        assertTrue(outcome is WriteBackOutcome.Failed)
        assertTrue(WriteBackPolicy.isDirty(base, state()))
        assertEquals(1, rows(original))
    }

    @Test
    fun `a write that stops half way is reported as failed`() {
        val source = FileSource(original, failAfterBytes = 1024)
        val base = baseline(source)
        editCopy()
        assertTrue(WriteBack.perform(copy, source, base, confirmedOverwrite = false) is WriteBackOutcome.Failed)
        // The copy is intact, so a retry is possible.
        assertEquals(2, rows(copy))
        assertTrue(WriteBackPolicy.isDirty(base, state()))
    }

    @Test
    fun `a result that does not read back as sqlite is a failure`() {
        val source = FileSource(original, corruptHead = true)
        val base = baseline(source)
        editCopy()
        val outcome = WriteBack.perform(copy, source, base, confirmedOverwrite = false)
        val failure = (outcome as WriteBackOutcome.Failed).cause
        assertTrue(failure is WriteBackVerificationException)
        assertNotNull(failure.message)
    }

    @Test
    fun `a wal copy is written whole after a checkpoint`() {
        val source = FileSource(original)
        val base = baseline(source)
        connect(copy).use { c ->
            c.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("INSERT INTO t VALUES (3, 'three')")
            }
            // The state the app sees before it checkpoints: changes in the -wal file only.
            c.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        }
        copy.setLastModified(2_000_000)
        assertTrue(WriteBackPolicy.isDirty(base, state()))
        assertTrue(WriteBack.perform(copy, source, base, confirmedOverwrite = false) is WriteBackOutcome.Done)
        assertEquals(2, rows(original))
    }
}
