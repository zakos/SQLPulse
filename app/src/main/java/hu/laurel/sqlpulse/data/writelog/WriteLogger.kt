package hu.laurel.sqlpulse.data.writelog

import hu.laurel.sqlpulse.data.db.WriteLogDao
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Records every write the app sent, at the places where writes actually run.
 *
 * The one rule: **logging can never fail or undo a write.** [record] is called after the write has
 * finished, and swallows everything but cancellation — a full disk or a locked database costs a
 * log row, not the user's edit. (Cancellation is rethrown because swallowing it would leave a
 * cancelled coroutine running on.)
 */
@Singleton
class WriteLogger @Inject constructor(
    private val sessions: SqlSessionManager,
    private val dao: WriteLogDao,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * @param inTransaction whether the write ran inside a transaction the user opened. The caller
     *   knows (a row edit sees the JDBC connection, the editor sees the session), so it says.
     */
    suspend fun record(
        source: WriteSource,
        statement: String,
        affectedRows: Int?,
        failure: Throwable?,
        startedAt: Long,
        durationMs: Long,
        inTransaction: Boolean,
    ) {
        try {
            val entry = WriteLogEntries.build(
                time = startedAt,
                connection = sessions.currentConnection(),
                database = sessions.database.value,
                source = source,
                statement = statement,
                affectedRows = affectedRows,
                failure = failure,
                durationMs = durationMs,
                inTransaction = inTransaction,
            )
            withContext(io) {
                dao.insert(entry)
                // Retention runs on write rather than on a timer: the log only grows when a write
                // happens, and there is no background work to keep alive.
                val days = settings.settings.first().writeLogDays
                dao.deleteOlderThan(WriteLogRetention.cutoff(System.currentTimeMillis(), days))
                dao.trimToNewest(WriteLogRetention.MAX_ENTRIES)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Deliberately ignored, see the class comment.
        }
    }
}
