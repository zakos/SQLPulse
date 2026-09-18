package hu.laurel.sqlpulse.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * The phone's default network, as a flow (§5).
 *
 * All this class does is translate `registerDefaultNetworkCallback` into [NetworkStatus]; every
 * decision taken from it lives in [NetworkChange] and [ReconnectPolicy], which are plain Kotlin
 * and covered by unit tests. Nothing here is worth mocking a ConnectivityManager for.
 *
 * The *default* network specifically, not "any network with internet": what breaks a socket is the
 * route the app is actually using moving, and a Wi-Fi network appearing while mobile data is
 * carrying us is not that.
 *
 * Registration follows subscription — `callbackFlow` registers on the first collector and
 * `awaitClose` unregisters when the last one goes away — so a watcher nobody listens to costs
 * nothing, and there is exactly one place the callback can leak from.
 */
@Singleton
class NetworkWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
) {

    val state: StateFlow<NetworkStatus> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            // No connectivity service at all (a stripped ROM, an instrumentation context): report
            // nothing rather than "down", which would tear a perfectly live tunnel apart.
            trySend(NetworkStatus.Unknown)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // handle() is stable per network, so a Wi-Fi to mobile hand-over arrives as a new
                // value instead of looking like nothing happened.
                trySend(NetworkStatus.Available(network.handle()))
            }

            override fun onLost(network: Network) {
                trySend(NetworkStatus.Lost)
            }
        }

        // A registration can throw (a device out of callback slots); that must not take the app
        // with it. We then stay on Unknown and nothing invalidates anything.
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess

        awaitClose {
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
        .distinctUntilChanged()
        // Kept alive a little past the last collector, so moving between screens does not
        // deregister and reregister the callback on every resubscribe.
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NetworkStatus.Unknown)

    /**
     * What changed, rather than what is. A collector that starts while the network is already up
     * is not told it just "came up": only real transitions are emitted.
     */
    val changes: Flow<NetworkChange> = state
        .runningFold(NetworkStatus.Unknown as NetworkStatus to NetworkChange.NONE) { previous, status ->
            status to NetworkChange.between(previous.first, status)
        }
        .map { it.second }
        .filter { it != NetworkChange.NONE }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
