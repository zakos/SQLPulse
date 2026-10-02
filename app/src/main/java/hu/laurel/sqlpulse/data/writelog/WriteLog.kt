package hu.laurel.sqlpulse.data.writelog

import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.WriteLogEntity
import hu.laurel.sqlpulse.data.sql.ParameterValue

/** Which part of the app sent the write. */
enum class WriteSource { SQL_EDITOR, ROW_EDIT, RESULT_EDIT, CSV_IMPORT, UNDO }

enum class WriteOutcome { OK, FAILED }

/**
 * The pure half of the write log: turning "a write ran" into the row that records it, and the
 * rules for what is kept and what is shown. No Room, no Android — see WriteLogger for the half
 * that talks to the database, which must never be able to fail a write.
 */
object WriteLogEntries {

    /** A statement longer than this is cut: a log row is evidence, not a copy of a 5 MB script. */
    const val MAX_STATEMENT_CHARS = 8_000

    /** Long enough for a driver's message, short enough that a row stays a row. */
    const val MAX_ERROR_CHARS = 240

    /**
     * Builds the row for one write.
     *
     * The connection is copied by value (name, colour, environment) because it may be renamed or
     * deleted later and the log still has to say where the write went.
     */
    fun build(
        time: Long,
        connection: ConnectionEntity?,
        database: String?,
        source: WriteSource,
        statement: String,
        affectedRows: Int?,
        failure: Throwable?,
        durationMs: Long,
        inTransaction: Boolean,
    ): WriteLogEntity = WriteLogEntity(
        time = time,
        connectionId = connection?.id,
        connectionName = connection?.name.orEmpty(),
        connectionColor = connection?.color.orEmpty(),
        environment = ConnectionEnvironment.fromName(connection?.environment).name,
        database = database,
        source = source.name,
        statement = statement.trim().take(MAX_STATEMENT_CHARS),
        affectedRows = affectedRows,
        outcome = (if (failure == null) WriteOutcome.OK else WriteOutcome.FAILED).name,
        error = failure?.let(::describe),
        durationMs = durationMs.coerceAtLeast(0),
        inTransaction = inTransaction,
    )

    /**
     * Class name plus the first line of the message. The first line only: driver messages can
     * append the offending statement or a connection URL on further lines.
     */
    fun describe(failure: Throwable): String {
        val name = failure.javaClass.simpleName.ifEmpty { "Exception" }
        val message = failure.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return (if (message.isEmpty()) name else "$name: $message").take(MAX_ERROR_CHARS)
    }

    /**
     * The statement as typed plus the values bound to its `:name` placeholders, as trailing
     * comments — a log that said `WHERE id = :id` would not say which row was written.
     * Line breaks in a value are escaped so a value can never start a line of its own.
     */
    fun withParameters(
        sql: String,
        order: List<String>,
        values: Map<String, ParameterValue>,
    ): String {
        val names = order.distinct()
        if (names.isEmpty()) return sql.trim()
        return buildString {
            append(sql.trim())
            names.forEach { name ->
                val text = (values[name] ?: ParameterValue()).text
                append("\n-- :").append(name).append(" = '")
                append(text.replace("\\", "\\\\").replace("'", "''").replace("\r", "\\r").replace("\n", "\\n"))
                append('\'')
            }
        }
    }

    /** The first statement of a CSV import, headed by what the import as a whole did. */
    fun csvImport(database: String, table: String, rows: Int, firstStatement: String): String =
        "-- CSV import into `$database`.`$table`: $rows rows, one transaction\n$firstStatement"
}

/** How long the log is kept. */
object WriteLogRetention {
    const val DEFAULT_DAYS = 90
    const val MAX_ENTRIES = 5_000
    val DAY_CHOICES: List<Int> = listOf(30, 90, 365)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Entries older than this are deleted. */
    fun cutoff(now: Long, days: Int): Long = now - days.coerceAtLeast(1) * DAY_MS
}

/** The filters of the log screen, applied in memory: the whole log is at most [WriteLogRetention.MAX_ENTRIES] rows. */
object WriteLogFilter {
    fun apply(
        entries: List<WriteLogEntity>,
        environment: ConnectionEnvironment?,
        source: WriteSource?,
        query: String,
    ): List<WriteLogEntity> {
        val needle = query.trim()
        return entries.filter { entry ->
            (environment == null || entry.environment == environment.name) &&
                (source == null || entry.source == source.name) &&
                (
                    needle.isEmpty() ||
                        entry.statement.contains(needle, ignoreCase = true) ||
                        entry.connectionName.contains(needle, ignoreCase = true) ||
                        entry.database.orEmpty().contains(needle, ignoreCase = true) ||
                        entry.error.orEmpty().contains(needle, ignoreCase = true)
                    )
        }
    }
}
