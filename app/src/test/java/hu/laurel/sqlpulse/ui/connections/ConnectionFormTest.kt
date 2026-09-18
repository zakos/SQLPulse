package hu.laurel.sqlpulse.ui.connections

import hu.laurel.sqlpulse.ssh.SshAuthMethod
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a connection needs before it can be saved (§5). */
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
    fun `key authentication without a key means no save`() {
        assertFalse(valid.copy(sshKeyId = null).canSave)
    }

    @Test
    fun `password authentication needs a password instead of a key`() {
        val withPassword = valid.copy(
            sshAuthMethod = SshAuthMethod.PASSWORD,
            sshKeyId = null,
            sshPassword = "hunter2",
        )
        assertTrue(withPassword.canSave)
        assertFalse(withPassword.copy(sshPassword = "").canSave)
    }

    @Test
    fun `a direct connection needs neither`() {
        val direct = valid.copy(useSsh = false, sshKeyId = null, sshHost = "")
        assertTrue(direct.canSave)
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
