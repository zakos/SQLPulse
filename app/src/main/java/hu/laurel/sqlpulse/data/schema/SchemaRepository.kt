package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.ColumnFilter
import hu.laurel.sqlpulse.data.sql.ColumnSort
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.TableQuery
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
                SELECT TABLE_NAME, TABLE_TYPE, TABLE_ROWS, TABLE_COMMENT, ENGINE,
                       TABLE_COLLATION, DATA_LENGTH, INDEX_LENGTH
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
                                    engine = rows.getString("ENGINE"),
                                    collation = rows.getString("TABLE_COLLATION"),
                                    dataBytes = rows.getLong("DATA_LENGTH").takeUnless { rows.wasNull() },
                                    indexBytes = rows.getLong("INDEX_LENGTH").takeUnless { rows.wasNull() },
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

    /**
     * A page of rows for the Data tab (§7.3), loaded as the grid scrolls (§7.5).
     *
     * [limit] and [offset] are integers we control, never user input, so interpolating them is
     * safe; the identifiers are quoted because they cannot be bound.
     */
    suspend fun preview(
        database: String,
        table: String,
        limit: Int = PREVIEW_ROWS,
        offset: Int = 0,
        sort: ColumnSort? = null,
        filter: ColumnFilter? = null,
    ): ResultTable =
        sessions.withConnection { connection ->
            // LIMIT and OFFSET are integers we control; the filter value is bound.
            val sql = buildString {
                append("SELECT * FROM ${quoteIdentifier(database)}.${quoteIdentifier(table)}")
                append(TableQuery.where(filter))
                append(TableQuery.orderBy(sort))
                append(" LIMIT $limit OFFSET $offset")
            }
            connection.prepareStatement(sql).use { statement ->
                statement.fetchSize = limit
                TableQuery.whereParameter(filter)?.let { statement.setString(1, it) }
                val started = System.currentTimeMillis()
                statement.executeQuery().use { rows ->
                    ResultTable.from(rows, limit)
                        .copy(durationMs = System.currentTimeMillis() - started)
                }
            }
        }

    /** Exact count for the "loaded of total" line, honouring the same filter (§7.5). */
    suspend fun rowCount(database: String, table: String, filter: ColumnFilter? = null): Long =
        sessions.withConnection { connection ->
            val sql = "SELECT COUNT(*) FROM ${quoteIdentifier(database)}.${quoteIdentifier(table)}" +
                TableQuery.where(filter)
            connection.prepareStatement(sql).use { statement ->
                TableQuery.whereParameter(filter)?.let { statement.setString(1, it) }
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
            }
        }

    /**
     * Stored procedures and functions (§7.3).
     *
     * `information_schema.ROUTINES` only shows what the user may see, so an empty list can mean
     * "none defined" or "not visible" — the screen says as much rather than guessing.
     */
    suspend fun routines(database: String): List<SchemaRoutine> = sessions.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT ROUTINE_NAME, ROUTINE_TYPE, DTD_IDENTIFIER, ROUTINE_COMMENT
            FROM information_schema.ROUTINES
            WHERE ROUTINE_SCHEMA = ?
            ORDER BY ROUTINE_TYPE, ROUTINE_NAME
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, database)
            statement.executeQuery().collect { rows ->
                val kind = if (rows.getString("ROUTINE_TYPE") == "FUNCTION") {
                    RoutineKind.FUNCTION
                } else {
                    RoutineKind.PROCEDURE
                }
                SchemaRoutine(
                    name = rows.getString("ROUTINE_NAME"),
                    kind = kind,
                    returns = rows.getString("DTD_IDENTIFIER")?.takeIf { kind == RoutineKind.FUNCTION },
                    comment = rows.getString("ROUTINE_COMMENT")?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    suspend fun triggers(database: String): List<SchemaTrigger> = sessions.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, EVENT_MANIPULATION, ACTION_TIMING
            FROM information_schema.TRIGGERS
            WHERE TRIGGER_SCHEMA = ?
            ORDER BY EVENT_OBJECT_TABLE, TRIGGER_NAME
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, database)
            statement.executeQuery().collect { rows ->
                SchemaTrigger(
                    name = rows.getString("TRIGGER_NAME"),
                    table = rows.getString("EVENT_OBJECT_TABLE"),
                    event = rows.getString("EVENT_MANIPULATION"),
                    timing = rows.getString("ACTION_TIMING"),
                )
            }
        }
    }

    /**
     * Scheduled events. A server with the event scheduler switched off still lists them, which is
     * worth seeing: an event that never runs looks exactly like one that does.
     */
    suspend fun events(database: String): List<SchemaEvent> = sessions.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT EVENT_NAME, STATUS, INTERVAL_VALUE, INTERVAL_FIELD, EXECUTE_AT
            FROM information_schema.EVENTS
            WHERE EVENT_SCHEMA = ?
            ORDER BY EVENT_NAME
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, database)
            statement.executeQuery().collect { rows ->
                val interval = rows.getString("INTERVAL_VALUE")
                val field = rows.getString("INTERVAL_FIELD")
                SchemaEvent(
                    name = rows.getString("EVENT_NAME"),
                    status = rows.getString("STATUS"),
                    schedule = when {
                        interval != null && field != null -> "$interval $field"
                        else -> rows.getString("EXECUTE_AT")
                    },
                )
            }
        }
    }

    /** `SHOW CREATE PROCEDURE` / `FUNCTION` for the routine sheet. */
    suspend fun routineDdl(database: String, routine: SchemaRoutine): String =
        sessions.withConnection { connection ->
            val keyword = if (routine.kind == RoutineKind.FUNCTION) "FUNCTION" else "PROCEDURE"
            val sql = "SHOW CREATE $keyword ${quoteIdentifier(database)}.${quoteIdentifier(routine.name)}"
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    // Column 3 is "Create Procedure" / "Create Function"; it is NULL without the
                    // privilege to see the body, and the screen shows that as an empty definition.
                    if (rows.next()) rows.getString(3).orEmpty() else ""
                }
            }
        }

    /**
     * The first bytes of a BLOB, for the preview (§7.5).
     *
     * Two values are asked of the server in one row: `LENGTH(col)`, which it answers from the copy
     * it already holds, and `SUBSTRING(col, 1, maxBytes)`, which is the only part that travels
     * back. Reading the column itself — `getBytes` on the unsliced value — would pull a video in a
     * LONGBLOB down the tunnel in full just to show sixteen lines of hex, and asking the driver
     * for its size is no cheaper: the size is only known once the bytes have arrived. The length
     * is also what says "there is more", so not even one extra byte is fetched to find that out.
     */
    suspend fun blobBytes(
        database: String,
        table: String,
        key: Map<String, String?>,
        column: String,
        maxBytes: Int = PREVIEW_BYTES,
    ): Pair<ByteArray, Boolean> = sessions.withConnection { connection ->
        val where = key.keys.joinToString(prefix = " WHERE ", separator = " AND ") {
            "${quoteIdentifier(it)} = ?"
        }
        val sql = "SELECT LENGTH(${quoteIdentifier(column)}), " +
            "SUBSTRING(${quoteIdentifier(column)}, 1, $maxBytes) FROM " +
            "${quoteIdentifier(database)}.${quoteIdentifier(table)}$where"
        connection.prepareStatement(sql).use { statement ->
            key.values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@withConnection ByteArray(0) to false
                // LENGTH() of a NULL column is NULL, which getLong reports as 0 — the same as an
                // empty BLOB, and both preview as nothing at all.
                val total = rows.getLong(1)
                val bytes = rows.getBytes(2) ?: ByteArray(0)
                bytes to (total > bytes.size)
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

    /**
     * Every foreign key inside one database, as links for the map.
     *
     * One statement for the whole schema rather than one per table: a schema of two hundred tables
     * would otherwise be two hundred round trips through the tunnel. The columns of one constraint
     * are grouped into a single link, because that is what a reader sees — one arrow, however many
     * columns it is made of.
     */
    suspend fun links(database: String): List<GraphEdge> = sessions.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = ?
              AND REFERENCED_TABLE_NAME IS NOT NULL
              AND REFERENCED_TABLE_SCHEMA = TABLE_SCHEMA
            ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, database)
            statement.executeQuery().collect { rows ->
                Triple(
                    rows.getString("TABLE_NAME") to rows.getString("CONSTRAINT_NAME"),
                    rows.getString("REFERENCED_TABLE_NAME"),
                    rows.getString("COLUMN_NAME"),
                )
            }
        }.groupBy { it.first }
            .map { (key, columns) ->
                GraphEdge(
                    from = key.first,
                    to = columns.first().second,
                    columns = columns.map { it.third },
                )
            }
    }

    /**
     * Every column of every table in one database, with which ones are primary keys.
     *
     * One statement for the schema, and only the three columns a guessed link needs — this runs
     * for the map on schemas that have no foreign keys, where the names are all there is to go on.
     */
    suspend fun columnNames(database: String): List<TableColumns> =
        sessions.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT TABLE_NAME, COLUMN_NAME, COLUMN_KEY
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ?
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, database)
                statement.executeQuery().collect { rows ->
                    Triple(
                        rows.getString("TABLE_NAME"),
                        rows.getString("COLUMN_NAME"),
                        rows.getString("COLUMN_KEY").orEmpty(),
                    )
                }
            }.groupBy { it.first }
                .map { (table, columns) ->
                    TableColumns(
                        table = table,
                        columns = columns.map { it.second },
                        primaryKey = columns.filter { it.third == "PRI" }.map { it.second },
                    )
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

        /** Enough to recognise a file header or read a paragraph; not enough to hurt. */
        const val PREVIEW_BYTES = 4096
        val SYSTEM_SCHEMAS = setOf("information_schema", "mysql", "performance_schema", "sys")
    }
}
