package hu.laurel.sqlpulse.data.connection

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.crypto.KeystoreCrypto
import hu.laurel.sqlpulse.data.crypto.Sealed
import hu.laurel.sqlpulse.data.crypto.isAuthenticationRequired
import hu.laurel.sqlpulse.data.crypto.wipe
import hu.laurel.sqlpulse.data.db.ConnectionDao
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.DbCredentialDao
import hu.laurel.sqlpulse.data.db.DbCredentialEntity
import hu.laurel.sqlpulse.data.db.KnownHostDao
import hu.laurel.sqlpulse.di.IoDispatcher
import hu.laurel.sqlpulse.security.BiometricUnlock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Connection profiles (§9). The MySQL password is the only secret here; it is sealed by the
 * keystore password key, which authorises on a short window so that opening a connection does not
 * ask for biometrics a second time right after the key unlock.
 */
@Singleton
class ConnectionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connections: ConnectionDao,
    private val credentials: DbCredentialDao,
    private val knownHosts: KnownHostDao,
    private val crypto: KeystoreCrypto,
    private val unlock: BiometricUnlock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun observeAll(): Flow<List<ConnectionEntity>> = connections.observeAll()

    fun observe(id: Long): Flow<ConnectionEntity?> = connections.observe(id)

    suspend fun byId(id: Long): ConnectionEntity? = withContext(io) { connections.byId(id) }

    /**
     * @param password null keeps whatever is stored; an empty array clears it.
     * @return the saved row, with its id filled in for inserts.
     */
    suspend fun save(connection: ConnectionEntity, password: CharArray?): ConnectionEntity {
        require(!connection.useSshTunnel || connection.sshKeyId != null) {
            "a tunnelled connection without a key cannot be saved (§5)"
        }
        val saved = withContext(io) {
            if (connection.id == 0L) {
                connection.copy(id = connections.insert(connection))
            } else {
                connections.update(connection)
                connection
            }
        }
        if (password != null) storePassword(saved.id, password)
        return saved
    }

    suspend fun delete(connection: ConnectionEntity) = withContext(io) {
        // History rows cascade; the credential and the pinned host key are ours to clean up (§9).
        credentials.delete(connection.id)
        knownHosts.delete(connection.sshHost, connection.sshPort)
        connections.delete(connection)
    }

    suspend fun duplicate(connection: ConnectionEntity): ConnectionEntity = withContext(io) {
        // The password is deliberately not copied: the clone asks for its own.
        val copy = connection.copy(
            id = 0,
            name = context.getString(R.string.connection_duplicate) + " — " + connection.name,
            lastUsedAt = null,
        )
        copy.copy(id = connections.insert(copy))
    }

    suspend fun hasPassword(connectionId: Long): Boolean =
        withContext(io) { credentials.byConnection(connectionId) != null }

    /**
     * Unwraps the MySQL password for one connection attempt.
     *
     * The password key authorises on a short window, so right after the key unlock this needs no
     * prompt of its own; if the window has passed the keystore says so and we ask once.
     */
    suspend fun password(connectionId: Long, connectionName: String): CharArray? {
        val stored = withContext(io) { credentials.byConnection(connectionId) } ?: return null
        val sealed = Sealed.decode(stored.sealedPassword)
        val bytes = try {
            open(sealed)
        } catch (e: Exception) {
            if (!e.isAuthenticationRequired()) throw e
            unlock.authenticateUser(
                title = context.getString(R.string.unlock_title),
                subtitle = context.getString(R.string.unlock_subtitle, connectionName),
            )
            open(sealed)
        }
        return try {
            String(bytes, Charsets.UTF_8).toCharArray()
        } finally {
            bytes.wipe()
        }
    }

    private suspend fun open(sealed: Sealed): ByteArray = withContext(io) {
        crypto.open(crypto.decryptCipher(KeystoreCrypto.PASSWORD_KEY_ALIAS, sealed.iv), sealed)
    }

    private suspend fun storePassword(connectionId: Long, password: CharArray) {
        if (password.isEmpty()) {
            withContext(io) { credentials.delete(connectionId) }
            return
        }
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            val sealed = withContext(io) { seal(bytes) }
            withContext(io) {
                credentials.upsert(DbCredentialEntity(connectionId, sealed.encode()))
            }
        } catch (e: Exception) {
            if (!e.isAuthenticationRequired()) throw e
            unlock.authenticateUser(
                title = context.getString(R.string.unlock_title),
                subtitle = context.getString(R.string.db_password),
            )
            val sealed = withContext(io) { seal(bytes) }
            withContext(io) {
                credentials.upsert(DbCredentialEntity(connectionId, sealed.encode()))
            }
        } finally {
            bytes.wipe()
            password.wipe()
        }
    }

    private fun seal(bytes: ByteArray): Sealed =
        crypto.seal(crypto.encryptCipher(KeystoreCrypto.PASSWORD_KEY_ALIAS), bytes)
}
