package hu.laurel.sqlpulse.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import hu.laurel.sqlpulse.MainActivity
import hu.laurel.sqlpulse.R
import javax.inject.Inject

/**
 * Keeps the process alive while a tunnel is up, with a permanent notification that disconnects on
 * one tap (§5). Nothing is scheduled or woken in the background: the service exists for exactly
 * as long as the tunnel does (§10).
 */
@AndroidEntryPoint
class TunnelService : Service() {

    @Inject
    lateinit var tunnelManager: TunnelManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                tunnelManager.disconnect()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
                val localPort = intent?.getIntExtra(EXTRA_LOCAL_PORT, 0) ?: 0
                val dbHost = intent?.getStringExtra(EXTRA_DB_HOST).orEmpty()
                val dbPort = intent?.getIntExtra(EXTRA_DB_PORT, 3306) ?: 3306
                startForegroundCompat(buildNotification(name, localPort, dbHost, dbPort))
            }
        }
        // Not sticky: a restarted service could not rebuild the tunnel anyway — the key is gone.
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        name: String,
        localPort: Int,
        dbHost: String,
        dbPort: Int,
    ): Notification {
        createChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(getString(R.string.tunnel_notification_title, name))
            .setContentText(getString(R.string.tunnel_notification_body, localPort, dbHost, dbPort))
            .setContentIntent(open)
            .addAction(0, getString(R.string.tunnel_notification_stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tunnel_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "sqlpulse_tunnel"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "hu.laurel.sqlpulse.STOP_TUNNEL"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LOCAL_PORT = "local_port"
        private const val EXTRA_DB_HOST = "db_host"
        private const val EXTRA_DB_PORT = "db_port"

        fun start(context: Context, name: String, localPort: Int, dbHost: String, dbPort: Int) {
            val intent = Intent(context, TunnelService::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_LOCAL_PORT, localPort)
                .putExtra(EXTRA_DB_HOST, dbHost)
                .putExtra(EXTRA_DB_PORT, dbPort)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TunnelService::class.java))
        }
    }
}
