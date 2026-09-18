package hu.laurel.sqlpulse.net

/**
 * What the session was doing at the moment the network went away.
 *
 * Captured once, when the loss is noticed, rather than read again later: by the time a retry runs,
 * the pool is already gone and the flags would all read "idle" — which is exactly the state that
 * makes an automatic reconnect look safe when it is not.
 */
data class SessionSnapshot(
    /** The connection is marked read-only, so nothing it ran can have changed the database. */
    val readOnly: Boolean,
    /** A manual transaction was open; the server has rolled it back by dropping the connection. */
    val transactionOpen: Boolean,
    /** A statement was on the wire. We cannot know whether the server ran it. */
    val statementInFlight: Boolean,
    /** A statement that may have written was executed since this session opened. */
    val wroteSinceConnect: Boolean,
)

/** Why the user has to press "Reconnect" themselves instead of it just happening. */
enum class ManualReason {
    /** An open transaction is gone; saying so is more important than getting back quickly. */
    TRANSACTION_ROLLED_BACK,

    /** A writable session: we do not re-open a connection that could be used to write by accident. */
    WRITABLE_SESSION,

    /** Something was in flight and we do not know whether the server ran it. */
    STATEMENT_IN_FLIGHT,

    /** We tried the allowed number of times and the network is still not carrying us. */
    RETRIES_EXHAUSTED,
}

sealed interface ReconnectDecision {

    /** Try again: this is attempt [attempt], after waiting [delayMs]. */
    data class Retry(val attempt: Int, val delayMs: Long) : ReconnectDecision

    /**
     * Do nothing yet — there is no network to reconnect over. Not a failure: the moment one
     * appears the caller asks again, and a retry that was interrupted by a second loss lands here
     * rather than burning an attempt on a dial that cannot succeed.
     */
    data object Wait : ReconnectDecision

    /** Stop trying and put the reconnect button in front of the user, saying [reason]. */
    data class Manual(val reason: ManualReason) : ReconnectDecision
}

/**
 * Whether the app may quietly reconnect after the network moved under it (§5, §11).
 *
 * The bias is deliberate: reconnecting is a convenience, and the cost of getting it wrong is a
 * statement running twice against a live database. So it happens only for a session that could not
 * have changed anything — read-only, no open transaction, nothing in flight — and everything else
 * is handed to the user with an explanation of what the server already did.
 *
 * Pure on purpose: no Android, no coroutines, no clock. The caller does the waiting.
 */
object ReconnectPolicy {

    /** Bounded: past this the network is not flaky, it is gone, and retrying is just battery. */
    const val MAX_ATTEMPTS = 5

    /** First wait. Short enough that a Wi-Fi-to-mobile hand-over is invisible. */
    const val BASE_DELAY_MS = 1_000L

    /** Doubling stops here, so the last attempts are not minutes apart. */
    const val MAX_DELAY_MS = 16_000L

    /**
     * @param attemptsSoFar how many automatic reconnects have already been tried for this loss.
     */
    fun decide(
        session: SessionSnapshot,
        network: NetworkStatus,
        attemptsSoFar: Int,
    ): ReconnectDecision = when {
        // Checked first, and before the network: the user has to be told the transaction is gone
        // whether or not we could dial again, and they must re-open it deliberately.
        session.transactionOpen -> ReconnectDecision.Manual(ManualReason.TRANSACTION_ROLLED_BACK)

        !session.readOnly || session.wroteSinceConnect ->
            ReconnectDecision.Manual(ManualReason.WRITABLE_SESSION)

        session.statementInFlight -> ReconnectDecision.Manual(ManualReason.STATEMENT_IN_FLIGHT)

        // Nothing to dial over. Ask again when something comes up — including a different network,
        // which is a perfectly good one to reconnect on.
        network !is NetworkStatus.Available -> ReconnectDecision.Wait

        attemptsSoFar >= MAX_ATTEMPTS -> ReconnectDecision.Manual(ManualReason.RETRIES_EXHAUSTED)

        else -> {
            val attempt = attemptsSoFar + 1
            ReconnectDecision.Retry(attempt = attempt, delayMs = delayForAttempt(attempt))
        }
    }

    /** 1s, 2s, 4s, 8s, 16s — doubling, capped. [attempt] is 1-based. */
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 1) return BASE_DELAY_MS
        // Shifting rather than pow(): the cap is reached long before an Int shift could overflow,
        // and the coerce is what enforces it.
        val steps = (attempt - 1).coerceAtMost(MAX_SHIFT)
        return (BASE_DELAY_MS shl steps).coerceAtMost(MAX_DELAY_MS)
    }

    private const val MAX_SHIFT = 16
}
