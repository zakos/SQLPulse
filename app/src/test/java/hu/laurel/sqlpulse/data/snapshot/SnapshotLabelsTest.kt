package hu.laurel.sqlpulse.data.snapshot

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotLabelsTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-10-02 14:05:09 UTC
    private val afternoon = 1_790_949_909_000L

    private val hungarian = Locale.forLanguageTag("hu-HU")

    @Test
    fun `hungarian times are 24 hour`() {
        assertEquals("14:05", SnapshotLabels.clock(afternoon, hungarian, zone = utc))
        assertEquals("14:05:09", SnapshotLabels.clock(afternoon, hungarian, withSeconds = true, zone = utc))
    }

    @Test
    fun `english times are 12 hour`() {
        val text = SnapshotLabels.clock(afternoon, Locale.US, zone = utc)
        assertTrue(text, text.startsWith("2:05"))
        assertTrue(text, text.endsWith("PM"))
    }

    private fun origin(id: Long, name: String? = "prod", database: String? = "shop") =
        SnapshotOrigin(connectionId = id, connectionName = name, database = database)

    @Test
    fun `a snapshot from the same connection and database has no note`() {
        assertNull(SnapshotLabels.originNote(origin(1), currentConnectionId = 1, currentDatabase = "shop"))
    }

    @Test
    fun `a snapshot from another connection names it and the database`() {
        assertEquals("prod / shop", SnapshotLabels.originNote(origin(1), currentConnectionId = 2, currentDatabase = "shop"))
    }

    @Test
    fun `a snapshot from another database of the same connection names only the database`() {
        assertEquals("shop", SnapshotLabels.originNote(origin(1), currentConnectionId = 1, currentDatabase = "blog"))
    }

    @Test
    fun `no live connection counts as elsewhere`() {
        assertEquals("prod / shop", SnapshotLabels.originNote(origin(1), currentConnectionId = 0, currentDatabase = null))
    }

    @Test
    fun `a snapshot without an origin has no note`() {
        assertNull(SnapshotLabels.originNote(null, 1, "shop"))
        assertNull(SnapshotLabels.originNote(origin(1, name = null, database = null), 2, "shop"))
    }
}
