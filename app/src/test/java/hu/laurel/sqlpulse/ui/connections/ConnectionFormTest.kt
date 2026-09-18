package hu.laurel.sqlpulse.ui.connections

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule with no exceptions (§5): a connection cannot be saved without an SSH key. */
class ConnectionFormTest {

    private val valid = ConnectionForm(
        name = "Reporting replica",
        sshHost = "jump.internal",
        sshUser = "andras",
        sshKeyId = 7,
        dbHost = "db.internal",
        database = "reporting",
        dbUser = "mobile_ro",
    )

    @Test
    fun `a complete form can be saved`() {
        assertTrue(valid.canSave)
    }

    @Test
    fun `no key means no save`() {
        assertFalse(valid.copy(sshKeyId = null).canSave)
    }

    @Test
    fun `a blank ssh host means no save`() {
        assertFalse(valid.copy(sshHost = "  ").canSave)
    }

    @Test
    fun `non numeric ports are rejected`() {
        assertFalse(valid.copy(sshPort = "twenty-two").canSave)
        assertFalse(valid.copy(dbPort = "").canSave)
    }

    @Test
    fun `read only is the default`() {
        assertTrue(ConnectionForm().readOnly)
    }
}
