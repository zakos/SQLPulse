package hu.laurel.sqlpulse.data.backup

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The passphrase-based encryption of a backup file.
 *
 * Nothing Android-specific: `javax.crypto` is the platform's own, so the same code runs on the
 * phone and in a plain JVM test, and the file written here can be read by anything with a JCE.
 *
 * Why these parameters:
 *
 *  - **PBKDF2-HMAC-SHA256.** Argon2 and scrypt are better at resisting a GPU, but neither is on
 *    this project's classpath (gradle/libs.versions.toml) and neither is in the Android platform.
 *    Adding a dependency for one screen is not worth it; PBKDF2 with a serious iteration count is
 *    what the platform gives for free, and it is what every other tool that has to read a
 *    passphrase-protected file on Android ends up using.
 *  - **[KDF_ITERATIONS] = 210 000.** OWASP's current figure for PBKDF2-HMAC-SHA256. It costs
 *    roughly a fifth of a second on a mid-range phone, which is invisible next to picking a file
 *    in the system picker, and it multiplies an offline guessing attack by the same factor.
 *  - **[SALT_BYTES] = 16, freshly random per file.** Without it two backups made with the same
 *    passphrase would derive the same key, and one precomputed table would open every backup
 *    anyone ever made with a common passphrase.
 *  - **[KEY_BITS] = 256.** Matches the AES-256 the Keystore uses for the same secrets on the
 *    device; there is no reason for the travelling copy to be weaker than the resident one.
 *  - **AES-GCM with a 12-byte nonce and a 128-bit tag.** GCM authenticates as well as encrypts,
 *    which is the whole answer to "a truncated or tampered file must not half-import": the tag is
 *    checked before any plaintext is handed back. 12 bytes is GCM's native nonce length (anything
 *    else is hashed first, for no benefit), and the nonce is random per file — the key is derived
 *    fresh from a fresh salt each time, so a nonce is never reused under one key.
 *  - **The cleartext header is the additional authenticated data.** It has to be readable before
 *    the passphrase is asked for, and it must still be impossible to edit: AAD gives both.
 */
object BackupCrypto {

    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val KDF_ITERATIONS = 210_000
    const val SALT_BYTES = 16
    const val KEY_BITS = 256

    const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128

    /**
     * The smallest an authenticated ciphertext can be: an empty plaintext still carries its tag.
     * Anything shorter is a truncated file and is refused before the cipher is even built.
     */
    const val MIN_CIPHERTEXT_BYTES = TAG_BITS / 8

    fun randomBytes(size: Int, random: SecureRandom): ByteArray =
        ByteArray(size).also { random.nextBytes(it) }

    /**
     * @param passphrase not copied and not kept; the caller owns it and should wipe it.
     * @return raw key bytes, which the caller should wipe when the file is written or read.
     */
    fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = KDF_ITERATIONS,
        keyBits: Int = KEY_BITS,
        algorithm: String = KDF_ALGORITHM,
    ): ByteArray {
        require(iterations > 0) { "iteration count must be positive" }
        require(salt.isNotEmpty()) { "a backup key needs a salt" }
        val spec = PBEKeySpec(passphrase, salt, iterations, keyBits)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            // PBEKeySpec keeps its own copy of the passphrase; clear that one too.
            spec.clearPassword()
        }
    }

    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** @throws BackupUnlockException on a failed tag — wrong passphrase, or an altered file. */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < MIN_CIPHERTEXT_BYTES) {
            throw BackupFormatException("the backup file is truncated: its payload is too short to carry an authentication tag")
        }
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw BackupUnlockException()
        } catch (e: BadPaddingException) {
            // Some providers report a failed GCM tag as the more general BadPaddingException.
            throw BackupUnlockException()
        } catch (e: IllegalBlockSizeException) {
            throw BackupFormatException("the backup payload is damaged: ${e.message}")
        }
    }
}
