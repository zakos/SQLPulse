package hu.laurel.sqlpulse.data.schema

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Replication as PostgreSQL and SQL Server report it, mapped onto the cards the Server screen
 * already draws for MySQL ([ReplicationChannel]).
 *
 * Neither has an IO/SQL thread pair, so these channels set `showThreads = false` and carry their
 * own verdict. Pure functions of rows given as column-name to text maps, so every mapping is
 * unit-tested without a server.
 */

private fun Map<String, String?>.text(name: String): String? =
    this[name]?.trim()?.takeIf { it.isNotEmpty() }

private fun Map<String, String?>.seconds(name: String): Double? = text(name)?.toDoubleOrNull()

private fun Map<String, String?>.long(name: String): Long? =
    text(name)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.roundToLong() }

/** A lag as people read it: `3 ms`, `1.2 s`, `4 m 5 s`; a dash when the server did not say. */
internal fun lagText(seconds: Double?): String = when {
    seconds == null -> MetricFormat.ABSENT
    seconds < 1.0 -> "${(seconds * 1000).roundToLong()} ms"
    seconds < 60.0 -> String.format(Locale.ROOT, "%.1f s", seconds)
    else -> MetricFormat.duration(seconds.roundToLong())
}

private fun channel(
    name: String,
    sourceHost: String?,
    sourcePort: Int?,
    sourceUser: String?,
    lagSeconds: Long?,
    state: String?,
    verdict: ReplicationHealth,
    downstream: Boolean,
    details: List<ServerFact>,
    lastError: String? = null,
) = ReplicationChannel(
    name = name,
    sourceHost = sourceHost,
    sourcePort = sourcePort,
    sourceUser = sourceUser,
    ioThread = ThreadState.UNKNOWN,
    sqlThread = ThreadState.UNKNOWN,
    lagSeconds = lagSeconds,
    sqlDelaySeconds = 0,
    remainingDelaySeconds = null,
    ioState = null,
    sqlState = state,
    lastIoError = lastError,
    lastSqlError = null,
    retrievedGtidSet = null,
    executedGtidSet = null,
    sourceLogFile = null,
    readSourceLogPos = null,
    relayLogFile = null,
    relayLogPos = null,
    execSourceLogPos = null,
    showThreads = false,
    verdictOverride = verdict,
    peerIsDownstream = downstream,
    details = details,
)

private fun fact(label: String, value: String?) = value?.let { ServerFact(label, it) }

object PostgresReplication {

    /**
     * A standby in `pg_stat_replication`, seen from the primary: `state` is `streaming` once it
     * has caught up, `catchup` while it is working through backlog, `startup` / `backup` before.
     * `replay_lag` is NULL once the standby has been idle for a while, which is "nothing left to
     * replay", not "unknown" — so a streaming standby with no lag figure counts as in sync.
     */
    fun primary(rows: List<Map<String, String?>>): List<ReplicationChannel> = rows.map { row ->
        val state = row.text("state")
        val replay = row.seconds("replay_lag")
        val verdict = when (state?.lowercase()) {
            "streaming" -> if (replay == null) ReplicationHealth.OK else ReplicationStatus.verdictByLag(replay.roundToLong())
            "catchup" -> ReplicationHealth.LAGGING
            "startup", "backup" -> ReplicationHealth.UNKNOWN
            null -> ReplicationHealth.UNKNOWN
            else -> ReplicationHealth.STOPPED
        }
        channel(
            name = row.text("application_name") ?: row.text("client_addr").orEmpty(),
            sourceHost = row.text("client_addr"),
            sourcePort = row.long("client_port")?.toInt()?.takeIf { it > 0 },
            sourceUser = row.text("usename"),
            lagSeconds = replay?.roundToLong(),
            state = state,
            verdict = verdict,
            downstream = true,
            details = listOfNotNull(
                fact("state", state),
                fact("sync_state", row.text("sync_state")),
                fact("write_lag", lagText(row.seconds("write_lag"))),
                fact("flush_lag", lagText(row.seconds("flush_lag"))),
                fact("replay_lag", lagText(replay)),
                fact("replay_bytes_behind", row.long("replay_bytes")?.let { formatByteSize(it) }),
            ),
        )
    }

    /**
     * This server as a standby: the WAL receiver's status and the replay lag. [row] has `status`,
     * `sender_host`, `sender_port`, `slot_name`, `replay_lag` (0 when everything received has been
     * replayed), `receive_lsn` and `replay_lsn`.
     */
    fun standby(row: Map<String, String?>): ReplicationChannel {
        val status = row.text("status")
        val lag = row.seconds("replay_lag")
        val verdict = when (status?.lowercase()) {
            "streaming" -> if (lag == null) ReplicationHealth.UNKNOWN else ReplicationStatus.verdictByLag(lag.roundToLong())
            "catchup", "starting", "startup", "waiting" -> ReplicationHealth.LAGGING
            null -> ReplicationHealth.UNKNOWN
            else -> ReplicationHealth.STOPPED
        }
        return channel(
            name = row.text("slot_name").orEmpty(),
            sourceHost = row.text("sender_host"),
            sourcePort = row.long("sender_port")?.toInt(),
            sourceUser = null,
            lagSeconds = lag?.roundToLong(),
            state = status,
            verdict = verdict,
            downstream = false,
            details = listOfNotNull(
                fact("status", status),
                fact("receive_lsn", row.text("receive_lsn")),
                fact("replay_lsn", row.text("replay_lsn")),
            ),
        )
    }
}

/** Availability-group replicas from `sys.dm_hadr_database_replica_states`. */
object SqlServerReplication {

    /**
     * One card per (replica, database). `synchronization_health_desc` is the engine's own
     * verdict (HEALTHY, PARTIALLY_HEALTHY, NOT_HEALTHY); a healthy replica whose
     * `secondary_lag_seconds` is large is still flagged by lag. Keys: `replica_server_name`,
     * `database_name`, `is_local`, `synchronization_state_desc`, `synchronization_health_desc`,
     * `log_send_queue_size` (KB), `redo_queue_size` (KB), `secondary_lag_seconds`,
     * `availability_mode_desc`, `last_commit_time`.
     */
    fun parse(rows: List<Map<String, String?>>): List<ReplicationChannel> = rows.map { row ->
        val health = row.text("synchronization_health_desc")?.uppercase()
        val lag = row.long("secondary_lag_seconds")
        val verdict = when (health) {
            "HEALTHY" -> if (lag == null) ReplicationHealth.OK else ReplicationStatus.verdictByLag(lag)
            "PARTIALLY_HEALTHY" -> ReplicationHealth.LAGGING
            "NOT_HEALTHY" -> ReplicationHealth.STOPPED
            else -> ReplicationHealth.UNKNOWN
        }
        val local = row["is_local"]?.trim().let { it == "1" || it.equals("true", ignoreCase = true) }
        channel(
            name = listOfNotNull(row.text("replica_server_name"), row.text("database_name")).joinToString(" / "),
            sourceHost = row.text("replica_server_name"),
            sourcePort = null,
            sourceUser = null,
            lagSeconds = lag,
            state = row.text("synchronization_state_desc"),
            verdict = verdict,
            // From a primary these are the secondaries it feeds; the local row of a secondary is itself.
            downstream = !local,
            details = listOfNotNull(
                fact("health", row.text("synchronization_health_desc")),
                fact("availability_mode", row.text("availability_mode_desc")),
                fact("log_send_queue", row.long("log_send_queue_size")?.let { formatByteSize(it * 1024) }),
                fact("redo_queue", row.long("redo_queue_size")?.let { formatByteSize(it * 1024) }),
                fact("last_commit", row.text("last_commit_time")),
            ),
        )
    }
}

/** Roles written the way `CREATE ROLE` would recreate them, for the read-only grants view. */
object PostgresRoles {

    fun createRole(
        name: String,
        superuser: Boolean,
        inherit: Boolean,
        createRole: Boolean,
        createDb: Boolean,
        login: Boolean,
        replication: Boolean,
        bypassRls: Boolean,
        connectionLimit: Int,
        validUntil: String?,
    ): String {
        val options = buildList {
            add(if (login) "LOGIN" else "NOLOGIN")
            if (superuser) add("SUPERUSER")
            if (createDb) add("CREATEDB")
            if (createRole) add("CREATEROLE")
            if (!inherit) add("NOINHERIT")
            if (replication) add("REPLICATION")
            if (bypassRls) add("BYPASSRLS")
            if (connectionLimit >= 0) add("CONNECTION LIMIT $connectionLimit")
            if (!validUntil.isNullOrBlank()) add("VALID UNTIL '${validUntil.replace("'", "''")}'")
        }
        return "CREATE ROLE \"${name.replace("\"", "\"\"")}\" WITH ${options.joinToString(" ")};"
    }
}
