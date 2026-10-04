package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.db.SchemaCacheDao
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialects
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One side of a schema comparison as it sits in the cache, with the moments it was taken.
 *
 * The times travel with the data for the same reason [SchemaView.Cached] carries its own: a
 * comparison against a capture from last month has to say so, or "production has no `discount`
 * column" reads as a fact about production today.
 */
data class SchemaCapture(
    val side: SchemaDiffSide,
    /** When the table list was read; null when this database has never been listed. */
    val tablesCapturedAt: Long?,
    /** How many of [SchemaDiffSide.tables] have their columns, indexes and keys captured. */
    val structuresCaptured: Int,
    /** The oldest of those structure captures: the comparison is only as fresh as this. */
    val oldestStructureAt: Long?,
    /** When this database's views and triggers were read; null when they never were. */
    val objectsCapturedAt: Long? = null,
) {
    val tableCount: Int get() = side.tables.size
    val complete: Boolean get() = structuresCaptured == tableCount
}

/**
 * Reads the two sides of a schema comparison.
 *
 * The app holds one SQL session at a time ([SqlSessionManager] follows the single tunnel), so the
 * two sides cannot both be read live. Both are therefore read from the offline cache, the same
 * rows the schema browser falls back to, and the side whose connection is open can be refreshed
 * first: the refresh goes through [SchemaCacheRepository], which reads the server and writes the
 * cache in one step, so the comparison never has to know which side came from where.
 */
@Singleton
class SchemaDiffRepository @Inject constructor(
    private val cache: SchemaCacheDao,
    private val schemaCache: SchemaCacheRepository,
    private val sessions: SqlSessionManager,
) {

    /** The connection the session is open on, or null without one. Only that side can be refreshed. */
    fun liveConnectionId(): Long? = sessions.state.value.liveId()

    /** [liveConnectionId] as it changes, so the refresh button appears and goes with the session. */
    val liveConnection: Flow<Long?> = sessions.state.map { it.liveId() }.distinctUntilChanged()

    private fun SqlSessionState.liveId(): Long? = (this as? SqlSessionState.Ready)?.connection?.id

    /**
     * The databases a side can be pointed at.
     *
     * Live for the open connection (and captured on the way), otherwise whatever was ever listed or
     * browsed. The server's own schemas are left out: comparing `mysql` between two servers says
     * which version each one runs, which the connection card already does. What counts as the
     * server's own is the [engine]'s call (`pg_catalog`, `sys`, …).
     */
    suspend fun databases(connectionId: Long, engine: DatabaseEngine = DatabaseEngine.MYSQL): List<String> {
        val system = SqlDialects.forEngine(engine).systemNamespaces.map { it.lowercase() }.toSet()
        if (liveConnectionId() == connectionId) {
            val live = schemaCache.databases(connectionId).valueOrNull()
            if (live != null) return live.filter { it.lowercase() !in system }
        }
        val listed = cache.databases(connectionId).map { it.name }
        val browsed = cache.databasesWithTables(connectionId)
        return (listed + browsed).distinct()
            .filter { it.lowercase() !in system }
            .sortedBy { it.lowercase() }
    }

    /**
     * Everything the cache holds about one database, assembled into a comparable side.
     * [engine] is the connection's; it is stamped on the side because the cache does not know it.
     */
    suspend fun load(connectionId: Long, database: String, engine: DatabaseEngine = DatabaseEngine.MYSQL): SchemaCapture {
        val tableRows = cache.tables(connectionId, database)
        val captured = tableRows.filter { it.structureCapturedAt != null }.map { it.name }.toSet()
        val columns = cache.columnsOfDatabase(connectionId, database).groupBy { it.tableName }
        val indexes = cache.indexesOfDatabase(connectionId, database).groupBy { it.tableName }
        val keys = cache.foreignKeysOfDatabase(connectionId, database).groupBy { it.tableName }
        val checks = cache.checksOfDatabase(connectionId, database).groupBy { it.tableName }
        val checksKnown = tableRows.filter { it.checksCapturedAt != null }.map { it.name }.toSet()
        // Only a table with a structure timestamp counts as captured: a table with no columns
        // stored and no timestamp was never opened, which is not the same as having no columns.
        val structures = captured.associateWith { table ->
            CachedStructure(
                columns = columns[table].orEmpty().map {
                    CachedColumn(
                        name = it.name,
                        typeName = it.typeName,
                        nullable = it.nullable,
                        defaultValue = it.defaultValue,
                        isPrimaryKey = it.isPrimaryKey,
                        extra = it.extra,
                        comment = it.comment,
                        position = it.position,
                    )
                },
                indexes = indexes[table].orEmpty().map {
                    CachedIndex(it.name, it.isUnique, SchemaCache.splitColumns(it.columns), it.position)
                },
                foreignKeys = keys[table].orEmpty().map {
                    CachedForeignKey(
                        it.constraintName, it.column, it.referencedDatabase, it.referencedTable, it.referencedColumn,
                        onDelete = it.onDelete, onUpdate = it.onUpdate,
                    )
                },
                // A structure captured before CHECK constraints were kept has none recorded, which
                // is not the same as having none: null keeps the comparison from guessing.
                checks = if (table in checksKnown) {
                    checks[table].orEmpty().map { CachedCheck(it.name, it.expression, it.enforced) }
                } else {
                    null
                },
            )
        }
        val tables = tableRows.map {
            CachedTable(
                database = it.database,
                name = it.name,
                kind = it.kind,
                approximateRows = it.approximateRows,
                comment = it.comment,
                engine = it.engine,
                collation = it.collation,
                dataBytes = it.dataBytes,
                indexBytes = it.indexBytes,
            )
        }
        val objectsAt = cache.objectsCapturedAt(connectionId, database)
        val views = if (objectsAt != null) {
            cache.views(connectionId, database).associate { it.name to it.definition }
        } else {
            emptyMap()
        }
        val triggers = if (objectsAt != null) {
            cache.triggers(connectionId, database).map { CachedTrigger(it.name, it.tableName, it.timing, it.event, it.body) }
        } else {
            null
        }
        return SchemaCapture(
            side = SchemaDiffSide(
                database = database,
                tables = tables,
                structures = structures,
                viewDefinitions = views,
                triggers = triggers,
                engine = engine,
            ),
            objectsCapturedAt = objectsAt,
            tablesCapturedAt = tableRows.maxOfOrNull { it.capturedAt },
            structuresCaptured = structures.size,
            oldestStructureAt = tableRows.mapNotNull { it.structureCapturedAt }.minOrNull(),
        )
    }

    /**
     * Reads one database from the open session into the cache, then loads it as above.
     *
     * [wanted] narrows which tables have their structure read: a table the other side does not
     * have needs no columns to be reported missing, and every structure is five round trips
     * through the tunnel. [onProgress] is told how far along the reads are.
     */
    suspend fun refresh(
        connectionId: Long,
        database: String,
        wanted: (String) -> Boolean = { true },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        engine: DatabaseEngine = DatabaseEngine.MYSQL,
    ): SchemaCapture {
        if (liveConnectionId() != connectionId) throw NotLiveException()
        val listed = schemaCache.tables(connectionId, database, userAsked = true)
        val tables = (listed as? SchemaView.Live)?.value ?: throw NotLiveException()
        val targets = tables.filter { wanted(it.name) }
        targets.forEachIndexed { index, table ->
            onProgress(index, targets.size)
            // A session that drops half-way makes this fall back to the cache rather than throw;
            // the capture times then say honestly which tables were refreshed and which were not.
            schemaCache.structure(connectionId, database, table.name, userAsked = true)
        }
        onProgress(targets.size, targets.size)
        // The views' text and the triggers come with one statement each. A server that refuses
        // them (no privilege, an engine quirk) must not throw away the structures just read: the
        // comparison then says those objects were not captured, which is the truth.
        try {
            schemaCache.objects(connectionId, database, userAsked = true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Left as it was.
        }
        return load(connectionId, database, engine)
    }

    /** The side asked to be refreshed is not the one with the open session. */
    class NotLiveException : Exception("no live session for this connection")
}
