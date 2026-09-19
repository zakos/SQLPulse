package hu.laurel.sqlpulse.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.BuildConfig
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.connection.CertificateStore
import hu.laurel.sqlpulse.data.connection.ConnectionRepository
import hu.laurel.sqlpulse.data.crypto.wipe
import hu.laurel.sqlpulse.data.db.ConnectionDao
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.DbCredentialDao
import hu.laurel.sqlpulse.data.db.DbCredentialEntity
import hu.laurel.sqlpulse.data.db.KeyMaterialFormat
import hu.laurel.sqlpulse.data.db.SavedQueryDao
import hu.laurel.sqlpulse.data.db.SavedQueryEntity
import hu.laurel.sqlpulse.data.db.SqlPulseDatabase
import hu.laurel.sqlpulse.data.db.SshCredentialDao
import hu.laurel.sqlpulse.data.db.SshCredentialEntity
import hu.laurel.sqlpulse.data.db.SshJumpCredentialDao
import hu.laurel.sqlpulse.data.db.SshJumpCredentialEntity
import hu.laurel.sqlpulse.data.db.SshKeyAlgorithm
import hu.laurel.sqlpulse.data.db.SshKeyDao
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.data.keys.SshKeyRepository
import hu.laurel.sqlpulse.di.IoDispatcher
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What an import actually did, so the screen can say it rather than just "done". */
data class ImportOutcome(
    val connectionsImported: Int = 0,
    val connectionsReplaced: Int = 0,
    val connectionsSkipped: Int = 0,
    val savedQueriesImported: Int = 0,
    val keysImported: Int = 0,
    /** Keys the file only described, because it was a settings-only backup. */
    val keysWithoutMaterial: Int = 0,
    val certificatesImported: Int = 0,
    /** Imported connections left without the SSH key they name; the user has to attach one. */
    val connectionsMissingKey: Int = 0,
)

/**
 * Gathers the configuration into a [BackupPayload] and puts one back (§9).
 *
 * The file format, the encryption and the merge rules are not here — they are in the plain-Kotlin
 * half of this package, which has no Android in it and is tested directly. What is here is the
 * part that only makes sense on a phone: the DAOs, the Keystore, the biometric prompts and the
 * content resolver.
 *
 * Two rules shape the import:
 *
 *  - **Nothing is written until the whole file has been read, verified and re-sealed.** The
 *    ciphertext is authenticated by its GCM tag before a single row is looked at, then every
 *    secret is sealed into this device's Keystore, and only then does one Room transaction write
 *    everything. A truncated file, an edited byte or a wrong passphrase all stop before that.
 *  - **Nothing is overwritten unless the user said so.** A name that already exists arrives here
 *    as a [MergeDecision], never as a silent upsert.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: SqlPulseDatabase,
    private val connections: ConnectionDao,
    private val credentials: DbCredentialDao,
    private val sshCredentials: SshCredentialDao,
    private val sshJumpCredentials: SshJumpCredentialDao,
    private val savedQueries: SavedQueryDao,
    private val sshKeys: SshKeyDao,
    private val keyRepository: SshKeyRepository,
    private val connectionRepository: ConnectionRepository,
    private val certificates: CertificateStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    val appVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun connectionNames(): List<String> =
        withContext(io) { connections.observeAll().first().map { it.name } }

    /**
     * Reads everything a backup carries.
     *
     * With [includeSecrets] the Keystore is asked to unseal each password and each private key,
     * which means a biometric prompt — the SSH key alias authenticates per use, so a phone with
     * several keys asks several times. That is the cost of material that the hardware will not let
     * out in bulk, and it is why settings-only is the default.
     */
    suspend fun collect(includeSecrets: Boolean): BackupPayload {
        val storedKeys = withContext(io) { sshKeys.observeAll().first() }
        val storedConnections = withContext(io) { connections.observeAll().first() }

        val keys = storedKeys.map { key ->
            val material = if (includeSecrets) keyRepository.exportMaterial(key) else null
            try {
                BackupSshKey(
                    name = key.name,
                    algorithm = key.algorithm.name,
                    bits = key.bits,
                    fingerprint = key.fingerprint,
                    publicKey = key.publicKey,
                    createdAt = key.createdAt,
                    materialFormat = key.materialFormat.name,
                    privateKeyBase64 = material?.let { Base64.getEncoder().encodeToString(it) },
                )
            } finally {
                material?.wipe()
            }
        }
        val keyFingerprints = storedKeys.associate { it.id to it.fingerprint }

        val profiles = storedConnections.map { connection ->
            val queries = withContext(io) { savedQueries.observeAll(connection.id).first() }
            BackupConnection(
                name = connection.name,
                color = connection.color,
                useSshTunnel = connection.useSshTunnel,
                sshHost = connection.sshHost,
                sshPort = connection.sshPort,
                sshUser = connection.sshUser,
                sshAuthMethod = connection.sshAuthMethod,
                sshKeyFingerprint = connection.sshKeyId?.let { keyFingerprints[it] },
                sshJumpHost = connection.sshJumpHost,
                sshJumpPort = connection.sshJumpPort,
                sshJumpUser = connection.sshJumpUser,
                sshJumpAuthMethod = connection.sshJumpAuthMethod,
                sshJumpKeyFingerprint = connection.sshJumpKeyId?.let { keyFingerprints[it] },
                dbHost = connection.dbHost,
                dbPort = connection.dbPort,
                database = connection.database,
                dbUser = connection.dbUser,
                readOnly = connection.readOnly,
                environment = connection.environment,
                connectTimeoutSeconds = connection.connectTimeoutSeconds,
                queryTimeoutSeconds = connection.queryTimeoutSeconds,
                sslMode = connection.sslMode,
                caCertificate = connection.caCertificate,
                savedQueries = queries.map {
                    BackupSavedQuery(name = it.name, sql = it.sql, parameters = it.parameters)
                },
                dbPassword = if (includeSecrets) {
                    connectionRepository.password(connection.id, connection.name)?.consume()
                } else {
                    null
                },
                sshPassword = if (includeSecrets) {
                    connectionRepository.sshPassword(connection.id, connection.name)?.consume()
                } else {
                    null
                },
                jumpSshPassword = if (includeSecrets) {
                    connectionRepository.jumpSshPassword(connection.id, connection.name)?.consume()
                } else {
                    null
                },
            )
        }

        // Only the certificates some connection actually names; an orphan file is not worth
        // carrying to a device that has nothing to point at it.
        val named = storedConnections.mapNotNull { it.caCertificate }.toSet()
        val cas = withContext(io) {
            named.mapNotNull { name -> certificates.read(name)?.let { BackupCertificate(name, it) } }
        }

        return BackupPayload(connections = profiles, sshKeys = keys, certificates = cas)
    }

    /** Writes the encrypted file to the place the system file picker gave us. */
    suspend fun writeTo(uri: Uri, payload: BackupPayload, passphrase: CharArray) {
        val bytes = BackupFile.write(payload, passphrase, appVersion)
        withContext(io) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw BackupFormatException("the chosen file could not be opened for writing")
        }
    }

    suspend fun readFile(uri: Uri): ByteArray = withContext(io) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BackupFormatException("the chosen file could not be read")
    }

    /**
     * Applies a verified payload.
     *
     * Everything that can stop to ask the user — sealing a password, sealing a private key —
     * happens first, in memory. The transaction that follows touches the database only once the
     * whole import is ready, so a prompt the user dismisses halfway through leaves nothing behind.
     */
    suspend fun apply(payload: BackupPayload, decisions: List<MergeDecision>): ImportOutcome {
        val decisionsByName = decisions.associateBy { it.sourceName }
        val importing = payload.connections.filter { decisionsByName[it.name]?.imports == true }

        val existingKeysByFingerprint =
            withContext(io) { sshKeys.observeAll().first() }.associateBy { it.fingerprint }
        // Read outside the transaction: a Room Flow sets up an invalidation observer, which is not
        // something to do while holding one.
        val existingByName =
            withContext(io) { connections.observeAll().first() }.associateBy { it.name }

        // 1. Seal the SSH keys the file brought and this device does not already have.
        val newKeys = mutableListOf<SshKeyEntity>()
        var keysWithoutMaterial = 0
        payload.sshKeys.forEach { key ->
            if (existingKeysByFingerprint.containsKey(key.fingerprint)) return@forEach
            val encoded = key.privateKeyBase64
            if (encoded == null) {
                // A settings-only backup describes its keys but cannot carry them; the key has to
                // be re-imported by hand on this device, and the connection says so until it is.
                keysWithoutMaterial++
                return@forEach
            }
            val material = Base64.getDecoder().decode(encoded)
            val sealed = try {
                keyRepository.sealImportedMaterial(material, key.name)
            } finally {
                material.wipe()
            }
            newKeys += SshKeyEntity(
                name = key.name,
                algorithm = runCatching { SshKeyAlgorithm.valueOf(key.algorithm) }
                    .getOrDefault(SshKeyAlgorithm.ED25519),
                bits = key.bits,
                fingerprint = key.fingerprint,
                materialFormat = runCatching { KeyMaterialFormat.valueOf(key.materialFormat) }
                    .getOrDefault(KeyMaterialFormat.ED25519_SEED),
                sealedPrivateKey = sealed,
                publicKey = key.publicKey,
                createdAt = key.createdAt,
            )
        }

        // 2. Seal the connection passwords.
        val sealedSecrets = importing.associate { connection ->
            connection.name to SealedSecrets(
                dbPassword = connection.dbPassword?.sealAs(R.string.db_password),
                sshPassword = connection.sshPassword?.sealAs(R.string.ssh_password),
                jumpSshPassword = connection.jumpSshPassword?.sealAs(R.string.ssh_password),
            )
        }

        // 3. One transaction. Everything above is already in hand, so this cannot stop halfway.
        var outcome = ImportOutcome(
            connectionsSkipped = decisions.count { !it.imports },
            connectionsReplaced = decisions.count { it.replacesExisting },
            keysWithoutMaterial = keysWithoutMaterial,
        )
        withContext(io) {
            database.withTransaction {
                val fingerprintToId = existingKeysByFingerprint
                    .mapValues { (_, key) -> key.id }
                    .toMutableMap()
                newKeys.forEach { key ->
                    fingerprintToId[key.fingerprint] = sshKeys.insert(key)
                }

                var imported = 0
                var queries = 0
                var missingKey = 0
                importing.forEach { source ->
                    val decision = decisionsByName.getValue(source.name)
                    if (decision.replacesExisting) {
                        existingByName[source.name]?.let { existing ->
                            credentials.delete(existing.id)
                            sshCredentials.delete(existing.id)
                            sshJumpCredentials.delete(existing.id)
                            // Saved queries and history cascade with the connection row (§9).
                            connections.delete(existing)
                        }
                    }
                    val keyId = source.sshKeyFingerprint?.let { fingerprintToId[it] }
                    val jumpKeyId = source.sshJumpKeyFingerprint?.let { fingerprintToId[it] }
                    if (source.sshKeyFingerprint != null && keyId == null) missingKey++
                    val id = connections.insert(source.toEntity(decision.finalName, keyId, jumpKeyId))
                    imported++

                    sealedSecrets[source.name]?.let { secrets ->
                        secrets.dbPassword?.let { credentials.upsert(DbCredentialEntity(id, it)) }
                        secrets.sshPassword?.let { sshCredentials.upsert(SshCredentialEntity(id, it)) }
                        secrets.jumpSshPassword?.let {
                            sshJumpCredentials.upsert(SshJumpCredentialEntity(id, it))
                        }
                    }
                    source.savedQueries.forEach { query ->
                        savedQueries.upsert(
                            SavedQueryEntity(
                                connectionId = id,
                                name = query.name,
                                sql = query.sql,
                                parameters = query.parameters,
                            ),
                        )
                        queries++
                    }
                }
                outcome = outcome.copy(
                    connectionsImported = imported,
                    savedQueriesImported = queries,
                    keysImported = newKeys.size,
                    connectionsMissingKey = missingKey,
                )
            }
        }

        // 4. The certificates last, because they are files rather than rows and cannot join the
        // transaction. They are public material and overwriting one loses nothing, so a failure
        // here leaves a consistent database and at worst a CA to re-import.
        val wanted = importing.mapNotNull { it.caCertificate }.toSet()
        val written = withContext(io) {
            payload.certificates.count { it.name in wanted && runCatching { certificates.write(it.name, it.pem) }.isSuccess }
        }
        return outcome.copy(certificatesImported = written)
    }

    private data class SealedSecrets(
        val dbPassword: ByteArray?,
        val sshPassword: ByteArray?,
        val jumpSshPassword: ByteArray?,
    )

    private suspend fun String.sealAs(subtitleRes: Int): ByteArray {
        val chars = toCharArray()
        return try {
            connectionRepository.sealImportedPassword(chars, context.getString(subtitleRes))
        } finally {
            chars.wipe()
        }
    }

    /** Reads a freshly unsealed secret into the payload and wipes the array behind it. */
    private fun CharArray.consume(): String = try {
        String(this)
    } finally {
        wipe()
    }

    private fun BackupConnection.toEntity(
        finalName: String,
        keyId: Long?,
        jumpKeyId: Long?,
    ) = ConnectionEntity(
        id = 0,
        name = finalName,
        color = color,
        useSshTunnel = useSshTunnel,
        sshHost = sshHost,
        sshPort = sshPort,
        sshUser = sshUser,
        sshAuthMethod = sshAuthMethod,
        sshKeyId = keyId,
        sshJumpHost = sshJumpHost,
        sshJumpPort = sshJumpPort,
        sshJumpUser = sshJumpUser,
        sshJumpAuthMethod = sshJumpAuthMethod,
        sshJumpKeyId = jumpKeyId,
        dbHost = dbHost,
        dbPort = dbPort,
        database = this.database,
        dbUser = dbUser,
        readOnly = readOnly,
        environment = environment,
        connectTimeoutSeconds = connectTimeoutSeconds,
        queryTimeoutSeconds = queryTimeoutSeconds,
        sslMode = sslMode,
        caCertificate = caCertificate,
        // Deliberately not carried: "last used" is this device's history, not the other one's.
        lastUsedAt = null,
    )
}
