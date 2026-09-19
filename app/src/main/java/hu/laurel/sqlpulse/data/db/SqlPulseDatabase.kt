package hu.laurel.sqlpulse.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun toKeyFormat(value: String): KeyMaterialFormat = KeyMaterialFormat.valueOf(value)

    @TypeConverter
    fun fromKeyFormat(value: KeyMaterialFormat): String = value.name

    @TypeConverter
    fun toAlgorithm(value: String): SshKeyAlgorithm = SshKeyAlgorithm.valueOf(value)

    @TypeConverter
    fun fromAlgorithm(value: SshKeyAlgorithm): String = value.name
}

@Database(
    entities = [
        SshKeyEntity::class,
        ConnectionEntity::class,
        DbCredentialEntity::class,
        SshCredentialEntity::class,
        SshJumpCredentialEntity::class,
        KnownHostEntity::class,
        QueryHistoryEntity::class,
        SavedQueryEntity::class,
        CachedDatabaseEntity::class,
        CachedTableEntity::class,
        CachedColumnEntity::class,
        CachedIndexEntity::class,
        CachedForeignKeyEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SqlPulseDatabase : RoomDatabase() {
    abstract fun sshKeys(): SshKeyDao
    abstract fun connections(): ConnectionDao
    abstract fun credentials(): DbCredentialDao
    abstract fun sshCredentials(): SshCredentialDao
    abstract fun sshJumpCredentials(): SshJumpCredentialDao
    abstract fun knownHosts(): KnownHostDao
    abstract fun queryHistory(): QueryHistoryDao
    abstract fun savedQueries(): SavedQueryDao
    abstract fun schemaCache(): SchemaCacheDao

    companion object {
        const val NAME = "sqlpulse.db"
    }
}
