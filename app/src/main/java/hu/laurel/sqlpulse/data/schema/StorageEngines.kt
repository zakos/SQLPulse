package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import java.math.BigInteger
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

/** One statement at a time with the session's own timeout; parameters are bound, never pasted in. */
internal class StorageRunner(private val connection: Connection, private val timeoutSeconds: Int) {
    fun <T> query(sql: String, vararg params: String, read: (ResultSet) -> T?): List<T> =
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = timeoutSeconds
            params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) read(rows)?.let(::add) }
            }
        }
}

internal fun ResultSet.longOrNull(column: Int): Long? = getLong(column).takeUnless { wasNull() }

internal fun firstLine(e: Throwable): String = e.message.orEmpty().lineSequence().firstOrNull().orEmpty()

/** A source that fails on its own: the section says why instead of the screen failing. */
internal inline fun <T> storageSource(block: () -> StorageSource<T>): StorageSource<T> = try {
    block()
} catch (e: SQLException) {
    StorageSource.Unavailable(UnavailableKind.FAILED, firstLine(e))
}

/**
 * The storage screen for PostgreSQL. The "database" the screen asks about is a schema here (the
 * app's namespace, see docs/tobb-motor-terv.md).
 *
 * Sizes come from the `pg_*_size` functions, which include TOAST for the table; row counts are
 * the statistics collector's `n_live_tup` (an estimate, like MySQL's `TABLE_ROWS`), falling back
 * to the planner's `reltuples` for a table the collector has not seen. A serial or identity
 * column's headroom is read from the sequence that backs it, since there is no AUTO_INCREMENT.
 */
internal object PostgresStorage {

    fun load(run: StorageRunner, schema: String): StorageSnapshot {
        val sequences = runCatching {
            run.query(SEQUENCES, schema) { rows ->
                Sequence(rows.getString(1), rows.getString(2), rows.getString(3)?.toBigIntegerOrNull())
            }.groupBy { it.table }
        }.getOrDefault(emptyMap())
        val tables = run.query(TABLES, schema) { rows ->
            val name = rows.getString(1)
            val sequence = sequences[name]?.maxByOrNull { it.lastValue ?: BigInteger.ZERO }
            StorageTable(
                name = name,
                engine = null,
                rowFormat = null,
                rowsEstimate = rows.longOrNull(2),
                dataBytes = rows.longOrNull(3) ?: 0,
                indexBytes = rows.longOrNull(4) ?: 0,
                freeBytes = 0,
                autoIncrement = sequence?.let { (it.lastValue ?: BigInteger.ZERO).add(BigInteger.ONE) },
                autoIncrementType = sequence?.type,
                createTime = null,
                updateTime = null,
                collation = null,
                counter = CounterKind.SEQUENCE,
            )
        }
        val indexSizes = runCatching {
            run.query(INDEX_SIZES, schema) { Triple(it.getString(1), it.getString(2), it.getLong(3)) }
                .groupBy({ it.first }, { IndexSize(it.second, it.third) })
        }.getOrNull()

        // Counted since the statistics were last reset, which is what the usage counters mean.
        val sinceReset = runCatching { run.query(STATS_AGE) { it.longOrNull(1) }.firstOrNull() }.getOrNull()
        val sinceStart = runCatching { run.query(SERVER_AGE) { it.longOrNull(1) }.firstOrNull() }.getOrNull()
        return StorageSnapshot(
            database = schema,
            engine = DatabaseEngine.POSTGRESQL,
            tables = if (indexSizes != null) mergeIndexSizes(tables, indexSizes) else tables,
            indexSizesAvailable = indexSizes != null,
            unused = storageSource {
                val found = run.query(UNUSED, schema) { IndexRef(it.getString(1), it.getString(2)) }
                StorageSource.Loaded(
                    UnusedIndexes(
                        found,
                        fromUserstat = false,
                        basis = if (sinceReset != null) UnusedBasis.STATS_RESET else UnusedBasis.SERVER_START,
                    ),
                )
            },
            redundant = StorageSource.Unavailable(UnavailableKind.NOT_FOR_ENGINE),
            uptimeSeconds = sinceReset ?: sinceStart,
        )
    }

    private data class Sequence(val table: String, val type: String?, val lastValue: BigInteger?)

    // Partitions (relkind 'p') are listed too; their size is what their children hold.
    const val TABLES = """
        SELECT c.relname,
               CASE WHEN s.n_live_tup > 0 THEN s.n_live_tup
                    WHEN c.reltuples > 0 THEN c.reltuples::bigint ELSE 0 END,
               pg_total_relation_size(c.oid) - pg_indexes_size(c.oid),
               pg_indexes_size(c.oid)
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
        WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
        ORDER BY c.relname
    """

    // The primary key is the table's own order, not an extra cost worth listing.
    const val INDEX_SIZES = """
        SELECT t.relname, i.relname, pg_relation_size(i.oid)
        FROM pg_index x
        JOIN pg_class i ON i.oid = x.indexrelid
        JOIN pg_class t ON t.oid = x.indrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = ? AND NOT x.indisprimary AND t.relkind IN ('r', 'p')
    """

    // serial ('a') and identity ('i') columns own their sequence; last_value is NULL until first use
    // and also when the user may not read the sequence.
    const val SEQUENCES = """
        SELECT t.relname, s.data_type::text, s.last_value::text
        FROM pg_class t
        JOIN pg_namespace n ON n.oid = t.relnamespace
        JOIN pg_depend d ON d.refobjid = t.oid AND d.deptype IN ('a', 'i')
        JOIN pg_class q ON q.oid = d.objid AND q.relkind = 'S'
        JOIN pg_namespace qn ON qn.oid = q.relnamespace
        JOIN pg_sequences s ON s.schemaname = qn.nspname AND s.sequencename = q.relname
        WHERE n.nspname = ?
    """

    // Unique and primary indexes enforce a constraint whether or not a query reads them.
    const val UNUSED = """
        SELECT s.relname, s.indexrelname
        FROM pg_stat_user_indexes s
        JOIN pg_index i ON i.indexrelid = s.indexrelid
        WHERE s.schemaname = ? AND s.idx_scan = 0 AND NOT i.indisunique AND NOT i.indisprimary
        ORDER BY s.relname, s.indexrelname
    """

    const val STATS_AGE = """
        SELECT CAST(EXTRACT(EPOCH FROM (now() - stats_reset)) AS bigint)
        FROM pg_stat_database WHERE datname = current_database()
    """

    const val SERVER_AGE = "SELECT CAST(EXTRACT(EPOCH FROM (now() - pg_postmaster_start_time())) AS bigint)"
}

/**
 * The storage screen for SQL Server and Azure SQL; the "database" is a schema.
 *
 * `sys.dm_db_partition_stats` counts 8 KB pages: used pages of the heap or clustered index are the
 * data, used pages of the other indexes are the indexes, reserved minus used is free. It needs
 * VIEW DATABASE STATE, which is the one source the screen cannot do without here. Identity
 * headroom is the identity column's `last_value` against its type (a `tinyint` is unsigned 0..255).
 * Index usage lives in a DMV that is emptied when the server restarts.
 */
internal object SqlServerStorage {

    private const val PAGE = 8192L

    fun load(run: StorageRunner, schema: String): StorageSnapshot {
        val identities = runCatching {
            run.query(IDENTITIES, schema) { rows ->
                Identity(rows.getString(1), rows.getString(2), rows.getString(3)?.toBigIntegerOrNull())
            }.groupBy { it.table }
        }.getOrDefault(emptyMap())
        val tables = run.query(TABLES, schema) { rows ->
            val name = rows.getString(1)
            val identity = identities[name]?.firstOrNull()
            StorageTable(
                name = name,
                engine = null,
                rowFormat = null,
                rowsEstimate = rows.longOrNull(2),
                dataBytes = (rows.longOrNull(3) ?: 0) * PAGE,
                indexBytes = (rows.longOrNull(4) ?: 0) * PAGE,
                freeBytes = (rows.longOrNull(5) ?: 0) * PAGE,
                autoIncrement = identity?.let { (it.lastValue ?: BigInteger.ZERO).add(BigInteger.ONE) },
                autoIncrementType = identity?.type?.let(::headroomType),
                createTime = rows.getString(6),
                updateTime = rows.getString(7),
                collation = null,
                counter = CounterKind.IDENTITY,
            )
        }
        val indexSizes = runCatching {
            run.query(INDEX_SIZES, schema) { Triple(it.getString(1), it.getString(2), (it.longOrNull(3) ?: 0) * PAGE) }
                .groupBy({ it.first }, { IndexSize(it.second, it.third) })
        }.getOrNull()
        val uptime = runCatching { run.query(UPTIME) { it.longOrNull(1) }.firstOrNull() }.getOrNull()
        return StorageSnapshot(
            database = schema,
            engine = DatabaseEngine.SQLSERVER,
            tables = if (indexSizes != null) mergeIndexSizes(tables, indexSizes) else tables,
            indexSizesAvailable = indexSizes != null,
            unused = storageSource {
                val found = run.query(UNUSED, schema) { IndexRef(it.getString(1), it.getString(2)) }
                StorageSource.Loaded(UnusedIndexes(found, fromUserstat = false))
            },
            redundant = StorageSource.Unavailable(UnavailableKind.NOT_FOR_ENGINE),
            uptimeSeconds = uptime,
        )
    }

    private data class Identity(val table: String, val type: String?, val lastValue: BigInteger?)

    /** SQL Server's `tinyint` has no sign; the headroom maths knows MySQL's spelling of that. */
    fun headroomType(sqlServerType: String): String =
        if (sqlServerType.equals("tinyint", ignoreCase = true)) "tinyint unsigned" else sqlServerType

    const val TABLES = """
        SELECT t.name,
               SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.row_count ELSE 0 END),
               SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.used_page_count ELSE 0 END),
               SUM(CASE WHEN ps.index_id > 1 THEN ps.used_page_count ELSE 0 END),
               SUM(ps.reserved_page_count) - SUM(ps.used_page_count),
               CONVERT(varchar(19), t.create_date, 120),
               CONVERT(varchar(19), t.modify_date, 120)
        FROM sys.tables t
        JOIN sys.schemas s ON s.schema_id = t.schema_id
        JOIN sys.dm_db_partition_stats ps ON ps.object_id = t.object_id
        WHERE s.name = ?
        GROUP BY t.object_id, t.name, t.create_date, t.modify_date
        ORDER BY t.name
    """

    const val INDEX_SIZES = """
        SELECT t.name, i.name, SUM(ps.used_page_count)
        FROM sys.dm_db_partition_stats ps
        JOIN sys.indexes i ON i.object_id = ps.object_id AND i.index_id = ps.index_id
        JOIN sys.tables t ON t.object_id = ps.object_id
        JOIN sys.schemas s ON s.schema_id = t.schema_id
        WHERE s.name = ? AND i.index_id > 1 AND i.is_primary_key = 0 AND i.name IS NOT NULL
        GROUP BY t.name, i.name
    """

    const val IDENTITIES = """
        SELECT t.name, ty.name, CAST(ic.last_value AS decimal(38, 0))
        FROM sys.identity_columns ic
        JOIN sys.tables t ON t.object_id = ic.object_id
        JOIN sys.schemas s ON s.schema_id = t.schema_id
        JOIN sys.types ty ON ty.user_type_id = ic.user_type_id
        WHERE s.name = ?
    """

    // An index with no row in the usage DMV has not been touched since the server started.
    const val UNUSED = """
        SELECT t.name, i.name
        FROM sys.indexes i
        JOIN sys.tables t ON t.object_id = i.object_id
        JOIN sys.schemas s ON s.schema_id = t.schema_id
        LEFT JOIN sys.dm_db_index_usage_stats u
               ON u.object_id = i.object_id AND u.index_id = i.index_id AND u.database_id = DB_ID()
        WHERE s.name = ? AND i.index_id > 1 AND i.is_unique = 0 AND i.is_primary_key = 0
          AND i.is_disabled = 0 AND i.name IS NOT NULL
          AND ISNULL(u.user_seeks, 0) + ISNULL(u.user_scans, 0) + ISNULL(u.user_lookups, 0) = 0
        ORDER BY t.name, i.name
    """

    const val UPTIME = "SELECT DATEDIFF(SECOND, sqlserver_start_time, SYSDATETIME()) FROM sys.dm_os_sys_info"
}

/**
 * The storage screen for a SQLite file. The file is the database, so the numbers that matter are
 * its page count and free list; per-table sizes come from the `dbstat` virtual table, which is
 * compiled into the driver the app ships but is optional in SQLite builds — without it the tables
 * are listed with row counts and the screen says sizes are not available.
 */
internal object SqliteStorage {

    /** Counting rows reads a whole table; past this size the count is left out rather than made slow. */
    const val COUNT_UP_TO_BYTES = 256L * 1024 * 1024

    fun load(run: StorageRunner, quote: (String) -> String, schema: String): StorageSnapshot {
        val q = quote(schema)
        fun pragma(name: String): Long? = runCatching {
            run.query("PRAGMA $q.$name") { it.getLong(1) }.firstOrNull()
        }.getOrNull()
        val pageSize = pragma("page_size")
        val pageCount = pragma("page_count")
        val free = pragma("freelist_count")
        val fileBytes = if (pageSize != null && pageCount != null) pageSize * pageCount else null

        val names = run.query(
            "SELECT name FROM $q.sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite!_%' ESCAPE '!' ORDER BY name",
        ) { it.getString(1) }
        val indexNames = runCatching {
            run.query("SELECT tbl_name, name FROM $q.sqlite_schema WHERE type = 'index'") { it.getString(1) to it.getString(2) }
                .groupBy({ it.first }, { it.second })
        }.getOrDefault(emptyMap())

        // dbstat has one row per page; aggregate = true folds them per b-tree (table or index).
        val sizes: Map<String, Pair<Long, Long>>? = runCatching {
            run.query("SELECT name, SUM(pgsize), SUM(unused) FROM dbstat WHERE schema = ? AND aggregate = TRUE GROUP BY name", schema) {
                it.getString(1) to (it.getLong(2) to it.getLong(3))
            }.toMap()
        }.getOrNull()

        val countRows = fileBytes == null || fileBytes <= COUNT_UP_TO_BYTES
        val tables = names.map { name ->
            val rows = if (countRows) {
                runCatching { run.query("SELECT COUNT(*) FROM $q.${quote(name)}") { it.getLong(1) }.firstOrNull() }.getOrNull()
            } else {
                null
            }
            val own = sizes?.get(name)
            val tableIndexes = indexNames[name].orEmpty()
            val indexes = tableIndexes.mapNotNull { index -> sizes?.get(index)?.let { IndexSize(index, it.first) } }
            StorageTable(
                name = name,
                engine = null,
                rowFormat = null,
                rowsEstimate = rows,
                dataBytes = own?.first ?: 0,
                indexBytes = indexes.sumOf { it.bytes },
                freeBytes = (own?.second ?: 0) + tableIndexes.sumOf { sizes?.get(it)?.second ?: 0 },
                autoIncrement = null,
                autoIncrementType = null,
                createTime = null,
                updateTime = null,
                collation = null,
                indexes = indexes.filter { !it.index.startsWith("sqlite_autoindex") }.sortedByDescending { it.bytes },
            )
        }
        return StorageSnapshot(
            database = schema,
            engine = DatabaseEngine.SQLITE,
            tables = tables,
            indexSizesAvailable = sizes != null,
            // SQLite keeps no index usage statistics at all.
            unused = StorageSource.Unavailable(UnavailableKind.NOT_FOR_ENGINE),
            redundant = StorageSource.Unavailable(UnavailableKind.NOT_FOR_ENGINE),
            uptimeSeconds = null,
            sizesAvailable = sizes != null,
            fileBytes = fileBytes,
            fileFreeBytes = if (pageSize != null && free != null) pageSize * free else null,
        )
    }
}
