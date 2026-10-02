package hu.laurel.sqlpulse.data.search

import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import java.sql.Statement
import javax.inject.Inject

/** One matching row: what identifies it, and the searched cells that hold the term. */
data class SearchRow(
    val key: List<Pair<String, String?>>,
    val cells: List<Pair<String, String>>,
)

/**
 * Runs the statements [DatabaseSearch] builds.
 *
 * Only ever reads, one statement at a time on the shared session; the caller walks the tables in
 * sequence, so a search never competes with itself for the single connection.
 */
class DatabaseSearchRepository @Inject constructor(
    private val sessions: SqlSessionManager,
) {
    @Volatile
    private var running: Statement? = null

    /**
     * Every column of every table in [database], in one statement.
     *
     * One round trip instead of the five per table that the table page spends: a search touches
     * every table, and over a tunnel on a phone the round trips are the cost worth counting.
     */
    suspend fun columns(database: String): Map<String, List<SchemaColumn>> =
        sessions.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_KEY, EXTRA
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ?
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """.trimIndent(),
            ).use { statement ->
                statement.queryTimeout = sessions.queryTimeoutSeconds()
                statement.setString(1, database)
                statement.executeQuery().use { rows ->
                    val byTable = linkedMapOf<String, MutableList<SchemaColumn>>()
                    while (rows.next()) {
                        byTable.getOrPut(rows.getString("TABLE_NAME")) { mutableListOf() }.add(
                            SchemaColumn(
                                name = rows.getString("COLUMN_NAME"),
                                typeName = rows.getString("COLUMN_TYPE").orEmpty(),
                                nullable = true,
                                defaultValue = null,
                                isPrimaryKey = rows.getString("COLUMN_KEY") == "PRI",
                                extra = rows.getString("EXTRA"),
                                comment = null,
                            ),
                        )
                    }
                    byTable
                }
            }
        }

    /** Runs one table's search with the connection's own query timeout. */
    suspend fun search(plan: SearchPlan): List<SearchRow> =
        sessions.withConnection { connection ->
            connection.prepareStatement(plan.sql).use { statement ->
                statement.queryTimeout = sessions.queryTimeoutSeconds()
                plan.parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                running = statement
                try {
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                val key = plan.keyColumns.mapIndexed { i, name -> name to rows.getString(i + 1) }
                                val offset = plan.keyColumns.size
                                val cells = plan.searchColumns.mapIndexedNotNull { i, name ->
                                    rows.getString(offset + i + 1)?.let { name to it }
                                }
                                add(SearchRow(key, cells))
                            }
                        }
                    }
                } finally {
                    running = null
                }
            }
        }

    /** Stops the statement in flight, so that Cancel does not wait for a slow table to finish. */
    fun cancelRunning() {
        try {
            running?.cancel()
        } catch (ignored: java.sql.SQLException) {
            // The statement finished between the check and the cancel; there is nothing left to stop.
        }
    }
}
