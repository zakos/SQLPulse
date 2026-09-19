package hu.laurel.sqlpulse.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_key ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_key WHERE id = :id")
    suspend fun byId(id: Long): SshKeyEntity?

    @Query("SELECT * FROM ssh_key WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun byFingerprint(fingerprint: String): SshKeyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(key: SshKeyEntity): Long

    @Query("DELETE FROM ssh_key WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Both columns: a key used only as a jump host's key is still in use, and deleting it would
     * break that connection's first hop.
     */
    @Query("SELECT COUNT(*) FROM connection WHERE sshKeyId = :keyId OR sshJumpKeyId = :keyId")
    suspend fun connectionsUsing(keyId: Long): Int

    @Query("DELETE FROM ssh_key")
    suspend fun deleteAll()
}

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connection ORDER BY lastUsedAt DESC, name ASC")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connection WHERE id = :id")
    fun observe(id: Long): Flow<ConnectionEntity?>

    @Query("SELECT * FROM connection WHERE id = :id")
    suspend fun byId(id: Long): ConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(connection: ConnectionEntity): Long

    @Update
    suspend fun update(connection: ConnectionEntity)

    @Delete
    suspend fun delete(connection: ConnectionEntity)

    @Query("UPDATE connection SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)
}

@Dao
interface DbCredentialDao {
    @Query("SELECT * FROM db_credential WHERE connectionId = :connectionId")
    suspend fun byConnection(connectionId: Long): DbCredentialEntity?

    @Upsert
    suspend fun upsert(credential: DbCredentialEntity)

    @Query("DELETE FROM db_credential WHERE connectionId = :connectionId")
    suspend fun delete(connectionId: Long)
}

@Dao
interface SshCredentialDao {
    @Query("SELECT * FROM ssh_credential WHERE connectionId = :connectionId")
    suspend fun byConnection(connectionId: Long): SshCredentialEntity?

    @Upsert
    suspend fun upsert(credential: SshCredentialEntity)

    @Query("DELETE FROM ssh_credential WHERE connectionId = :connectionId")
    suspend fun delete(connectionId: Long)
}

@Dao
interface SshJumpCredentialDao {
    @Query("SELECT * FROM ssh_jump_credential WHERE connectionId = :connectionId")
    suspend fun byConnection(connectionId: Long): SshJumpCredentialEntity?

    @Upsert
    suspend fun upsert(credential: SshJumpCredentialEntity)

    @Query("DELETE FROM ssh_jump_credential WHERE connectionId = :connectionId")
    suspend fun delete(connectionId: Long)
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_host WHERE host = :host AND port = :port")
    suspend fun find(host: String, port: Int): KnownHostEntity?

    @Upsert
    suspend fun upsert(host: KnownHostEntity)

    @Query("DELETE FROM known_host WHERE host = :host AND port = :port")
    suspend fun delete(host: String, port: Int)
}

@Dao
interface QueryHistoryDao {
    @Query("SELECT * FROM query_history WHERE connectionId = :connectionId ORDER BY executedAt DESC LIMIT :limit")
    fun observeRecent(connectionId: Long, limit: Int): Flow<List<QueryHistoryEntity>>

    @Insert
    suspend fun insert(entry: QueryHistoryEntity)

    /** §9: history is discarded after 30 days. */
    @Query("DELETE FROM query_history WHERE executedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface SavedQueryDao {
    @Query("SELECT * FROM saved_query WHERE connectionId = :connectionId ORDER BY name")
    fun observeAll(connectionId: Long): Flow<List<SavedQueryEntity>>

    @Upsert
    suspend fun upsert(query: SavedQueryEntity)

    @Query("DELETE FROM saved_query WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * The offline schema cache (§11).
 *
 * One DAO for all five tables rather than one each: they are never read or written separately —
 * a capture writes a whole level at once and a refresh replaces one — and splitting them would
 * only spread a single transaction across five interfaces.
 *
 * The writes are `@Upsert` plus an explicit delete of what the server no longer has, driven by
 * [hu.laurel.sqlpulse.data.schema.SchemaCache.merge]. Deleting everything and re-inserting would
 * be shorter, but it empties the cache for the duration of the write, and the one moment this
 * data is needed is the moment the link is unreliable.
 */
@Dao
interface SchemaCacheDao {

    @Query("SELECT * FROM cached_database WHERE connectionId = :connectionId ORDER BY name")
    suspend fun databases(connectionId: Long): List<CachedDatabaseEntity>

    /**
     * The capture time of the database list, as a flow: the marker on the screen has to move the
     * moment a refresh lands, and it is the only thing on that screen that does.
     */
    @Query("SELECT MAX(capturedAt) FROM cached_database WHERE connectionId = :connectionId")
    fun observeDatabasesCapturedAt(connectionId: Long): Flow<Long?>

    @Upsert
    suspend fun upsertDatabases(databases: List<CachedDatabaseEntity>)

    @Query("DELETE FROM cached_database WHERE connectionId = :connectionId AND name IN (:names)")
    suspend fun deleteDatabases(connectionId: Long, names: List<String>)

    @Query(
        "SELECT * FROM cached_table WHERE connectionId = :connectionId AND `database` = :database" +
            " ORDER BY name",
    )
    suspend fun tables(connectionId: Long, database: String): List<CachedTableEntity>

    @Query(
        "SELECT MAX(capturedAt) FROM cached_table WHERE connectionId = :connectionId" +
            " AND `database` = :database",
    )
    fun observeTablesCapturedAt(connectionId: Long, database: String): Flow<Long?>

    @Query(
        "SELECT structureCapturedAt FROM cached_table WHERE connectionId = :connectionId" +
            " AND `database` = :database AND name = :table",
    )
    suspend fun structureCapturedAt(connectionId: Long, database: String, table: String): Long?

    @Upsert
    suspend fun upsertTables(tables: List<CachedTableEntity>)

    @Query(
        "DELETE FROM cached_table WHERE connectionId = :connectionId AND `database` = :database" +
            " AND name IN (:names)",
    )
    suspend fun deleteTables(connectionId: Long, database: String, names: List<String>)

    @Query(
        "UPDATE cached_table SET structureCapturedAt = :capturedAt WHERE connectionId = :connectionId" +
            " AND `database` = :database AND name = :table",
    )
    suspend fun markStructureCaptured(
        connectionId: Long,
        database: String,
        table: String,
        capturedAt: Long,
    )

    @Query(
        "SELECT * FROM cached_column WHERE connectionId = :connectionId AND `database` = :database" +
            " AND tableName = :table ORDER BY position",
    )
    suspend fun columns(connectionId: Long, database: String, table: String): List<CachedColumnEntity>

    @Upsert
    suspend fun upsertColumns(columns: List<CachedColumnEntity>)

    @Query(
        "DELETE FROM cached_column WHERE connectionId = :connectionId AND `database` = :database" +
            " AND tableName = :table",
    )
    suspend fun deleteColumns(connectionId: Long, database: String, table: String)

    @Query(
        "SELECT * FROM cached_index WHERE connectionId = :connectionId AND `database` = :database" +
            " AND tableName = :table ORDER BY position",
    )
    suspend fun indexes(connectionId: Long, database: String, table: String): List<CachedIndexEntity>

    @Upsert
    suspend fun upsertIndexes(indexes: List<CachedIndexEntity>)

    @Query(
        "DELETE FROM cached_index WHERE connectionId = :connectionId AND `database` = :database" +
            " AND tableName = :table",
    )
    suspend fun deleteIndexes(connectionId: Long, database: String, table: String)

    @Query(
        "SELECT * FROM cached_foreign_key WHERE connectionId = :connectionId" +
            " AND `database` = :database AND tableName = :table ORDER BY constraintName, `column`",
    )
    suspend fun foreignKeys(
        connectionId: Long,
        database: String,
        table: String,
    ): List<CachedForeignKeyEntity>

    @Upsert
    suspend fun upsertForeignKeys(keys: List<CachedForeignKeyEntity>)

    @Query(
        "DELETE FROM cached_foreign_key WHERE connectionId = :connectionId" +
            " AND `database` = :database AND tableName = :table",
    )
    suspend fun deleteForeignKeys(connectionId: Long, database: String, table: String)

    /**
     * Everything cached for one connection, dropped.
     *
     * The rows would go anyway when the connection does, by the cascade; this exists for the
     * user who wants what they browsed forgotten without giving up the connection itself, and for
     * a capture that has passed [hu.laurel.sqlpulse.data.schema.SchemaCachePolicy.expireAfterMillis].
     */
    @Query("DELETE FROM cached_database WHERE connectionId = :connectionId")
    suspend fun deleteAllDatabases(connectionId: Long)

    @Query("DELETE FROM cached_table WHERE connectionId = :connectionId")
    suspend fun deleteAllTables(connectionId: Long)

    @Query("DELETE FROM cached_column WHERE connectionId = :connectionId")
    suspend fun deleteAllColumns(connectionId: Long)

    @Query("DELETE FROM cached_index WHERE connectionId = :connectionId")
    suspend fun deleteAllIndexes(connectionId: Long)

    @Query("DELETE FROM cached_foreign_key WHERE connectionId = :connectionId")
    suspend fun deleteAllForeignKeys(connectionId: Long)
}

/**
 * The schema cache's DAO binding.
 *
 * Here rather than in di.DatabaseModule for the same reason [JumpCredentialModule] is here: the
 * schema and the binding arrived together, and keeping them in one file is one fewer file two
 * people have to edit at the same time.
 */
@Module
@InstallIn(SingletonComponent::class)
object SchemaCacheDaoModule {

    @Provides
    fun provideSchemaCacheDao(db: SqlPulseDatabase): SchemaCacheDao = db.schemaCache()
}
