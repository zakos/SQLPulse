package hu.laurel.sqlpulse.data.db

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The jump host's credential DAO.
 *
 * It sits here rather than in di.DatabaseModule next to its siblings only because the schema and
 * this binding arrived together; folding it into that module is a one-line move and changes
 * nothing about the graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object JumpCredentialModule {

    @Provides
    fun provideSshJumpCredentialDao(db: SqlPulseDatabase): SshJumpCredentialDao =
        db.sshJumpCredentials()
}
