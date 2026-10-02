package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable

/** State of one replication thread as the server words it (`Yes`, `No`, `Connecting`, …). */
enum class ThreadState {
    RUNNING, CONNECTING, STOPPED, UNKNOWN;

    companion object {
        fun of(value: String?): ThreadState = when (value?.trim()?.lowercase()) {
            "yes", "on" -> RUNNING
            "no", "off" -> STOPPED
            // "Preparing" is the SQL thread's state while it starts; it is not a failure.
            "connecting", "preparing" -> CONNECTING
            else -> UNKNOWN
        }
    }
}

/** The one-word answer a DBA wants before reading any detail. */
enum class ReplicationHealth { OK, LAGGING, FAR_BEHIND, STOPPED, ERROR, UNKNOWN }

/** One replication channel (the only one on a classic replica, one per source on multi-source). */
data class ReplicationChannel(
    /** `Channel_Name` (MySQL) or `Connection_name` (MariaDB); empty for the default channel. */
    val name: String,
    val sourceHost: String?,
    val sourcePort: Int?,
    val sourceUser: String?,
    val ioThread: ThreadState,
    val sqlThread: ThreadState,
    val lagSeconds: Long?,
    val sqlDelaySeconds: Long,
    val remainingDelaySeconds: Long?,
    val ioState: String?,
    val sqlState: String?,
    val lastIoError: String?,
    val lastSqlError: String?,
    val retrievedGtidSet: String?,
    val executedGtidSet: String?,
    val sourceLogFile: String?,
    val readSourceLogPos: Long?,
    val relayLogFile: String?,
    val relayLogPos: Long?,
    val execSourceLogPos: Long?,
) {
    /** The first error either thread reported, IO before SQL. */
    val lastError: String? get() = lastIoError ?: lastSqlError

    /**
     * Lag without the delay that was asked for. A replica configured with `SOURCE_DELAY` reports
     * the delay as lag, and flagging something set up on purpose would cry wolf.
     */
    val effectiveLag: Long? get() = lagSeconds?.let { (it - sqlDelaySeconds).coerceAtLeast(0) }

    val health: ReplicationHealth get() = ReplicationStatus.verdict(this)

    val hasPositions: Boolean
        get() = retrievedGtidSet != null || executedGtidSet != null || sourceLogFile != null ||
            relayLogFile != null
}

/**
 * Turns `SHOW REPLICA STATUS` into something readable.
 *
 * MySQL renamed Slave/Master to Replica/Source in 8.0.22 and MariaDB (`SHOW ALL SLAVES STATUS`)
 * kept the old words, so every field is looked up under each of its names, newest first.
 */
object ReplicationStatus {

    /** Lag above this is worth a glance (amber), above [FAR_BEHIND_SECONDS] it is a problem (red). */
    const val LAGGING_SECONDS = 30L
    const val FAR_BEHIND_SECONDS = 300L

    /** Parses rows given as column-name to value maps. Column names match case-insensitively. */
    fun parse(rows: List<Map<String, String?>>): List<ReplicationChannel> = rows.map { raw ->
        val row = raw.entries.associate { it.key.lowercase() to it.value }
        fun text(vararg names: String): String? =
            names.firstNotNullOfOrNull { row[it.lowercase()]?.trim()?.takeIf(String::isNotEmpty) }
        fun long(vararg names: String): Long? = text(*names)?.toLongOrNull()

        ReplicationChannel(
            name = text("Channel_Name", "Connection_name").orEmpty(),
            sourceHost = text("Source_Host", "Master_Host"),
            sourcePort = long("Source_Port", "Master_Port")?.toInt(),
            sourceUser = text("Source_User", "Master_User"),
            ioThread = ThreadState.of(text("Replica_IO_Running", "Slave_IO_Running")),
            sqlThread = ThreadState.of(text("Replica_SQL_Running", "Slave_SQL_Running")),
            lagSeconds = long("Seconds_Behind_Source", "Seconds_Behind_Master"),
            sqlDelaySeconds = long("SQL_Delay") ?: 0L,
            remainingDelaySeconds = long("SQL_Remaining_Delay"),
            ioState = text("Replica_IO_State", "Slave_IO_State"),
            sqlState = text("Replica_SQL_Running_State", "Slave_SQL_Running_State", "Slave_SQL_State"),
            lastIoError = errorText(text("Last_IO_Error"), long("Last_IO_Errno")),
            lastSqlError = errorText(text("Last_SQL_Error", "Last_Error"), long("Last_SQL_Errno", "Last_Errno")),
            retrievedGtidSet = text("Retrieved_Gtid_Set"),
            // MariaDB keeps the position it has applied in Gtid_IO_Pos instead.
            executedGtidSet = text("Executed_Gtid_Set", "Gtid_IO_Pos"),
            sourceLogFile = text("Source_Log_File", "Master_Log_File"),
            readSourceLogPos = long("Read_Source_Log_Pos", "Read_Master_Log_Pos"),
            relayLogFile = text("Relay_Log_File"),
            relayLogPos = long("Relay_Log_Pos"),
            execSourceLogPos = long("Exec_Source_Log_Pos", "Exec_Master_Log_Pos"),
        )
    }

    /** Same, from a result table as the driver returned it. */
    fun parse(table: ResultTable): List<ReplicationChannel> = parse(
        table.rows.map { row ->
            table.columns.indices.associate { index ->
                table.columns[index].label to when (val cell = row.getOrNull(index)) {
                    null, CellValue.Null -> null
                    is CellValue.Text -> cell.value
                    is CellValue.Number -> cell.value
                    is CellValue.Date -> cell.value
                    is CellValue.Bool -> cell.value.toString()
                    is CellValue.Blob -> null
                }
            }
        },
    )

    /**
     * An error text, or null when there is none. The server leaves the text empty and the number 0
     * when all is well; a non-zero number with no text still counts, and says so.
     */
    private fun errorText(text: String?, errno: Long?): String? = when {
        text != null -> text
        errno != null && errno != 0L -> "error $errno"
        else -> null
    }

    fun verdict(channel: ReplicationChannel): ReplicationHealth {
        if (channel.lastError != null) return ReplicationHealth.ERROR
        if (channel.ioThread == ThreadState.STOPPED || channel.sqlThread == ThreadState.STOPPED) {
            return ReplicationHealth.STOPPED
        }
        if (channel.ioThread != ThreadState.RUNNING || channel.sqlThread != ThreadState.RUNNING) {
            // Connecting, or a state this parser does not know: not a verdict anybody should act on.
            return ReplicationHealth.UNKNOWN
        }
        // Running threads with NULL lag: the server cannot say. Calling that OK would be a guess.
        val lag = channel.effectiveLag ?: return ReplicationHealth.UNKNOWN
        return when {
            lag > FAR_BEHIND_SECONDS -> ReplicationHealth.FAR_BEHIND
            lag > LAGGING_SECONDS -> ReplicationHealth.LAGGING
            else -> ReplicationHealth.OK
        }
    }

    private fun severity(health: ReplicationHealth) = when (health) {
        ReplicationHealth.ERROR -> 5
        ReplicationHealth.STOPPED -> 4
        ReplicationHealth.FAR_BEHIND -> 3
        ReplicationHealth.LAGGING -> 2
        ReplicationHealth.UNKNOWN -> 1
        ReplicationHealth.OK -> 0
    }

    /** Worst first, which is the order the cards should read in. */
    fun sortedBySeverity(channels: List<ReplicationChannel>): List<ReplicationChannel> =
        channels.sortedByDescending { severity(it.health) }

    /** Lag as a person reads it: `45 s`, `5 m 3 s`, `2 h 4 m`. */
    fun formatSeconds(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} m ${seconds % 60} s"
        else -> "${seconds / 3600} h ${seconds % 3600 / 60} m"
    }

    /** MySQL error numbers that mean "you may not ask", as opposed to "this syntax is unknown". */
    private val DENIED = setOf(1044, 1045, 1142, 1227)

    fun isAccessDenied(errorCode: Int): Boolean = errorCode in DENIED

    /**
     * Which statements to try, in order. MariaDB's `SHOW ALL SLAVES STATUS` is first there
     * because the plain forms show only the default connection.
     */
    fun statements(version: ServerVersion): List<String> = when {
        version.mariaDb -> listOf("SHOW ALL SLAVES STATUS", "SHOW REPLICA STATUS", "SHOW SLAVE STATUS")
        else -> listOf("SHOW REPLICA STATUS", "SHOW SLAVE STATUS")
    }
}

/** What the replication panel has to say. The non-channel answers are deliberately distinct. */
sealed interface ReplicationReport {
    /** The statement ran and returned nothing: this server is not a replica. */
    data object NotReplica : ReplicationReport

    /** Every attempt was refused for lack of REPLICATION CLIENT (or SUPER on older servers). */
    data object NoPrivilege : ReplicationReport

    data class Channels(val channels: List<ReplicationChannel>, val raw: ResultTable) : ReplicationReport
}
