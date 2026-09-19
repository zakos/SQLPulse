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
import hu.laurel.sqlpulse.data.db.SshCredentialDao
import hu.laurel.sqlpulse.data.db.SshCredentialEntity
import hu.laurel.sqlpulse.data.db.SshJumpCredentialDao
import hu.laurel.sqlpulse.data.db.SshJumpCredentialEntity
import hu.laurel.sqlpulse.ssh.SshAuthMethod
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
    private val sshCredentials: SshCredentialDao,
    private val sshJumpCredentials: SshJumpCredentialDao,
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
    suspend fun save(
        connection: ConnectionEntity,
        password: CharArray?,
        sshPassword: CharArray? = null,
        jumpSshPassword: CharArray? = null,
    ): ConnectionEntity {
        val usesKey = SshAuthMethod.fromName(connection.sshAuthMethod) == SshAuthMethod.KEY
        require(!connection.useSshTunnel || !usesKey || connection.sshKeyId != null) {
            "a tunnelled connection without a key cannot be saved (§5)"
        }
        // The same rule one hop earlier, for a jump host that no longer shares the credential.
        require(
            connection.sshJumpAuthMethod != SshAuthMethod.KEY.name ||
                connection.sshJumpKeyId != null,
        ) {
            "a jump host entered with a key of its own cannot be saved without one (§5)"
        }
        val refusal = ProductionPolicy.refusal(connection.productionShape())
        require(refusal == null) { "production policy refused this connection: $refusal" }
        val saved = withContext(io) {
            if (connection.id == 0L) {
                connection.copy(id = connections.insert(connection))
            } else {
                connections.update(connection)
                connection
            }
        }
        if (password != null) storePassword(saved.id, password)
        if (sshPassword != null) storeSshPassword(saved.id, sshPassword)
        if (jumpSshPassword != null) storeJumpSshPassword(saved.id, jumpSshPassword)
        return saved
    }

    suspend fun delete(connection: ConnectionEntity) = withContext(io) {
        // History rows cascade; the credentials and the pinned host key are ours to clean up (§9).
        credentials.delete(connection.id)
        sshCredentials.delete(connection.id)
        sshJumpCredentials.delete(connection.id)
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

    suspend fun hasSshPassword(connectionId: Long): Boolean =
        withContext(io) { sshCredentials.byConnection(connectionId) != null }

    suspend fun hasJumpSshPassword(connectionId: Long): Boolean =
        withContext(io) { sshJumpCredentials.byConnection(connectionId) != null }

    /**
     * Unwraps the MySQL password for one connection attempt.
     *
     * The password key authorises on a short window, so right after the key unlock this needs no
     * prompt of its own; if the window has passed the keystore says so and we ask once.
     */
    suspend fun password(connectionId: Long, connectionName: String): CharArray? {
        val stored = withContext(io) { credentials.byConnection(connectionId) } ?: return null
        return unseal(Sealed.decode(stored.sealedPassword), connectionName)
    }

    /** The SSH password, unwrapped for one connection attempt. Unlocked like the MySQL one. */
    suspend fun sshPassword(connectionId: Long, connectionName: String): CharArray? {
        val stored = withContext(io) { sshCredentials.byConnection(connectionId) } ?: return null
        return unseal(Sealed.decode(stored.sealedPassword), connectionName)
    }

    private suspend fun unseal(sealed: Sealed, connectionName: String): CharArray {
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

    /**
     * The jump host's own SSH password, unwrapped for one connection attempt.
     *
     * Only for a connection whose first hop has a credential of its own; where the two hops share
     * one, [sshPassword] is what both of them use, exactly as before this column existed.
     */
    suspend fun jumpSshPassword(connectionId: Long, connectionName: String): CharArray? {
        val stored = withContext(io) { sshJumpCredentials.byConnection(connectionId) } ?: return null
        return unseal(Sealed.decode(stored.sealedPassword), connectionName)
    }

    /**
     * Seals a password that arrived in a backup into *this* device's keystore, and returns the
     * blob the credential tables hold.
     *
     * Public only for the backup import, which seals every secret it is going to write before it
     * opens its transaction: sealing can stop to ask the user to authenticate, and a database
     * transaction is the wrong place to be waiting for a fingerprint.
     */
    suspend fun sealImportedPassword(password: CharArray, subtitle: String): ByteArray {
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        return try {
            sealWithUnlock(bytes, subtitle).encode()
        } finally {
            bytes.wipe()
        }
    }

    private suspend fun storeJumpSshPassword(connectionId: Long, password: CharArray) {
        if (password.isEmpty()) {
            withContext(io) { sshJumpCredentials.delete(connectionId) }
            password.wipe()
            return
        }
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            val sealed = sealWithUnlock(bytes, context.getString(R.string.ssh_password))
            withContext(io) {
                sshJumpCredentials.upsert(SshJumpCredentialEntity(connectionId, sealed.encode()))
            }
        } finally {
            bytes.wipe()
            password.wipe()
        }
    }

    private suspend fun storeSshPassword(connectionId: Long, password: CharArray) {
        if (password.isEmpty()) {
            withContext(io) { sshCredentials.delete(connectionId) }
            password.wipe()
            return
        }
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            val sealed = sealWithUnlock(bytes, context.getString(R.string.ssh_password))
            withContext(io) {
                sshCredentials.upsert(SshCredentialEntity(connectionId, sealed.encode()))
            }
        } finally {
            bytes.wipe()
            password.wipe()
        }
    }

    /** Seals, asking for authentication once if the keystore's window has passed. */
    private suspend fun sealWithUnlock(bytes: ByteArray, subtitle: String): Sealed = try {
        withContext(io) { seal(bytes) }
    } catch (e: Exception) {
        if (!e.isAuthenticationRequired()) throw e
        unlock.authenticateUser(title = context.getString(R.string.unlock_title), subtitle = subtitle)
        withContext(io) { seal(bytes) }
    }

    private suspend fun open(sealed: Sealed): ByteArray = withContext(io) {
        crypto.open(crypto.decryptCipher(KeystoreCrypto.PASSWORD_KEY_ALIAS, sealed.iv), sealed)
    }

    private suspend fun storePassword(connectionId: Long, password: CharArray) {
        if (password.isEmpty()) {
            withContext(io) { credentials.delete(connectionId) }
            password.wipe()
            return
        }
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            val sealed = sealWithUnlock(bytes, context.getString(R.string.db_password))
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
