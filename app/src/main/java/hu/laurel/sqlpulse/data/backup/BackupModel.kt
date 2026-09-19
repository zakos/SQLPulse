package hu.laurel.sqlpulse.data.backup

/**
 * What a backup carries (§9, §6).
 *
 * These are deliberately *not* the Room entities. Row ids are local to one device and mean nothing
 * on another, so a connection points at its key by fingerprint and at its CA by file name. A
 * change to a Room entity therefore cannot silently change the file format, and the format can be
 * read and written by a plain JVM test.
 */

/** Everything a backup can contain. */
data class BackupPayload(
    val connections: List<BackupConnection> = emptyList(),
    val sshKeys: List<BackupSshKey> = emptyList(),
    val certificates: List<BackupCertificate> = emptyList(),
) {
    /** True when anything in here was only readable after a biometric unlock. */
    val containsSecrets: Boolean
        get() = sshKeys.any { it.privateKeyBase64 != null } || connections.any { it.hasSecrets }

    val savedQueryCount: Int get() = connections.sumOf { it.savedQueries.size }

    val isEmpty: Boolean
        get() = connections.isEmpty() && sshKeys.isEmpty() && certificates.isEmpty()
}

/**
 * One SSH key.
 *
 * [privateKeyBase64] is null in a settings-only backup: the key's name, algorithm, fingerprint and
 * public half are not secret and are worth carrying on their own, because they tell the user on
 * the new device exactly which key they have to re-import.
 */
data class BackupSshKey(
    val name: String,
    val algorithm: String,
    val bits: Int,
    val fingerprint: String,
    val publicKey: String,
    val createdAt: Long,
    /** Name of a data.db.KeyMaterialFormat entry; how [privateKeyBase64] should be read. */
    val materialFormat: String,
    val privateKeyBase64: String? = null,
)

/** A CA certificate. Public material, carried verbatim as the PEM text it is stored as. */
data class BackupCertificate(val name: String, val pem: String)

/** A saved query, carried under the connection it belongs to. */
data class BackupSavedQuery(val name: String, val sql: String, val parameters: String)

/**
 * One connection profile, with its SSH and database settings.
 *
 * The three password fields are filled only in a with-secrets backup. Each was sealed by the
 * Android Keystore on the source device; that key cannot leave the phone, so exporting means
 * unsealing it (biometrics) and re-sealing it under the user's passphrase — which is what the
 * envelope around this payload does.
 */
data class BackupConnection(
    val name: String,
    val color: String,
    val useSshTunnel: Boolean,
    val sshHost: String,
    val sshPort: Int,
    val sshUser: String,
    val sshAuthMethod: String,
    /** Fingerprint of an entry in [BackupPayload.sshKeys], not a row id. */
    val sshKeyFingerprint: String?,
    val sshJumpHost: String?,
    val sshJumpPort: Int,
    val sshJumpUser: String?,
    val sshJumpAuthMethod: String?,
    val sshJumpKeyFingerprint: String?,
    val dbHost: String,
    val dbPort: Int,
    val database: String,
    val dbUser: String,
    val readOnly: Boolean,
    val environment: String,
    val connectTimeoutSeconds: Int,
    val queryTimeoutSeconds: Int,
    val sslMode: String,
    /** File name of an entry in [BackupPayload.certificates]. */
    val caCertificate: String?,
    val savedQueries: List<BackupSavedQuery> = emptyList(),
    val dbPassword: String? = null,
    val sshPassword: String? = null,
    val jumpSshPassword: String? = null,
) {
    val hasSecrets: Boolean
        get() = dbPassword != null || sshPassword != null || jumpSshPassword != null
}

/**
 * The cleartext description of a file, shown before anything is decrypted or written.
 *
 * It travels outside the ciphertext so the import screen can say what the file is before asking
 * for a passphrase — but it is fed to AES-GCM as additional authenticated data, so a header that
 * has been edited fails the tag exactly as an edited payload does. Nothing here is secret: it is
 * counts and timestamps, never a host name.
 */
data class BackupMetadata(
    val formatVersion: Int,
    val app: String,
    val appVersion: String,
    val createdAtEpochMs: Long,
    val containsSecrets: Boolean,
    val connectionCount: Int,
    val sshKeyCount: Int,
    val certificateCount: Int,
    val savedQueryCount: Int,
)

/** The file is not a SQLPulse backup, or is damaged beyond the point of being parsed. */
class BackupFormatException(message: String) : Exception(message)

/** The file is a backup, but of a version this build does not know how to read. */
class UnsupportedBackupVersionException(val fileVersion: Int, val supportedVersion: Int) :
    Exception("backup format version $fileVersion, this build reads up to $supportedVersion")

/**
 * The passphrase did not open the file.
 *
 * Wrong passphrase and altered file are the same event to AES-GCM — the tag check fails and no
 * plaintext is produced — and pretending to tell them apart would mean adding a passphrase
 * verifier to the file, which is one more thing for an attacker to test guesses against. So one
 * exception, and a message that names both possibilities honestly.
 */
class BackupUnlockException : Exception("wrong passphrase, or the file was altered after it was written")
