package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.MySqlPulse
import hu.laurel.sqlpulse.data.schema.PulseProfile
import hu.laurel.sqlpulse.data.schema.ReplicationReport
import hu.laurel.sqlpulse.data.schema.ReplicationStatus
import hu.laurel.sqlpulse.data.schema.ServerFact
import hu.laurel.sqlpulse.data.schema.ServerMetrics
import hu.laurel.sqlpulse.data.schema.ServerSample
import hu.laurel.sqlpulse.data.schema.ServerVersion
import hu.laurel.sqlpulse.data.schema.SlowSort
import hu.laurel.sqlpulse.data.schema.SlowStatement
import hu.laurel.sqlpulse.data.schema.SlowStatements
import hu.laurel.sqlpulse.data.schema.SlowStatementsReport
import hu.laurel.sqlpulse.data.schema.quoteStringLiteral
import hu.laurel.sqlpulse.data.sql.ResultTable
import java.sql.Connection
import java.sql.SQLException

/**
 * MySQL and MariaDB: `SHOW PROCESSLIST`, `INNODB_TRX`, `SHOW REPLICA STATUS`, the digest table of
 * performance_schema, `mysql.user`, `SHOW GLOBAL STATUS`. This is the code that lived in
 * ServerRepository before engines existed, moved here unchanged so its output did not move.
 */
object MySqlServerCatalog : ServerCatalog {

    override val capabilities = ServerCapabilities.MYSQL
    override val pulse: PulseProfile = MySqlPulse

    /** `SHOW FULL PROCESSLIST` — the whole query text, not the truncated form. */
    override fun processList(connection: Connection, includeIdle: Boolean, limit: Int): ResultTable =
        connection.createStatement().use { statement ->
            statement.executeQuery("SHOW FULL PROCESSLIST").use { rows -> ResultTable.from(rows, limit) }
        }

    /**
     * `KILL QUERY` rather than `KILL`: it stops the statement and leaves the connection alive,
     * which is the less destructive of the two and enough to free a stuck query (§11).
     */
    override fun stop(connection: Connection, id: Long, action: KillAction): Boolean {
        connection.createStatement().use { statement ->
            // KILL takes no parameters; interpolating a Long cannot carry anything but digits.
            statement.execute("KILL QUERY $id")
        }
        return true
    }

    /**
     * Open transactions, oldest first (research summary, §2.0). The thread id is the same one
     * `KILL QUERY` takes, so what is found here can be acted on from the same screen.
     */
    override fun transactions(connection: Connection, limit: Int): ResultTable =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT trx_mysql_thread_id AS Id,
                       trx_state AS State,
                       TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS Seconds,
                       trx_rows_locked AS RowsLocked,
                       trx_rows_modified AS RowsModified,
                       trx_query AS Query
                FROM information_schema.INNODB_TRX
                ORDER BY trx_started
                """.trimIndent(),
            ).use { rows -> ResultTable.from(rows, limit) }
        }

    /**
     * Who is waiting for whom.
     *
     * The tables moved in MySQL 8.0: the old `INNODB_LOCK_WAITS` became
     * `performance_schema.data_lock_waits`, and asking for the wrong one is an error rather than
     * an empty answer. The modern one is tried first, the old one second, and a server that has
     * neither — or a user without the grant — gets an empty table rather than a failure, because
     * "no lock waits" is also what an idle server looks like.
     */
    override fun lockWaits(connection: Connection, limit: Int): ResultTable {
        val queries = listOf(
            """
            SELECT r.trx_mysql_thread_id AS WaitingId,
                   r.trx_query AS WaitingQuery,
                   b.trx_mysql_thread_id AS BlockingId,
                   b.trx_query AS BlockingQuery
            FROM performance_schema.data_lock_waits w
            JOIN information_schema.INNODB_TRX r ON r.trx_id = w.REQUESTING_ENGINE_TRANSACTION_ID
            JOIN information_schema.INNODB_TRX b ON b.trx_id = w.BLOCKING_ENGINE_TRANSACTION_ID
            """.trimIndent(),
            """
            SELECT r.trx_mysql_thread_id AS WaitingId,
                   r.trx_query AS WaitingQuery,
                   b.trx_mysql_thread_id AS BlockingId,
                   b.trx_query AS BlockingQuery
            FROM information_schema.INNODB_LOCK_WAITS w
            JOIN information_schema.INNODB_TRX r ON r.trx_id = w.requesting_trx_id
            JOIN information_schema.INNODB_TRX b ON b.trx_id = w.blocking_trx_id
            """.trimIndent(),
        )
        return firstTable(connection, queries, limit)
    }

    /**
     * `SHOW REPLICA STATUS` is the 8.0.22 name and `SHOW SLAVE STATUS` the older one (MariaDB adds
     * `SHOW ALL SLAVES STATUS` for several connections); both need a grant that plenty of
     * read-only users do not have. The outcomes are kept apart on purpose: a statement that ran
     * and returned no rows means "not a replica", while refusals on every spelling mean "not
     * allowed to ask" — blaming a missing grant on a server that simply is not a replica, or the
     * reverse, would send somebody looking in the wrong place.
     */
    override fun replication(connection: Connection): ReplicationReport {
        val version = serverVersion(connection)
        var denied = false
        for (sql in ReplicationStatus.statements(version)) {
            try {
                val table = connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows -> ResultTable.from(rows, MAX_CHANNELS) }
                }
                return if (table.rows.isEmpty()) {
                    ReplicationReport.NotReplica
                } else {
                    ReplicationReport.Channels(
                        ReplicationStatus.sortedBySeverity(ReplicationStatus.parse(table)),
                        table,
                    )
                }
            } catch (e: SQLException) {
                // A syntax error is just the wrong generation of the statement; try the next.
                if (ReplicationStatus.isAccessDenied(e.errorCode)) denied = true
            }
        }
        return if (denied) ReplicationReport.NoPrivilege else ReplicationReport.NotReplica
    }

    /**
     * The statements that cost the most time, from the server's own digest table. Three states a
     * plain error would blur are told apart: the server is too old, performance_schema is
     * switched off (the table exists but stays empty), and the user lacks SELECT on it.
     */
    override fun slowStatements(connection: Connection, sort: SlowSort): SlowStatementsReport {
        if (!SlowStatements.supported(serverVersion(connection))) return SlowStatementsReport.Unsupported
        val enabled = runCatching {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT @@performance_schema").use { rows ->
                    rows.next() && rows.getInt(1) == 1
                }
            }
        }.getOrDefault(true)
        if (!enabled) return SlowStatementsReport.PerformanceSchemaOff
        return try {
            val statements = connection.createStatement().use { statement ->
                statement.executeQuery(SlowStatements.query(sort)).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                SlowStatement(
                                    digestText = rows.getString(1).orEmpty(),
                                    schema = rows.getString(2)?.takeIf { it.isNotBlank() },
                                    count = SlowStatements.parseCounter(rows.getString(3)),
                                    totalPicos = SlowStatements.parseCounter(rows.getString(4)),
                                    avgPicos = SlowStatements.parseCounter(rows.getString(5)),
                                    rowsExamined = SlowStatements.parseCounter(rows.getString(6)),
                                    rowsSent = SlowStatements.parseCounter(rows.getString(7)),
                                    noIndexUsed = SlowStatements.parseCounter(rows.getString(8)),
                                    noGoodIndexUsed = SlowStatements.parseCounter(rows.getString(9)),
                                    firstSeen = rows.getString(10),
                                    lastSeen = rows.getString(11),
                                ),
                            )
                        }
                    }
                }
            }
            SlowStatementsReport.Rows(statements, sort)
        } catch (e: SQLException) {
            when {
                SlowStatements.isAccessDenied(e.errorCode) -> SlowStatementsReport.NoPrivilege
                SlowStatements.isMissingTable(e.errorCode) -> SlowStatementsReport.Unsupported
                else -> throw e
            }
        }
    }

    /**
     * The server's accounts (research summary, §2.0). `mysql.user` is the full answer and needs a
     * grant on that table; without it, `information_schema.user_privileges` still lists who
     * exists. Neither shows a password, and nothing here can change an account.
     */
    override fun users(connection: Connection, limit: Int): ResultTable {
        val queries = listOf(
            """
            SELECT CONCAT(user, '@', host) AS Account,
                   plugin AS Plugin,
                   account_locked AS Locked,
                   password_expired AS Expired
            FROM mysql.user
            ORDER BY user, host
            """.trimIndent(),
            """
            SELECT DISTINCT grantee AS Account
            FROM information_schema.user_privileges
            ORDER BY grantee
            """.trimIndent(),
        )
        return firstTable(connection, queries, limit)
    }

    /**
     * `SHOW GRANTS` takes no parameters, so the account is quoted into the statement; it comes
     * from the server's own answer above rather than from anything typed, and it is quoted anyway.
     * The output is left exactly as MySQL writes it — a GRANT line is what would be pasted
     * somewhere else, and rewording it would make that useless.
     */
    override fun grants(connection: Connection, account: String): List<String> {
        val (user, host) = account.substringBeforeLast('@') to account.substringAfterLast('@', "%")
        val sql = "SHOW GRANTS FOR ${quoteStringLiteral(user.trim('\''))}@" +
            quoteStringLiteral(host.trim('\''))
        return connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1).orEmpty())
                }
            }
        }
    }

    /**
     * Two statements rather than one `SHOW GLOBAL STATUS` of several hundred rows — this runs
     * every few seconds, over a tunnel, on a phone's battery. The replica question is allowed to
     * fail: plenty of read-only users may not ask it, and a server that is not a replica answers
     * with no rows, which is the same "nothing to report".
     */
    override fun sample(connection: Connection): ServerSample {
        val values = mutableMapOf<String, Long>()
        val names = ServerMetrics.WATCHED.joinToString(", ") { quoteStringLiteral(it) }
        connection.createStatement().use { statement ->
            statement.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ($names)").use { rows ->
                while (rows.next()) {
                    // Anything that is not a whole number is left out rather than rounded: the
                    // screen would rather show a dash than a number it made up.
                    rows.getString(2)?.toLongOrNull()?.let { values[rows.getString(1)] = it }
                }
            }
        }
        return ServerSample(
            takenAtMs = System.currentTimeMillis(),
            uptimeSeconds = values["Uptime"] ?: 0L,
            values = values,
            replicationLagSeconds = replicationLag(connection),
        )
    }

    /** `Seconds_Behind_Source` on 8.0.22 and later, `Seconds_Behind_Master` before it. */
    private fun replicationLag(connection: Connection): Long? =
        listOf("SHOW REPLICA STATUS", "SHOW SLAVE STATUS").firstNotNullOfOrNull { sql ->
            runCatching {
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        if (!rows.next()) return@use null
                        listOf("Seconds_Behind_Source", "Seconds_Behind_Master")
                            .firstNotNullOfOrNull { column ->
                                runCatching { rows.getString(column) }.getOrNull()
                            }
                            ?.toLongOrNull()
                    }
                }
            }.getOrNull()
        }

    /** A handful of variables worth seeing at a glance. */
    override fun overview(connection: Connection): List<ServerFact> {
        val facts = mutableListOf<ServerFact>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT VERSION() AS version,
                       @@hostname AS hostname,
                       @@version_comment AS build,
                       CURRENT_USER() AS current_user_name,
                       @@max_connections AS max_connections,
                       @@read_only AS read_only,
                       @@time_zone AS time_zone,
                       @@character_set_server AS charset
                """.trimIndent(),
            ).use { rows ->
                if (rows.next()) {
                    val meta = rows.metaData
                    for (index in 1..meta.columnCount) {
                        facts += ServerFact(meta.getColumnLabel(index), rows.getString(index) ?: "")
                    }
                }
            }
            // Uptime and the current thread count come from the status variables.
            statement.executeQuery(
                "SHOW GLOBAL STATUS WHERE Variable_name IN ('Uptime', 'Threads_connected', 'Threads_running')",
            ).use { rows ->
                while (rows.next()) {
                    facts += ServerFact(rows.getString(1), rows.getString(2) ?: "")
                }
            }
            // Session status, not global: this is what *this* connection negotiated. An empty
            // Ssl_version means the connection is not encrypted at all (research summary, §1).
            statement.executeQuery(
                "SHOW SESSION STATUS WHERE Variable_name IN ('Ssl_version', 'Ssl_cipher')",
            ).use { rows ->
                while (rows.next()) {
                    val value = rows.getString(2).orEmpty()
                    facts += ServerFact(rows.getString(1), value.ifBlank { "—" })
                }
            }
        }
        return facts
    }

    private fun firstTable(connection: Connection, queries: List<String>, limit: Int): ResultTable =
        queries.firstNotNullOfOrNull { sql ->
            runCatching {
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows -> ResultTable.from(rows, limit) }
                }
            }.getOrNull()
        } ?: ResultTable.EMPTY

    private fun serverVersion(connection: Connection): ServerVersion =
        runCatching {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT VERSION()").use { rows ->
                    ServerVersion.parse(if (rows.next()) rows.getString(1) else null)
                }
            }
        }.getOrDefault(ServerVersion.UNKNOWN)

    /** Multi-source replicas have a handful of channels, not hundreds. */
    private const val MAX_CHANNELS = 64
}
