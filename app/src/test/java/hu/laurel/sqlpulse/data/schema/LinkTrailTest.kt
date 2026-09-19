package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTrailTest {

    private fun step(table: String, value: String? = null, guessed: Boolean = false) = TrailStep(
        database = "shop",
        table = table,
        filter = value?.let { RowFilter(listOf(ColumnMatch("id", it))) },
        label = table,
        guessed = guessed,
    )

    @Test
    fun `an empty trail has nowhere to go`() {
        val trail = LinkTrail()

        assertTrue(trail.isEmpty)
        assertFalse(trail.canGoBack)
        assertNull(trail.current)
        assertEquals(LinkTrail(), trail.pop())
    }

    @Test
    fun `the first step cannot be popped away`() {
        val trail = LinkTrail().push(step("orders"))

        assertFalse(trail.canGoBack)
        assertEquals(1, trail.pop().depth)
        assertEquals("orders", trail.current?.table)
    }

    @Test
    fun `parent then child then back returns to where it was`() {
        val trail = LinkTrail()
            .push(step("orders"))
            .push(step("customers", "42"))
            .push(step("orders", "7"))

        assertEquals(3, trail.depth)
        assertTrue(trail.canGoBack)

        val back = trail.pop()
        assertEquals("customers", back.current?.table)
        assertEquals(2, back.depth)
        assertEquals("orders", back.pop().current?.table)
        assertFalse(back.pop().canGoBack)
    }

    @Test
    fun `returning to a step already walked rewinds rather than growing the trail`() {
        val start = step("orders")
        val trail = LinkTrail()
            .push(start)
            .push(step("customers", "42"))
            .push(start)

        assertEquals(1, trail.depth)
        assertEquals(listOf(start), trail.steps)
        assertFalse(trail.canGoBack)
    }

    @Test
    fun `the same table with a different filter is a new step`() {
        val trail = LinkTrail()
            .push(step("orders"))
            .push(step("orders", "7"))

        assertEquals(2, trail.depth)
        assertTrue(trail.canGoBack)
    }

    @Test
    fun `a guessed step marks the trail`() {
        val trail = LinkTrail().push(step("orders")).push(step("customers", "42", guessed = true))

        assertTrue(trail.hasGuessedStep)
        assertFalse(trail.pop().hasGuessedStep)
    }
}
