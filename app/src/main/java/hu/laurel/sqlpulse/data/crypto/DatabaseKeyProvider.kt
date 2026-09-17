package hu.laurel.sqlpulse.data.crypto

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the SQLCipher passphrase (§6: encrypted local database, key held in the Keystore).
 *
 * The passphrase is 32 random bytes generated on first run and stored sealed by the keystore
 * device key, so plain preferences only ever hold ciphertext.
 */
@Singleton
class DatabaseKeyProvider @Inject constructor(
    @ApplicationContext context: Context,
    private val crypto: KeystoreCrypto,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun passphrase(): ByteArray {
        val stored = prefs.getString(KEY_SEALED, null)
        if (stored != null) {
            val sealed = Sealed.decode(Base64.decode(stored, Base64.NO_WRAP))
            return crypto.openWithDeviceKey(sealed)
        }
        val fresh = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val sealed = crypto.sealWithDeviceKey(fresh)
        prefs.edit()
            .putString(KEY_SEALED, Base64.encodeToString(sealed.encode(), Base64.NO_WRAP))
            .commit()
        return fresh
    }

    companion object {
        private const val PREFS = "sqlpulse_db"
        private const val KEY_SEALED = "db_passphrase"
        private const val PASSPHRASE_BYTES = 32
    }
}
