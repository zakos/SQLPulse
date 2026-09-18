package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.db.QueryHistoryDao
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.settings.SettingsRepository
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
        parameters: Map<String, ParameterValue> = emptyMap(),
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
        val count = WriteImpact.countQuery(sql) ?: return null
        val bound = SqlGuards.bindParameters(count)
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
