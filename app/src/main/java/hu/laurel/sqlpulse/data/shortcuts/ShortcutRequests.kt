package hu.laurel.sqlpulse.data.shortcuts

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-over between MainActivity (which receives the launcher intent) and the connection list
 * (which owns the connect action). The list only exists once the app is unlocked, so a request
 * made while locked simply waits here: the shortcut goes through the same unlock as any start.
 */
@Singleton
class ShortcutRequests @Inject constructor() {
    private val _pending = MutableStateFlow<ShortcutRequest?>(null)
    val pending: StateFlow<ShortcutRequest?> = _pending.asStateFlow()

    fun post(connectionId: Long, now: Long = System.currentTimeMillis()) {
        _pending.value = ShortcutRequest(connectionId, now)
    }

    /** Takes the request if it is still fresh; either way it is gone afterwards. */
    fun take(now: Long = System.currentTimeMillis()): ShortcutRequest? {
        val request = _pending.value ?: return null
        _pending.value = null
        return request.takeIf { it.isFresh(now) }
    }
}
