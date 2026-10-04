package hu.laurel.sqlpulse.data.alerts

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Open the Pulse screen", left by a tapped alert notification.
 *
 * Same idea as the launcher shortcuts: MainActivity only records the wish, and the navigation host
 * acts on it once it exists, i.e. after the normal unlock. The notification never bypasses the lock.
 */
@Singleton
class AlertRequests @Inject constructor() {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun post() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }
}
