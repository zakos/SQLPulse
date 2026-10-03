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

    /**
     * A backup written before the app knew other engines has no `engine` key. Every connection in
     * it was a MySQL connection, so that is what it has to come back as — not a decode failure,
     * and not whatever the newest default might be.
     */
    @Test
    fun `a connection from a backup without engines comes back as MySQL`() {
        val old = """
            {"connections":[{"name":"legacy","color":"Teal","useSshTunnel":false,"dbHost":"db.example.com",
            "dbPort":3306,"database":"shop","dbUser":"app","savedQueries":[]}],"sshKeys":[],"certificates":[]}
        """.trimIndent()
        val connection = BackupCodec.decode(old).connections.single()
        assertEquals("MYSQL", connection.engine)
        assertNull(connection.fileName)
        assertEquals("db.example.com", connection.dbHost)
    }

    @Test
    fun `the engine and the SQLite file name survive the round trip`() {
        val sqlite = BackupSamples.connection("field data").copy(
            engine = "SQLITE",
            useSshTunnel = false,
            fileName = "inspections.db",
        )
        val postgres = BackupSamples.connection("reporting").copy(engine = "POSTGRESQL", dbPort = 5432)
        val payload = BackupPayload(connections = listOf(sqlite, postgres))
        val text = BackupCodec.encode(payload)
        assertTrue(text.contains("\"engine\":\"SQLITE\""))
        val decoded = BackupCodec.decode(text).connections
        assertEquals("SQLITE", decoded[0].engine)
        assertEquals("inspections.db", decoded[0].fileName)
        assertEquals("POSTGRESQL", decoded[1].engine)
        assertEquals(payload.connections, decoded)
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
