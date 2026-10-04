package hu.laurel.sqlpulse.data.shortcuts

import android.content.Intent
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment

/** What the planner needs of a connection; deliberately not the entity, so tests stay plain JVM. */
data class ShortcutCandidate(
    val id: Long,
    val name: String,
    val environment: ConnectionEnvironment,
    val lastUsedAt: Long?,
)

/** One launcher entry. The label is the connection name and nothing else (no host, no user). */
data class ShortcutEntry(val connectionId: Long, val label: String) {
    val shortcutId: String get() = ShortcutPlan.shortcutId(connectionId)
}

/**
 * Decides which connections the launcher may show. A home-screen label is visible without
 * unlocking anything, so the plan errs on the side of showing less.
 */
object ShortcutPlan {
    const val MAX_SHORTCUTS = 3
    const val ACTION_CONNECT = "hu.laurel.sqlpulse.action.CONNECT"
    const val EXTRA_CONNECTION_ID = "hu.laurel.sqlpulse.extra.CONNECTION_ID"
    private const val ID_PREFIX = "connection-"

    fun shortcutId(connectionId: Long) = "$ID_PREFIX$connectionId"

    /**
     * The most recently used connections, newest first. Off means none, which is what makes the
     * setting remove every shortcut. Production is never listed: opening it takes a deliberate
     * walk through the list, not a tap on the home screen. A connection that was never used has
     * no recency to rank by, so it does not qualify.
     */
    fun plan(
        connections: List<ShortcutCandidate>,
        enabled: Boolean,
        max: Int = MAX_SHORTCUTS,
    ): List<ShortcutEntry> {
        if (!enabled) return emptyList()
        return connections
            .filter { it.lastUsedAt != null && !it.environment.isProduction && it.name.isNotBlank() }
            .sortedWith(compareByDescending<ShortcutCandidate> { it.lastUsedAt }.thenBy { it.id })
            .take(max)
            .map { ShortcutEntry(it.id, it.name.trim()) }
    }

    /** Shortcut ids that are no longer wanted and must be disabled where the user pinned them. */
    fun stale(existingIds: Collection<String>, plan: List<ShortcutEntry>): List<String> {
        val keep = plan.mapTo(HashSet()) { it.shortcutId }
        return existingIds.filter { it.startsWith(ID_PREFIX) && it !in keep }
    }

    /** The connection id an intent asks for; null for anything that is not ours or is malformed. */
    fun parse(action: String?, extraId: Long?): Long? =
        if (action == ACTION_CONNECT && extraId != null && extraId > 0) extraId else null

    fun parse(intent: Intent?): Long? =
        parse(intent?.action, intent?.getLongExtra(EXTRA_CONNECTION_ID, 0L))
}

/** A request from the launcher waiting for the unlocked app to pick it up. */
data class ShortcutRequest(val connectionId: Long, val createdAt: Long) {
    /** An old request is dropped: unlocking an hour later must not suddenly open a connection. */
    fun isFresh(now: Long) = now - createdAt in 0..MAX_AGE_MS

    companion object {
        const val MAX_AGE_MS = 2 * 60_000L
    }
}
