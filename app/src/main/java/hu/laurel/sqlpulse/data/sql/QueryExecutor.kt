package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.db.QueryHistoryDao
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.di.IoDispatcher
import java.sql.Statement
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The connection is read-only, so a write was refused before it ever reached the server. */
class ReadOnlyConnectionException : Exception("this connection is read-only")

/** DDL and administration are out of scope (§2). */
class UnsupportedStatementException : Exception("only queries and row edits are supported")

/** An UPDATE or DELETE with no WHERE clause, refused by the setting that is on by default. */
class UnguardedWriteException : Exception("this statement has no WHERE clause")

data class QueryOutcome(
    val table: ResultTable,
    /** Rows changed, for statements that return no result set. */
    val updateCount: Int? = null,
    val sqlRun: String,
    /** Set when the statement was a `USE`, which moves the session rather than running. */
    val switchedDatabase: String? = null,
)

/**
 * Runs one statement against the live session (§7.4).
 *
 * Three things happen before anything is sent: writes are refused on a read-only connection, DDL
 * is refused outright, and a read without a LIMIT gets the default one. A running statement can be
 * cancelled, which sends a real KILL QUERY through the driver (§11).
 */
@Singleton
class QueryExecutor @Inject constructor(
    private val sessions: SqlSessionManager,
    private val history: QueryHistoryDao,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @Volatile
    private var running: Statement? = null

    suspend fun run(
        connectionId: Long,
        sql: String,
        parameters: Map<String, String> = emptyMap(),
        rowLimit: Int = SqlGuards.DEFAULT_ROW_LIMIT,
        readOnly: Boolean,
    ): QueryOutcome {
        // `USE` is answered by moving the session's database, not by sending it to one pooled
        // connection and leaving the others behind (§7.4).
        SqlGuards.useTarget(sql)?.let { database ->
            sessions.selectDatabase(database)
            return QueryOutcome(
                table = ResultTable.EMPTY,
                sqlRun = sql.trim(),
                switchedDatabase = database,
            )
        }

        val kind = SqlGuards.classify(sql)
        if (kind == StatementKind.OTHER) throw UnsupportedStatementException()
        if (kind == StatementKind.WRITE && readOnly) throw ReadOnlyConnectionException()
        if (settings.settings.first().blockWritesWithoutWhere && SqlGuards.isUnguardedWrite(sql)) {
            throw UnguardedWriteException()
        }

        val limited = SqlGuards.applyDefaultLimit(sql, rowLimit)
        val bound = SqlGuards.bindParameters(limited.sql)

        val started = System.currentTimeMillis()
        val outcome = sessions.withConnection { connection ->
            connection.prepareStatement(bound.sql).use { statement ->
                bound.parameterOrder.forEachIndexed { index, name ->
                    // Values arrive as text; MySQL coerces them against the column type.
                    statement.setString(index + 1, parameters[name] ?: "")
                }
                statement.queryTimeout = sessions.queryTimeoutSeconds()
                running = statement
                try {
                    if (statement.execute()) {
                        statement.resultSet.use { rows ->
                            QueryOutcome(
                                table = ResultTable.from(rows, rowLimit)
                                    .copy(limitAdded = limited.limitAdded),
                                sqlRun = bound.sql,
                            )
                        }
                    } else {
                        QueryOutcome(
                            table = ResultTable.EMPTY,
                            updateCount = statement.updateCount,
                            sqlRun = bound.sql,
                        )
                    }
                } finally {
                    running = null
                }
            }
        }

        val duration = System.currentTimeMillis() - started
        withContext(io) {
            // §9: the history keeps the SQL and the timings, never the result.
            history.insert(
                QueryHistoryEntity(
                    connectionId = connectionId,
                    sql = sql.trim(),
                    executedAt = started,
                    durationMs = duration,
                    rowCount = outcome.updateCount ?: outcome.table.rowCount,
                ),
            )
        }
        return outcome.copy(table = outcome.table.copy(durationMs = duration))
    }

    /**
     * Cancels the statement in flight. The MariaDB driver opens a second connection and issues
     * KILL QUERY, so this stops the work on the server rather than only on the client (§11).
     */
    suspend fun cancel() = withContext(io) {
        runCatching { running?.cancel() }
        Unit
    }
}
