package hu.laurel.sqlpulse.data.crypto

import java.security.Security
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Puts the full Bouncy Castle in place of Android's cut-down one, before any SSH work starts.
 *
 * Android registers a stripped provider under the name "BC" that has no X25519. The SSH key
 * exchange the app negotiates (curve25519-sha256) needs exactly that, so connecting fails with
 * "no such algorithm: X25519 for provider BC". sshj's own AndroidConfig tries to register
 * SpongyCastle, which this app does not bundle, so it silently falls back to the stub.
 *
 * The full provider is appended rather than inserted first: the platform providers must keep
 * priority, or `Cipher.getInstance("AES/GCM/NoPadding")` would resolve to Bouncy Castle and then
 * refuse the AndroidKeyStore keys that wrap the private keys (§6).
 */
object CryptoProviders {

    @Volatile
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
        SecurityUtils.setRegisterBouncyCastle(true)
        SecurityUtils.setSecurityProvider(SecurityUtils.BOUNCY_CASTLE)
        installed = true
    }
}
