package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.inject.Inject
import javax.inject.Singleton

/** Puts per-index sizes onto their tables, biggest first; PRIMARY is the data itself, so it is left out. */
fun mergeIndexSizes(tables: List<StorageTable>, sizes: Map<String, List<IndexSize>>): List<StorageTable> =
    tables.map { table ->
        val own = sizes[table.name].orEmpty().filter { it.index != "PRIMARY" }.sortedByDescending { it.bytes }
        table.copy(indexes = own)
    }

/**
 * Where the space of one database goes, and which indexes earn none of it (read-only, §2).
 *
 * Every source is its own statement with its own failure: a user who may read
 * `information_schema.TABLES` but not `mysql.innodb_index_stats` still gets the table list, and
 * the section that could not be read says why instead of the whole screen failing.
 */
@Singleton
class StorageRepository @Inject constructor(
    private val sessions: SqlSessionManager,
) {

    suspend fun load(database: String): StorageSnapshot = sessions.withConnection { connection ->
        val timeout = sessions.queryTimeoutSeconds()
        val run = Runner(connection, timeout)

        // The one source the screen cannot do without; its failure is the screen's failure.
        val types = runCatching { run.query(AUTO_INCREMENT_TYPES, database) { it.getString(1) to it.getString(2) }.toMap() }
            .getOrDefault(emptyMap())
        val tables = run.query(TABLES, database) { rows -> readTable(rows, types) }

        val indexSizes = runCatching {
            // Pages, not bytes, in the stats table; the page size is a server variable (16 KB by default).
            run.query(INDEX_SIZES, database) { Triple(it.getString(1), it.getString(2), it.getLong(3)) }
                .groupBy({ it.first }, { IndexSize(it.second, it.third) })
        }.getOrNull()

        val mariaDb = runCatching { run.query("SELECT VERSION()") { it.getString(1) }.firstOrNull() }
            .getOrNull().let(ServerFlavor::isMariaDb)

        StorageSnapshot(
            database = database,
            tables = if (indexSizes != null) mergeIndexSizes(tables, indexSizes) else tables,
            indexSizesAvailable = indexSizes != null,
            unused = source { if (mariaDb) unusedOnMariaDb(run, database) else unusedOnMySql(run, database) },
            redundant = if (mariaDb) {
                StorageSource.Unavailable(UnavailableKind.NOT_ON_SERVER)
            } else {
                source { redundant(run, database) }
            },
            uptimeSeconds = runCatching {
                run.query("SHOW GLOBAL STATUS LIKE 'Uptime'") { it.getString(2)?.toLongOrNull() }.firstOrNull()
            }.getOrNull(),
        )
    }

    private fun readTable(rows: ResultSet, autoIncrementTypes: Map<String, String?>): StorageTable {
        val name = rows.getString(1)
        return StorageTable(
            name = name,
            engine = rows.getString(2),
            rowFormat = rows.getString(3),
            rowsEstimate = rows.longOrNull(4),
            dataBytes = rows.longOrNull(5) ?: 0,
            indexBytes = rows.longOrNull(6) ?: 0,
            freeBytes = rows.longOrNull(7) ?: 0,
            autoIncrement = rows.getString(8)?.toBigIntegerOrNull(),
            autoIncrementType = autoIncrementTypes[name],
            createTime = rows.getString(9),
            updateTime = rows.getString(10),
            collation = rows.getString(11),
        )
    }

    private fun ResultSet.longOrNull(column: Int): Long? = getLong(column).takeUnless { wasNull() }

    /**
     * MySQL: the `sys` view when the schema exists, otherwise the performance schema it reads.
     *
     * Unique indexes are never reported — they enforce a constraint whether or not a query ever
     * reads them, so "unused" would be the wrong word.
     */
    private fun unusedOnMySql(run: Runner, database: String): StorageSource<UnusedIndexes> {
        // With the instrument or the whole schema off, every counter is zero and every index would
        // be named unused; better to say the answer is not there.
        val enabled = runCatching { run.query("SELECT @@performance_schema") { it.getInt(1) }.firstOrNull() }.getOrNull()
        if (enabled == 0) return StorageSource.Unavailable(UnavailableKind.PERFORMANCE_SCHEMA_OFF)
        val instrument = runCatching {
            run.query(
                "SELECT ENABLED FROM performance_schema.setup_instruments WHERE NAME = 'wait/io/table/sql/handler'",
            ) { it.getString(1) }.firstOrNull()
        }.getOrNull()
        if (instrument.equals("NO", ignoreCase = true)) {
            return StorageSource.Unavailable(UnavailableKind.PERFORMANCE_SCHEMA_OFF)
        }

        val viaSys = runCatching { run.query(UNUSED_SYS, database) { IndexRef(it.getString(1), it.getString(2)) } }
        val found = viaSys.getOrNull() ?: runCatching {
            run.query(UNUSED_PERFORMANCE_SCHEMA, database, database) { IndexRef(it.getString(1), it.getString(2)) }
        }.getOrElse { return StorageSource.Unavailable(UnavailableKind.FAILED, firstLine(it)) }
        return StorageSource.Loaded(UnusedIndexes(found, fromUserstat = false))
    }

    /** MariaDB counts index reads only with `userstat` on; without it there is nothing to ask. */
    private fun unusedOnMariaDb(run: Runner, database: String): StorageSource<UnusedIndexes> {
        val userstat = runCatching { run.query("SELECT @@userstat") { it.getInt(1) }.firstOrNull() }
            .getOrElse { return StorageSource.Unavailable(UnavailableKind.FAILED, firstLine(it)) }
        if (userstat != 1) return StorageSource.Unavailable(UnavailableKind.USERSTAT_OFF)
        val found = run.query(UNUSED_USERSTAT, database, database) { IndexRef(it.getString(1), it.getString(2)) }
        return StorageSource.Loaded(UnusedIndexes(found, fromUserstat = true))
    }

    private fun redundant(run: Runner, database: String): StorageSource<List<RedundantIndex>> {
        val rows = run.query(REDUNDANT, database) {
            RedundantIndex(
                table = it.getString(1),
                index = it.getString(2),
                columns = it.getString(3).orEmpty(),
                coveredBy = it.getString(4).orEmpty(),
                coveredByColumns = it.getString(5).orEmpty(),
            )
        }
        return StorageSource.Loaded(rows)
    }

    private inline fun <T> source(block: () -> StorageSource<T>): StorageSource<T> = try {
        block()
    } catch (e: SQLException) {
        StorageSource.Unavailable(UnavailableKind.FAILED, firstLine(e))
    }

    private fun firstLine(e: Throwable): String = e.message.orEmpty().lineSequence().firstOrNull().orEmpty()

    /** One statement at a time with the session's own timeout; parameters are bound, never pasted in. */
    private class Runner(private val connection: Connection, private val timeoutSeconds: Int) {
        fun <T> query(sql: String, vararg params: String, read: (ResultSet) -> T): List<T> =
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = timeoutSeconds
                params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) read(rows)?.let(::add) }
                }
            }
    }

    private companion object {
        const val TABLES = """
            SELECT TABLE_NAME, ENGINE, ROW_FORMAT, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, DATA_FREE,
                   AUTO_INCREMENT, CREATE_TIME, UPDATE_TIME, TABLE_COLLATION
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
            ORDER BY TABLE_NAME
        """

        const val AUTO_INCREMENT_TYPES = """
            SELECT TABLE_NAME, COLUMN_TYPE
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = ? AND EXTRA LIKE '%auto_increment%'
        """

        const val INDEX_SIZES = """
            SELECT table_name, index_name, stat_value * @@innodb_page_size
            FROM mysql.innodb_index_stats
            WHERE database_name = ? AND stat_name = 'size'
        """

        const val UNUSED_SYS = """
            SELECT object_name, index_name FROM sys.schema_unused_indexes
            WHERE object_schema = ? ORDER BY object_name, index_name
        """

        const val UNUSED_PERFORMANCE_SCHEMA = """
            SELECT t.OBJECT_NAME, t.INDEX_NAME
            FROM performance_schema.table_io_waits_summary_by_index_usage t
            JOIN (SELECT DISTINCT TABLE_NAME, INDEX_NAME FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = ? AND NON_UNIQUE = 1) s
              ON s.TABLE_NAME = t.OBJECT_NAME AND s.INDEX_NAME = t.INDEX_NAME
            WHERE t.OBJECT_SCHEMA = ? AND t.INDEX_NAME IS NOT NULL AND t.INDEX_NAME <> 'PRIMARY'
              AND t.COUNT_STAR = 0
            ORDER BY t.OBJECT_NAME, t.INDEX_NAME
        """

        // The first placeholder belongs to the derived table, the second to INDEX_STATISTICS.
        const val UNUSED_USERSTAT = """
            SELECT s.TABLE_NAME, s.INDEX_NAME
            FROM (SELECT DISTINCT TABLE_NAME, INDEX_NAME FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = ? AND NON_UNIQUE = 1) s
            LEFT JOIN information_schema.INDEX_STATISTICS u
              ON u.TABLE_SCHEMA = ? AND u.TABLE_NAME = s.TABLE_NAME AND u.INDEX_NAME = s.INDEX_NAME
            WHERE u.INDEX_NAME IS NULL
            ORDER BY s.TABLE_NAME, s.INDEX_NAME
        """

        const val REDUNDANT = """
            SELECT table_name, redundant_index_name, redundant_index_columns,
                   dominant_index_name, dominant_index_columns
            FROM sys.schema_redundant_indexes
            WHERE table_schema = ? ORDER BY table_name, redundant_index_name
        """
    }
}
