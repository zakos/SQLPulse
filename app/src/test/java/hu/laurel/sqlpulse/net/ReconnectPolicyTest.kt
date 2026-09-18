package hu.laurel.sqlpulse.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val wifi = NetworkStatus.Available(handle = 1L)
private val mobile = NetworkStatus.Available(handle = 2L)

/** A session nothing can have been half-done on: the only one that reconnects by itself. */
private val idleReadOnly = SessionSnapshot(
    readOnly = true,
    transactionOpen = false,
    statementInFlight = false,
    wroteSinceConnect = false,
)

class ReconnectPolicyTest {

    @Test
    fun `an idle read-only session reconnects by itself`() {
        val decision = ReconnectPolicy.decide(idleReadOnly, wifi, attemptsSoFar = 0)
        assertEquals(ReconnectDecision.Retry(attempt = 1, delayMs = 1_000L), decision)
    }

    @Test
    fun `a writable session is never reconnected automatically`() {
        // Even though nothing was in flight and no transaction was open: a connection that can
        // write is not re-opened behind the user's back.
        val decision = ReconnectPolicy.decide(
            idleReadOnly.copy(readOnly = false),
            wifi,
            attemptsSoFar = 0,
        )
        assertEquals(ReconnectDecision.Manual(ManualReason.WRITABLE_SESSION), decision)
    }

    @Test
    fun `a session that has already written is never reconnected automatically`() {
        val decision = ReconnectPolicy.decide(
            idleReadOnly.copy(wroteSinceConnect = true),
            wifi,
            attemptsSoFar = 0,
        )
        assertEquals(ReconnectDecision.Manual(ManualReason.WRITABLE_SESSION), decision)
    }

    @Test
    fun `an open transaction is reported as rolled back, not reconnected`() {
        // The server rolled it back when the connection went. Getting the session quietly back
        // would leave the user believing their uncommitted work is still waiting for a COMMIT.
        val decision = ReconnectPolicy.decide(
            idleReadOnly.copy(transactionOpen = true),
            wifi,
            attemptsSoFar = 0,
        )
        assertEquals(ReconnectDecision.Manual(ManualReason.TRANSACTION_ROLLED_BACK), decision)
    }

    @Test
    fun `the transaction is reported even when there is no network to reconnect over`() {
        val decision = ReconnectPolicy.decide(
            idleReadOnly.copy(transactionOpen = true),
            NetworkStatus.Lost,
            attemptsSoFar = 0,
        )
        assertEquals(ReconnectDecision.Manual(ManualReason.TRANSACTION_ROLLED_BACK), decision)
    }

    @Test
    fun `a transaction on a writable session is reported as the transaction, not as the write`() {
        // Both are true of a real editing session; the rollback is the one that changes what the
        // user has to do next.
        val decision = ReconnectPolicy.decide(
            idleReadOnly.copy(readOnly = false, transactionOpen = true, wroteSinceConnect = true),
            wifi,
            attemptsSoFar = 0,
        )
        assertEquals(ReconnectDecision.Manual(ManualReason.TRANSACTION_ROLLED_BACK), decision)
    }

    @Test
    fun `a statement in flight stops an automatic reconnect`() {
        // We do not know whether the server ran it, and a silent reconnect invites the user to
        // simply press run again.
        val decision = ReconnectPolicy.decide(
            idleReadOnly.copy(statementInFlight = true),
            wifi,
            attemptsSoFar = 0,
        )
        assertEquals(ReconnectDecision.Manual(ManualReason.STATEMENT_IN_FLIGHT), decision)
    }

    @Test
    fun `nothing is dialled while there is no network`() {
        assertEquals(
            ReconnectDecision.Wait,
            ReconnectPolicy.decide(idleReadOnly, NetworkStatus.Lost, attemptsSoFar = 0),
        )
        assertEquals(
            ReconnectDecision.Wait,
            ReconnectPolicy.decide(idleReadOnly, NetworkStatus.Unknown, attemptsSoFar = 0),
        )
    }

    @Test
    fun `the network coming back on a different network is still worth reconnecting on`() {
        // The tunnel was opened over Wi-Fi and we are now on mobile data. That is a new connection
        // either way, so the handle it is dialled over does not matter.
        val decision = ReconnectPolicy.decide(idleReadOnly, mobile, attemptsSoFar = 0)
        assertEquals(ReconnectDecision.Retry(attempt = 1, delayMs = 1_000L), decision)
    }

    @Test
    fun `losing the network again during a retry waits instead of burning an attempt`() {
        // Two attempts are gone; the third is interrupted by a second loss. The remaining attempts
        // are kept for a network that can actually carry the connection.
        val interrupted = ReconnectPolicy.decide(idleReadOnly, NetworkStatus.Lost, attemptsSoFar = 2)
        assertEquals(ReconnectDecision.Wait, interrupted)

        val resumed = ReconnectPolicy.decide(idleReadOnly, mobile, attemptsSoFar = 2)
        assertEquals(ReconnectDecision.Retry(attempt = 3, delayMs = 4_000L), resumed)
    }

    @Test
    fun `retries are bounded`() {
        val last = ReconnectPolicy.decide(
            idleReadOnly,
            wifi,
            attemptsSoFar = ReconnectPolicy.MAX_ATTEMPTS - 1,
        )
        assertTrue(last is ReconnectDecision.Retry)

        assertEquals(
            ReconnectDecision.Manual(ManualReason.RETRIES_EXHAUSTED),
            ReconnectPolicy.decide(idleReadOnly, wifi, attemptsSoFar = ReconnectPolicy.MAX_ATTEMPTS),
        )
        assertEquals(
            ReconnectDecision.Manual(ManualReason.RETRIES_EXHAUSTED),
            ReconnectPolicy.decide(idleReadOnly, wifi, attemptsSoFar = 99),
        )
    }

    @Test
    fun `the wait doubles and then stops growing`() {
        assertEquals(1_000L, ReconnectPolicy.delayForAttempt(1))
        assertEquals(2_000L, ReconnectPolicy.delayForAttempt(2))
        assertEquals(4_000L, ReconnectPolicy.delayForAttempt(3))
        assertEquals(8_000L, ReconnectPolicy.delayForAttempt(4))
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayForAttempt(5))
        // Nothing past the cap, however the attempt number got there.
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayForAttempt(40))
        assertEquals(ReconnectPolicy.BASE_DELAY_MS, ReconnectPolicy.delayForAttempt(0))
    }

    @Test
    fun `a whole run of attempts ends in the user being asked`() {
        // Walked end to end, the way the retry loop does it: every attempt is a Retry with a
        // longer wait, and the one after the last is handed back to the user.
        var attempts = 0
        val waits = mutableListOf<Long>()
        while (true) {
            val decision = ReconnectPolicy.decide(idleReadOnly, wifi, attempts)
            if (decision is ReconnectDecision.Retry) {
                waits += decision.delayMs
                attempts = decision.attempt
            } else {
                assertEquals(ReconnectDecision.Manual(ManualReason.RETRIES_EXHAUSTED), decision)
                break
            }
        }
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS, attempts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), waits)
        assertFalse(waits.any { it > ReconnectPolicy.MAX_DELAY_MS })
    }
}
