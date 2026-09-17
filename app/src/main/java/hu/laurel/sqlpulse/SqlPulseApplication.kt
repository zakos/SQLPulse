package hu.laurel.sqlpulse

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import hu.laurel.sqlpulse.data.crypto.KeystoreCrypto
import hu.laurel.sqlpulse.data.db.QueryHistoryDao
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

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        keystoreCrypto.ensureKeys()

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
