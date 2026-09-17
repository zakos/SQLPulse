package hu.laurel.sqlpulse.security

import hu.laurel.sqlpulse.data.export.ExportManager
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.di.ApplicationScope
import hu.laurel.sqlpulse.ssh.TunnelManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Automatic lock (§6): after a few minutes without interaction the app locks, the tunnel drops and
 * the result cache goes with it (§9).
 *
 * The timer is restarted by interaction, not by time passing in the background — the tunnel's own
 * five-minute background limit (§5) covers that case.
 */
@Singleton
class LockManager @Inject constructor(
    private val tunnelManager: TunnelManager,
    private val settings: SettingsRepository,
    private val exports: ExportManager,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var timer: Job? = null

    /** Called on every touch, and whenever the app comes back to the foreground. */
    fun onUserInteraction() {
        if (_locked.value) return
        timer?.cancel()
        timer = scope.launch {
            val minutes = settings.settings.first().autoLockMinutes
            delay(TimeUnit.MINUTES.toMillis(minutes.toLong()))
            lock()
        }
    }

    fun lock() {
        timer?.cancel()
        timer = null
        _locked.value = true
        tunnelManager.disconnect()
        exports.clearExports()
    }

    /** Called after a successful unlock; the caller owns the biometric prompt. */
    fun unlock() {
        _locked.value = false
        onUserInteraction()
    }
}
