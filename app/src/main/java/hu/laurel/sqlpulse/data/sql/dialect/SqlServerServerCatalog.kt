package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.MetricFormat
import hu.laurel.sqlpulse.data.schema.PulseProfile
import hu.laurel.sqlpulse.data.schema.ReplicationReport
import hu.laurel.sqlpulse.data.schema.ReplicationStatus
import hu.laurel.sqlpulse.data.schema.ServerFact
import hu.laurel.sqlpulse.data.schema.ServerSample
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.schema.SlowStatement
import hu.laurel.sqlpulse.data.schema.SlowStatementsReport
import hu.laurel.sqlpulse.data.schema.SqlServerPulse
import hu.laurel.sqlpulse.data.schema.SqlServerReplication
import hu.laurel.sqlpulse.data.sql.ResultTable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * SQL Server and Azure SQL: the `sys.dm_exec_*` views, `sys.dm_tran_*`, `sys.dm_hadr_*`,
 * `sys.dm_os_performance_counters` and `sys.server_principals`.
 *
 * Almost all of them need `VIEW SERVER STATE` (`VIEW DATABASE STATE` on Azure SQL Database). Where
 * the account lacks it the server answers error 300 (or 297/229/262), which becomes a
 * [MissingPrivilegeException] naming the permission — or, for the two panels that have a
 * dedicated "not allowed" state, [ReplicationReport.NoPrivilege] / [SlowStatementsReport.NoPrivilege]
 * — never a bare failure.
 *
 * T-SQL has no way to cancel a statement from another session: `KILL` ends the session and rolls
 * its transaction back, so [KillAction.TERMINATE] is the only action offered.
 */
object SqlServerServerCatalog : ServerCatalog {

    const val VIEW_SERVER_STATE = "VIEW SERVER STATE"

    override val capabilities = ServerCapabilities(
        killActions = setOf(KillAction.TERMINATE),
        idleFilter = true,
        transactionCards = true,
    )

    override val pulse: PulseProfile = SqlServerPulse

    /** The statements, as text, so unit tests can pin their shape and the columns the screens read. */
    object SqlText {

        /** The text of the statement a request is running, cut out of its batch by the offsets. */
        private const val STATEMENT_OF_REQUEST = """
            SUBSTRING(t.text, r.statement_start_offset / 2 + 1,
                      (CASE WHEN r.statement_end_offset = -1 THEN DATALENGTH(t.text)
                            ELSE r.statement_end_offset END - r.statement_start_offset) / 2 + 1)
        """

        /**
         * Sessions as the process cards read them. `Time` is how long the running request has taken,
         * or how long a sleeping session has been idle; the text of a sleeping session is its last
         * statement (from the connection), which is what an idle-in-transaction session was doing.
         */
        fun processList(includeIdle: Boolean): String = """
            SELECT TOP (?) s.session_id AS Id,
                   s.login_name AS [User],
                   s.host_name AS Host,
                   DB_NAME(COALESCE(r.database_id, s.database_id)) AS db,
                   COALESCE(r.total_elapsed_time / 1000,
                            DATEDIFF(SECOND, s.last_request_end_time, GETDATE())) AS [Time],
                   COALESCE(r.status, s.status) + COALESCE(' · ' + r.wait_type, '') AS State,
                   CASE WHEN r.session_id IS NULL THEN t.text ELSE $STATEMENT_OF_REQUEST END AS Info
            FROM sys.dm_exec_sessions s
            LEFT JOIN sys.dm_exec_requests r ON r.session_id = s.session_id
            LEFT JOIN sys.dm_exec_connections c ON c.session_id = s.session_id
            OUTER APPLY sys.dm_exec_sql_text(COALESCE(r.sql_handle, c.most_recent_sql_handle)) t
            WHERE s.is_user_process = 1
              AND s.session_id <> @@SPID
              ${if (includeIdle) "" else "AND r.session_id IS NOT NULL"}
            ORDER BY CASE WHEN r.session_id IS NULL THEN 1 ELSE 0 END, [Time] DESC
        """.trimIndent()

        /**
         * Sessions holding a transaction open. A sleeping one (no request) is reported as
         * `idle in transaction`, the same words PostgreSQL uses, so the screen highlights both alike.
         */
        val transactions = """
            SELECT TOP (?) st.session_id AS Id,
                   s.login_name AS [User],
                   CASE WHEN r.session_id IS NULL THEN 'idle in transaction' ELSE 'active' END AS State,
                   DATEDIFF(SECOND, at.transaction_begin_time, GETDATE()) AS Seconds,
                   DB_NAME(s.database_id) AS db,
                   CASE WHEN r.session_id IS NULL THEN t.text ELSE $STATEMENT_OF_REQUEST END AS Query
            FROM sys.dm_tran_session_transactions st
            JOIN sys.dm_tran_active_transactions at ON at.transaction_id = st.transaction_id
            JOIN sys.dm_exec_sessions s ON s.session_id = st.session_id
            LEFT JOIN sys.dm_exec_requests r ON r.session_id = st.session_id
            LEFT JOIN sys.dm_exec_connections c ON c.session_id = st.session_id
            OUTER APPLY sys.dm_exec_sql_text(COALESCE(r.sql_handle, c.most_recent_sql_handle)) t
            WHERE s.is_user_process = 1
              AND st.is_user_transaction = 1
              AND st.session_id <> @@SPID
            ORDER BY at.transaction_begin_time
        """.trimIndent()

        /** `blocking_session_id` is the engine's own answer; negative values are special markers, not sessions. */
        val lockWaits = """
            SELECT TOP (?) r.session_id AS WaitingId,
                   $STATEMENT_OF_REQUEST AS WaitingQuery,
                   r.blocking_session_id AS BlockingId,
                   bt.text AS BlockingQuery,
                   r.wait_time / 1000 AS WaitSeconds,
                   r.wait_type AS WaitType
            FROM sys.dm_exec_requests r
            OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t
            LEFT JOIN sys.dm_exec_connections bc ON bc.session_id = r.blocking_session_id
            OUTER APPLY sys.dm_exec_sql_text(bc.most_recent_sql_handle) bt
            WHERE r.blocking_session_id > 0
            ORDER BY r.wait_time DESC
        """.trimIndent()

        const val HADR_ENABLED = "SELECT CAST(SERVERPROPERTY('IsHadrEnabled') AS int)"

        /** `secondary_lag_seconds` arrived in SQL Server 2016 (major version 13). */
        fun replication(hasLag: Boolean): String = """
            SELECT ar.replica_server_name,
                   DB_NAME(rs.database_id) AS database_name,
                   rs.is_local,
                   rs.synchronization_state_desc,
                   rs.synchronization_health_desc,
                   rs.log_send_queue_size,
                   rs.redo_queue_size,
                   ${if (hasLag) "rs.secondary_lag_seconds" else "NULL"} AS secondary_lag_seconds,
                   ar.availability_mode_desc,
                   CONVERT(varchar(19), rs.last_commit_time, 120) AS last_commit_time
            FROM sys.dm_hadr_database_replica_states rs
            JOIN sys.availability_replicas ar ON ar.replica_id = rs.replica_id AND ar.group_id = rs.group_id
            ORDER BY ar.replica_server_name, database_name
        """.trimIndent()

        /**
         * Statements grouped by `query_hash` (a plan recompiled many times is still one statement),
         * with the text of one of them. Elapsed times are microseconds. The app's own query against
         * this view is left out.
         */
        fun slow(sort: SlowSort, limit: Int = SLOW_LIMIT): String {
            val order = when (sort) {
                SlowSort.TOTAL -> "total_elapsed"
                SlowSort.AVERAGE -> "avg_elapsed"
                SlowSort.COUNT -> "executions"
            }
            return """
                WITH q AS (
                    SELECT SUM(qs.total_elapsed_time) AS total_elapsed,
                           SUM(qs.execution_count) AS executions,
                           SUM(qs.total_logical_reads) AS logical_reads,
                           SUM(qs.total_rows) AS total_rows,
                           MIN(qs.creation_time) AS first_seen,
                           MAX(qs.last_execution_time) AS last_seen,
                           MAX(SUBSTRING(st.text, qs.statement_start_offset / 2 + 1,
                                (CASE WHEN qs.statement_end_offset = -1 THEN DATALENGTH(st.text)
                                      ELSE qs.statement_end_offset END - qs.statement_start_offset) / 2 + 1)) AS statement_text,
                           MAX(DB_NAME(st.dbid)) AS db
                    FROM sys.dm_exec_query_stats qs
                    CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
                    WHERE st.text NOT LIKE '%dm_exec_query_stats%'
                    GROUP BY COALESCE(qs.query_hash, qs.sql_handle)
                )
                SELECT TOP (${limit.coerceIn(1, 1_000)}) statement_text, db, executions, total_elapsed,
                       total_elapsed / NULLIF(executions, 0) AS avg_elapsed,
                       total_rows, logical_reads,
                       CONVERT(varchar(19), first_seen, 120) AS first_seen,
                       CONVERT(varchar(19), last_seen, 120) AS last_seen
                FROM q
                ORDER BY $order DESC
            """.trimIndent()
        }

        val users = """
            SELECT TOP (?) name AS Account,
                   type_desc AS Kind,
                   is_disabled AS Disabled,
                   default_database_name AS DefaultDb,
                   CONVERT(varchar(19), create_date, 120) AS Created
            FROM sys.server_principals
            WHERE type IN ('S', 'U', 'G', 'E', 'X', 'R') AND name NOT LIKE '##%'
            ORDER BY CASE WHEN type = 'R' THEN 1 ELSE 0 END, name
        """.trimIndent()

        const val SERVER_ROLES = """
            SELECT r.name
            FROM sys.server_role_members m
            JOIN sys.server_principals r ON r.principal_id = m.role_principal_id
            JOIN sys.server_principals p ON p.principal_id = m.member_principal_id
            WHERE p.name = ?
            ORDER BY r.name
        """

        const val SERVER_PERMISSIONS = """
            SELECT sp.state_desc, sp.permission_name
            FROM sys.server_permissions sp
            JOIN sys.server_principals p ON p.principal_id = sp.grantee_principal_id
            WHERE p.name = ?
            ORDER BY sp.permission_name
        """

        /** The database user the login maps to in the current database, if any. */
        const val DATABASE_USER = """
            SELECT dp.principal_id, dp.name
            FROM sys.database_principals dp
            JOIN sys.server_principals sp ON sp.sid = dp.sid
            WHERE sp.name = ?
        """

        const val DATABASE_ROLES = """
            SELECT r.name
            FROM sys.database_role_members m
            JOIN sys.database_principals r ON r.principal_id = m.role_principal_id
            WHERE m.member_principal_id = ?
            ORDER BY r.name
        """

        const val DATABASE_PERMISSIONS = """
            SELECT TOP (200) p.state_desc, p.permission_name, p.class_desc,
                   s.name AS schema_name, o.name AS object_name
            FROM sys.database_permissions p
            LEFT JOIN sys.objects o ON p.class = 1 AND o.object_id = p.major_id
            LEFT JOIN sys.schemas s ON s.schema_id = o.schema_id
            WHERE p.grantee_principal_id = ?
            ORDER BY p.class, s.name, o.name, p.permission_name
        """

        /**
         * One row per value, keyed: the cumulative "/sec" counters (deltas are taken on the phone),
         * the instantaneous ones, the count of blocked requests and the uptime. `LIKE` on the
         * object name because a named instance prefixes it `MSSQL$NAME:` instead of `SQLServer:`.
         */
        val sample = """
            SELECT k, v FROM (
                SELECT CASE
                    WHEN RTRIM(counter_name) = 'Batch Requests/sec' AND object_name LIKE '%:SQL Statistics%' THEN 'batch_requests'
                    WHEN RTRIM(counter_name) = 'User Connections' AND object_name LIKE '%:General Statistics%' THEN 'user_connections'
                    WHEN RTRIM(counter_name) = 'Page life expectancy' AND object_name LIKE '%:Buffer Manager%' THEN 'page_life_expectancy'
                    WHEN RTRIM(counter_name) = 'Buffer cache hit ratio' AND object_name LIKE '%:Buffer Manager%' THEN 'cache_hit'
                    WHEN RTRIM(counter_name) = 'Buffer cache hit ratio base' AND object_name LIKE '%:Buffer Manager%' THEN 'cache_hit_base'
                    WHEN RTRIM(counter_name) = 'Transactions/sec' AND RTRIM(instance_name) = '_Total' AND object_name LIKE '%:Databases%' THEN 'transactions'
                    WHEN RTRIM(counter_name) = 'Lock Waits/sec' AND RTRIM(instance_name) = '_Total' AND object_name LIKE '%:Locks%' THEN 'lock_waits'
                END AS k, cntr_value AS v
                FROM sys.dm_os_performance_counters
            ) counters WHERE k IS NOT NULL
            UNION ALL SELECT 'blocked', COUNT_BIG(*) FROM sys.dm_exec_requests WHERE blocking_session_id > 0
            UNION ALL SELECT 'uptime', DATEDIFF(SECOND, sqlserver_start_time, SYSDATETIME()) FROM sys.dm_os_sys_info
        """.trimIndent()
    }

    /** Error numbers meaning "not permitted": 300 (VIEW SERVER STATE), 297, 229 and 262. */
    private val DENIED = setOf(300, 297, 229, 262)

    fun isPermissionDenied(error: SQLException): Boolean = error.errorCode in DENIED

    private inline fun <T> requiring(permission: String, block: () -> T): T =
        try {
            block()
        } catch (e: SQLException) {
            if (isPermissionDenied(e)) throw MissingPrivilegeException(permission, e) else throw e
        }

    override fun processList(connection: Connection, includeIdle: Boolean, limit: Int): ResultTable =
        requiring(VIEW_SERVER_STATE) {
            table(connection, SqlText.processList(includeIdle)) { setInt(1, limit) }
        }

    override fun stop(connection: Connection, id: Long, action: KillAction): Boolean {
        require(action == KillAction.TERMINATE) { "SQL Server can only end a whole session" }
        if (id !in 1..Short.MAX_VALUE) return false
        val own = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT @@SPID").use { rows -> rows.next(); rows.getLong(1) }
        }
        if (own == id) throw OwnSessionException(id)
        return try {
            // KILL takes a literal; a Long interpolated is only ever digits.
            connection.createStatement().use { it.execute("KILL $id") }
            true
        } catch (e: SQLException) {
            // 6106: "Process ID n is not an active process ID" — it ended a moment ago.
            if (e.errorCode == 6106) false else throw e
        }
    }

    override fun transactions(connection: Connection, limit: Int): ResultTable =
        requiring(VIEW_SERVER_STATE) { table(connection, SqlText.transactions) { setInt(1, limit) } }

    override fun lockWaits(connection: Connection, limit: Int): ResultTable =
        requiring(VIEW_SERVER_STATE) { table(connection, SqlText.lockWaits) { setInt(1, limit) } }

    /**
     * Availability-group state if AGs are enabled. A server without them answers "not a replica"
     * (the DMV exists but is empty); an account without VIEW SERVER STATE gets "not allowed".
     * Log shipping and transactional replication are not covered.
     */
    override fun replication(connection: Connection): ReplicationReport = try {
        val enabled = connection.createStatement().use { statement ->
            statement.executeQuery(SqlText.HADR_ENABLED).use { rows -> rows.next() && rows.getInt(1) == 1 }
        }
        if (!enabled) {
            ReplicationReport.NotReplica
        } else {
            val sql = SqlText.replication(majorVersion(connection) >= 13)
            val maps = connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    val meta = rows.metaData
                    buildList {
                        while (rows.next()) {
                            add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rows.getString(it) })
                        }
                    }
                }
            }
            if (maps.isEmpty()) {
                ReplicationReport.NotReplica
            } else {
                ReplicationReport.Channels(
                    ReplicationStatus.sortedBySeverity(SqlServerReplication.parse(maps)),
                    table(connection, sql) {},
                )
            }
        }
    } catch (e: SQLException) {
        if (isPermissionDenied(e)) ReplicationReport.NoPrivilege else throw e
    }

    override fun slowStatements(connection: Connection, sort: SlowSort): SlowStatementsReport =
        slowStatements(connection, sort, SLOW_LIMIT)

    /** [limit] is the page size; the screen asks for the default, a test for more than a busy server's top. */
    internal fun slowStatements(connection: Connection, sort: SlowSort, limit: Int): SlowStatementsReport = try {
        val statements = connection.createStatement().use { statement ->
            statement.executeQuery(SqlText.slow(sort, limit)).use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            SlowStatement(
                                digestText = rows.getString(1).orEmpty().trim(),
                                schema = rows.getString(2)?.takeIf { it.isNotBlank() },
                                count = rows.getLong(3),
                                totalPicos = rows.getLong(4) * MICROS_TO_PICOS,
                                avgPicos = rows.getLong(5) * MICROS_TO_PICOS,
                                rowsExamined = null,
                                rowsSent = rows.getLong(6),
                                noIndexUsed = 0,
                                noGoodIndexUsed = 0,
                                firstSeen = rows.getString(8),
                                lastSeen = rows.getString(9),
                                blocksRead = rows.getLong(7),
                            ),
                        )
                    }
                }
            }
        }
        SlowStatementsReport.Rows(statements, sort)
    } catch (e: SQLException) {
        if (isPermissionDenied(e)) SlowStatementsReport.NoPrivilege else throw e
    }

    override fun users(connection: Connection, limit: Int): ResultTable =
        table(connection, SqlText.users) { setInt(1, limit) }

    /**
     * Server roles and permissions of the login, then — in the current database — the roles and
     * permissions of the user it maps to. Written as the `GRANT` / `ALTER ROLE` statements that
     * would recreate them; nothing here can run one.
     */
    override fun grants(connection: Connection, account: String): List<String> {
        val lines = mutableListOf<String>()
        val login = bracket(account)
        strings(connection, SqlText.SERVER_ROLES, account).forEach {
            lines += "ALTER SERVER ROLE ${bracket(it)} ADD MEMBER $login;"
        }
        connection.prepareStatement(SqlText.SERVER_PERMISSIONS).use { statement ->
            statement.setString(1, account)
            statement.executeQuery().use { rows ->
                while (rows.next()) lines += permissionLine(rows.getString(1), rows.getString(2), "", login)
            }
        }
        // The database half is best effort: the login may have no user here, or the metadata
        // may be hidden from this account.
        runCatching {
            val user = connection.prepareStatement(SqlText.DATABASE_USER).use { statement ->
                statement.setString(1, account)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getInt(1) to rows.getString(2) else null
                }
            }
            if (user != null) {
                val (principalId, userName) = user
                val dbUser = bracket(userName)
                connection.prepareStatement(SqlText.DATABASE_ROLES).use { statement ->
                    statement.setInt(1, principalId)
                    statement.executeQuery().use { rows ->
                        while (rows.next()) lines += "ALTER ROLE ${bracket(rows.getString(1))} ADD MEMBER $dbUser;"
                    }
                }
                connection.prepareStatement(SqlText.DATABASE_PERMISSIONS).use { statement ->
                    statement.setInt(1, principalId)
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            val on = when {
                                rows.getString(4) != null && rows.getString(5) != null ->
                                    " ON ${bracket(rows.getString(4))}.${bracket(rows.getString(5))}"
                                rows.getString(3) == "DATABASE" -> ""
                                else -> " ON ${rows.getString(3)}"
                            }
                            lines += permissionLine(rows.getString(1), rows.getString(2), on, dbUser)
                        }
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
                SELECT CAST(SERVERPROPERTY('ProductVersion') AS nvarchar(50)) AS version,
                       CAST(SERVERPROPERTY('Edition') AS nvarchar(100)) AS edition,
                       CAST(SERVERPROPERTY('ProductLevel') AS nvarchar(50)) AS level,
                       CAST(SERVERPROPERTY('ServerName') AS nvarchar(200)) AS server_name,
                       DB_NAME() AS [database],
                       SUSER_SNAME() AS current_user_name,
                       @@MAX_CONNECTIONS AS max_connections,
                       CAST(SERVERPROPERTY('Collation') AS nvarchar(100)) AS collation
                """.trimIndent(),
            ).use { rows ->
                if (rows.next()) {
                    for (label in listOf(
                        "version", "edition", "level", "server_name", "database",
                        "current_user_name", "max_connections", "collation",
                    )) {
                        val value = rows.getString(label)
                        if (!value.isNullOrBlank()) facts += ServerFact(label, value)
                    }
                }
            }
        }
        // These need VIEW SERVER STATE; without it the card is simply shorter.
        runCatching {
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT DATEDIFF(SECOND, sqlserver_start_time, SYSDATETIME()) FROM sys.dm_os_sys_info",
                ).use { rows -> if (rows.next()) facts += ServerFact("uptime", MetricFormat.duration(rows.getLong(1))) }
                statement.executeQuery(
                    "SELECT COUNT(*) FROM sys.dm_exec_sessions WHERE is_user_process = 1",
                ).use { rows -> if (rows.next()) facts += ServerFact("connections", rows.getString(1)) }
                statement.executeQuery(
                    "SELECT encrypt_option FROM sys.dm_exec_connections WHERE session_id = @@SPID",
                ).use { rows ->
                    if (rows.next()) {
                        val on = rows.getString(1).equals("TRUE", ignoreCase = true)
                        facts += ServerFact("encrypted", if (on) "yes" else "no")
                    }
                }
            }
        }
        return facts
    }

    override fun sample(connection: Connection): ServerSample = requiring(VIEW_SERVER_STATE) {
        val values = mutableMapOf<String, Long>()
        connection.createStatement().use { statement ->
            statement.executeQuery(SqlText.sample).use { rows ->
                while (rows.next()) values[rows.getString(1)] = rows.getLong(2)
            }
        }
        ServerSample(
            takenAtMs = System.currentTimeMillis(),
            uptimeSeconds = values.remove("uptime") ?: 0L,
            values = values,
        )
    }

    // ------------------------------------------------------------------ helpers

    /** `GRANT SELECT ON [s].[t] TO [u]`, `DENY …`, `… WITH GRANT OPTION`. */
    internal fun permissionLine(state: String, permission: String, on: String, grantee: String): String {
        val verb = when (state.uppercase()) {
            "GRANT_WITH_GRANT_OPTION" -> "GRANT"
            else -> state.uppercase()
        }
        val suffix = if (state.equals("GRANT_WITH_GRANT_OPTION", ignoreCase = true)) " WITH GRANT OPTION" else ""
        return "$verb $permission$on TO $grantee$suffix;"
    }

    private fun bracket(name: String) = SqlServerDialect.quoteIdentifier(name)

    private fun majorVersion(connection: Connection): Int =
        runCatching {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT CAST(SERVERPROPERTY('ProductVersion') AS nvarchar(50))").use { rows ->
                    if (rows.next()) rows.getString(1).substringBefore('.').toInt() else 0
                }
            }
        }.getOrDefault(0)

    private fun strings(connection: Connection, sql: String, parameter: String): List<String> =
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, parameter)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }

    private fun table(connection: Connection, sql: String, bind: PreparedStatement.() -> Unit): ResultTable =
        connection.prepareStatement(sql).use { statement ->
            statement.bind()
            statement.executeQuery().use { rows -> ResultTable.from(rows, MAX_ROWS) }
        }

    const val SLOW_LIMIT = 25
    private const val MICROS_TO_PICOS = 1_000_000L
    private const val MAX_ROWS = 500
}
