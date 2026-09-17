package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import java.sql.ResultSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the schema through `information_schema` (§7.3).
 *
 * Everything here is a prepared statement with bound parameters — identifiers that must be
 * interpolated (a table name in a `SELECT *`) go through [quoteIdentifier] instead.
 *
 * The result is cached per connection for as long as the session lives, because the tree is walked
 * constantly and the schema does not move underneath us mid-session.
 */
@Singleton
class SchemaRepository @Inject constructor(
    private val sessions: SqlSessionManager,
) {

    private val tableCache = mutableMapOf<String, List<SchemaTable>>()
    private val structureCache = mutableMapOf<String, TableStructure>()

    /** User databases, with the server's own schemas last rather than hidden. */
    suspend fun databases(): List<String> = sessions.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SHOW DATABASES").use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1))
                }
            }
        }
    }.sortedWith(compareBy({ it in SYSTEM_SCHEMAS }, { it.lowercase() }))

    suspend fun tables(database: String, refresh: Boolean = false): List<SchemaTable> {
        if (!refresh) tableCache[database]?.let { return it }
        val tables = sessions.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT TABLE_NAME, TABLE_TYPE, TABLE_ROWS, TABLE_COMMENT
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = ?
                ORDER BY TABLE_NAME
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, database)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                SchemaTable(
                                    database = database,
                                    name = rows.getString("TABLE_NAME"),
                                    kind = if (rows.getString("TABLE_TYPE") == "VIEW") {
                                        TableKind.VIEW
                                    } else {
                                        TableKind.TABLE
                                    },
                                    approximateRows = rows.getLong("TABLE_ROWS")
                                        .takeUnless { rows.wasNull() },
                                    comment = rows.getString("TABLE_COMMENT")?.takeIf { it.isNotBlank() },
                                ),
                            )
                        }
                    }
                }
            }
        }
        tableCache[database] = tables
        return tables
    }

    suspend fun structure(database: String, table: String, refresh: Boolean = false): TableStructure {
        val key = "$database.$table"
        if (!refresh) structureCache[key]?.let { return it }
        val structure = TableStructure(
            columns = columns(database, table),
            indexes = indexes(database, table),
            foreignKeys = foreignKeys(database, table),
        )
        structureCache[key] = structure
        return structure
    }

    /** `SHOW CREATE TABLE` output for the DDL tab (§7.3). */
    suspend fun ddl(database: String, table: String): String = sessions.withConnection { connection ->
        connection.createStatement().use { statement ->
            val sql = "SHOW CREATE TABLE ${quoteIdentifier(database)}.${quoteIdentifier(table)}"
            statement.executeQuery(sql).use { rows ->
                if (rows.next()) {
                    // Column 2 is "Create Table" for tables and "Create View" for views.
                    rows.getString(2)
                } else {
                    ""
                }
            }
        }
    }

    /** First page of rows for the Data tab (§7.3). */
    suspend fun preview(database: String, table: String, limit: Int = PREVIEW_ROWS): ResultTable =
        sessions.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.fetchSize = limit
                val sql =
                    "SELECT * FROM ${quoteIdentifier(database)}.${quoteIdentifier(table)} LIMIT $limit"
                val started = System.currentTimeMillis()
                statement.executeQuery(sql).use { rows ->
                    ResultTable.from(rows, limit)
                        .copy(durationMs = System.currentTimeMillis() - started)
                }
            }
        }

    fun clearCache() {
        tableCache.clear()
        structureCache.clear()
    }

    private suspend fun columns(database: String, table: String): List<SchemaColumn> =
        sessions.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_KEY,
                       EXTRA, COLUMN_COMMENT
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, database)
                statement.setString(2, table)
                statement.executeQuery().collect { rows ->
                    SchemaColumn(
                        name = rows.getString("COLUMN_NAME"),
                        typeName = rows.getString("COLUMN_TYPE"),
                        nullable = rows.getString("IS_NULLABLE") == "YES",
                        defaultValue = rows.getString("COLUMN_DEFAULT"),
                        isPrimaryKey = rows.getString("COLUMN_KEY") == "PRI",
                        extra = rows.getString("EXTRA")?.takeIf { it.isNotBlank() },
                        comment = rows.getString("COLUMN_COMMENT")?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }

    private suspend fun indexes(database: String, table: String): List<SchemaIndex> =
        sessions.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                ORDER BY INDEX_NAME, SEQ_IN_INDEX
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, database)
                statement.setString(2, table)
                statement.executeQuery().use { rows ->
                    val grouped = LinkedHashMap<String, Pair<Boolean, MutableList<String>>>()
                    while (rows.next()) {
                        val name = rows.getString("INDEX_NAME")
                        val unique = rows.getInt("NON_UNIQUE") == 0
                        grouped.getOrPut(name) { unique to mutableListOf() }
                            .second += rows.getString("COLUMN_NAME")
                    }
                    grouped.map { (name, value) -> SchemaIndex(name, value.first, value.second) }
                }
            }
        }

    private suspend fun foreignKeys(database: String, table: String): List<ForeignKey> =
        sessions.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_SCHEMA,
                       REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL
                ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, database)
                statement.setString(2, table)
                statement.executeQuery().collect { rows ->
                    ForeignKey(
                        constraintName = rows.getString("CONSTRAINT_NAME"),
                        column = rows.getString("COLUMN_NAME"),
                        referencedDatabase = rows.getString("REFERENCED_TABLE_SCHEMA"),
                        referencedTable = rows.getString("REFERENCED_TABLE_NAME"),
                        referencedColumn = rows.getString("REFERENCED_COLUMN_NAME"),
                    )
                }
            }
        }

    private inline fun <T> ResultSet.collect(mapper: (ResultSet) -> T): List<T> = use { rows ->
        buildList {
            while (rows.next()) add(mapper(rows))
        }
    }

    private companion object {
        const val PREVIEW_ROWS = 100
        val SYSTEM_SCHEMAS = setOf("information_schema", "mysql", "performance_schema", "sys")
    }
}
