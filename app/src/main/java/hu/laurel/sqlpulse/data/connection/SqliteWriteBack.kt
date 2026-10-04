package hu.laurel.sqlpulse.data.connection

import android.net.Uri
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.writelog.WriteLogger
import hu.laurel.sqlpulse.data.writelog.WriteSource
import hu.laurel.sqlpulse.di.IoDispatcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** What the app knows about writing one SQLite connection's copy back; see [SqliteWriteBack.status]. */
data class WriteBackStatus(
    /** The copy holds changes the original does not. */
    val dirty: Boolean,
    /** The original can be written to without the picker. */
    val canWrite: Boolean,
) {
    /** Worth asking about: changes exist and there is somewhere to put them. */
    val offer: Boolean get() = dirty && canWrite
}

sealed interface WriteBackResult {
    data class Done(val bytes: Long) : WriteBackResult
    data class NeedsConfirmation(val state: OriginalState) : WriteBackResult
    data class Refused(val reason: WriteBackRefusal) : WriteBackResult
    data class Failed(val message: String) : WriteBackResult
}

/**
 * The Android side of writing a SQLite copy back: makes the copy consistent, runs [WriteBack] and
 * moves the baseline on success. The rules themselves live in [WriteBackPolicy].
 */
@Singleton
class SqliteWriteBack @Inject constructor(
    private val files: LocalDatabaseFiles,
    private val sessions: SqlSessionManager,
    private val writeLog: WriteLogger,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun isSqliteFile(connection: ConnectionEntity?): Boolean =
        connection != null && DatabaseEngine.fromName(connection.engine) == DatabaseEngine.SQLITE

    /** Cheap enough for a back press: two file stats and a permission lookup. */
    suspend fun status(connection: ConnectionEntity?): WriteBackStatus {
        if (!isSqliteFile(connection)) return WriteBackStatus(dirty = false, canWrite = false)
        return withContext(io) {
            WriteBackStatus(
                dirty = files.isDirty(connection!!.id),
                canWrite = files.canWriteBack(connection.fileUri),
            )
        }
    }

    /**
     * Writes the copy of [connection] over the original.
     *
     * @param confirmedOverwrite the user has been told the original changed (or could not be
     *   checked) and wants it replaced anyway.
     */
    suspend fun write(connection: ConnectionEntity, confirmedOverwrite: Boolean): WriteBackResult =
        withContext(io) {
            val uri = connection.fileUri?.let(Uri::parse)
            val live = sessions.currentConnection()?.id == connection.id
            val baseline = files.baseline(connection.id)
            val refusal = WriteBackPolicy.refusal(
                baseline = baseline,
                copy = files.copyState(connection.id),
                hasWritePermission = uri != null && files.canWriteBack(connection.fileUri),
                // Another connection's transaction does not touch this file.
                inTransaction = live && sessions.inTransaction.value,
            )
            if (refusal != null) return@withContext WriteBackResult.Refused(refusal)
            val started = System.currentTimeMillis()
            try {
                checkpoint(connection.id, live)
                when (val outcome = WriteBack.perform(files.pathFor(connection.id), files.sourceFor(uri!!), baseline!!, confirmedOverwrite)) {
                    is WriteBackOutcome.NeedsConfirmation -> WriteBackResult.NeedsConfirmation(outcome.state)
                    is WriteBackOutcome.Done -> {
                        files.writeBaseline(connection.id, outcome.baseline)
                        log(connection, outcome.bytes, null, started)
                        WriteBackResult.Done(outcome.bytes)
                    }
                    is WriteBackOutcome.Failed -> {
                        log(connection, 0, outcome.cause, started)
                        WriteBackResult.Failed(outcome.cause.message ?: outcome.cause.javaClass.simpleName)
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                log(connection, 0, e, started)
                WriteBackResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }

    /**
     * Folds a WAL file into the main file, which is the only file that gets copied. Through the live
     * session when there is one (it holds the database open, and a second connection could be
     * blocked by its locks); on a short connection of its own otherwise.
     */
    private suspend fun checkpoint(connectionId: Long, live: Boolean) {
        val file: File = files.pathFor(connectionId)
        // Without a WAL file with content the main file already is the whole database.
        if (File(file.path + "-wal").length() == 0L) return
        val statement = "PRAGMA wal_checkpoint(TRUNCATE)"
        if (live) {
            sessions.withConnection { it.createStatement().use { s -> s.execute(statement) } }
        } else {
            // The class is named rather than found through DriverManager, as in SqliteConnector.
            org.sqlite.JDBC().connect("jdbc:sqlite:${file.absolutePath}", java.util.Properties())?.use { c ->
                c.createStatement().use { it.execute(statement) }
            }
        }
    }

    private suspend fun log(connection: ConnectionEntity, bytes: Long, failure: Throwable?, startedAt: Long) {
        writeLog.record(
            source = WriteSource.WRITE_BACK,
            statement = "-- ${connection.fileName ?: "?"}: $bytes bytes written to the original file",
            affectedRows = null,
            failure = failure,
            startedAt = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
            inTransaction = false,
            connection = connection,
        )
    }
}
