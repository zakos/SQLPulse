package hu.laurel.sqlpulse.ssh

/** The four dots of the stepped connection indicator (§8). */
enum class ConnectStep { KEY, SSH, MYSQL, SCHEMA }

/** Which layer broke, so the message can say so (§11). */
enum class FailureLayer { KEY, SSH_AUTH, SSH_NETWORK, HOST_KEY, MYSQL, LOCAL }

data class TunnelFailure(
    val layer: FailureLayer,
    /** Already-localised, user-facing sentence. */
    val message: String,
    /** Raw cause, only shown behind "Details" (§11). */
    val detail: String? = null,
)

/**
 * The tunnel lifecycle of §5:
 *
 *   Disconnected -> Unlocking -> Connecting -> Active -> Paused -> Active | Disconnected
 *                                    |                     |
 *                                    +-------> Failed <----+
 */
sealed interface TunnelState {
    val connectionId: Long?

    data object Disconnected : TunnelState {
        override val connectionId: Long? = null
    }

    /** Waiting for biometrics / device PIN to unwrap the private key. */
    data class Unlocking(override val connectionId: Long) : TunnelState

    data class Connecting(
        override val connectionId: Long,
        val step: ConnectStep,
    ) : TunnelState

    data class Active(
        override val connectionId: Long,
        val localPort: Int,
        val since: Long,
    ) : TunnelState

    /** App went to the background; the tunnel stays up for at most five minutes (§5). */
    data class Paused(
        override val connectionId: Long,
        val localPort: Int,
        val pausedAt: Long,
    ) : TunnelState

    data class Failed(
        override val connectionId: Long,
        val failure: TunnelFailure,
    ) : TunnelState
}

/** A host key that needs a decision from the user before the handshake may continue (§5). */
data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val keyType: String,
    val offeredFingerprint: String,
    /** Non-null when a different key was pinned before: that case is blocked, not promptable. */
    val storedFingerprint: String?,
)
