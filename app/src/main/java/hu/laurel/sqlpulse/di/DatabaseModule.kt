package hu.laurel.sqlpulse.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import hu.laurel.sqlpulse.data.crypto.DatabaseKeyProvider
import hu.laurel.sqlpulse.data.crypto.wipe
import hu.laurel.sqlpulse.data.db.ConnectionDao
import hu.laurel.sqlpulse.data.db.DbCredentialDao
import hu.laurel.sqlpulse.data.db.KnownHostDao
import hu.laurel.sqlpulse.data.db.QueryHistoryDao
import hu.laurel.sqlpulse.data.db.SavedQueryDao
import hu.laurel.sqlpulse.data.db.SqlPulseDatabase
import hu.laurel.sqlpulse.data.db.SshKeyDao
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyProvider: DatabaseKeyProvider,
    ): SqlPulseDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = keyProvider.passphrase()
        return try {
            Room.databaseBuilder(context, SqlPulseDatabase::class.java, SqlPulseDatabase.NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                // Foreign keys guard the "deleting a connection deletes its history" rule (§9).
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        } finally {
            // SupportOpenHelperFactory copies the passphrase; ours must not linger.
            passphrase.wipe()
        }
    }

    @Provides
    fun provideSshKeyDao(db: SqlPulseDatabase): SshKeyDao = db.sshKeys()

    @Provides
    fun provideConnectionDao(db: SqlPulseDatabase): ConnectionDao = db.connections()

    @Provides
    fun provideCredentialDao(db: SqlPulseDatabase): DbCredentialDao = db.credentials()

    @Provides
    fun provideKnownHostDao(db: SqlPulseDatabase): KnownHostDao = db.knownHosts()

    @Provides
    fun provideQueryHistoryDao(db: SqlPulseDatabase): QueryHistoryDao = db.queryHistory()

    @Provides
    fun provideSavedQueryDao(db: SqlPulseDatabase): SavedQueryDao = db.savedQueries()
}
