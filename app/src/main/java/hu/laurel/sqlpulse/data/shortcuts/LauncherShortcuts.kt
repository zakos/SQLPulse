package hu.laurel.sqlpulse.data.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.MainActivity
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.connection.ConnectionRepository
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the launcher's dynamic shortcuts equal to [ShortcutPlan]. It follows the connection table
 * and the setting, so rename, delete, restore from a backup and switching the setting off all
 * need no code of their own: the plan changes and the shortcuts follow.
 */
@Singleton
class LauncherShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connections: ConnectionRepository,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    fun start() {
        scope.launch {
            combine(connections.observeAll(), settings.settings.map { it.launcherShortcuts }) { all, enabled ->
                ShortcutPlan.plan(all.map(::candidate), enabled)
            }.distinctUntilChanged().collect { runCatching { apply(it) } }
        }
    }

    /**
     * Whether [connectionId] is one the launcher may offer right now. MainActivity is exported, so
     * any app can send the connect intent: honouring only what the current plan would show keeps
     * that intent from opening a production connection, or anything while the setting is off.
     */
    suspend fun allows(connectionId: Long): Boolean {
        val enabled = settings.settings.first().launcherShortcuts
        val plan = ShortcutPlan.plan(connections.observeAll().first().map(::candidate), enabled)
        return plan.any { it.connectionId == connectionId }
    }

    private fun candidate(connection: ConnectionEntity) = ShortcutCandidate(
        connection.id,
        connection.name,
        ConnectionEnvironment.fromName(connection.environment),
        connection.lastUsedAt,
    )

    private fun apply(plan: List<ShortcutEntry>) {
        // A shortcut the user pinned survives removal of the dynamic one; disable it so a deleted
        // or switched-off connection is not left behind on the home screen.
        val stale = ShortcutPlan.stale(
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id },
            plan,
        )
        if (stale.isNotEmpty()) {
            ShortcutManagerCompat.disableShortcuts(context, stale, context.getString(R.string.shortcut_disabled))
        }
        if (plan.isEmpty()) {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            return
        }
        val infos = plan.map { entry ->
            ShortcutInfoCompat.Builder(context, entry.shortcutId)
                .setShortLabel(entry.label)
                .setLongLabel(entry.label)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(ShortcutPlan.ACTION_CONNECT)
                        .putExtra(ShortcutPlan.EXTRA_CONNECTION_ID, entry.connectionId),
                )
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, infos)
    }
}
