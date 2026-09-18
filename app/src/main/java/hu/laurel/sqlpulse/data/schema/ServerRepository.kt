package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** One line of the server overview. */
data class ServerFact(val label: String, val value: String)

/**
 * What the DBA role of §3 asks for: the running queries, and enough server state to see what is
 * going on. Both need only SELECT and PROCESS.
 */
@Singleton
class ServerRepository @Inject constructor(
    private val sessions: SqlSessionManager,
) {

    /** `SHOW FULL PROCESSLIST` — the whole query text, not the truncated form. */
    suspend fun processList(): ResultTable = sessions.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SHOW FULL PROCESSLIST").use { rows ->
                ResultTable.from(rows, MAX_PROCESSES)
            }
        }
    }

    /**
     * Ends one connection's current statement.
     *
     * `KILL QUERY` rather than `KILL`: it stops the statement and leaves the connection alive,
     * which is the less destructive of the two and enough to free a stuck query (§11).
     */
    suspend fun killQuery(processId: Long) = sessions.withConnection { connection ->
        connection.createStatement().use { statement ->
            // KILL takes no parameters; interpolating a Long cannot carry anything but digits.
            statement.execute("KILL QUERY $processId")
        }
        Unit
    }

    /**
     * Open transactions, oldest first (research summary, §2.0).
     *
     * A transaction that has been open for minutes is usually somebody's forgotten session, and it
     * is what other queries are waiting behind. The thread id is the same one `KILL QUERY` takes,
     * so what is found here can be acted on from the same screen.
     */
    suspend fun transactions(): ResultTable = sessions.withConnection { connection ->
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
            ).use { rows -> ResultTable.from(rows, MAX_PROCESSES) }
        }
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
    suspend fun lockWaits(): ResultTable = sessions.withConnection { connection ->
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
        queries.firstNotNullOfOrNull { sql ->
            runCatching {
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows -> ResultTable.from(rows, MAX_PROCESSES) }
                }
            }.getOrNull()
        } ?: ResultTable.EMPTY
    }

    /**
     * Replication, as this server sees it.
     *
     * `SHOW REPLICA STATUS` is the 8.0.22 name and `SHOW SLAVE STATUS` the older one; both need a
     * grant that plenty of read-only users do not have. A server that is not a replica answers
     * with no rows at all, which is the same empty table as "not allowed to ask" — so the screen
     * says "nothing to show" rather than claiming the server is not replicating.
     */
    suspend fun replication(): ResultTable = sessions.withConnection { connection ->
        listOf("SHOW REPLICA STATUS", "SHOW SLAVE STATUS").firstNotNullOfOrNull { sql ->
            runCatching {
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows -> ResultTable.from(rows, 10) }
                }
            }.getOrNull()
        } ?: ResultTable.EMPTY
    }

    /**
     * The server's accounts (research summary, §2.0).
     *
     * `mysql.user` is the full answer and needs a grant on that table; without it,
     * `information_schema.user_privileges` still lists who exists, which is what the screen is
     * for. Neither shows a password, and nothing here can change an account.
     */
    suspend fun users(): ResultTable = sessions.withConnection { connection ->
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
        queries.firstNotNullOfOrNull { sql ->
            runCatching {
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows -> ResultTable.from(rows, MAX_PROCESSES) }
                }
            }.getOrNull()
        } ?: ResultTable.EMPTY
    }

    /**
     * What one account may do, as the server itself words it.
     *
     * `SHOW GRANTS` takes no parameters, so the account is quoted into the statement; it comes
     * from the server's own answer above rather than from anything typed, and it is quoted anyway.
     * The output is left exactly as MySQL writes it — a GRANT line is what would be pasted
     * somewhere else, and rewording it would make that useless.
     */
    suspend fun grants(account: String): List<String> = sessions.withConnection { connection ->
        val (user, host) = account.substringBeforeLast('@') to account.substringAfterLast('@', "%")
        val sql = "SHOW GRANTS FOR ${quoteStringLiteral(user.trim('\''))}@" +
            quoteStringLiteral(host.trim('\''))
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1).orEmpty())
                }
            }
        }
    }

    /** A handful of variables worth seeing at a glance. */
    suspend fun overview(): List<ServerFact> = sessions.withConnection { connection ->
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
        // Which driver this session settled on. Worth showing: on an old server it explains why
        // TLS verification is unavailable, and it is the first thing to check if something the
        // modern driver does is missing.
        sessions.driverInUse()?.let { facts += ServerFact("JDBC driver", it.name.lowercase()) }
        facts
    }

    private companion object {
        /** A busy server can have thousands of connections; the screen shows the first page. */
        const val MAX_PROCESSES = 500
    }
}
