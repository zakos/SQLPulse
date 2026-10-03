package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.connection.WriteAccess
import hu.laurel.sqlpulse.data.connection.WriteUnlockStore
import hu.laurel.sqlpulse.data.db.QueryHistoryDao
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.data.writelog.WriteLogEntries
import hu.laurel.sqlpulse.data.writelog.WriteLogger
import hu.laurel.sqlpulse.data.writelog.WriteSource
import hu.laurel.sqlpulse.di.IoDispatcher
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Types
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

/**
 * A write on a production connection that has not been unlocked, or whose unlock has run out.
 *
 * Separate from [ReadOnlyConnectionException] because the answer is different: this one is undone
 * by unlocking writes for fifteen minutes on the connection card, not by editing the connection.
 */
class WritesLockedException(val access: WriteAccess) : Exception("writes are locked on this connection")

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
    private val writeUnlock: WriteUnlockStore,
    private val writeLog: WriteLogger,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @Volatile
    private var running: Statement? = null

    suspend fun run(
        connectionId: Long,
        sql: String,
        parameters: Map<String, ParameterValue> = emptyMap(),
        rowLimit: Int = SqlGuards.DEFAULT_ROW_LIMIT,
        readOnly: Boolean,
    ): QueryOutcome {
        // Every decision below is the live engine's: MySQL's dialect is SqlGuards and WriteImpact
        // themselves, so for MySQL nothing changed.
        val dialect = sessions.dialect()

        // `USE` is answered by moving the session's database, not by sending it to one pooled
        // connection and leaving the others behind (§7.4).
        dialect.namespaceSwitch(sql)?.let { database ->
            sessions.selectDatabase(database)
            return QueryOutcome(
                table = ResultTable.EMPTY,
                sqlRun = sql.trim(),
                switchedDatabase = database,
            )
        }

        val kind = dialect.classify(sql)
        if (kind == StatementKind.OTHER) throw UnsupportedStatementException()
        if (kind == StatementKind.WRITE && readOnly) throw ReadOnlyConnectionException()
        if (kind == StatementKind.WRITE) {
            // The production policy, checked where the statement actually runs rather than only in
            // the dialog that offers to run it — the editor is not the only caller.
            val access = writeUnlock.writeAccess(
                connectionId = connectionId,
                environment = ConnectionEnvironment.fromName(
                    sessions.currentConnection()?.environment,
                ),
                readOnly = readOnly,
            )
            if (!access.allowed) throw WritesLockedException(access)
        }
        if (settings.settings.first().blockWritesWithoutWhere && dialect.isUnguardedWrite(sql)) {
            throw UnguardedWriteException()
        }

        val limited = dialect.applyDefaultLimit(sql, rowLimit)
        val bound = dialect.bindParameters(limited.sql)

        val started = System.currentTimeMillis()
        // Taken before the write: a COMMIT from elsewhere while it runs must not change what the
        // log says about this statement.
        val inTransaction = sessions.inTransaction.value
        val outcome = try {
            sessions.withConnection { connection ->
                connection.prepareStatement(bound.sql).use { statement ->
                    bind(statement, bound.parameterOrder, parameters)
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
        } catch (e: Exception) {
            // Every guard above throws before this point, so a failure here means the statement
            // was sent. Only writes are logged; a failed SELECT is not a write.
            if (kind == StatementKind.WRITE && e !is NoSqlSessionException) {
                logWrite(sql, bound, parameters, started, null, e, inTransaction)
            }
            throw e
        }

        // A session that has written is never reconnected to automatically: the server rolled the
        // work back and silently picking the connection up again would hide that.
        if (kind == StatementKind.WRITE) sessions.noteWrite()

        val duration = System.currentTimeMillis() - started
        if (kind == StatementKind.WRITE) {
            logWrite(sql, bound, parameters, started, outcome.updateCount, null, inTransaction)
        }
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

    /** Records a write that was sent, with the bound values written out next to the SQL. */
    private suspend fun logWrite(
        sql: String,
        bound: SqlGuards.BoundStatement,
        parameters: Map<String, ParameterValue>,
        started: Long,
        affectedRows: Int?,
        failure: Throwable?,
        inTransaction: Boolean,
    ) = writeLog.record(
        source = WriteSource.SQL_EDITOR,
        statement = WriteLogEntries.withParameters(sql, bound.parameterOrder, parameters),
        affectedRows = affectedRows,
        failure = failure,
        startedAt = started,
        durationMs = System.currentTimeMillis() - started,
        inTransaction = inTransaction,
    )

    /**
     * How many rows the write in [sql] would touch, or null when that cannot be said.
     *
     * Null covers both halves of "we do not know": a statement WriteImpact will not rewrite, and a
     * count that failed to run. The caller shows the same "unknown" for either, because the user's
     * next decision is the same one.
     *
     * The count runs on the session like any other query — through [SqlSessionManager.withConnection],
     * so off the main thread — but is not recorded in the history: it is this app's question, not
     * the user's.
     */
    suspend fun estimateAffectedRows(
        sql: String,
        parameters: Map<String, ParameterValue> = emptyMap(),
    ): Long? {
        val dialect = sessions.dialect()
        val count = dialect.writeCountQuery(sql) ?: return null
        val bound = dialect.bindParameters(count)
        return runCatching {
            sessions.withConnection { connection ->
                connection.prepareStatement(bound.sql).use { statement ->
                    bind(statement, bound.parameterOrder, parameters)
                    statement.queryTimeout = sessions.queryTimeoutSeconds()
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
                }
            }
        }.getOrNull()
    }

    /**
     * The rows the write in [sql] would change (up to [WriteImpact.PREVIEW_ROWS]), or null when no
     * preview can be derived or it failed to run — the card then simply has no table, and the write
     * is not held up by it.
     *
     * It goes through exactly the path the count takes: a plain SELECT on the session, with the
     * session's query timeout, not recorded in the history. [WriteImpact.previewQuery] only ever
     * produces a single SELECT (the check below keeps it that way), so it has no way to write; the
     * one thing it cannot rule out is a function with side effects hidden in a subquery, which is
     * why the SET expressions are screened there.
     */
    suspend fun previewWrite(
        sql: String,
        parameters: Map<String, ParameterValue> = emptyMap(),
    ): WriteRowPreview? {
        val dialect = sessions.dialect()
        val query = dialect.writePreviewQuery(sql, WriteImpact.PREVIEW_ROWS) ?: return null
        if (!query.sql.startsWith("SELECT ")) return null
        val bound = dialect.bindParameters(query.sql)
        return runCatching {
            sessions.withConnection { connection ->
                connection.prepareStatement(bound.sql).use { statement ->
                    bind(statement, bound.parameterOrder, parameters)
                    statement.queryTimeout = sessions.queryTimeoutSeconds()
                    statement.executeQuery().use { rows ->
                        WriteRowPreview(query.kind, query.changedColumns, ResultTable.from(rows, WriteImpact.PREVIEW_ROWS))
                    }
                }
            }
        }.getOrNull()
    }

    /** Binds the values by the type the user gave each one (§7.4). */
    private fun bind(
        statement: PreparedStatement,
        order: List<String>,
        parameters: Map<String, ParameterValue>,
    ) {
        order.forEachIndexed { index, name ->
            val position = index + 1
            when (val binding = QueryParameters.binding(parameters[name] ?: ParameterValue())) {
                // The driver ignores the type it is given for a null; VARCHAR is the one every
                // column accepts.
                ParameterBinding.Null -> statement.setNull(position, Types.VARCHAR)
                is ParameterBinding.Text -> statement.setString(position, binding.value)
                is ParameterBinding.Integer -> statement.setLong(position, binding.value)
                is ParameterBinding.Decimal -> statement.setDouble(position, binding.value)
                is ParameterBinding.Bool -> statement.setBoolean(position, binding.value)
            }
        }
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
