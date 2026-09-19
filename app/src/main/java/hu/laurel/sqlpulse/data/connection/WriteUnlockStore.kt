package hu.laurel.sqlpulse.data.connection

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which production connections may be written to at the moment, and until when.
 *
 * In memory only, and deliberately so: an unlock is an intention held for the next quarter of an
 * hour, not a setting. Killing the app, or letting the window run out, puts production back where
 * it belongs. The store keeps only the deadline — whether that deadline still means anything is
 * [ProductionPolicy]'s answer, computed against the caller's clock.
 */
@Singleton
class WriteUnlockStore @Inject constructor() {

    private val _unlockedUntil = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /** Connection id to the end of its unlock window. */
    val unlockedUntil: StateFlow<Map<Long, Long>> = _unlockedUntil.asStateFlow()

    fun unlockedUntil(connectionId: Long): Long? = _unlockedUntil.value[connectionId]

    /** Opens a window of [ProductionPolicy.UNLOCK_MILLIS] from [now]. Re-unlocking simply extends it. */
    fun unlock(connectionId: Long, now: Long = System.currentTimeMillis()): Long {
        val until = ProductionPolicy.unlockUntil(now)
        _unlockedUntil.value = _unlockedUntil.value + (connectionId to until)
        return until
    }

    /** Locks again before the window is out, for the user who has finished early. */
    fun lock(connectionId: Long) {
        _unlockedUntil.value = _unlockedUntil.value - connectionId
    }

    /** Everything locks when the tunnel goes down: a new session is a new decision. */
    fun lockAll() {
        _unlockedUntil.value = emptyMap()
    }

    fun writeAccess(
        connectionId: Long,
        environment: ConnectionEnvironment,
        readOnly: Boolean,
        now: Long = System.currentTimeMillis(),
    ): WriteAccess = ProductionPolicy.writeAccess(
        environment = environment,
        readOnly = readOnly,
        unlockedUntil = unlockedUntil(connectionId),
        now = now,
    )
}
