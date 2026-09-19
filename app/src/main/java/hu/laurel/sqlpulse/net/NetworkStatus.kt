package hu.laurel.sqlpulse.net

/**
 * What the phone's default network is doing, as far as this app is concerned (§5).
 *
 * Deliberately not an Android type. Everything that *decides* anything — [NetworkChange],
 * [ReconnectPolicy] — is then plain Kotlin and testable on a JVM, and NetworkWatcher is left with
 * nothing but the ConnectivityManager registration, which is the part no unit test can run.
 */
sealed interface NetworkStatus {

    /** Nothing observed yet: the callback has not fired since it was registered. */
    data object Unknown : NetworkStatus

    /**
     * A default network is up. [handle] identifies *which* one — Android's `Network` object is
     * per-network, so Wi-Fi giving way to mobile data shows up as a new handle rather than as a
     * loss followed by an arrival. Without it, a hand-over looks exactly like nothing happening.
     */
    data class Available(val handle: Long) : NetworkStatus

    /** There is no default network at all: flight mode, or out of coverage. */
    data object Lost : NetworkStatus
}

/**
 * What happened between two observations of [NetworkStatus].
 *
 * A socket — the SSH transport, and every JDBC connection in the pool behind it — is bound to the
 * network it was opened on. Both [LOST] and [SWITCHED] leave it dead without anything having
 * thrown yet, which is the whole reason this type exists: waiting for the next statement to time
 * out is up to a minute of the user staring at a spinner.
 */
enum class NetworkChange {
    NONE,
    CAME_UP,
    LOST,
    SWITCHED,
    ;

    /** True when whatever was open on the old network can no longer carry traffic. */
    val invalidatesConnections: Boolean
        get() = this == LOST || this == SWITCHED

    companion object {

        fun between(previous: NetworkStatus, current: NetworkStatus): NetworkChange = when {
            previous == current -> NONE

            // Nothing was known to be up, so nothing of ours can have been broken by this.
            previous is NetworkStatus.Unknown ->
                if (current is NetworkStatus.Available) CAME_UP else NONE

            previous is NetworkStatus.Lost ->
                if (current is NetworkStatus.Available) CAME_UP else NONE

            current is NetworkStatus.Lost -> LOST

            // Both are Available with different handles: a hand-over, e.g. Wi-Fi to mobile data.
            else -> SWITCHED
        }
    }
}
