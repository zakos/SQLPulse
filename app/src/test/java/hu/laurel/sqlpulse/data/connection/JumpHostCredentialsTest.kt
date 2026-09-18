package hu.laurel.sqlpulse.data.connection

import hu.laurel.sqlpulse.ssh.SshAuthMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpHostCredentialsTest {

    @Test
    fun `a row written before the columns existed still shares one credential`() {
        // This is the compatibility guarantee of migration 7 to 8: NULL means "as before".
        assertEquals(
            JumpCredential.SameAsSshHost,
            JumpHostCredentials.resolve(jumpAuthMethod = null, jumpKeyId = null),
        )
        assertFalse(JumpHostCredentials.isSeparate(null, null))
        // Even a key id left behind by an edit does not split the hops on its own.
        assertEquals(
            JumpCredential.SameAsSshHost,
            JumpHostCredentials.resolve(jumpAuthMethod = null, jumpKeyId = 7L),
        )
    }

    @Test
    fun `a jump host with its own key names it`() {
        assertEquals(
            JumpCredential.Key(7L),
            JumpHostCredentials.resolve(SshAuthMethod.KEY.name, 7L),
        )
        assertTrue(JumpHostCredentials.isSeparate(SshAuthMethod.KEY.name, 7L))
    }

    @Test
    fun `a jump host with its own password needs no key`() {
        assertEquals(
            JumpCredential.Password,
            JumpHostCredentials.resolve(SshAuthMethod.PASSWORD.name, null),
        )
        assertTrue(JumpHostCredentials.isSeparate(SshAuthMethod.PASSWORD.name, null))
    }

    @Test
    fun `a half-written row falls back to what worked before it`() {
        // KEY without a key id would be a connection that cannot enter its first hop at all, so it
        // shares the credential instead — a connection that used to work keeps working.
        assertEquals(
            JumpCredential.SameAsSshHost,
            JumpHostCredentials.resolve(SshAuthMethod.KEY.name, null),
        )
        assertEquals(
            JumpCredential.SameAsSshHost,
            JumpHostCredentials.resolve("KERBEROS", 3L),
        )
    }
}
