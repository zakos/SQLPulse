package hu.laurel.sqlpulse.data.backup

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * The backup file format.
 *
 * A file is UTF-8 text in three parts, one per line, so that it survives being mailed to oneself
 * and so that `head -2` tells a human what they are holding:
 *
 * ```
 * SQLPULSE-BACKUP v1
 * {"formatVersion":1,"app":"SQLPulse","appVersion":"0.1.42","createdAt":"2026-09-19T08:11:00Z", ...}
 * QUJDREVG...        (base64 of the AES-GCM ciphertext, wrapped at 76 columns)
 * ```
 *
 *  1. **The magic line** names the format and its version. A future version bumps the number, and
 *     this build refuses it with a sentence ([UnsupportedBackupVersionException]) instead of
 *     failing somewhere deep in a parser. Anything that is not this line at all is "not a SQLPulse
 *     backup", which is a different sentence again.
 *  2. **The header** is cleartext JSON on exactly one line: what wrote the file, when, whether it
 *     carries secrets, how much of each thing is inside, and the KDF and cipher parameters needed
 *     to open it. Cleartext because the import screen has to show it before asking for a
 *     passphrase; the *bytes of this line* are the AES-GCM additional authenticated data, so
 *     editing it — to claim fewer connections, or a smaller iteration count — fails the tag.
 *  3. **The payload** is base64 of the ciphertext of [BackupCodec]'s JSON.
 *
 * Reading writes nothing anywhere: [peek] parses only the header, [read] returns a whole payload
 * or throws. The caller is what decides to apply it.
 */
object BackupFile {

    const val MAGIC = "SQLPULSE-BACKUP"
    const val APP_NAME = "SQLPulse"

    /** The highest format version this build understands, and the one it writes. */
    const val FORMAT_VERSION = 1

    const val FILE_EXTENSION = "sqlpulsebackup"
    const val MIME_TYPE = "application/octet-stream"

    private const val BASE64_LINE_LENGTH = 76

    fun suggestedFileName(createdAtEpochMs: Long): String {
        val stamp = Instant.ofEpochMilli(createdAtEpochMs).toString()
            .replace(':', '-')
            .substringBefore('.')
        return "sqlpulse-$stamp.$FILE_EXTENSION"
    }

    /**
     * @param passphrase used here and then forgotten; never stored, never written to the file.
     * @return the whole file, ready to be handed to the system file picker.
     */
    fun write(
        payload: BackupPayload,
        passphrase: CharArray,
        appVersion: String,
        createdAtEpochMs: Long = System.currentTimeMillis(),
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES, random)
        val nonce = BackupCrypto.randomBytes(BackupCrypto.NONCE_BYTES, random)
        val metadata = BackupMetadata(
            formatVersion = FORMAT_VERSION,
            app = APP_NAME,
            appVersion = appVersion,
            createdAtEpochMs = createdAtEpochMs,
            containsSecrets = payload.containsSecrets,
            connectionCount = payload.connections.size,
            sshKeyCount = payload.sshKeys.size,
            certificateCount = payload.certificates.size,
            savedQueryCount = payload.savedQueryCount,
        )
        val headerLine = writeHeader(metadata, salt, nonce)
        val aad = headerLine.toByteArray(Charsets.UTF_8)
        val key = BackupCrypto.deriveKey(passphrase, salt)
        val ciphertext = try {
            BackupCrypto.encrypt(key, nonce, aad, BackupCodec.encode(payload).toByteArray(Charsets.UTF_8))
        } finally {
            key.fill(0)
        }

        val out = StringBuilder()
        out.append(MAGIC).append(" v").append(FORMAT_VERSION).append('\n')
        out.append(headerLine).append('\n')
        Base64.getEncoder().encodeToString(ciphertext).chunked(BASE64_LINE_LENGTH).forEach {
            out.append(it).append('\n')
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Reads the cleartext header, without the passphrase and without touching the payload.
     *
     * What it says is not yet trustworthy — only a successful [read] proves the header was not
     * edited — so the import screen shows it as "the file says", and nothing is written on its
     * word alone.
     */
    fun peek(bytes: ByteArray): BackupMetadata = parse(bytes).metadata

    /** @return the payload, only once the GCM tag has verified the whole file. */
    fun read(bytes: ByteArray, passphrase: CharArray): BackupPayload {
        val parsed = parse(bytes)
        val key = BackupCrypto.deriveKey(
            passphrase = passphrase,
            salt = parsed.salt,
            iterations = parsed.iterations,
            keyBits = parsed.keyBits,
            algorithm = parsed.kdfAlgorithm,
        )
        val plaintext = try {
            BackupCrypto.decrypt(key, parsed.nonce, parsed.aad, parsed.ciphertext)
        } finally {
            key.fill(0)
        }
        return try {
            BackupCodec.decode(String(plaintext, Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    private fun writeHeader(metadata: BackupMetadata, salt: ByteArray, nonce: ByteArray): String =
        JsonValue.Obj(
            linkedMapOf(
                "formatVersion" to JsonValue.Num(metadata.formatVersion),
                "app" to JsonValue.Str(metadata.app),
                "appVersion" to JsonValue.Str(metadata.appVersion),
                // Both forms: one for a human reading the file, one that needs no parsing.
                "createdAt" to JsonValue.Str(Instant.ofEpochMilli(metadata.createdAtEpochMs).toString()),
                "createdAtEpochMs" to JsonValue.Num(metadata.createdAtEpochMs),
                "containsSecrets" to JsonValue.Bool(metadata.containsSecrets),
                "connectionCount" to JsonValue.Num(metadata.connectionCount),
                "sshKeyCount" to JsonValue.Num(metadata.sshKeyCount),
                "certificateCount" to JsonValue.Num(metadata.certificateCount),
                "savedQueryCount" to JsonValue.Num(metadata.savedQueryCount),
                "kdf" to JsonValue.Obj(
                    linkedMapOf(
                        "algorithm" to JsonValue.Str(BackupCrypto.KDF_ALGORITHM),
                        "iterations" to JsonValue.Num(BackupCrypto.KDF_ITERATIONS),
                        "keyBits" to JsonValue.Num(BackupCrypto.KEY_BITS),
                        "salt" to JsonValue.Str(Base64.getEncoder().encodeToString(salt)),
                    ),
                ),
                "cipher" to JsonValue.Obj(
                    linkedMapOf(
                        "algorithm" to JsonValue.Str(BackupCrypto.CIPHER_ALGORITHM),
                        "tagBits" to JsonValue.Num(BackupCrypto.TAG_BITS),
                        "nonce" to JsonValue.Str(Base64.getEncoder().encodeToString(nonce)),
                    ),
                ),
            ),
        ).write()

    private class ParsedFile(
        val metadata: BackupMetadata,
        val aad: ByteArray,
        val salt: ByteArray,
        val nonce: ByteArray,
        val iterations: Int,
        val keyBits: Int,
        val kdfAlgorithm: String,
        val ciphertext: ByteArray,
    )

    private fun parse(bytes: ByteArray): ParsedFile {
        val text = String(bytes, Charsets.UTF_8)
        val lines = text.split('\n').map { it.trimEnd('\r') }
        val magicLine = lines.firstOrNull()?.trim()
            ?: throw BackupFormatException("the file is empty")
        if (!magicLine.startsWith("$MAGIC v")) {
            throw BackupFormatException("this is not a SQLPulse backup file")
        }
        val fileVersion = magicLine.removePrefix("$MAGIC v").trim().toIntOrNull()
            ?: throw BackupFormatException("the backup file does not say which format version it is")
        if (fileVersion > FORMAT_VERSION) {
            throw UnsupportedBackupVersionException(fileVersion, FORMAT_VERSION)
        }

        val headerLine = lines.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: throw BackupFormatException("the backup file is truncated: it has no header")
        val header = try {
            JsonValue.parse(headerLine).asObject("header")
        } catch (e: JsonException) {
            throw BackupFormatException("the backup file's header is damaged: ${e.message}")
        }

        val kdf: JsonValue.Obj
        val cipher: JsonValue.Obj
        val metadata: BackupMetadata
        try {
            kdf = header.obj("kdf")
            cipher = header.obj("cipher")
            metadata = BackupMetadata(
                formatVersion = header.int("formatVersion"),
                app = header.strOrNull("app") ?: APP_NAME,
                appVersion = header.strOrNull("appVersion").orEmpty(),
                createdAtEpochMs = header.long("createdAtEpochMs"),
                containsSecrets = header.boolOr("containsSecrets", false),
                connectionCount = header.intOr("connectionCount", 0),
                sshKeyCount = header.intOr("sshKeyCount", 0),
                certificateCount = header.intOr("certificateCount", 0),
                savedQueryCount = header.intOr("savedQueryCount", 0),
            )
        } catch (e: JsonException) {
            throw BackupFormatException("the backup file's header is incomplete: ${e.message}")
        }
        // The magic line and the header must agree; if they do not, the file was assembled by hand.
        if (metadata.formatVersion != fileVersion) {
            throw BackupFormatException(
                "the backup file contradicts itself: it is labelled v$fileVersion but its header says version ${metadata.formatVersion}",
            )
        }

        val payloadText = lines.drop(2).joinToString("") { it.trim() }
        if (payloadText.isEmpty()) {
            throw BackupFormatException("the backup file is truncated: it has no payload")
        }
        val ciphertext = try {
            Base64.getDecoder().decode(payloadText)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("the backup file's payload is damaged: it is not valid base64")
        }

        return ParsedFile(
            metadata = metadata,
            aad = headerLine.toByteArray(Charsets.UTF_8),
            salt = decodeBase64(kdf, "salt"),
            nonce = decodeBase64(cipher, "nonce"),
            iterations = kdf.intOr("iterations", BackupCrypto.KDF_ITERATIONS),
            keyBits = kdf.intOr("keyBits", BackupCrypto.KEY_BITS),
            kdfAlgorithm = kdf.strOrNull("algorithm") ?: BackupCrypto.KDF_ALGORITHM,
            ciphertext = ciphertext,
        )
    }

    private fun decodeBase64(obj: JsonValue.Obj, field: String): ByteArray = try {
        Base64.getDecoder().decode(obj.str(field))
    } catch (e: JsonException) {
        throw BackupFormatException("the backup file's header is missing its $field")
    } catch (e: IllegalArgumentException) {
        throw BackupFormatException("the backup file's $field is not valid base64")
    }
}
