package hu.laurel.sqlpulse.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.R
import java.lang.ref.WeakReference
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The user dismissed the prompt; not an error worth a red banner. */
class UnlockCancelledException : Exception("unlock cancelled")

/** No biometrics and no device PIN: the app cannot store keys at all (§11). */
class NoDeviceCredentialException : Exception("no biometric or device credential enrolled")

class UnlockFailedException(val code: Int, override val message: String) : Exception(message)

/**
 * Keeps a weak reference to the foreground activity so services and repositories can raise a
 * prompt without holding an activity themselves.
 */
@Singleton
class ActivityHolder @Inject constructor() {
    private var reference: WeakReference<FragmentActivity>? = null

    fun attach(activity: FragmentActivity) {
        reference = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (reference?.get() === activity) reference = null
    }

    fun current(): FragmentActivity? = reference?.get()?.takeIf { !it.isFinishing }
}

/**
 * Wraps BiometricPrompt around a keystore [Cipher] (§6: the private key is unlocked with
 * biometrics or the device PIN, at app start and for every new tunnel).
 *
 * The returned cipher is the *authenticated* one from the callback — using the original instance
 * after a successful prompt is not guaranteed to work.
 */
@Singleton
class BiometricUnlock @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activityHolder: ActivityHolder,
) {

    /**
     * On API 30+ a CryptoObject may be unlocked by the device credential as well; below that the
     * platform only accepts a strong biometric, so the prompt gets an explicit cancel button.
     */
    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_STRONG
        }

    fun canAuthenticate(): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    suspend fun authenticate(cipher: Cipher, title: String, subtitle: String): Cipher =
        withContext(Dispatchers.Main) {
            val activity = activityHolder.current()
                ?: throw UnlockCancelledException()
            if (!canAuthenticate()) throw NoDeviceCredentialException()

            suspendCancellableCoroutine { continuation ->
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(context),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val authenticated = result.cryptoObject?.cipher
                            if (authenticated == null) {
                                continuation.resumeWithException(
                                    UnlockFailedException(-1, "prompt returned no cipher"),
                                )
                            } else {
                                continuation.resume(authenticated)
                            }
                        }

                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            when (code) {
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_CANCELED,
                                -> continuation.resumeWithException(UnlockCancelledException())

                                BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                                BiometricPrompt.ERROR_NO_BIOMETRICS,
                                -> continuation.resumeWithException(NoDeviceCredentialException())

                                else -> continuation.resumeWithException(
                                    UnlockFailedException(code, message.toString()),
                                )
                            }
                        }

                        // Not terminal: the prompt stays up and the user tries again.
                        override fun onAuthenticationFailed() = Unit
                    },
                )

                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(authenticators)
                    .apply {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                            setNegativeButtonText(context.getString(R.string.unlock_cancel))
                        }
                    }
                    .setConfirmationRequired(false)
                    .build()

                continuation.invokeOnCancellation { prompt.cancelAuthentication() }
                prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            }
        }
}
