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
        facts
    }

    private companion object {
        /** A busy server can have thousands of connections; the screen shows the first page. */
        const val MAX_PROCESSES = 500
    }
}
