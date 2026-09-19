package hu.laurel.sqlpulse.data.connection

import hu.laurel.sqlpulse.data.sql.SslMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionPolicyTest {

    private fun shape(
        environment: ConnectionEnvironment = ConnectionEnvironment.PRODUCTION,
        tunnelled: Boolean = true,
        sslMode: SslMode = SslMode.DISABLED,
        readOnly: Boolean = true,
        queryTimeoutSeconds: Int = 30,
    ) = ProductionShape(environment, tunnelled, sslMode, readOnly, queryTimeoutSeconds)

    @Test
    fun `a new production connection starts read-only, and nothing else does`() {
        assertTrue(ProductionPolicy.defaultReadOnly(ConnectionEnvironment.PRODUCTION))
        assertFalse(ProductionPolicy.defaultReadOnly(ConnectionEnvironment.DEVELOPMENT))
        assertFalse(ProductionPolicy.defaultReadOnly(ConnectionEnvironment.TEST))
        // An unclassified connection is not treated as production: the label has to be chosen.
        assertFalse(ProductionPolicy.defaultReadOnly(ConnectionEnvironment.UNSET))
    }

    @Test
    fun `either the tunnel or TLS counts as protection`() {
        assertTrue(ProductionPolicy.isProtected(tunnelled = true, sslMode = SslMode.DISABLED))
        assertTrue(ProductionPolicy.isProtected(tunnelled = false, sslMode = SslMode.REQUIRED))
        assertTrue(ProductionPolicy.isProtected(tunnelled = false, sslMode = SslMode.VERIFY_CA))
        assertFalse(ProductionPolicy.isProtected(tunnelled = false, sslMode = SslMode.DISABLED))
    }

    @Test
    fun `production over a plain socket is refused, and says why`() {
        val refusal = ProductionPolicy.refusal(shape(tunnelled = false, sslMode = SslMode.DISABLED))
        assertEquals(SaveRefusal.Unprotected, refusal)
        assertFalse(ProductionPolicy.canSave(shape(tunnelled = false, sslMode = SslMode.DISABLED)))
    }

    @Test
    fun `the same connection anywhere else is the user's own business`() {
        listOf(
            ConnectionEnvironment.DEVELOPMENT,
            ConnectionEnvironment.TEST,
            ConnectionEnvironment.UNSET,
        ).forEach { environment ->
            assertNull(
                environment.name,
                ProductionPolicy.refusal(
                    shape(environment = environment, tunnelled = false, sslMode = SslMode.DISABLED),
                ),
            )
        }
    }

    @Test
    fun `a protected production connection saves`() {
        assertTrue(ProductionPolicy.canSave(shape(tunnelled = true)))
        assertTrue(ProductionPolicy.canSave(shape(tunnelled = false, sslMode = SslMode.VERIFY_IDENTITY)))
    }

    @Test
    fun `a statement that may run longer than production tolerates is refused with both numbers`() {
        val refusal = ProductionPolicy.refusal(shape(queryTimeoutSeconds = 600))
        assertEquals(
            SaveRefusal.QueryTimeoutTooLong(600, ProductionPolicy.MAX_QUERY_SECONDS),
            refusal,
        )
        // Exactly at the cap is fine: the boundary belongs to the user, not to the rule.
        assertNull(ProductionPolicy.refusal(shape(queryTimeoutSeconds = ProductionPolicy.MAX_QUERY_SECONDS)))
    }

    @Test
    fun `a stored timeout above the cap is brought down on production only`() {
        assertEquals(
            ProductionPolicy.MAX_QUERY_SECONDS,
            ProductionPolicy.cappedQueryTimeoutSeconds(ConnectionEnvironment.PRODUCTION, 3_600),
        )
        assertEquals(
            30,
            ProductionPolicy.cappedQueryTimeoutSeconds(ConnectionEnvironment.PRODUCTION, 30),
        )
        assertEquals(
            3_600,
            ProductionPolicy.cappedQueryTimeoutSeconds(ConnectionEnvironment.DEVELOPMENT, 3_600),
        )
    }

    @Test
    fun `writes outside production need no unlock`() {
        val access = ProductionPolicy.writeAccess(
            environment = ConnectionEnvironment.DEVELOPMENT,
            readOnly = false,
            unlockedUntil = null,
            now = 1_000L,
        )
        assertEquals(WriteAccess.Open, access)
        assertTrue(access.allowed)
    }

    @Test
    fun `the read-only switch wins everywhere, unlock or not`() {
        val now = 1_000L
        assertEquals(
            WriteAccess.ReadOnly,
            ProductionPolicy.writeAccess(
                environment = ConnectionEnvironment.PRODUCTION,
                readOnly = true,
                unlockedUntil = ProductionPolicy.unlockUntil(now),
                now = now,
            ),
        )
        assertEquals(
            WriteAccess.ReadOnly,
            ProductionPolicy.writeAccess(
                environment = ConnectionEnvironment.DEVELOPMENT,
                readOnly = true,
                unlockedUntil = null,
                now = now,
            ),
        )
    }

    @Test
    fun `a production connection that was never unlocked cannot write`() {
        val access = ProductionPolicy.writeAccess(
            environment = ConnectionEnvironment.PRODUCTION,
            readOnly = false,
            unlockedUntil = null,
            now = 0L,
        )
        assertEquals(WriteAccess.Locked, access)
        assertFalse(access.allowed)
    }

    @Test
    fun `an unlock lasts a quarter of an hour and then stops`() {
        val now = 10_000L
        val until = ProductionPolicy.unlockUntil(now)
        assertEquals(now + ProductionPolicy.UNLOCK_MILLIS, until)

        assertEquals(
            WriteAccess.Unlocked(ProductionPolicy.UNLOCK_MILLIS),
            unlocked(until, now),
        )
        assertEquals(WriteAccess.Unlocked(1L), unlocked(until, until - 1))
        // The last millisecond is the end of it: the window is closed at its own deadline.
        assertEquals(WriteAccess.Expired, unlocked(until, until))
        assertEquals(WriteAccess.Expired, unlocked(until, until + 60_000L))
    }

    @Test
    fun `a clock that jumps backwards locks instead of unlocking forever`() {
        // A window longer than one unlock cannot have been granted by this policy. Trusting it
        // would leave production writable until the clock caught up again, which on a phone whose
        // time zone or NTP moved could be hours.
        val now = 1_000_000L
        val until = ProductionPolicy.unlockUntil(now)
        assertEquals(WriteAccess.Expired, unlocked(until, now - 60_000L))
        assertEquals(WriteAccess.Expired, unlocked(until, 0L))
        // One millisecond inside the window is still the window.
        assertEquals(
            WriteAccess.Unlocked(ProductionPolicy.UNLOCK_MILLIS),
            unlocked(until, now),
        )
    }

    @Test
    fun `the countdown never shows a minute that is not there`() {
        val now = 0L
        val until = ProductionPolicy.unlockUntil(now)
        assertEquals(15, ProductionPolicy.remainingMinutes(until, now))
        assertEquals(1, ProductionPolicy.remainingMinutes(until, until - 1))
        assertEquals(1, ProductionPolicy.remainingMinutes(until, until - 60_000L))
        assertEquals(0, ProductionPolicy.remainingMinutes(until, until))
        assertEquals(0, ProductionPolicy.remainingMinutes(null, now))
        // A backwards clock reads as nothing left, matching what writeAccess decided.
        assertEquals(0, ProductionPolicy.remainingMinutes(until, now - 1))
        assertEquals(0L, ProductionPolicy.remainingMillis(null, now))
    }

    private fun unlocked(until: Long, now: Long): WriteAccess = ProductionPolicy.writeAccess(
        environment = ConnectionEnvironment.PRODUCTION,
        readOnly = false,
        unlockedUntil = until,
        now = now,
    )
}
