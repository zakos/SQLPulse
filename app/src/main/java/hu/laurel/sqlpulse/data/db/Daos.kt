package hu.laurel.sqlpulse.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
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
