package hu.laurel.sqlpulse.data.export

import android.content.Intent
import hu.laurel.sqlpulse.data.sql.ParameterValue
import hu.laurel.sqlpulse.data.sql.QueryExecutor
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.di.IoDispatcher
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** What a finished full export hands back: the share intent, and how complete the file is. */
data class FullExportResult(val intent: Intent, val outcome: FullExportOutcome)

/**
 * Re-runs a read statement as the user typed it and streams every row to an export file.
 *
 * The statement is sent without the row limit the editor added, with the connection's own query
 * timeout (so a production connection's cap still applies), on a pooled connection like any other
 * query. Cancelling the calling coroutine cancels the statement on the server and deletes the
 * half-written file.
 */
@Singleton
class FullExporter @Inject constructor(
    private val sessions: SqlSessionManager,
    private val executor: QueryExecutor,
    private val exports: ExportManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @Volatile
    private var running: PreparedStatement? = null

    @Volatile
    private var cancelRequested = false

    suspend fun export(
        sql: String,
        parameters: Map<String, ParameterValue>,
        format: ExportFormat,
        baseName: String,
        onProgress: (rows: Long, bytes: Long) -> Unit,
    ): FullExportResult = coroutineScope {
        val dialect = sessions.dialect()
        // The rule is checked again here, not only where the choice is offered: this is the code
        // that sends the statement, and a second run of a write is not something to leave to a button.
        require(FullExport.isRerunnableRead(sql, dialect)) { "only a plain read can be exported in full" }

        cancelRequested = false
        val bound = dialect.bindParameters(sql.trim().trimEnd(';'))
        val file = exports.newFile(baseName, format)
        // The watcher turns a cancelled coroutine into a cancelled statement: the blocking read
        // below cannot see a coroutine cancellation by itself.
        val watcher = launch(io) {
            try {
                awaitCancellation()
            } finally {
                cancelRequested = true
                runCatching { running?.cancel() }
            }
        }
        try {
            val outcome = sessions.withConnection { connection ->
                connection.prepareStatement(bound.sql).use { statement ->
                    executor.bind(statement, bound.parameterOrder, parameters)
                    statement.queryTimeout = sessions.queryTimeoutSeconds()
                    running = statement
                    val restore = streamingMode(dialect.engine, connection, statement)
                    try {
                        statement.executeQuery().use { rows ->
                            file.outputStream().use { out ->
                                FullExport.write(
                                    resultSet = rows,
                                    format = format,
                                    out = out,
                                    syntax = dialect,
                                    shouldContinue = { !cancelRequested },
                                    onProgress = onProgress,
                                ).also {
                                    // The rest of a capped result is not wanted; ask the server to stop
                                    // sending it rather than have closing the cursor read it all.
                                    if (it.cap != null || it.cancelled) runCatching { statement.cancel() }
                                }
                            }
                        }
                    } finally {
                        running = null
                        restore()
                    }
                }
            }
            if (outcome.cancelled) throw CancellationException("export cancelled")
            FullExportResult(exports.shareIntent(file, format), outcome)
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            watcher.cancel()
        }
    }

    /**
     * Asks the driver to hand rows over as they arrive instead of reading the whole result first,
     * which is what keeps a million rows out of the heap. Each engine spells that differently, and
     * a driver that refuses the hint still works, only less frugally.
     *
     * @return what puts the connection back the way it was.
     */
    private fun streamingMode(engine: DatabaseEngine, connection: Connection, statement: PreparedStatement): () -> Unit {
        when (engine) {
            DatabaseEngine.MYSQL -> {
                // MariaDB Connector/J streams on a positive fetch size and rejects the magic
                // Integer.MIN_VALUE ("invalid fetch size", measured); the legacy MySQL Connector/J
                // 5.1 is the opposite and only streams on that value. The driver's own name tells
                // them apart, which survives R8 where a class name might not.
                val legacy = runCatching { connection.metaData.driverName.startsWith("MySQL") }.getOrDefault(false)
                runCatching { statement.fetchSize = if (legacy) Int.MIN_VALUE else FETCH_SIZE }
            }
            DatabaseEngine.POSTGRESQL -> {
                // pgjdbc only uses a cursor inside a transaction; outside one it reads everything.
                val wasAutoCommit = runCatching { connection.autoCommit }.getOrDefault(false)
                if (wasAutoCommit) {
                    runCatching { connection.autoCommit = false }
                    runCatching { statement.fetchSize = FETCH_SIZE }
                    return {
                        runCatching { connection.rollback() }
                        runCatching { connection.autoCommit = true }
                        Unit
                    }
                }
                runCatching { statement.fetchSize = FETCH_SIZE }
            }
            else -> runCatching { statement.fetchSize = FETCH_SIZE }
        }
        return {}
    }

    private companion object {
        const val FETCH_SIZE = 2_000
    }
}
