package hu.laurel.sqlpulse

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import hu.laurel.sqlpulse.data.crypto.KeystoreCrypto
import hu.laurel.sqlpulse.data.db.QueryHistoryDao
import hu.laurel.sqlpulse.data.export.ExportManager
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.di.ApplicationScope
import hu.laurel.sqlpulse.ssh.TunnelManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class SqlPulseApplication : Application() {

    @Inject
    lateinit var keystoreCrypto: KeystoreCrypto

    @Inject
    lateinit var tunnelManager: TunnelManager

    @Inject
    lateinit var queryHistory: QueryHistoryDao

    /**
     * Injected so it exists from process start: it watches the tunnel and opens the JDBC session
     * the moment the tunnel is up, while the unlock that built the tunnel is still valid.
     */
    @Inject
    lateinit var sqlSessions: SqlSessionManager

    @Inject
    lateinit var exports: ExportManager

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        keystoreCrypto.ensureKeys()
        // §7.7, §9: an export from a previous session must not outlive it.
        exports.clearExports()

        // §9: query history is kept for 30 days, then dropped.
        scope.launch {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(HISTORY_RETENTION_DAYS)
            runCatching { queryHistory.deleteOlderThan(cutoff) }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = tunnelManager.onAppForegrounded()
                override fun onStop(owner: LifecycleOwner) = tunnelManager.onAppBackgrounded()
            },
        )
    }

    private companion object {
        const val HISTORY_RETENTION_DAYS = 30L
    }
}
