package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.MetricFormat
import hu.laurel.sqlpulse.data.schema.PostgresPulse
import hu.laurel.sqlpulse.data.schema.PostgresReplication
import hu.laurel.sqlpulse.data.schema.PostgresRoles
import hu.laurel.sqlpulse.data.schema.PulseProfile
import hu.laurel.sqlpulse.data.schema.ReplicationReport
import hu.laurel.sqlpulse.data.schema.ReplicationStatus
import hu.laurel.sqlpulse.data.schema.ServerFact
import hu.laurel.sqlpulse.data.schema.ServerSample
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.schema.SlowStatement
import hu.laurel.sqlpulse.data.schema.SlowStatementsReport
import hu.laurel.sqlpulse.data.sql.ResultTable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * PostgreSQL: `pg_stat_activity`, `pg_blocking_pids`, `pg_stat_replication` / the WAL receiver,
 * `pg_stat_statements`, `pg_roles` and `pg_stat_database`.
 *
 * Everything is read-only apart from [stop], which is a plain `SELECT pg_cancel_backend(pid)` /
 * `pg_terminate_backend(pid)`: the server checks that the caller may signal that backend (same
 * role, or `pg_signal_backend`), so no guard here can be looser than the server's own.
 *
 * Statement text is the SQL; the `SqlText` object below keeps the statements apart from the
 * JDBC plumbing so their shape can be unit-tested without a server.
 */
object PostgresServerCatalog : ServerCatalog {

    override val capabilities = ServerCapabilities(
        killActions = setOf(KillAction.CANCEL, KillAction.TERMINATE),
        idleFilter = true,
        transactionCards = true,
    )

    override val pulse: PulseProfile = PostgresPulse

    /** The statements, as text. Public so the unit tests can pin their shape and the columns the screen reads. */
    object SqlText {

        /**
         * Columns are named for what the process cards read (`Id`, `User`, `Host`, `db`, `Time`,
         * `State`, `Info`). `Time` is how long the current statement has run for an active
         * session, and how long it has sat in its state otherwise: an hour of "idle in
         * transaction" is the thing to see.
         */
        fun processList(includeIdle: Boolean): String = """
            SELECT pid AS "Id",
                   usename AS "User",
                   host(client_addr) AS "Host",
                   datname AS "db",
                   COALESCE(EXTRACT(EPOCH FROM (now() - CASE WHEN state = 'active' THEN query_start ELSE state_change END))::bigint, 0) AS "Time",
                   concat_ws(' · ', state, CASE WHEN state = 'active' THEN NULLIF(concat_ws(':', wait_event_type, wait_event), '') END) AS "State",
                   query AS "Info"
            FROM pg_stat_activity
            WHERE backend_type = 'client backend'
              AND pid <> pg_backend_pid()
              ${if (includeIdle) "" else "AND state IS DISTINCT FROM 'idle'"}
            ORDER BY (state = 'active') DESC NULLS LAST, "Time" DESC
            LIMIT ?
        """.trimIndent()

        val transactions = """
            SELECT pid AS "Id",
                   usename AS "User",
                   state AS "State",
                   EXTRACT(EPOCH FROM (now() - xact_start))::bigint AS "Seconds",
                   datname AS "db",
                   query AS "Query"
            FROM pg_stat_activity
            WHERE xact_start IS NOT NULL
              AND backend_type = 'client backend'
              AND pid <> pg_backend_pid()
            ORDER BY xact_start
            LIMIT ?
        """.trimIndent()

        /** One row per (waiting, blocking) pair; `pg_blocking_pids` is the server's own answer. */
        val lockWaits = """
            SELECT w.pid AS "WaitingId",
                   w.query AS "WaitingQuery",
                   b.pid AS "BlockingId",
                   b.query AS "BlockingQuery",
                   EXTRACT(EPOCH FROM (now() - w.state_change))::bigint AS "WaitSeconds"
            FROM pg_stat_activity w
            CROSS JOIN LATERAL unnest(pg_blocking_pids(w.pid)) AS blocker(pid)
            JOIN pg_stat_activity b ON b.pid = blocker.pid
            ORDER BY "WaitSeconds" DESC
            LIMIT ?
        """.trimIndent()

        val primaryReplication = """
            SELECT application_name, host(client_addr) AS client_addr, client_port, usename, state, sync_state,
                   EXTRACT(EPOCH FROM write_lag) AS write_lag,
                   EXTRACT(EPOCH FROM flush_lag) AS flush_lag,
                   EXTRACT(EPOCH FROM replay_lag) AS replay_lag,
                   pg_wal_lsn_diff(sent_lsn, replay_lsn)::bigint AS replay_bytes
            FROM pg_stat_replication
            ORDER BY application_name, pid
        """.trimIndent()

        /**
         * A standby's view. The lag is "now minus the commit time of the last replayed
         * transaction", which on an idle primary grows forever although nothing is missing — so
         * when everything received has been replayed the lag is reported as 0.
         */
        fun standbyReplication(withSenderHost: Boolean) = """
            SELECT r.status AS status,
                   ${if (withSenderHost) "r.sender_host" else "NULL"} AS sender_host,
                   ${if (withSenderHost) "r.sender_port" else "NULL"} AS sender_port,
                   r.slot_name AS slot_name,
                   CASE WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0
                        ELSE EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp())) END AS replay_lag,
                   pg_last_wal_receive_lsn()::text AS receive_lsn,
                   pg_last_wal_replay_lsn()::text AS replay_lsn
            FROM (SELECT 1) one
            LEFT JOIN pg_stat_wal_receiver r ON true
        """.trimIndent()

        /** The column names changed in 13 (`total_time` became `total_exec_time`). */
        fun slow(sort: SlowSort, version13: Boolean, limit: Int): String {
            val total = if (version13) "total_exec_time" else "total_time"
            val mean = if (version13) "mean_exec_time" else "mean_time"
            val order = when (sort) {
                SlowSort.TOTAL -> "s.$total"
                SlowSort.AVERAGE -> "s.$mean"
                SlowSort.COUNT -> "s.calls"
            }
            return """
                SELECT s.query, d.datname, s.calls, s.$total, s.$mean, s.rows,
                       s.shared_blks_hit + s.shared_blks_read
                FROM pg_stat_statements s
                LEFT JOIN pg_database d ON d.oid = s.dbid
                WHERE s.query IS NOT NULL
                ORDER BY $order DESC
                LIMIT ${limit.coerceIn(1, 100)}
            """.trimIndent()
        }

        val users = """
            SELECT rolname AS "Account",
                   CASE WHEN rolcanlogin THEN 'login' ELSE 'group' END AS "Kind",
                   rolsuper AS "Superuser",
                   rolcreatedb AS "CreateDB",
                   rolcreaterole AS "CreateRole",
                   rolreplication AS "Replication",
                   rolvaliduntil AS "ValidUntil"
            FROM pg_roles
            WHERE rolname !~ '^pg_'
            ORDER BY rolcanlogin DESC, rolname
            LIMIT ?
        """.trimIndent()

        /** One reading: counters summed over all databases, plus counts of sessions. */
        val sample = """
            SELECT EXTRACT(EPOCH FROM (now() - pg_postmaster_start_time()))::bigint AS uptime,
                   current_setting('max_connections')::bigint AS max_connections,
                   a.connections, a.active, a.idle_in_xact,
                   d.xact_commit, d.xact_rollback, d.blks_read, d.blks_hit,
                   d.tup_returned, d.tup_fetched, d.tup_inserted, d.tup_updated, d.tup_deleted, d.deadlocks,
                   CASE WHEN pg_is_in_recovery() THEN
                        (CASE WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0
                              ELSE EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp())) END)::bigint
                   END AS replay_lag
            FROM (SELECT count(*) AS connections,
                         count(*) FILTER (WHERE state = 'active' AND pid <> pg_backend_pid()) AS active,
                         count(*) FILTER (WHERE state LIKE 'idle in transaction%') AS idle_in_xact
                  FROM pg_stat_activity WHERE backend_type = 'client backend') a,
                 (SELECT COALESCE(sum(xact_commit), 0)::bigint AS xact_commit,
                         COALESCE(sum(xact_rollback), 0)::bigint AS xact_rollback,
                         COALESCE(sum(blks_read), 0)::bigint AS blks_read,
                         COALESCE(sum(blks_hit), 0)::bigint AS blks_hit,
                         COALESCE(sum(tup_returned), 0)::bigint AS tup_returned,
                         COALESCE(sum(tup_fetched), 0)::bigint AS tup_fetched,
                         COALESCE(sum(tup_inserted), 0)::bigint AS tup_inserted,
                         COALESCE(sum(tup_updated), 0)::bigint AS tup_updated,
                         COALESCE(sum(tup_deleted), 0)::bigint AS tup_deleted,
                         COALESCE(sum(deadlocks), 0)::bigint AS deadlocks
                  FROM pg_stat_database) d
        """.trimIndent()

        /** The role's attributes, for the grants view. */
        const val ROLE = """
            SELECT rolname, rolsuper, rolinherit, rolcreaterole, rolcreatedb, rolcanlogin,
                   rolreplication, rolbypassrls, rolconnlimit, rolvaliduntil::text
            FROM pg_roles WHERE rolname = ?
        """

        const val MEMBERSHIPS = """
            SELECT g.rolname, m.admin_option
            FROM pg_auth_members m
            JOIN pg_roles g ON g.oid = m.roleid
            JOIN pg_roles r ON r.oid = m.member
            WHERE r.rolname = ?
            ORDER BY g.rolname
        """

        const val SCHEMA_PRIVILEGES = """
            SELECT n.nspname,
                   has_schema_privilege(?, n.oid, 'USAGE') AS usage,
                   has_schema_privilege(?, n.oid, 'CREATE') AS create_priv
            FROM pg_namespace n
            WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
            ORDER BY n.nspname
        """

        const val DATABASE_PRIVILEGES = """
            SELECT current_database(),
                   has_database_privilege(?, current_database(), 'CONNECT'),
                   has_database_privilege(?, current_database(), 'CREATE'),
                   has_database_privilege(?, current_database(), 'TEMPORARY')
        """
    }

    override fun processList(connection: Connection, includeIdle: Boolean, limit: Int): ResultTable =
        table(connection, SqlText.processList(includeIdle)) { setInt(1, limit) }

    override fun stop(connection: Connection, id: Long, action: KillAction): Boolean {
        if (id !in 1..Int.MAX_VALUE) return false
        if (id == ownPid(connection)) throw OwnSessionException(id)
        // The function name comes from a closed enum; the pid is bound.
        val function = if (action == KillAction.CANCEL) "pg_cancel_backend" else "pg_terminate_backend"
        return connection.prepareStatement("SELECT $function(?)").use { statement ->
            statement.setInt(1, id.toInt())
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
    }

    override fun transactions(connection: Connection, limit: Int): ResultTable =
        table(connection, SqlText.transactions) { setInt(1, limit) }

    override fun lockWaits(connection: Connection, limit: Int): ResultTable =
        table(connection, SqlText.lockWaits) { setInt(1, limit) }

    /**
     * On a primary: one card per standby from `pg_stat_replication`. On a standby: one card for
     * its WAL receiver. A primary with no standbys answers "not a replica" — except that without
     * `pg_read_all_stats` the view shows rows with every column blanked, which is "not allowed",
     * not "nobody is connected".
     */
    override fun replication(connection: Connection): ReplicationReport {
        val inRecovery = scalarBoolean(connection, "SELECT pg_is_in_recovery()")
        if (inRecovery) {
            val sql = SqlText.standbyReplication(serverVersionNum(connection) >= 110_000)
            val row = maps(connection, sql).firstOrNull { it["status"] != null }
                ?: return ReplicationReport.NotReplica
            return report(listOf(PostgresReplication.standby(row)), connection, sql)
        }
        val rows = maps(connection, SqlText.primaryReplication)
        if (rows.isEmpty()) {
            return if (canMonitor(connection)) ReplicationReport.NotReplica else ReplicationReport.NoPrivilege
        }
        if (!canMonitor(connection)) return ReplicationReport.NoPrivilege
        return report(PostgresReplication.primary(rows), connection, SqlText.primaryReplication)
    }

    private fun report(
        channels: List<hu.laurel.sqlpulse.data.schema.ReplicationChannel>,
        connection: Connection,
        rawSql: String,
    ): ReplicationReport.Channels = ReplicationReport.Channels(
        ReplicationStatus.sortedBySeverity(channels),
        table(connection, rawSql, MAX_CHANNELS) {},
    )

    override fun slowStatements(connection: Connection, sort: SlowSort): SlowStatementsReport {
        val installed = scalarBoolean(
            connection,
            "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements')",
        )
        if (!installed) return SlowStatementsReport.ExtensionMissing
        val sql = SqlText.slow(sort, serverVersionNum(connection) >= 130_000, SLOW_LIMIT)
        return try {
            val statements = connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                SlowStatement(
                                    digestText = rows.getString(1).orEmpty(),
                                    schema = rows.getString(2)?.takeIf { it.isNotBlank() },
                                    count = rows.getLong(3),
                                    totalPicos = millisToPicos(rows.getDouble(4)),
                                    avgPicos = millisToPicos(rows.getDouble(5)),
                                    rowsExamined = null,
                                    rowsSent = rows.getLong(6),
                                    noIndexUsed = 0,
                                    noGoodIndexUsed = 0,
                                    firstSeen = null,
                                    lastSeen = null,
                                    blocksRead = rows.getLong(7),
                                    dollarPlaceholders = true,
                                ),
                            )
                        }
                    }
                }
            }
            SlowStatementsReport.Rows(statements, sort)
        } catch (e: SQLException) {
            when (e.sqlState) {
                // object_not_in_prerequisite_state: "must be loaded via shared_preload_libraries".
                "55000" -> SlowStatementsReport.ExtensionNotLoaded
                "42501" -> SlowStatementsReport.NoPrivilege
                "42P01" -> SlowStatementsReport.ExtensionMissing
                else -> throw e
            }
        }
    }

    override fun users(connection: Connection, limit: Int): ResultTable =
        table(connection, SqlText.users) { setInt(1, limit) }

    /**
     * Roles have no single GRANT listing, so this is assembled from what the catalog says: the
     * role's attributes written as the `CREATE ROLE` that would recreate them, its memberships,
     * and its rights on the current database and its schemas. Per-table rights are left out —
     * there can be thousands — and read as `has_table_privilege` in the editor when needed.
     */
    override fun grants(connection: Connection, account: String): List<String> {
        val lines = mutableListOf<String>()
        val role = account
        connection.prepareStatement(SqlText.ROLE).use { statement ->
            statement.setString(1, role)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    lines += PostgresRoles.createRole(
                        name = rows.getString(1),
                        superuser = rows.getBoolean(2),
                        inherit = rows.getBoolean(3),
                        createRole = rows.getBoolean(4),
                        createDb = rows.getBoolean(5),
                        login = rows.getBoolean(6),
                        replication = rows.getBoolean(7),
                        bypassRls = rows.getBoolean(8),
                        connectionLimit = rows.getInt(9),
                        validUntil = rows.getString(10),
                    )
                }
            }
        }
        if (lines.isEmpty()) return emptyList()
        connection.prepareStatement(SqlText.MEMBERSHIPS).use { statement ->
            statement.setString(1, role)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    lines += "GRANT ${q(rows.getString(1))} TO ${q(role)}" +
                        if (rows.getBoolean(2)) " WITH ADMIN OPTION;" else ";"
                }
            }
        }
        connection.prepareStatement(SqlText.DATABASE_PRIVILEGES).use { statement ->
            repeat(3) { statement.setString(it + 1, role) }
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    val granted = listOf("CONNECT", "CREATE", "TEMPORARY")
                        .filterIndexed { index, _ -> rows.getBoolean(index + 2) }
                    if (granted.isNotEmpty()) {
                        lines += "GRANT ${granted.joinToString(", ")} ON DATABASE ${q(rows.getString(1))} TO ${q(role)};"
                    }
                }
            }
        }
        connection.prepareStatement(SqlText.SCHEMA_PRIVILEGES).use { statement ->
            statement.setString(1, role)
            statement.setString(2, role)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val granted = listOfNotNull(
                        "USAGE".takeIf { rows.getBoolean(2) },
                        "CREATE".takeIf { rows.getBoolean(3) },
                    )
                    if (granted.isNotEmpty()) {
                        lines += "GRANT ${granted.joinToString(", ")} ON SCHEMA ${q(rows.getString(1))} TO ${q(role)};"
                    }
                }
            }
        }
        return lines
    }

    override fun overview(connection: Connection): List<ServerFact> {
        val facts = mutableListOf<ServerFact>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT current_setting('server_version') AS version,
                       current_database() AS database,
                       current_user AS current_user_name,
                       current_setting('max_connections') AS max_connections,
                       (SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend') AS connections,
                       pg_is_in_recovery() AS in_recovery,
                       current_setting('TimeZone') AS time_zone,
                       current_setting('server_encoding') AS charset,
                       EXTRACT(EPOCH FROM (now() - pg_postmaster_start_time()))::bigint AS uptime
                """.trimIndent(),
            ).use { rows ->
                if (rows.next()) facts += overviewFacts(rows)
            }
        }
        // This session's own TLS state, the way MySQL's Ssl_version / Ssl_cipher show it.
        // pg_stat_ssl is readable by everyone for their own backend.
        runCatching {
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT ssl, version, cipher FROM pg_stat_ssl WHERE pid = pg_backend_pid()",
                ).use { rows ->
                    if (rows.next()) {
                        val on = rows.getBoolean(1)
                        facts += ServerFact("Ssl_version", if (on) rows.getString(2).orEmpty() else "—")
                        facts += ServerFact("Ssl_cipher", if (on) rows.getString(3).orEmpty() else "—")
                    }
                }
            }
        }
        return facts
    }

    private fun overviewFacts(rows: ResultSet): List<ServerFact> = listOf(
        ServerFact("version", rows.getString("version").orEmpty()),
        ServerFact("database", rows.getString("database").orEmpty()),
        ServerFact("current_user", rows.getString("current_user_name").orEmpty()),
        ServerFact("role", if (rows.getBoolean("in_recovery")) "standby" else "primary"),
        ServerFact("max_connections", rows.getString("max_connections").orEmpty()),
        ServerFact("connections", rows.getString("connections").orEmpty()),
        ServerFact("uptime", MetricFormat.duration(rows.getLong("uptime"))),
        ServerFact("time_zone", rows.getString("time_zone").orEmpty()),
        ServerFact("charset", rows.getString("charset").orEmpty()),
    )

    override fun sample(connection: Connection): ServerSample =
        connection.createStatement().use { statement ->
            statement.executeQuery(SqlText.sample).use { rows ->
                check(rows.next()) { "pg_stat_database returned nothing" }
                val values = mutableMapOf<String, Long>()
                val names = listOf("max_connections", "connections", "active", "idle_in_xact") +
                    PostgresPulse.COUNTERS
                for (name in names) {
                    val value = rows.getLong(name)
                    if (!rows.wasNull()) values[name] = value
                }
                val lag = rows.getLong("replay_lag").takeIf { !rows.wasNull() }
                ServerSample(
                    takenAtMs = System.currentTimeMillis(),
                    uptimeSeconds = rows.getLong("uptime"),
                    values = values,
                    replicationLagSeconds = lag,
                )
            }
        }

    // ------------------------------------------------------------------ helpers

    private fun ownPid(connection: Connection): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows -> rows.next(); rows.getLong(1) }
        }

    private fun serverVersionNum(connection: Connection): Int =
        runCatching {
            connection.createStatement().use { statement ->
                statement.executeQuery("SHOW server_version_num").use { rows ->
                    if (rows.next()) rows.getString(1).toInt() else 0
                }
            }
        }.getOrDefault(0)

    /** Whether the role sees other sessions' replication details (superuser, or `pg_read_all_stats`). */
    private fun canMonitor(connection: Connection): Boolean =
        runCatching {
            scalarBoolean(connection, "SELECT pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER')")
        }.getOrDefault(true)

    private fun scalarBoolean(connection: Connection, sql: String): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> rows.next() && rows.getBoolean(1) }
        }

    private fun table(
        connection: Connection,
        sql: String,
        limit: Int = Int.MAX_VALUE,
        bind: PreparedStatement.() -> Unit,
    ): ResultTable = connection.prepareStatement(sql).use { statement ->
        statement.bind()
        statement.executeQuery().use { rows -> ResultTable.from(rows, limit) }
    }

    private fun maps(connection: Connection, sql: String): List<Map<String, String?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                val meta = rows.metaData
                buildList {
                    while (rows.next()) {
                        add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rows.getString(it) })
                    }
                }
            }
        }

    private fun q(name: String) = PostgresDialect.quoteIdentifier(name)

    private fun millisToPicos(millis: Double): Long = (millis * 1_000_000_000.0).toLong()

    private const val MAX_CHANNELS = 64
    private const val SLOW_LIMIT = 25
}
