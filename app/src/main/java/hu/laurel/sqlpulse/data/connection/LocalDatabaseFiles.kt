package hu.laurel.sqlpulse.data.connection

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a SQLite connection's database file lives: the app's own copy, never the file the user
 * picked.
 *
 * SQLite needs a real path it can open with random access and put its journal next to. A file
 * picked through the Storage Access Framework is a content URI — no path, no sibling files, and it
 * may sit on a provider (Drive, a USB stick) that vanishes mid-transaction. So the picked file is
 * copied here and opened here; ConnectionEntity.fileUri remembers where it came from so the copy
 * can be refreshed. docs/tobb-motor-terv.md weighs this against opening in place.
 *
 * A copy goes through two steps. [stage] copies the picked file next to its destination under a
 * temporary name and checks it is a SQLite database; [commit] moves it onto `<id>.sqlite` once the
 * connection is saved and has an id. Cancelling the editor therefore leaves the old copy alone.
 *
 * Only the main file is copied. A database in WAL mode keeps its newest changes in a `-wal` file
 * beside it, which the picker hands over separately or not at all; those changes are missing from
 * the copy, and [DatabaseFileInfo.walMode] lets the editor say so.
 *
 * Changes made to a writable copy are not written back to the original. That would need write
 * access to a document that may be on a provider which cannot take it, and a safe answer to "what
 * if the original changed in the meantime"; it is left for later.
 */
@Singleton
class LocalDatabaseFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /** The copy belonging to connection [connectionId], whether or not it exists yet. */
    fun pathFor(connectionId: Long): File = File(directory, "$connectionId$EXTENSION")

    /** Removes the copy (and the files SQLite keeps beside it) when its connection is deleted. */
    fun delete(connectionId: Long) {
        removeWithSidecars(pathFor(connectionId))
    }

    /** What is known about the copy of [connectionId], or null when there is none. */
    fun info(connectionId: Long): DatabaseFileInfo? {
        val file = pathFor(connectionId)
        if (!file.isFile) return null
        val header = SqliteFile.inspect(file) as? SqliteFile.Header.Valid
        return DatabaseFileInfo(
            sizeBytes = file.length(),
            copiedAt = file.lastModified(),
            walMode = header?.walMode == true,
        )
    }

    /** The size of the copy, for the connection list; null when there is none. */
    fun sizeOf(connectionId: Long): Long? = pathFor(connectionId).takeIf { it.isFile }?.length()

    /**
     * Copies the file behind [uri] into a staging file and checks it.
     *
     * @throws SqliteFile.NotSqliteException when it is not a database
     * @throws IOException when it cannot be read or there is no room for it
     */
    fun stage(uri: Uri): StagedFile {
        removeStaleStaging()
        val size = sizeOfUri(uri)
        val free = directory.usableSpace
        // A copy that fills the storage takes the rest of the phone down with it; the slack is
        // for the journal the first write will want.
        if (size != null && free < size + FREE_SPACE_SLACK) throw IOException("not enough free storage for ${size} bytes")
        val file = File(directory, "staging-${System.nanoTime()}$STAGING_EXTENSION")
        val copied = try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            input.use { SqliteFile.copyValidated(it, file) }
        } catch (e: IOException) {
            file.delete()
            throw e
        } catch (e: SecurityException) {
            file.delete()
            throw IOException("access to the file was refused", e)
        }
        return StagedFile(
            file = file,
            name = displayName(uri),
            info = DatabaseFileInfo(copied.bytes, System.currentTimeMillis(), copied.walMode),
        )
    }

    /** Puts a staged copy in place for [connectionId], replacing the one that was there. */
    fun commit(staged: File, connectionId: Long) {
        val target = pathFor(connectionId)
        // A leftover `-wal` or `-shm` of the old file next to a new main file is at best ignored
        // and at worst read as that file's recent changes.
        sidecars(target).forEach { it.delete() }
        if (!staged.renameTo(target)) {
            // Same directory, so a failed rename is unusual; fall back to copying rather than
            // losing the file the user just chose.
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }
    }

    /** Drops a staged copy that will not be committed. */
    fun discard(staged: File) {
        staged.delete()
    }

    /** Gives a duplicated connection its own copy of the file. */
    fun duplicate(fromConnectionId: Long, toConnectionId: Long) {
        val source = pathFor(fromConnectionId)
        if (source.isFile) source.copyTo(pathFor(toConnectionId), overwrite = true)
    }

    /**
     * Asks the provider to keep the permission to read [uri] across restarts, which is what lets
     * "Refresh copy" read the original again without the picker. Not every provider allows it, and
     * the copy works without it, so a refusal is not an error.
     */
    fun rememberAccess(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Offered by the picker but not persistable: the copy still works.
        }
    }

    /** Whether the app can still read [uri] without asking, so the copy can be refreshed. */
    fun canRefresh(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        val parsed = Uri.parse(uri)
        return context.contentResolver.persistedUriPermissions.any { it.uri == parsed && it.isReadPermission }
    }

    private fun displayName(uri: Uri): String? = queryOpenable(uri, OpenableColumns.DISPLAY_NAME)

    private fun sizeOfUri(uri: Uri): Long? = queryOpenable(uri, OpenableColumns.SIZE)?.toLongOrNull()

    private fun queryOpenable(uri: Uri, column: String): String? = try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** A staging file left by a crash would otherwise stay as big as a database for ever. */
    private fun removeStaleStaging() {
        val cutoff = System.currentTimeMillis() - STALE_STAGING_MILLIS
        directory.listFiles { file -> file.name.endsWith(STAGING_EXTENSION) }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun removeWithSidecars(file: File) {
        sidecars(file).forEach { it.delete() }
        file.delete()
    }

    private fun sidecars(file: File): List<File> =
        listOf("-wal", "-shm", "-journal").map { File(file.path + it) }

    private companion object {
        const val DIRECTORY = "sqlite"
        const val EXTENSION = ".sqlite"
        const val STAGING_EXTENSION = ".part"
        const val FREE_SPACE_SLACK = 16L * 1024 * 1024
        const val STALE_STAGING_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** A database file copied in but not yet attached to a saved connection. */
data class StagedFile(val file: File, val name: String?, val info: DatabaseFileInfo)

/** What the editor shows about the app's copy of a database file. */
data class DatabaseFileInfo(
    val sizeBytes: Long,
    /** When the copy was made, in epoch milliseconds. */
    val copiedAt: Long,
    /** The file is in WAL mode, so changes still in its `-wal` file are not in the copy. */
    val walMode: Boolean,
)
