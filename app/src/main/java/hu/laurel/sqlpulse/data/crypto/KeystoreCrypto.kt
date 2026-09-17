package hu.laurel.sqlpulse.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All persistent secrets are wrapped by a hardware-backed, non-exportable AES-256-GCM key (§6).
 *
 * Three aliases, on purpose:
 *  - [DEVICE_KEY_ALIAS] wraps the SQLCipher passphrase. It is device-bound but not
 *    user-authentication-bound, otherwise the database could not be opened before the user
 *    unlocks — and the local database holds no usable key material on its own.
 *  - [USER_KEY_ALIAS] wraps SSH private keys. Every single use requires a fresh biometric or
 *    device-credential authentication, so a [Cipher] created from it must be passed through a
 *    BiometricPrompt CryptoObject before it will do any work.
 *  - [PASSWORD_KEY_ALIAS] wraps MySQL passwords on a short authentication window, so building a
 *    tunnel prompts once rather than twice. The trade-off is deliberate and narrow: the key is the
 *    secret worth binding per use, the database password is reachable for half a minute after the
 *    user has already proved themselves.
 */
@Singleton
class KeystoreCrypto @Inject constructor() {

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun ensureKeys() {
        ensureKey(DEVICE_KEY_ALIAS, userAuthRequired = false)
        ensureKey(USER_KEY_ALIAS, userAuthRequired = true)
        ensureKey(PASSWORD_KEY_ALIAS, userAuthRequired = true)
    }

    /** @return a cipher that must be authenticated before use when [alias] needs the user. */
    fun encryptCipher(alias: String): Cipher {
        ensureKey(alias, userAuthRequired = alias != DEVICE_KEY_ALIAS)
        return Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.ENCRYPT_MODE, secretKey(alias)) }
    }

    fun decryptCipher(alias: String, iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(alias), GCMParameterSpec(GCM_TAG_BITS, iv))
        }

    fun seal(cipher: Cipher, plaintext: ByteArray): Sealed =
        Sealed(iv = cipher.iv.copyOf(), ciphertext = cipher.doFinal(plaintext))

    fun open(cipher: Cipher, sealed: Sealed): ByteArray = cipher.doFinal(sealed.ciphertext)

    /** Convenience for the device key, which never needs user authentication. */
    fun sealWithDeviceKey(plaintext: ByteArray): Sealed =
        seal(encryptCipher(DEVICE_KEY_ALIAS), plaintext)

    fun openWithDeviceKey(sealed: Sealed): ByteArray =
        open(decryptCipher(DEVICE_KEY_ALIAS, sealed.iv), sealed)

    /**
     * Dropping the user key invalidates every stored private key and MySQL password. Called when
     * the keystore reports the key as permanently invalidated (new biometric enrolment, screen
     * lock removed), because nothing sealed with it can be recovered at that point.
     */
    fun resetUserKey() {
        listOf(USER_KEY_ALIAS, PASSWORD_KEY_ALIAS).forEach { alias ->
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            ensureKey(alias, userAuthRequired = true)
        }
    }

    private fun secretKey(alias: String): SecretKey =
        (keyStore.getKey(alias, null) as? SecretKey)
            ?: error("keystore alias $alias is missing after ensureKey()")

    private fun ensureKey(alias: String, userAuthRequired: Boolean) {
        if (keyStore.containsAlias(alias)) return
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (userAuthRequired) {
            builder.setUserAuthenticationRequired(true)
            val validitySeconds = if (alias == PASSWORD_KEY_ALIAS) AUTH_WINDOW_SECONDS else 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    validitySeconds,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(
                    if (validitySeconds == 0) -1 else validitySeconds,
                )
            }
            builder.setInvalidatedByBiometricEnrollment(true)
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        generator.generateKey()
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val DEVICE_KEY_ALIAS = "sqlpulse_device_key"
        const val USER_KEY_ALIAS = "sqlpulse_user_key"

        /**
         * MySQL passwords. Unlike private keys this one authenticates on a short time window
         * rather than per use, so opening a tunnel does not ask for biometrics twice in a row:
         * the prompt that unwrapped the private key also covers the password that follows it.
         */
        const val PASSWORD_KEY_ALIAS = "sqlpulse_password_key"
        private const val AUTH_WINDOW_SECONDS = 30
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

/** The keystore refused because the user has not authenticated for this operation. */
fun Throwable.isAuthenticationRequired(): Boolean = this is UserNotAuthenticatedException

/** The wrapping key is gone for good; everything sealed with it is unrecoverable. */
fun Throwable.isKeyPermanentlyInvalidated(): Boolean = this is KeyPermanentlyInvalidatedException
