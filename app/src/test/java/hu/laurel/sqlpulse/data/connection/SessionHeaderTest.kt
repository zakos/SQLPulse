package hu.laurel.sqlpulse.data.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHeaderTest {

    private fun header(
        tunnelled: Boolean = true,
        database: String = "shop",
        currentDatabase: String? = null,
        environment: ConnectionEnvironment = ConnectionEnvironment.PRODUCTION,
    ) = sessionHeader(
        connectionName = "Webshop",
        tunnelled = tunnelled,
        sshUser = "deploy",
        sshHost = "gate.example.com",
        dbHost = "db01",
        dbPort = 3306,
        database = database,
        currentDatabase = currentDatabase,
        dbUser = "reader",
        environment = environment,
        readOnly = true,
    )

    @Test
    fun `a tunnelled connection names both ends`() {
        assertEquals("deploy@gate.example.com → db01:3306", header().server)
        assertEquals("deploy@gate.example.com → db01:3306/shop", header().target)
    }

    @Test
    fun `a direct connection has no SSH host to name`() {
        assertEquals("db01:3306", header(tunnelled = false).server)
    }

    @Test
    fun `the header follows the session when a USE moves the database`() {
        assertEquals("reports", header(currentDatabase = "reports").database)
        // Nothing has moved it yet, or the session is between databases: the connection's own.
        assertEquals("shop", header(currentDatabase = null).database)
        assertEquals("shop", header(currentDatabase = "").database)
    }

    @Test
    fun `only production is flagged`() {
        assertTrue(header().isProduction)
        assertFalse(header(environment = ConnectionEnvironment.TEST).isProduction)
    }

    @Test
    fun `a connection with no database named still has something to show`() {
        assertEquals("deploy@gate.example.com → db01:3306", header(database = "").target)
    }
}
