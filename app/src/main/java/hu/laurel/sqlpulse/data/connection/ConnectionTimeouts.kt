package hu.laurel.sqlpulse.data.connection

/**
 * How long one connection waits, in seconds (research summary, §2.0).
 *
 * A database on the other side of a VPN needs longer to answer than one on the same rack, and a
 * report that legitimately runs for two minutes should not be killed at thirty seconds — while on
 * a production server a runaway query is exactly what should be killed early. Both numbers are
 * therefore per connection rather than one setting for the whole app.
 */
object ConnectionTimeouts {

    /** Long enough for a tunnel that is still settling, short enough to fail while watching. */
    const val DEFAULT_CONNECT_SECONDS = 10

    const val DEFAULT_QUERY_SECONDS = 30

    /** An hour. Past this the number is a mistake, not a preference. */
    const val MAX_SECONDS = 3_600

    /**
     * The socket is given a little more than the query, so that a query running over its time is
     * ended by `KILL QUERY` — which says what happened — rather than by the socket dropping under
     * the driver, which looks like the network failing.
     */
    private const val SOCKET_GRACE_SECONDS = 5

    /** Null for anything that is not a whole number of seconds in range: the field stays red. */
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_SECONDS }

    fun isValid(text: String): Boolean = parse(text) != null

    /**
     * Brings a stored number back into range. A row written by an older version, or by hand, must
     * not be able to set a zero timeout — in JDBC zero means "wait forever".
     */
    fun sane(seconds: Int, default: Int): Int =
        if (seconds in 1..MAX_SECONDS) seconds else default

    fun connectMillis(seconds: Int): Int = sane(seconds, DEFAULT_CONNECT_SECONDS) * 1_000

    fun socketMillis(querySeconds: Int): Int =
        (sane(querySeconds, DEFAULT_QUERY_SECONDS) + SOCKET_GRACE_SECONDS) * 1_000
}
