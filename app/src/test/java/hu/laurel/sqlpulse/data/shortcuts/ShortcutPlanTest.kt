package hu.laurel.sqlpulse.data.shortcuts

import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment.DEVELOPMENT
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment.PRODUCTION
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment.UNSET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutPlanTest {

    private fun c(id: Long, used: Long?, env: ConnectionEnvironment = DEVELOPMENT, name: String = "db$id") =
        ShortcutCandidate(id, name, env, used)

    @Test
    fun `off means no shortcuts`() {
        assertTrue(ShortcutPlan.plan(listOf(c(1, 10)), enabled = false).isEmpty())
    }

    @Test
    fun `newest first and at most three`() {
        val plan = ShortcutPlan.plan(listOf(c(1, 10), c(2, 40), c(3, 30), c(4, 20)), enabled = true)
        assertEquals(listOf(2L, 3L, 4L), plan.map { it.connectionId })
    }

    @Test
    fun `production and never used connections are excluded`() {
        val plan = ShortcutPlan.plan(listOf(c(1, 50, PRODUCTION), c(2, null), c(3, 5, UNSET)), enabled = true)
        assertEquals(listOf(3L), plan.map { it.connectionId })
    }

    @Test
    fun `ties break by id and the label is only the trimmed name`() {
        val plan = ShortcutPlan.plan(listOf(c(5, 9, name = " Shop "), c(2, 9), c(3, 9, name = " ")), enabled = true)
        assertEquals(listOf(2L, 5L), plan.map { it.connectionId })
        assertEquals("Shop", plan[1].label)
    }

    @Test
    fun `stale ids are ours only and not in the plan`() {
        val plan = ShortcutPlan.plan(listOf(c(1, 1)), enabled = true)
        assertEquals(listOf("connection-2"), ShortcutPlan.stale(listOf("connection-1", "connection-2", "other"), plan))
    }

    @Test
    fun `intent parsing accepts only our action with a positive id`() {
        assertEquals(7L, ShortcutPlan.parse(ShortcutPlan.ACTION_CONNECT, 7L))
        assertNull(ShortcutPlan.parse(ShortcutPlan.ACTION_CONNECT, 0L))
        assertNull(ShortcutPlan.parse(ShortcutPlan.ACTION_CONNECT, -3L))
        assertNull(ShortcutPlan.parse(ShortcutPlan.ACTION_CONNECT, null))
        assertNull(ShortcutPlan.parse("android.intent.action.MAIN", 7L))
        assertNull(ShortcutPlan.parse(null as String?, 7L))
    }

    @Test
    fun `a request goes stale and is consumed once`() {
        assertTrue(ShortcutRequest(1, 1_000).isFresh(1_000 + ShortcutRequest.MAX_AGE_MS))
        assertFalse(ShortcutRequest(1, 1_000).isFresh(1_001 + ShortcutRequest.MAX_AGE_MS))
        assertFalse(ShortcutRequest(1, 1_000).isFresh(500))
        val requests = ShortcutRequests()
        requests.post(4, now = 1_000)
        assertEquals(4L, requests.take(now = 2_000)?.connectionId)
        assertNull(requests.take(now = 2_000))
        requests.post(4, now = 1_000)
        assertNull(requests.take(now = 1_000 + ShortcutRequest.MAX_AGE_MS + 1))
        assertNull(requests.pending.value)
    }
}
