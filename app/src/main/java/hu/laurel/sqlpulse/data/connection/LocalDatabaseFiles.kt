package hu.laurel.sqlpulse.data.connection

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a SQLite connection's database file lives: the app's own copy, never the file the user
 * picked.
 *
 * SQLite needs a real path it can open with random access and put its journal next to. A file
 * picked through the Storage Access Framework is a content URI — no path, no sibling files, and it
 * may sit on a provider (Drive, a USB stick) that vanishes mid-transaction. So the picked file is
 * copied here once and opened here; ConnectionEntity.fileUri remembers where it came from.
 * docs/tobb-motor-terv.md weighs this against opening in place.
 *
 * Phase 2 (the SQLite engine) adds the copy-in, the refresh and the "share a copy back" on top of
 * this path; this class only fixes where the file is, so the session layer can find it.
 */
@Singleton
class LocalDatabaseFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /** The copy belonging to connection [connectionId], whether or not it exists yet. */
    fun pathFor(connectionId: Long): File = File(directory, "$connectionId$EXTENSION")

    /** Removes the copy when its connection is deleted; a missing file is not an error. */
    fun delete(connectionId: Long) {
        pathFor(connectionId).delete()
    }

    private companion object {
        const val DIRECTORY = "sqlite"
        const val EXTENSION = ".sqlite"
    }
}
