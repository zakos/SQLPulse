package hu.laurel.sqlpulse.data.connection

import hu.laurel.sqlpulse.data.sql.SslMode

/**
 * What the PRODUCTION label costs, once it is more than a colour (research summary, §2.0).
 *
 * The label used to be a warning: the list grouped by it and a dialog asked once. A warning that
 * appears on every connect is a warning that gets tapped through, so production now carries rules
 * instead — a new one is read-only, it may not travel in clear text at all, and writing needs an
 * unlock that runs out by itself. None of this is a lock the user cannot open; it is the
 * difference between deciding to write to production and finding out afterwards that they did.
 *
 * Everything here is pure: no clock of its own, no storage, no Android. The caller passes the
 * current time in, which is what makes "the window has run out" testable and what keeps the same
 * answer coming back when the UI recomposes.
 */
object ProductionPolicy {

    /**
     * How long one unlock lasts. Long enough for the edit the user came to make, short enough that
     * a forgotten session locks itself again before the phone is put down.
     */
    const val UNLOCK_MILLIS: Long = 15 * 60 * 1_000L

    /**
     * The longest a single statement may run on production.
     *
     * Shorter than the app-wide maximum on purpose: on a development box a query that runs for ten
     * minutes wastes ten minutes, and on production it holds locks while everybody else waits.
     */
    const val MAX_QUERY_SECONDS: Int = 60

    /** A new connection's read-only switch. Production starts protected; the user can turn it off. */
    fun defaultReadOnly(environment: ConnectionEnvironment): Boolean = environment.isProduction

    /**
     * Whether the connection is encrypted end to end, by the tunnel or by TLS.
     *
     * Either is enough on its own: inside an SSH tunnel the tunnel is the encryption, and a direct
     * connection with TLS on is protected by the driver. Both off means the password and every row
     * cross the network in the clear, which is the one thing production is never allowed to do.
     */
    fun isProtected(tunnelled: Boolean, sslMode: SslMode): Boolean =
        tunnelled || sslMode != SslMode.DISABLED

    /**
     * Why this connection may not be saved, or null when it may.
     *
     * Only production is judged: the same shape on a development box is the user's business.
     */
    fun refusal(shape: ProductionShape): SaveRefusal? {
        if (!shape.environment.isProduction) return null
        if (!isProtected(shape.tunnelled, shape.sslMode)) return SaveRefusal.Unprotected
        val requested = shape.queryTimeoutSeconds
        if (requested > MAX_QUERY_SECONDS) {
            return SaveRefusal.QueryTimeoutTooLong(requested, MAX_QUERY_SECONDS)
        }
        return null
    }

    fun canSave(shape: ProductionShape): Boolean = refusal(shape) == null

    /**
     * The query timeout a connection actually runs with.
     *
     * A row saved before this policy existed — or edited by hand — can carry any number, and it
     * must not be able to hold a production lock for an hour. [refusal] is what tells the user;
     * this is what the connection runs with regardless.
     */
    fun cappedQueryTimeoutSeconds(environment: ConnectionEnvironment, seconds: Int): Int =
        if (environment.isProduction) seconds.coerceAtMost(MAX_QUERY_SECONDS) else seconds

    /** When an unlock granted at [now] runs out. */
    fun unlockUntil(now: Long): Long = now + UNLOCK_MILLIS

    /**
     * Whether writes go through, and what to say when they do not.
     *
     * @param unlockedUntil the end of the current unlock window, or null if writes were never
     *   unlocked for this connection.
     */
    fun writeAccess(
        environment: ConnectionEnvironment,
        readOnly: Boolean,
        unlockedUntil: Long?,
        now: Long,
    ): WriteAccess = when {
        readOnly -> WriteAccess.ReadOnly
        !environment.isProduction -> WriteAccess.Open
        unlockedUntil == null -> WriteAccess.Locked
        else -> {
            val remaining = unlockedUntil - now
            when {
                remaining <= 0L -> WriteAccess.Expired
                // A window longer than one unlock cannot have been granted by this policy: either
                // the device clock moved backwards, or the value was tampered with. Both are read
                // as locked, because the alternative is a window that never ends.
                remaining > UNLOCK_MILLIS -> WriteAccess.Expired
                else -> WriteAccess.Unlocked(remaining)
            }
        }
    }

    /** What the header counts down, in milliseconds; zero once the window has gone. */
    fun remainingMillis(unlockedUntil: Long?, now: Long): Long {
        if (unlockedUntil == null) return 0L
        val remaining = unlockedUntil - now
        return if (remaining in 1L..UNLOCK_MILLIS) remaining else 0L
    }

    /** Whole minutes left, rounded up: 1 second left still reads "1 min", never "0 min". */
    fun minutesLeft(remainingMillis: Long): Int =
        ((remainingMillis.coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()

    fun remainingMinutes(unlockedUntil: Long?, now: Long): Int =
        minutesLeft(remainingMillis(unlockedUntil, now))
}

/**
 * The part of a connection the policy judges — deliberately not the Room entity, so the rules can
 * be checked against a half-typed form as easily as against a saved row.
 */
data class ProductionShape(
    val environment: ConnectionEnvironment,
    /** Whether the MySQL traffic goes through an SSH tunnel. */
    val tunnelled: Boolean,
    val sslMode: SslMode,
    val readOnly: Boolean,
    val queryTimeoutSeconds: Int,
)

/**
 * Why a production connection was refused at save time.
 *
 * A type rather than a boolean: the editor has to say which rule was broken and offer the fix, and
 * "cannot save" on its own sends the user hunting through four sections.
 */
sealed interface SaveRefusal {

    /** Production over a plain socket: no tunnel, TLS disabled. */
    data object Unprotected : SaveRefusal

    /** A statement allowed to run longer than production tolerates. */
    data class QueryTimeoutTooLong(val requestedSeconds: Int, val maxSeconds: Int) : SaveRefusal
}

/** Whether this connection may write right now, and why not when it may not. */
sealed interface WriteAccess {

    val allowed: Boolean

    /** Not production: writes go through for as long as the session lasts. */
    data object Open : WriteAccess {
        override val allowed: Boolean get() = true
    }

    /** Production, unlocked. [remainingMillis] is what the header counts down. */
    data class Unlocked(val remainingMillis: Long) : WriteAccess {
        override val allowed: Boolean get() = true
    }

    /** The connection's own read-only switch is on; nothing to unlock until it is turned off. */
    data object ReadOnly : WriteAccess {
        override val allowed: Boolean get() = false
    }

    /** Production, writes permitted in principle, never unlocked. The state a session starts in. */
    data object Locked : WriteAccess {
        override val allowed: Boolean get() = false
    }

    /** The unlock window has run out — or the clock says something impossible. */
    data object Expired : WriteAccess {
        override val allowed: Boolean get() = false
    }
}
