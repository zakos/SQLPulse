package hu.laurel.sqlpulse.data.schema

import androidx.room.withTransaction
import hu.laurel.sqlpulse.data.db.CachedColumnEntity
import hu.laurel.sqlpulse.data.db.CachedDatabaseEntity
import hu.laurel.sqlpulse.data.db.CachedForeignKeyEntity
import hu.laurel.sqlpulse.data.db.CachedIndexEntity
import hu.laurel.sqlpulse.data.db.CachedTableEntity
import hu.laurel.sqlpulse.data.db.SchemaCacheDao
import hu.laurel.sqlpulse.data.db.SqlPulseDatabase
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * The offline schema cache's storage side (§11).
 *
 * The rule the whole feature stands on is in one place: **the server always wins**. Every read
 * here asks the session first, and only reaches for the stored copy when there is no session at
 * all. A live answer is never merged with a cached one, never topped up from it and never
 * silently replaced by it — a table the server has dropped disappears from the screen the moment
 * the server says so, and what comes back from disk is handed to the UI as
 * [SchemaView.Cached], which cannot be rendered without also having the moment it was taken.
 *
 * The decisions — stale, expired, what to show, what a refresh changes — are not here. They live
 * in [SchemaCache], which has no Android, Room or JDBC import and is therefore actually tested.
 * This class is the plumbing around them: it talks to [SchemaRepository] for the live read, to
 * [SchemaCacheDao] for the stored one, and translates between the live models and the cache's
 * own.
 *
 * The two vocabularies are kept apart on purpose. [SchemaTable] describes what a server said a
 * moment ago and is free to grow a field for some live-only detail; [CachedTable] describes what
 * is on disk and has to keep meaning the same thing months later. The mapping below is the only
 * place they meet, so a change to either one fails here rather than quietly reinterpreting old
 * rows.
 */
@Singleton
class SchemaCacheRepository @Inject constructor(
    private val room: SqlPulseDatabase,
    private val cache: SchemaCacheDao,
    private val schema: SchemaRepository,
    private val sessions: SqlSessionManager,
) {

    /**
     * The staleness rule, and the clock it is measured against.
     *
     * Neither is a constructor parameter: Hilt builds this class, and a parameter it cannot
     * provide would have to be bound in a module for the sake of a value nothing varies at
     * runtime. [SchemaCache] takes both as arguments, so the rules stay testable without this
     * class having to be constructible in a test.
     */
    private val policy = SchemaCachePolicy()

    private fun now(): Long = System.currentTimeMillis()

    /** True only with a session that can actually be asked. Anything else browses the cache. */
    val online: Boolean get() = sessions.state.value is SqlSessionState.Ready

    /**
     * When this connection's database list was captured, as a flow.
     *
     * A flow because the marker has to correct itself the instant a refresh lands: a line reading
     * "cached, taken 3 days ago" left over the top of freshly fetched tables would be worse than
     * no marker at all.
     */
    fun observeDatabasesCapturedAt(connectionId: Long): Flow<Long?> =
        cache.observeDatabasesCapturedAt(connectionId)

    fun observeTablesCapturedAt(connectionId: Long, database: String): Flow<Long?> =
        cache.observeTablesCapturedAt(connectionId, database)

    /**
     * The database list: live when there is a session, otherwise what was captured.
     *
     * [userAsked] is the refresh button. It only forces the capture to be rewritten — the live
     * read happens either way, because with a session there is nothing to decide.
     */
    suspend fun databases(connectionId: Long, userAsked: Boolean = false): SchemaView<List<String>> {
        if (online) {
            val live = schema.databases()
            val capturedAt = cache.databases(connectionId).maxOfOrNull { it.capturedAt }
            if (SchemaCache.shouldCapture(capturedAt, now(), userAsked, policy)) {
                captureDatabases(connectionId, live)
            }
            return SchemaView.Live(live)
        }
        val stored = cache.databases(connectionId)
        val capturedAt = stored.maxOfOrNull { it.capturedAt }
        val snapshot = capturedAt?.let { SchemaSnapshot(stored.map { row -> row.name }, it) }
        return SchemaCache.view(live = null, cached = snapshot, now = now(), policy = policy)
    }

    /** The tables of one database, live or cached, marked either way. */
    suspend fun tables(
        connectionId: Long,
        database: String,
        userAsked: Boolean = false,
    ): SchemaView<List<SchemaTable>> {
        if (online) {
            val live = schema.tables(database, refresh = userAsked)
            val capturedAt = cache.tables(connectionId, database).maxOfOrNull { it.capturedAt }
            if (SchemaCache.shouldCapture(capturedAt, now(), userAsked, policy)) {
                captureTables(connectionId, database, live)
            }
            return SchemaView.Live(live)
        }
        val stored = cache.tables(connectionId, database)
        val capturedAt = stored.maxOfOrNull { it.capturedAt }
        val snapshot = capturedAt?.let {
            SchemaSnapshot(stored.map { row -> row.toCached().toSchemaTable() }, it)
        }
        return SchemaCache.view(live = null, cached = snapshot, now = now(), policy = policy)
    }

    /** One table's columns, indexes and foreign keys, live or cached, marked either way. */
    suspend fun structure(
        connectionId: Long,
        database: String,
        table: String,
        userAsked: Boolean = false,
    ): SchemaView<TableStructure> {
        if (online) {
            val live = schema.structure(database, table, refresh = userAsked)
            val capturedAt = cache.structureCapturedAt(connectionId, database, table)
            if (SchemaCache.shouldCapture(capturedAt, now(), userAsked, policy)) {
                captureStructure(connectionId, database, table, live)
            }
            return SchemaView.Live(live)
        }
        val capturedAt = cache.structureCapturedAt(connectionId, database, table)
        val snapshot = capturedAt?.let {
            SchemaSnapshot(
                CachedStructure(
                    columns = cache.columns(connectionId, database, table).map { row -> row.toCached() },
                    indexes = cache.indexes(connectionId, database, table).map { row -> row.toCached() },
                    foreignKeys = cache.foreignKeys(connectionId, database, table)
                        .map { row -> row.toCached() },
                ).toTableStructure(),
                it,
            )
        }
        return SchemaCache.view(live = null, cached = snapshot, now = now(), policy = policy)
    }

    // ------------------------------------------------------------- capturing

    /**
     * Writes a database list back, as a difference rather than a rewrite.
     *
     * One transaction, and the deletions come from [SchemaCache.merge] rather than from a blanket
     * `DELETE`: a wipe-then-insert leaves the cache empty if the insert fails halfway, and this
     * data exists for exactly the moments when a write can be interrupted.
     */
    private suspend fun captureDatabases(connectionId: Long, databases: List<String>) {
        val capturedAt = now()
        val stored = cache.databases(connectionId)
        val fresh = databases.map { CachedDatabaseEntity(connectionId, it, capturedAt) }
        val merge = SchemaCache.merge(stored.map { it.name }, databases) { it }
        room.withTransaction {
            cache.upsertDatabases(fresh)
            if (merge.removedKeys.isNotEmpty()) {
                cache.deleteDatabases(connectionId, merge.removedKeys)
            }
        }
    }

    private suspend fun captureTables(connectionId: Long, db: String, tables: List<SchemaTable>) {
        val capturedAt = now()
        val stored = cache.tables(connectionId, db)
        val storedByKey = stored.associateBy { "${it.database}.${it.name}" }
        val merge = SchemaCache.mergeTables(
            cached = stored.map { it.toCached() },
            fresh = tables.map { it.toCached() },
        )
        // The structure timestamps belong to the rows that already exist: a table whose columns
        // were captured yesterday has not had them re-read just because its row was rewritten.
        val structureTimes = stored.associate { it.name to it.structureCapturedAt }
        val rows = tables.map { it.toEntity(connectionId, capturedAt, structureTimes[it.name]) }
        room.withTransaction {
            cache.upsertTables(rows)
            if (merge.removedKeys.isNotEmpty()) {
                val gone = merge.removedKeys.mapNotNull { storedByKey[it]?.name }
                cache.deleteTables(connectionId, db, gone)
                // A dropped table's structure would otherwise outlive the table itself.
                gone.forEach { table ->
                    cache.deleteColumns(connectionId, db, table)
                    cache.deleteIndexes(connectionId, db, table)
                    cache.deleteForeignKeys(connectionId, db, table)
                }
            }
        }
    }

    /**
     * Writes one table's structure back.
     *
     * Replaced wholesale rather than merged, unlike the lists above: a structure is small, it is
     * always read as a unit, and a column that was renamed has no identity to merge on. The
     * timestamp is only moved once the new rows are in, inside the same transaction, so a capture
     * that fails leaves the old structure with its old, honest date.
     */
    private suspend fun captureStructure(
        connectionId: Long,
        db: String,
        table: String,
        structure: TableStructure,
    ) {
        val capturedAt = now()
        room.withTransaction {
            cache.deleteColumns(connectionId, db, table)
            cache.deleteIndexes(connectionId, db, table)
            cache.deleteForeignKeys(connectionId, db, table)
            cache.upsertColumns(
                structure.columns.mapIndexed { position, column ->
                    CachedColumnEntity(
                        connectionId = connectionId,
                        database = db,
                        tableName = table,
                        name = column.name,
                        typeName = column.typeName,
                        nullable = column.nullable,
                        defaultValue = column.defaultValue,
                        isPrimaryKey = column.isPrimaryKey,
                        extra = column.extra,
                        comment = column.comment,
                        position = position,
                    )
                },
            )
            cache.upsertIndexes(
                structure.indexes.mapIndexed { position, index ->
                    CachedIndexEntity(
                        connectionId = connectionId,
                        database = db,
                        tableName = table,
                        name = index.name,
                        isUnique = index.unique,
                        columns = SchemaCache.joinColumns(index.columns),
                        position = position,
                    )
                },
            )
            cache.upsertForeignKeys(
                structure.foreignKeys.map { key ->
                    CachedForeignKeyEntity(
                        connectionId = connectionId,
                        database = db,
                        tableName = table,
                        constraintName = key.constraintName,
                        column = key.column,
                        referencedDatabase = key.referencedDatabase,
                        referencedTable = key.referencedTable,
                        referencedColumn = key.referencedColumn,
                    )
                },
            )
            cache.markStructureCaptured(connectionId, db, table, capturedAt)
        }
    }

    /**
     * Forgets everything cached for one connection.
     *
     * Used when the user asks, and when a capture is older than
     * [SchemaCachePolicy.expireAfterMillis] — past that point the rows are deleted rather than
     * shown with a louder warning, because a schema nobody has looked at in a month is more
     * likely to mislead than to help.
     */
    suspend fun forget(connectionId: Long) {
        room.withTransaction {
            cache.deleteAllColumns(connectionId)
            cache.deleteAllIndexes(connectionId)
            cache.deleteAllForeignKeys(connectionId)
            cache.deleteAllTables(connectionId)
            cache.deleteAllDatabases(connectionId)
        }
    }

    // -------------------------------------------------------------- mapping

    private fun SchemaTable.toCached(): CachedTable = CachedTable(
        database = database,
        name = name,
        kind = kind.name,
        approximateRows = approximateRows,
        comment = comment,
        engine = engine,
        collation = collation,
        dataBytes = dataBytes,
        indexBytes = indexBytes,
    )

    private fun SchemaTable.toEntity(
        connectionId: Long,
        capturedAt: Long,
        structureCapturedAt: Long?,
    ): CachedTableEntity = CachedTableEntity(
        connectionId = connectionId,
        database = database,
        name = name,
        kind = kind.name,
        approximateRows = approximateRows,
        comment = comment,
        engine = engine,
        collation = collation,
        dataBytes = dataBytes,
        indexBytes = indexBytes,
        capturedAt = capturedAt,
        structureCapturedAt = structureCapturedAt,
    )

    private fun CachedTableEntity.toCached(): CachedTable = CachedTable(
        database = database,
        name = name,
        kind = kind,
        approximateRows = approximateRows,
        comment = comment,
        engine = engine,
        collation = collation,
        dataBytes = dataBytes,
        indexBytes = indexBytes,
    )

    /**
     * A stored row read back as a live-shaped table.
     *
     * An unknown [CachedTable.kind] reads as a plain table rather than throwing: the value was
     * written by an older version of this app, and refusing to show the row would lose the user
     * their offline schema over a word.
     */
    private fun CachedTable.toSchemaTable(): SchemaTable = SchemaTable(
        database = database,
        name = name,
        kind = if (kind == TableKind.VIEW.name) TableKind.VIEW else TableKind.TABLE,
        approximateRows = approximateRows,
        comment = comment,
        engine = engine,
        collation = collation,
        dataBytes = dataBytes,
        indexBytes = indexBytes,
    )

    private fun CachedColumnEntity.toCached(): CachedColumn = CachedColumn(
        name = name,
        typeName = typeName,
        nullable = nullable,
        defaultValue = defaultValue,
        isPrimaryKey = isPrimaryKey,
        extra = extra,
        comment = comment,
        position = position,
    )

    private fun CachedIndexEntity.toCached(): CachedIndex = CachedIndex(
        name = name,
        unique = isUnique,
        columns = SchemaCache.splitColumns(columns),
        position = position,
    )

    private fun CachedForeignKeyEntity.toCached(): CachedForeignKey = CachedForeignKey(
        constraintName = constraintName,
        column = column,
        referencedDatabase = referencedDatabase,
        referencedTable = referencedTable,
        referencedColumn = referencedColumn,
    )

    private fun CachedStructure.toTableStructure(): TableStructure = TableStructure(
        columns = columns.sortedBy { it.position }.map {
            SchemaColumn(
                name = it.name,
                typeName = it.typeName,
                nullable = it.nullable,
                defaultValue = it.defaultValue,
                isPrimaryKey = it.isPrimaryKey,
                extra = it.extra,
                comment = it.comment,
            )
        },
        indexes = indexes.sortedBy { it.position }.map {
            SchemaIndex(name = it.name, unique = it.unique, columns = it.columns)
        },
        foreignKeys = foreignKeys.map {
            ForeignKey(
                constraintName = it.constraintName,
                column = it.column,
                referencedDatabase = it.referencedDatabase,
                referencedTable = it.referencedTable,
                referencedColumn = it.referencedColumn,
            )
        },
    )
}
