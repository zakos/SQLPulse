package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFactViewTest {
    @Test
    fun tlsVersionAndCipherBecomeOneRow() {
        val rows = ServerFactView.present(
            listOf(ServerFact("Ssl_version", "TLSv1.3"), ServerFact("Ssl_cipher", "AES")),
        )
        assertEquals(1, rows.size)
        assertEquals(FactKind.TLS, rows[0].kind)
        assertEquals("TLSv1.3 · AES", rows[0].value)
        assertEquals(true, rows[0].flag)
    }

    @Test
    fun emptyOrDashedTlsMeansNoEncryption() {
        val rows = ServerFactView.present(listOf(ServerFact("Ssl_version", ""), ServerFact("Ssl_cipher", "—")))
        assertEquals("", rows.single().value)
        assertEquals(false, rows.single().flag)
    }

    @Test
    fun sqlServerEncryptedFlagIsKept() {
        assertEquals(true, ServerFactView.present(listOf(ServerFact("encrypted", "yes"))).single().flag)
        assertEquals(false, ServerFactView.present(listOf(ServerFact("encrypted", "no"))).single().flag)
    }

    @Test
    fun importantFactsComeFirstAndUnknownOnesKeepTheirName() {
        val rows = ServerFactView.present(
            listOf(
                ServerFact("charset", "utf8mb4"),
                ServerFact("mystery", "1"),
                ServerFact("Ssl_version", "TLSv1.2"),
                ServerFact("Threads_connected", "3"),
                ServerFact("Uptime", "10"),
                ServerFact("current_user", "me"),
                ServerFact("hostname", "h"),
                ServerFact("version", "8"),
            ),
        )
        assertEquals(
            listOf(
                FactKind.VERSION, FactKind.HOST, FactKind.USER, FactKind.UPTIME,
                FactKind.CONNECTIONS, FactKind.TLS, FactKind.CHARSET, null,
            ),
            rows.map { it.kind },
        )
        assertEquals("mystery", rows.last().label)
        assertNull(rows.last().kind)
    }

    @Test
    fun readOnlyFlag() {
        assertEquals(false, ServerFactView.present(listOf(ServerFact("read_only", "0"))).single().flag)
        assertTrue(ServerFactView.present(listOf(ServerFact("read_only", "1"))).single().flag == true)
    }

    @Test
    fun uptimeParts() {
        assertEquals(Triple(1L, 15L, 39L), ServerFactView.uptimeParts(142_747))
    }
}
