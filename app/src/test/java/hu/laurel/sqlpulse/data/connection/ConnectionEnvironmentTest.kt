package hu.laurel.sqlpulse.data.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionEnvironmentTest {

    @Test
    fun `a stored name comes back, and anything else is unclassified`() {
        assertEquals(ConnectionEnvironment.PRODUCTION, ConnectionEnvironment.fromName("PRODUCTION"))
        assertEquals(ConnectionEnvironment.TEST, ConnectionEnvironment.fromName("TEST"))
        // Never guesses at production: a row from an older version says nothing, not "live".
        assertEquals(ConnectionEnvironment.UNSET, ConnectionEnvironment.fromName(null))
        assertEquals(ConnectionEnvironment.UNSET, ConnectionEnvironment.fromName("STAGING"))
        assertEquals(ConnectionEnvironment.UNSET, ConnectionEnvironment.fromName("production"))
    }

    @Test
    fun `only production counts as production`() {
        assertTrue(ConnectionEnvironment.PRODUCTION.isProduction)
        listOf(
            ConnectionEnvironment.DEVELOPMENT,
            ConnectionEnvironment.TEST,
            ConnectionEnvironment.UNSET,
        ).forEach { assertFalse(it.name, it.isProduction) }
    }

    @Test
    fun `groups come in order, and empty ones are left out`() {
        val items = listOf(
            "prod one" to ConnectionEnvironment.PRODUCTION,
            "dev" to ConnectionEnvironment.DEVELOPMENT,
            "prod two" to ConnectionEnvironment.PRODUCTION,
        )
        val groups = ConnectionEnvironment.group(items) { it.second }
        assertEquals(
            listOf(ConnectionEnvironment.DEVELOPMENT, ConnectionEnvironment.PRODUCTION),
            groups.map { it.first },
        )
        // Within a group the list keeps the order it arrived in — most recently used first.
        assertEquals(listOf("prod one", "prod two"), groups[1].second.map { it.first })
    }

    @Test
    fun `unclassified connections sort last`() {
        val items = listOf(ConnectionEnvironment.UNSET, ConnectionEnvironment.TEST)
        assertEquals(
            listOf(ConnectionEnvironment.TEST, ConnectionEnvironment.UNSET),
            ConnectionEnvironment.group(items) { it }.map { it.first },
        )
    }

    @Test
    fun `an empty list has no groups at all`() {
        assertTrue(ConnectionEnvironment.group(emptyList<ConnectionEnvironment>()) { it }.isEmpty())
    }
}
