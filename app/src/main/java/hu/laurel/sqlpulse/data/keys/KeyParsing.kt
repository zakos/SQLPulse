package hu.laurel.sqlpulse.data.keys

import android.util.Base64
import hu.laurel.sqlpulse.data.db.KeyMaterialFormat
import hu.laurel.sqlpulse.data.db.SshKeyAlgorithm
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS5KeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.StringReader
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/** Why a key was refused. Each case maps to a specific, actionable message (§5, §11). */
sealed interface KeyRejection {
    /** PuTTY .ppk: not supported, with the conversion command. */
    data object PuttyFormat : KeyRejection

    /** DSA: obsolete. */
    data object DsaAlgorithm : KeyRejection

    /** RSA below 2048 bits (§5). */
    data class RsaTooShort(val bits: Int) : KeyRejection

    /** Passphrase missing or wrong — the caller re-prompts. */
    data object WrongPassphrase : KeyRejection

    /** Nothing recognisable. */
    data class Unreadable(val cause: Throwable?) : KeyRejection
}

class KeyRejectedException(val rejection: KeyRejection, cause: Throwable? = null) :
    Exception(rejection.toString(), cause)

/** A parsed, accepted key pair plus everything the UI wants to show about it. */
data class ParsedKey(
    val keyPair: KeyPair,
    val algorithm: SshKeyAlgorithm,
    val bits: Int,
    /** SHA256:... — same form ssh-keygen -l prints. */
    val fingerprint: String,
    /** authorized_keys line, without a comment. */
    val publicKey: String,
)

object SshKeyParser {

    private const val MIN_RSA_BITS = 2048

    /**
     * Parses OpenSSH v1, PKCS#8 and legacy PEM private keys (§5). Callers pass the passphrase for
     * protected keys; a wrong or missing one surfaces as [KeyRejection.WrongPassphrase] so the UI
     * can ask again rather than showing a stack trace.
     */
    fun parse(privateKeyText: String, passphrase: CharArray?): ParsedKey {
        val text = privateKeyText.trim()
        if (text.startsWith("PuTTY-User-Key-File")) throw KeyRejectedException(KeyRejection.PuttyFormat)
        if (text.contains("BEGIN DSA PRIVATE KEY")) throw KeyRejectedException(KeyRejection.DsaAlgorithm)

        val provider = providerFor(text, passphrase)
        val keyPair = try {
            KeyPair(provider.public, provider.private)
        } catch (e: Exception) {
            throw if (looksLikeBadPassphrase(e)) {
                KeyRejectedException(KeyRejection.WrongPassphrase, e)
            } else {
                KeyRejectedException(KeyRejection.Unreadable(e), e)
            }
        }
        return describe(keyPair)
    }

    /** True when [text] is passphrase-protected, so the import screen can ask up front. */
    fun isEncrypted(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.contains("ENCRYPTED PRIVATE KEY") ||
            trimmed.contains("Proc-Type: 4,ENCRYPTED") ||
            (trimmed.contains("BEGIN OPENSSH PRIVATE KEY") && !openSshIsUnencrypted(trimmed))
    }

    /** Generates an Ed25519 pair on the device (§5). The 32-byte seed is what gets stored. */
    fun generateEd25519(): Pair<ByteArray, ParsedKey> {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return seed to fromEd25519Seed(seed)
    }

    fun fromEd25519Seed(seed: ByteArray): ParsedKey {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val curve = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val privateSpec = EdDSAPrivateKeySpec(seed, curve)
        val keyPair = KeyPair(
            EdDSAPublicKey(EdDSAPublicKeySpec(privateSpec.a, curve)),
            EdDSAPrivateKey(privateSpec),
        )
        return describe(keyPair)
    }

    /**
     * The canonical bytes we seal for [key]: the Ed25519 seed, or a PKCS#8 encoding for RSA and EC.
     * Neither carries a passphrase — the keystore is what protects them from here on.
     */
    fun canonicalMaterial(key: ParsedKey): Pair<ByteArray, KeyMaterialFormat> {
        val private = key.keyPair.private
        return if (private is EdDSAPrivateKey) {
            private.seed.copyOf() to KeyMaterialFormat.ED25519_SEED
        } else {
            val encoded = private.encoded
                ?: throw KeyRejectedException(KeyRejection.Unreadable(null))
            encoded to KeyMaterialFormat.PKCS8_DER
        }
    }

    /** Rebuilds a pair from sealed PKCS#8 bytes plus the public key line stored next to them. */
    fun fromPkcs8(
        der: ByteArray,
        algorithm: SshKeyAlgorithm,
        publicKeyLine: String,
    ): KeyPair {
        val factory = KeyFactory.getInstance(
            when (algorithm) {
                SshKeyAlgorithm.RSA -> "RSA"
                SshKeyAlgorithm.ECDSA -> "EC"
                SshKeyAlgorithm.ED25519 -> throw IllegalArgumentException("Ed25519 is stored as a seed")
            },
        )
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(der))
        return KeyPair(publicKeyFrom(publicKeyLine), private)
    }

    /** Reads an authorized_keys line ("ssh-ed25519 AAAA... comment") back into a [PublicKey]. */
    fun publicKeyFrom(authorizedKeysLine: String): PublicKey {
        val blob = authorizedKeysLine.trim().split(" ").getOrNull(1)
            ?: throw KeyRejectedException(KeyRejection.Unreadable(null))
        return Buffer.PlainBuffer(Base64.decode(blob, Base64.DEFAULT)).readPublicKey()
    }

    fun describe(keyPair: KeyPair): ParsedKey {
        val public = keyPair.public
        val type = KeyType.fromKey(public)
        val algorithm = when (type) {
            KeyType.ED25519 -> SshKeyAlgorithm.ED25519
            KeyType.RSA -> SshKeyAlgorithm.RSA
            KeyType.ECDSA256, KeyType.ECDSA384, KeyType.ECDSA521 -> SshKeyAlgorithm.ECDSA
            KeyType.DSA -> throw KeyRejectedException(KeyRejection.DsaAlgorithm)
            else -> throw KeyRejectedException(KeyRejection.Unreadable(null))
        }
        val bits = bitsOf(public, algorithm)
        if (algorithm == SshKeyAlgorithm.RSA && bits < MIN_RSA_BITS) {
            throw KeyRejectedException(KeyRejection.RsaTooShort(bits))
        }
        val blob = publicKeyBlob(public)
        return ParsedKey(
            keyPair = keyPair,
            algorithm = algorithm,
            bits = bits,
            fingerprint = fingerprintOf(blob),
            publicKey = "$type ${Base64.encodeToString(blob, Base64.NO_WRAP)}",
        )
    }

    /** SHA256 fingerprint of an SSH wire-format public key blob, base64 without padding. */
    fun fingerprintOf(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun fingerprintOf(key: PublicKey): String = fingerprintOf(publicKeyBlob(key))

    fun publicKeyBlob(key: PublicKey): ByteArray =
        Buffer.PlainBuffer().putPublicKey(key).compactData

    private fun bitsOf(public: PublicKey, algorithm: SshKeyAlgorithm): Int = when (algorithm) {
        SshKeyAlgorithm.ED25519 -> 256
        SshKeyAlgorithm.RSA -> (public as RSAPublicKey).modulus.bitLength()
        SshKeyAlgorithm.ECDSA -> (public as ECPublicKey).params.curve.field.fieldSize
    }

    private fun providerFor(text: String, passphrase: CharArray?): FileKeyProvider {
        val format = try {
            KeyProviderUtil.detectKeyFileFormat(StringReader(text), false)
        } catch (e: Exception) {
            throw KeyRejectedException(KeyRejection.Unreadable(e), e)
        }
        val provider: FileKeyProvider = when (format) {
            KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
            KeyFormat.OpenSSH -> OpenSSHKeyFile()
            KeyFormat.PKCS8 -> PKCS8KeyFile()
            KeyFormat.PKCS5 -> PKCS5KeyFile()
            KeyFormat.PuTTY -> throw KeyRejectedException(KeyRejection.PuttyFormat)
            else -> throw KeyRejectedException(KeyRejection.Unreadable(null))
        }
        provider.init(StringReader(text), singleAttemptPassword(passphrase))
        return provider
    }

    /**
     * sshj retries the password finder on failure; we answer once and then give up, so a wrong
     * passphrase fails fast and the UI drives the retry (with its own backoff, §11).
     */
    private fun singleAttemptPassword(passphrase: CharArray?): PasswordFinder? =
        passphrase?.let {
            object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray = it.copyOf()
                override fun shouldRetry(resource: Resource<*>?): Boolean = false
            }
        }

    private fun looksLikeBadPassphrase(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            val name = cause::class.java.simpleName
            if (name.contains("KeyDecryptionFailed") || name.contains("BadPadding")) return true
            val message = cause.message.orEmpty().lowercase()
            if ("passphrase" in message || "decrypt" in message || "bad padding" in message) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * An unencrypted OpenSSH v1 key names "none" as its cipher in the header of the base64 body.
     * Decoding just the first block is enough to see it.
     */
    private fun openSshIsUnencrypted(text: String): Boolean = try {
        val body = text.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        val decoded = Base64.decode(body, Base64.DEFAULT)
        // "openssh-key-v1\0" magic, then a string: 4-byte length + cipher name.
        val magic = "openssh-key-v1".toByteArray(Charsets.US_ASCII)
        val offset = magic.size + 1
        val length = ((decoded[offset].toInt() and 0xFF) shl 24) or
            ((decoded[offset + 1].toInt() and 0xFF) shl 16) or
            ((decoded[offset + 2].toInt() and 0xFF) shl 8) or
            (decoded[offset + 3].toInt() and 0xFF)
        val cipher = String(decoded, offset + 4, length, Charsets.US_ASCII)
        cipher == "none"
    } catch (e: Exception) {
        // Unreadable here just means "ask for a passphrase"; parse() reports the real error.
        false
    }
}
