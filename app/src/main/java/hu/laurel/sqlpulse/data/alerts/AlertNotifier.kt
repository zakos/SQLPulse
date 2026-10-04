package hu.laurel.sqlpulse.data.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.MainActivity
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts an alert as a notification on its own channel.
 *
 * The text is the connection's name, the metric's name and two numbers — nothing the server sent
 * (no query text, no host, no user, no row values). On the lock screen only a generic line shows.
 */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** False on Android 13+ until the user allows notifications; alerts then show in the app only. */
    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notify(connectionId: Long, connectionName: String, engine: DatabaseEngine, event: AlertEvent) {
        if (!canNotify()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(manager)

        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_OPEN_PULSE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val metric = context.getString(AlertText.metricLabel(event.rule.metric))
        val limit = AlertCatalog.formatThreshold(event.rule.threshold, AlertCatalog.unit(engine, event.rule.metric))
        val body = context.getString(R.string.alerts_notification_body, metric, event.display, limit)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(context.getString(R.string.alerts_notification_title, connectionName))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // What a locked screen shows instead: that something fired, not where or what.
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_pulse)
                    .setContentTitle(context.getString(R.string.alerts_notification_public))
                    .build(),
            )
            .build()
        // One notification per rule: a repeated alert replaces the earlier one instead of stacking.
        manager.notify("alert_$connectionId", event.rule.metric.ordinal, notification)
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alerts_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "sqlpulse_alerts"
        const val EXTRA_OPEN_PULSE = "hu.laurel.sqlpulse.OPEN_PULSE"
        private const val REQUEST_CODE = 20
    }
}
