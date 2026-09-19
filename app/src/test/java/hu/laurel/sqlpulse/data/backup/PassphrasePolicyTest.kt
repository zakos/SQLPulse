package hu.laurel.sqlpulse.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassphrasePolicyTest {

    @Test
    fun `a long matching pair is accepted`() {
        assertNull(PassphrasePolicy.check("correct horse battery", "correct horse battery"))
    }

    @Test
    fun `a short passphrase is refused`() {
        assertEquals(PassphrasePolicy.Problem.TOO_SHORT, PassphrasePolicy.check("short", "short"))
    }

    @Test
    fun `a mistyped confirmation is refused`() {
        assertEquals(
            PassphrasePolicy.Problem.CONFIRMATION_DIFFERS,
            PassphrasePolicy.check("correct horse battery", "correct horse batteru"),
        )
    }

    @Test
    fun `length is checked before the confirmation, so the first message is the useful one`() {
        assertEquals(PassphrasePolicy.Problem.TOO_SHORT, PassphrasePolicy.check("abc", "xyz"))
    }

    @Test
    fun `an empty passphrase is refused`() {
        assertEquals(PassphrasePolicy.Problem.TOO_SHORT, PassphrasePolicy.check("", ""))
    }
}
