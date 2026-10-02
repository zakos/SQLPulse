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
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
                ShortcutPlan.plan(
                    all.map {
                        ShortcutCandidate(it.id, it.name, ConnectionEnvironment.fromName(it.environment), it.lastUsedAt)
                    },
                    enabled,
                )
            }.distinctUntilChanged().collect { runCatching { apply(it) } }
        }
    }

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
