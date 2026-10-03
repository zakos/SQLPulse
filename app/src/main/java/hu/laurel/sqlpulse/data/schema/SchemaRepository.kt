package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.ColumnFilter
import hu.laurel.sqlpulse.data.sql.ColumnSort
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.TableQuery
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.PreparedSql
import hu.laurel.sqlpulse.data.sql.dialect.SchemaCatalog
import java.sql.Connection
import java.sql.PreparedStatement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the schema (§7.3) and pages through table data.
 *
 * What to ask the server is the live engine's business: the data-dictionary statements live in
 * its [SchemaCatalog] (MySqlCatalog for MySQL — information_schema, moved there unchanged), and
 * every identifier or LIMIT written into a data query goes through its dialect. This class keeps
 * what is the same for every engine: borrowing the connection, the caching and the order in which
 * a page's statements are sent. Values are always bound.
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
    suspend fun databases(): List<String> {
        val system = sessions.dialect().systemNamespaces
        return catalog { connection, catalog -> catalog.namespaces(connection) }
            .sortedWith(compareBy({ it in system }, { it.lowercase() }))
    }

    suspend fun tables(database: String, refresh: Boolean = false): List<SchemaTable> {
        if (!refresh) tableCache[database]?.let { return it }
        val tables = catalog { connection, catalog -> catalog.tables(connection, database) }
        tableCache[database] = tables
        return tables
    }

    suspend fun structure(database: String, table: String, refresh: Boolean = false): TableStructure {
        val key = "$database.$table"
        if (!refresh) structureCache[key]?.let { return it }
        // Five statements for the whole page, and no more: the columns, the indexes, the foreign
        // keys with their rules, one statement that brings the partitions and the table collation
        // back together, and the CHECK constraints, which are the only part a server may not
        // have. Each of the five is one round trip through the tunnel, which on a phone is the
        // cost worth counting — the rules, the collations and the generated columns cost nothing
        // extra because they ride along in statements that were being sent anyway.
        val extras = tableExtras(database, table)
        val structure = TableStructure(
            columns = columns(database, table),
            indexes = indexes(database, table),
            foreignKeys = foreignKeys(database, table),
            checks = checkConstraints(database, table),
            partitions = extras.partitions,
            collation = extras.collation,
        )
        structureCache[key] = structure
        return structure
    }

    /** `SHOW CREATE TABLE` output (or the engine's equivalent) for the DDL tab (§7.3). */
    suspend fun ddl(database: String, table: String): String =
        catalog { connection, catalog -> catalog.ddl(connection, database, table) }

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
            val dialect = sessions.dialect()
            // LIMIT and OFFSET are integers we control; the filter value is bound.
            val sql = dialect.limit(
                select = buildString {
                    append("SELECT * FROM ${dialect.qualify(database, table)}")
                    append(TableQuery.where(filter, dialect))
                    append(TableQuery.orderBy(sort, dialect))
                },
                limit = limit,
                offset = offset,
                ordered = sort != null,
            )
            connection.prepareStatement(sql).use { statement ->
                statement.fetchSize = limit
                TableQuery.whereParameters(filter).forEachIndexed { i, value -> statement.setString(i + 1, value) }
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
            val dialect = sessions.dialect()
            val sql = "SELECT COUNT(*) FROM ${dialect.qualify(database, table)}" +
                TableQuery.where(filter, dialect)
            connection.prepareStatement(sql).use { statement ->
                TableQuery.whereParameters(filter).forEachIndexed { i, value -> statement.setString(i + 1, value) }
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
            }
        }

    /**
     * Stored procedures and functions (§7.3).
     *
     * The catalog only shows what the user may see, so an empty list can mean "none defined" or
     * "not visible" — the screen says as much rather than guessing.
     */
    suspend fun routines(database: String): List<SchemaRoutine> =
        catalog { connection, catalog -> catalog.routines(connection, database) }

    suspend fun triggers(database: String): List<SchemaTrigger> =
        catalog { connection, catalog -> catalog.triggers(connection, database) }

    /**
     * Scheduled events. A server with the event scheduler switched off still lists them, which is
     * worth seeing: an event that never runs looks exactly like one that does.
     */
    suspend fun events(database: String): List<SchemaEvent> =
        catalog { connection, catalog -> catalog.events(connection, database) }

    /** `SHOW CREATE PROCEDURE` / `FUNCTION` (or the engine's equivalent) for the routine sheet. */
    suspend fun routineDdl(database: String, routine: SchemaRoutine): String =
        catalog { connection, catalog -> catalog.routineDdl(connection, database, routine) }

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
        val dialect = sessions.dialect()
        val where = key.keys.joinToString(prefix = " WHERE ", separator = " AND ") {
            "${dialect.quoteIdentifier(it)} = ?"
        }
        val sql = "SELECT ${dialect.blobLengthAndHead(dialect.quoteIdentifier(column), maxBytes)} FROM " +
            "${dialect.qualify(database, table)}$where"
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
        catalog { connection, catalog -> catalog.columns(connection, database, table) }

    private suspend fun indexes(database: String, table: String): List<SchemaIndex> =
        catalog { connection, catalog -> catalog.indexes(connection, database, table) }

    /**
     * Every foreign key inside one database, as links for the map: one statement for the whole
     * schema rather than one per table, because each is a round trip through the tunnel.
     */
    suspend fun links(database: String): List<GraphEdge> =
        catalog { connection, catalog -> catalog.links(connection, database) }

    /**
     * Every column of every table in one database, with which ones are primary keys — what a
     * guessed link needs on a schema that declares no foreign keys.
     */
    suspend fun columnNames(database: String): List<TableColumns> =
        catalog { connection, catalog -> catalog.columnNames(connection, database) }

    /**
     * The links a row of this table can be walked along to its parents (§7.3).
     *
     * The declared foreign keys where there are any. Where the schema declares none — MyISAM, or
     * anything older than InnoDB's default — [LinkGuesser] reads them off the column names
     * instead, and everything it finds is marked [RowLink.guessed] all the way to the screen.
     */
    suspend fun parentLinks(database: String, table: String): List<RowLink> {
        val declared = RowLinks.parentLinks(database, table, structure(database, table).foreignKeys)
        if (declared.isNotEmpty()) return declared
        val columns = columnNames(database)
        return RowLinks.guessedLinks(
            database = database,
            table = table,
            guesses = LinkGuesser.infer(columns),
            primaryKeys = columns.associate { it.table to it.primaryKey },
        )
    }

    /**
     * The links pointing at this table: what a row of it is the parent of.
     *
     * One statement for the whole schema's inbound keys rather than one per candidate table, and
     * the same guessing fallback as [parentLinks] where nothing is declared.
     */
    suspend fun childLinks(database: String, table: String): List<RowLink> {
        val declared = RowLinks.group(referencingKeys(database, table))
        if (declared.isNotEmpty()) return declared
        val columns = columnNames(database)
        val keys = columns.associate { it.table to it.primaryKey }
        val guesses = LinkGuesser.infer(columns).filter { it.to == table }
        return guesses.flatMap { edge ->
            RowLinks.guessedLinks(database, edge.from, listOf(edge), keys)
        }
    }

    /** Rows of [table] matching a keyed filter, with every value bound (§7.3). */
    suspend fun rowsMatching(
        database: String,
        table: String,
        filter: RowFilter,
        limit: Int = RowLinks.CHILD_LIMIT,
    ): ResultTable = execute(RowLinks.selectRows(database, table, filter, limit, sessions.dialect()), limit)

    /** How many rows match — the number shown beside a table in "what points at this". */
    suspend fun countMatching(database: String, table: String, filter: RowFilter): Long =
        sessions.withConnection { connection ->
            val prepared = RowLinks.countRows(database, table, filter, sessions.dialect())
            connection.prepareStatement(prepared.sql).use { statement ->
                bind(statement, prepared.parameters)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
            }
        }

    private suspend fun execute(prepared: PreparedSql, limit: Int): ResultTable =
        sessions.withConnection { connection ->
            connection.prepareStatement(prepared.sql).use { statement ->
                statement.fetchSize = limit
                bind(statement, prepared.parameters)
                val started = System.currentTimeMillis()
                statement.executeQuery().use { rows ->
                    ResultTable.from(rows, limit)
                        .copy(durationMs = System.currentTimeMillis() - started)
                }
            }
        }

    private fun bind(statement: PreparedStatement, parameters: List<String?>) {
        parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
    }

    /** Foreign keys held by other tables and pointing at [table], in constraint column order. */
    private suspend fun referencingKeys(database: String, table: String): List<KeyColumnUsage> =
        catalog { connection, catalog -> catalog.referencingKeys(connection, database, table) }

    /** The table's foreign keys, each with what it does on a delete and on an update. */
    private suspend fun foreignKeys(database: String, table: String): List<ForeignKey> =
        catalog { connection, catalog -> catalog.foreignKeys(connection, database, table) }

    /** The partitions and the table's own collation, in one statement. */
    private suspend fun tableExtras(database: String, table: String) =
        catalog { connection, catalog -> catalog.tableExtras(connection, database, table) }

    /**
     * CHECK constraints, where the server has any notion of them. An engine (or a server version)
     * without them answers with an empty list rather than an error: see MySqlCatalog.
     */
    private suspend fun checkConstraints(database: String, table: String): List<CheckConstraint> =
        catalog { connection, catalog -> catalog.checkConstraints(connection, database, table) }

    /**
     * Runs one catalog read on a pooled connection. The catalog is taken from the dialect at the
     * moment of the call, so a read never mixes one engine's statements with another's session.
     */
    private suspend fun <T> catalog(read: (Connection, SchemaCatalog) -> T): T {
        val catalog = sessions.dialect().catalog
        return sessions.withConnection { connection -> read(connection, catalog) }
    }

    private companion object {
        const val PREVIEW_ROWS = 100

        /** Enough to recognise a file header or read a paragraph; not enough to hurt. */
        const val PREVIEW_BYTES = 4096
    }
}
