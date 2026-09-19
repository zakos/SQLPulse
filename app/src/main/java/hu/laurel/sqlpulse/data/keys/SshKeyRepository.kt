package hu.laurel.sqlpulse.data.keys

import android.content.Context
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.crypto.KeystoreCrypto
import hu.laurel.sqlpulse.data.crypto.Sealed
import hu.laurel.sqlpulse.data.crypto.wipe
import hu.laurel.sqlpulse.data.db.KeyMaterialFormat
import hu.laurel.sqlpulse.data.db.SshKeyDao
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.di.IoDispatcher
import hu.laurel.sqlpulse.security.BiometricUnlock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPair
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** A key in use by a connection cannot be deleted — the connection would be unusable. */
class KeyInUseException(val connections: Int) : Exception("key is used by $connections connection(s)")

/** Name collisions are caught early so two keys are never indistinguishable in a picker. */
class DuplicateKeyException(val existingName: String) : Exception("key already stored as $existingName")

/**
 * Owns the key store (§5). Private key material is only ever handed out as a [KeyPair] held for
 * the lifetime of one tunnel; the persisted copy stays sealed by the keystore user key, and the
 * app offers no way to export it (§6).
 */
@Singleton
class SshKeyRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SshKeyDao,
    private val crypto: KeystoreCrypto,
    private val unlock: BiometricUnlock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun observeKeys(): Flow<List<SshKeyEntity>> = dao.observeAll()

    suspend fun byId(id: Long): SshKeyEntity? = withContext(io) { dao.byId(id) }

    /**
     * Imports a key from text (file picker or paste). Parsing and validation happen before any
     * prompt, so a .ppk or a 1024-bit RSA key is refused without troubling the user for biometrics.
     *
     * The passphrase is used here and only here: what gets sealed is the bare key, so connecting
     * later needs the biometric unlock but not the passphrase again.
     */
    suspend fun import(name: String, privateKeyText: String, passphrase: CharArray?): SshKeyEntity {
        val parsed = withContext(io) { SshKeyParser.parse(privateKeyText, passphrase) }
        withContext(io) {
            dao.byFingerprint(parsed.fingerprint)?.let { throw DuplicateKeyException(it.name) }
        }
        val (material, format) = SshKeyParser.canonicalMaterial(parsed)
        return store(name = name, parsed = parsed, material = material, format = format)
    }

    /**
     * Generates an Ed25519 pair on the device. Only the seed is stored; the public key is shown so
     * it can be added to the server's authorized_keys (§5).
     */
    suspend fun generate(name: String): SshKeyEntity {
        val (seed, parsed) = withContext(io) { SshKeyParser.generateEd25519() }
        return store(
            name = name,
            parsed = parsed,
            material = seed,
            format = KeyMaterialFormat.ED25519_SEED,
        )
    }

    /**
     * Unwraps a stored key for one use. Raises the biometric prompt, and the decrypted material is
     * wiped as soon as the [KeyPair] is built (§5).
     */
    suspend fun unlockKeyPair(key: SshKeyEntity): KeyPair {
        val sealed = Sealed.decode(key.sealedPrivateKey)
        val cipher = crypto.decryptCipher(KeystoreCrypto.USER_KEY_ALIAS, sealed.iv)
        val authenticated = unlock.authenticate(
            cipher = cipher,
            title = context.getString(R.string.unlock_title),
            subtitle = context.getString(R.string.unlock_subtitle, key.name),
        )
        val material = withContext(io) { crypto.open(authenticated, sealed) }
        return try {
            when (key.materialFormat) {
                KeyMaterialFormat.ED25519_SEED -> SshKeyParser.fromEd25519Seed(material).keyPair
                KeyMaterialFormat.PKCS8_DER ->
                    SshKeyParser.fromPkcs8(material, key.algorithm, key.publicKey)
            }
        } finally {
            material.wipe()
        }
    }

    /**
     * Unwraps a stored key so it can be re-sealed into an encrypted backup file.
     *
     * §6 says the app offers no way to export a private key, and that stays true of anything a
     * user could accidentally hand over: this returns the bare material only to the backup writer,
     * which immediately re-seals it under the passphrase the user typed. It raises the same
     * biometric prompt as connecting does, once per key, and the caller must wipe what it gets.
     */
    suspend fun exportMaterial(key: SshKeyEntity): ByteArray {
        val sealed = Sealed.decode(key.sealedPrivateKey)
        val cipher = crypto.decryptCipher(KeystoreCrypto.USER_KEY_ALIAS, sealed.iv)
        val authenticated = unlock.authenticate(
            cipher = cipher,
            title = context.getString(R.string.unlock_title),
            subtitle = context.getString(R.string.unlock_subtitle, key.name),
        )
        return withContext(io) { crypto.open(authenticated, sealed) }
    }

    /**
     * Seals key material that arrived in a backup into *this* device's keystore, and returns the
     * blob for [SshKeyEntity.sealedPrivateKey].
     *
     * Nothing is written to the database here: the import wants every row it is going to write
     * ready before it opens its transaction, and sealing is the part that may stop to ask the user
     * for a fingerprint.
     */
    suspend fun sealImportedMaterial(material: ByteArray, name: String): ByteArray {
        val cipher = crypto.encryptCipher(KeystoreCrypto.USER_KEY_ALIAS)
        val authenticated = unlock.authenticate(
            cipher = cipher,
            title = context.getString(R.string.unlock_title),
            subtitle = context.getString(R.string.unlock_subtitle, name),
        )
        return withContext(io) { crypto.seal(authenticated, material).encode() }
    }

    suspend fun delete(id: Long) = withContext(io) {
        val inUse = dao.connectionsUsing(id)
        if (inUse > 0) throw KeyInUseException(inUse)
        dao.delete(id)
    }

    private suspend fun store(
        name: String,
        parsed: ParsedKey,
        material: ByteArray,
        format: KeyMaterialFormat,
    ): SshKeyEntity {
        val cipher = crypto.encryptCipher(KeystoreCrypto.USER_KEY_ALIAS)
        val authenticated = unlock.authenticate(
            cipher = cipher,
            title = context.getString(R.string.unlock_title),
            subtitle = context.getString(R.string.unlock_subtitle, name),
        )
        return try {
            val sealed = withContext(io) { crypto.seal(authenticated, material) }
            val entity = SshKeyEntity(
                name = name,
                algorithm = parsed.algorithm,
                bits = parsed.bits,
                fingerprint = parsed.fingerprint,
                materialFormat = format,
                sealedPrivateKey = sealed.encode(),
                publicKey = "${parsed.publicKey} sqlpulse-$name",
                createdAt = System.currentTimeMillis(),
            )
            withContext(io) { entity.copy(id = dao.insert(entity)) }
        } finally {
            material.wipe()
        }
    }
}
