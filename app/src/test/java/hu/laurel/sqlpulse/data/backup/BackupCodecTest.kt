package hu.laurel.sqlpulse.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    @Test
    fun `every field survives the round trip`() {
        val payload = BackupSamples.payload(withSecrets = true)
        val decoded = BackupCodec.decode(BackupCodec.encode(payload))
        assertEquals(payload, decoded)
    }

    @Test
    fun `settings-only payload carries no secret anywhere in its text`() {
        val payload = BackupSamples.payload(withSecrets = false)
        val text = BackupCodec.encode(payload)
        assertTrue(text.contains("bastion.example.com"))
        assertTrue(!text.contains("hunter2"))
        val decoded = BackupCodec.decode(text)
        assertNull(decoded.connections.first().dbPassword)
        assertNull(decoded.sshKeys.first().privateKeyBase64)
        assertTrue(!decoded.containsSecrets)
    }

    @Test
    fun `saved queries stay with their connection`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(BackupSamples.payload()))
        assertEquals(2, decoded.connections[0].savedQueries.size)
        assertEquals(0, decoded.connections[1].savedQueries.size)
        assertEquals(2, decoded.savedQueryCount)
    }

    @Test
    fun `text with newlines and quotes survives`() {
        val payload = BackupPayload(
            certificates = listOf(BackupCertificate("ca\"odd.pem", "line1\nline2\t\\end\n")),
        )
        assertEquals(payload, BackupCodec.decode(BackupCodec.encode(payload)))
    }

    @Test
    fun `a payload missing a required field is refused`() {
        val broken = """{"connections":[{"name":"x"}],"sshKeys":[],"certificates":[]}"""
        val thrown = runCatching { BackupCodec.decode(broken) }.exceptionOrNull()
        assertTrue("was $thrown", thrown is BackupFormatException)
    }

    @Test
    fun `text that is not json at all is refused`() {
        val thrown = runCatching { BackupCodec.decode("not json") }.exceptionOrNull()
        assertTrue("was $thrown", thrown is BackupFormatException)
    }
}
