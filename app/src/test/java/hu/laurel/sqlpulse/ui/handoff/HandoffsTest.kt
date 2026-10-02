package hu.laurel.sqlpulse.ui.handoff

import hu.laurel.sqlpulse.data.sql.ColumnFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoffsTest {

    @Test
    fun `editor text is taken once`() {
        val handoff = EditorHandoff()
        handoff.offer("SELECT 1")
        assertEquals("SELECT 1", handoff.pending.value)
        assertEquals("SELECT 1", handoff.take())
        assertNull(handoff.take())
        assertNull(handoff.pending.value)
    }

    @Test
    fun `blank text is not offered`() {
        val handoff = EditorHandoff()
        handoff.offer("   ")
        assertNull(handoff.take())
    }

    @Test
    fun `a table filter is only for its own table and only once`() {
        val handoff = TableFilterHandoff()
        val filter = ColumnFilter("id", "7", exact = true)
        handoff.offer("shop", "orders", filter)
        assertNull(handoff.take("shop", "customers"))
        assertEquals(filter, handoff.take("shop", "orders"))
        assertNull(handoff.take("shop", "orders"))
    }
}
