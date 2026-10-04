package hu.laurel.sqlpulse.data.connection

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import java.util.Properties

/**
 * Writing the app's private copy of a SQLite file back to the document it was picked from
 * (docs/tobb-motor-terv.md §3.2). Everything here is pure JVM: the Storage Access Framework is
 * hidden behind [SourceFile], so the rules and the copy itself can be tested with temp files.
 *
 * Dirty tracking needs no hooks at the write sites. When a copy is made (or written back) the
 * state of both files is recorded as a [WriteBackBaseline]; the copy is dirty exactly when its
 * size or modification time no longer match that record. SQLite touches the file on every commit,
 * so this stays correct across process death and for writes from any part of the app, including
 * ones added later.
 */

/** What a provider says about a document; either value may be unknown. */
data class SourceStat(val size: Long?, val lastModified: Long?)

/** The original document, as far as writing back needs it. */
interface SourceFile {
    /** Size and time of the document now, or null when the provider cannot say. */
    fun stat(): SourceStat?

    /** Replaces the content: the stream truncates the document first. */
    fun openOutput(): OutputStream

    fun openInput(): InputStream?
}

/** The app's copy as it is on disk right now. */
data class CopyState(val size: Long, val lastModified: Long, val walBytes: Long = 0)

/**
 * The record of the last moment copy and original were known to be the same: the original's
 * [sourceSize]/[sourceModified] then (null where the provider did not say) and the copy's own
 * [copySize]/[copyModified].
 */
data class WriteBackBaseline(
    val sourceSize: Long?,
    val sourceModified: Long?,
    val copySize: Long,
    val copyModified: Long,
) {
    fun encode(): String {
        val props = Properties()
        sourceSize?.let { props.setProperty("sourceSize", it.toString()) }
        sourceModified?.let { props.setProperty("sourceModified", it.toString()) }
        props.setProperty("copySize", copySize.toString())
        props.setProperty("copyModified", copyModified.toString())
        return StringWriter().also { props.store(it, null) }.toString()
    }

    companion object {
        /** Null for anything that is not a complete record: unknown is safer than a guess. */
        fun decode(text: String): WriteBackBaseline? = try {
            val props = Properties().apply { load(text.reader()) }
            WriteBackBaseline(
                sourceSize = props.getProperty("sourceSize")?.toLongOrNull(),
                sourceModified = props.getProperty("sourceModified")?.toLongOrNull(),
                copySize = props.getProperty("copySize").toLong(),
                copyModified = props.getProperty("copyModified").toLong(),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** Whether the original is still what the copy was made from. */
enum class OriginalState { UNCHANGED, CHANGED, UNKNOWN }

/** Why a write-back must not even be offered or started. */
enum class WriteBackRefusal {
    /** No record of the copy's origin (a copy from before this feature): nothing to compare. */
    NO_BASELINE,

    /** The copy has not changed since it was made. */
    NOTHING_TO_WRITE,

    /** The picker's write permission was not kept, or the connection has no source at all. */
    NO_PERMISSION,

    /** A manual transaction is open: the copy on disk is not what the user sees. */
    TRANSACTION_OPEN,
}

object WriteBackPolicy {

    /** A copy with no record is never dirty: guessing would nag about files we know nothing of. */
    fun isDirty(baseline: WriteBackBaseline?, copy: CopyState?): Boolean {
        if (baseline == null || copy == null) return false
        // Changes still in a WAL file have not touched the main file yet, but they are changes.
        return copy.size != baseline.copySize || copy.lastModified != baseline.copyModified || copy.walBytes > 0
    }

    /**
     * Compares the original now with what it was at the baseline. A value either side lacks cannot
     * prove sameness, so a provider that reports neither size nor time always lands on UNKNOWN —
     * which the caller treats like CHANGED: ask before overwriting.
     */
    fun originalState(baseline: WriteBackBaseline, now: SourceStat?): OriginalState {
        if (now == null) return OriginalState.UNKNOWN
        val sizeKnown = baseline.sourceSize != null && now.size != null
        val timeKnown = baseline.sourceModified != null && now.lastModified != null
        if (sizeKnown && baseline.sourceSize != now.size) return OriginalState.CHANGED
        if (timeKnown && baseline.sourceModified != now.lastModified) return OriginalState.CHANGED
        return if (sizeKnown && timeKnown) OriginalState.UNCHANGED else OriginalState.UNKNOWN
    }

    /** The first reason not to write back, or null when it may go ahead. */
    fun refusal(
        baseline: WriteBackBaseline?,
        copy: CopyState?,
        hasWritePermission: Boolean,
        inTransaction: Boolean,
    ): WriteBackRefusal? = when {
        baseline == null -> WriteBackRefusal.NO_BASELINE
        !hasWritePermission -> WriteBackRefusal.NO_PERMISSION
        inTransaction -> WriteBackRefusal.TRANSACTION_OPEN
        !isDirty(baseline, copy) -> WriteBackRefusal.NOTHING_TO_WRITE
        else -> null
    }
}

sealed interface WriteBackOutcome {
    /** The original is not (known to be) what the copy came from; confirmedOverwrite decides. */
    data class NeedsConfirmation(val state: OriginalState) : WriteBackOutcome

    data class Done(val bytes: Long, val baseline: WriteBackBaseline) : WriteBackOutcome

    /** The provider refused or the check afterwards failed; the copy stays dirty. */
    data class Failed(val cause: Throwable) : WriteBackOutcome
}

class WriteBackVerificationException(message: String) : IOException(message)

object WriteBack {

    /**
     * Streams [copy] over the original. The caller has already made the copy consistent (no open
     * transaction, WAL checkpointed). Streaming in place rather than through a temporary document
     * is the only option a content provider offers; a failure half-way can leave the original
     * damaged, which is why the result is read back and why a failed run keeps the copy dirty
     * for another try.
     */
    fun perform(
        copy: File,
        source: SourceFile,
        baseline: WriteBackBaseline,
        confirmedOverwrite: Boolean,
    ): WriteBackOutcome {
        val state = try {
            WriteBackPolicy.originalState(baseline, source.stat())
        } catch (e: Exception) {
            OriginalState.UNKNOWN
        }
        if (state != OriginalState.UNCHANGED && !confirmedOverwrite) {
            return WriteBackOutcome.NeedsConfirmation(state)
        }
        return try {
            val size = copy.length()
            copy.inputStream().use { input ->
                source.openOutput().use { output ->
                    input.copyTo(output, BUFFER_BYTES)
                    output.flush()
                }
            }
            val after = source.stat()
            verify(size, after, source)
            WriteBackOutcome.Done(
                bytes = size,
                baseline = WriteBackBaseline(
                    sourceSize = after?.size,
                    sourceModified = after?.lastModified,
                    copySize = copy.length(),
                    copyModified = copy.lastModified(),
                ),
            )
        } catch (e: Exception) {
            WriteBackOutcome.Failed(e)
        }
    }

    private fun verify(expectedSize: Long, after: SourceStat?, source: SourceFile) {
        val size = after?.size
        if (size != null && size != expectedSize) {
            throw WriteBackVerificationException("the original is $size bytes after writing, expected $expectedSize")
        }
        val head = source.openInput()?.use { stream ->
            val buffer = ByteArray(HEADER_BYTES)
            var filled = 0
            while (filled < buffer.size) {
                val read = stream.read(buffer, filled, buffer.size - filled)
                if (read < 0) break
                filled += read
            }
            buffer.copyOf(filled)
        }
        if (head != null && SqliteFile.inspect(head) !is SqliteFile.Header.Valid) {
            throw WriteBackVerificationException("the original does not start like a SQLite database after writing")
        }
    }

    private const val BUFFER_BYTES = 64 * 1024
    private const val HEADER_BYTES = 100
}
