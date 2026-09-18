package hu.laurel.sqlpulse.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The local data model of §9. Every table lives inside the SQLCipher database; the columns marked
 * "sealed" hold keystore-wrapped ciphertext on top of that.
 */

/**
 * How the decrypted private key bytes should be interpreted once unwrapped.
 *
 * Imported keys are canonicalised at import time: whatever container they arrived in, and whatever
 * passphrase protected that container, what gets sealed is the bare key. The keystore is the only
 * thing guarding it afterwards, so a passphrase is asked for exactly once — at import.
 */
enum class KeyMaterialFormat {
    /** Raw 32-byte Ed25519 seed. */
    ED25519_SEED,

    /** PKCS#8 DER encoding of an RSA or EC private key. */
    PKCS8_DER,
}

enum class SshKeyAlgorithm { ED25519, ECDSA, RSA }

@Entity(tableName = "ssh_key")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val algorithm: SshKeyAlgorithm,
    /** Bit length for RSA, curve size for ECDSA, 256 for Ed25519. */
    val bits: Int,
    /** SHA256:... form, as printed by ssh-keygen -l. */
    val fingerprint: String,
    val materialFormat: KeyMaterialFormat,
    /** Sealed by the keystore user key: opening it requires biometrics or device PIN. */
    val sealedPrivateKey: ByteArray,
    /** Public key in authorized_keys form. Not secret. */
    val publicKey: String,
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is SshKeyEntity && other.id == id && other.fingerprint == fingerprint

    override fun hashCode(): Int = 31 * id.hashCode() + fingerprint.hashCode()
}

@Entity(
    tableName = "connection",
    foreignKeys = [
        ForeignKey(
            entity = SshKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["sshKeyId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("sshKeyId")],
)
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Name of a ui.theme.ConnectionColor entry. */
    val color: String,
    /**
     * Whether the MySQL traffic goes through an SSH tunnel.
     *
     * The specification rules out a direct connection (§2); this flag exists because the app's
     * owner asked for it. With the tunnel off, the SSH fields are unused and [dbHost] is dialled
     * straight from the phone — which means the database port has to be reachable from wherever
     * the phone happens to be.
     */
    val useSshTunnel: Boolean = true,
    val sshHost: String,
    val sshPort: Int = 22,
    val sshUser: String,
    /** Name of an SshAuthMethod entry: how the SSH host is convinced who we are. */
    val sshAuthMethod: String = "KEY",
    /** Null unless the SSH host is entered with a key; a key tunnel without one is refused (§5). */
    val sshKeyId: Long?,
    /**
     * A first SSH host to reach [sshHost] through, where the database's own SSH host is not
     * reachable from outside. Null for the ordinary single-hop case; the same credential is used
     * for both hops.
     */
    val sshJumpHost: String? = null,
    val sshJumpPort: Int = 22,
    val sshJumpUser: String? = null,
    /** With a tunnel, as seen from the SSH host; without one, as seen from the phone. */
    val dbHost: String,
    val dbPort: Int = 3306,
    val database: String,
    val dbUser: String,
    val readOnly: Boolean = true,
    /**
     * Name of a ConnectionEnvironment entry: development, test, production, or never said.
     *
     * It changes nothing about how the connection is made; it groups the list and makes a
     * production connection announce itself before it opens.
     */
    val environment: String = "UNSET",
    /** Seconds to wait for the database to accept the connection. */
    val connectTimeoutSeconds: Int = 10,
    /** Seconds a single statement may run before it is killed. */
    val queryTimeoutSeconds: Int = 30,
    /** Name of an SslMode entry: how the MySQL connection itself is protected. */
    val sslMode: String = "DISABLED",
    /** File name under the app's `ca` directory holding the CA that signs the server cert. */
    val caCertificate: String? = null,
    val lastUsedAt: Long? = null,
)

@Entity(tableName = "db_credential")
data class DbCredentialEntity(
    @PrimaryKey val connectionId: Long,
    /** Sealed by the keystore user key. */
    val sealedPassword: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is DbCredentialEntity && other.connectionId == connectionId

    override fun hashCode(): Int = connectionId.hashCode()
}

/**
 * The SSH password, for hosts that do not take keys.
 *
 * A separate table from [DbCredentialEntity] rather than a second column: the two are unlocked at
 * different moments, and a connection can need one, both or neither.
 */
@Entity(tableName = "ssh_credential")
data class SshCredentialEntity(
    @PrimaryKey val connectionId: Long,
    /** Sealed by the keystore user key. */
    val sealedPassword: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is SshCredentialEntity && other.connectionId == connectionId

    override fun hashCode(): Int = connectionId.hashCode()
}

@Entity(tableName = "known_host", primaryKeys = ["host", "port"])
data class KnownHostEntity(
    val host: String,
    val port: Int,
    /** ssh-ed25519, ecdsa-sha2-nistp256, ssh-rsa, ... */
    val keyType: String,
    val fingerprint: String,
    val acceptedAt: Long,
)

@Entity(
    tableName = "query_history",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("connectionId")],
)
data class QueryHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectionId: Long,
    val sql: String,
    val executedAt: Long,
    val durationMs: Long,
    val rowCount: Int,
)

@Entity(
    tableName = "saved_query",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("connectionId")],
)
data class SavedQueryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectionId: Long,
    val name: String,
    val sql: String,
    /** Comma-separated :parameter names picked out of the SQL. */
    val parameters: String,
)
