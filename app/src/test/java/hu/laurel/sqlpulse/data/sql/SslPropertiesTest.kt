package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SslPropertiesTest {

    @Test
    fun `each mode maps onto the driver's own vocabulary`() {
        assertEquals(
            mapOf("useSsl" to "false"),
            SslProperties.propertiesFor(SslMode.DISABLED, null),
        )
        assertEquals(
            mapOf("useSsl" to "true", "trustServerCertificate" to "true"),
            SslProperties.propertiesFor(SslMode.REQUIRED, null),
        )
        assertEquals(
            mapOf(
                "useSsl" to "true",
                "trustServerCertificate" to "false",
                "serverSslCert" to "/ca/db.pem",
                "disableSslHostnameVerification" to "true",
            ),
            SslProperties.propertiesFor(SslMode.VERIFY_CA, "/ca/db.pem"),
        )
        assertEquals(
            mapOf(
                "useSsl" to "true",
                "trustServerCertificate" to "false",
                "serverSslCert" to "/ca/db.pem",
                "disableSslHostnameVerification" to "false",
            ),
            SslProperties.propertiesFor(SslMode.VERIFY_IDENTITY, "/ca/db.pem"),
        )
    }

    @Test
    fun `a certificate left over from another mode is not passed on`() {
        // Switching back to REQUIRED must actually stop verifying, not verify quietly.
        assertNull(SslProperties.propertiesFor(SslMode.REQUIRED, "/ca/db.pem")["serverSslCert"])
    }

    @Test
    fun `only the identity check separates the two verifying modes`() {
        val ca = SslProperties.propertiesFor(SslMode.VERIFY_CA, "/ca/db.pem")
        val identity = SslProperties.propertiesFor(SslMode.VERIFY_IDENTITY, "/ca/db.pem")
        assertEquals(
            ca - "disableSslHostnameVerification",
            identity - "disableSslHostnameVerification",
        )
        assertEquals("true", ca["disableSslHostnameVerification"])
        assertEquals("false", identity["disableSslHostnameVerification"])
    }

    @Test
    fun `verifying without a certificate is caught before the connection is attempted`() {
        assertTrue(SslProperties.missingCertificate(SslMode.VERIFY_CA, null))
        assertTrue(SslProperties.missingCertificate(SslMode.VERIFY_IDENTITY, "  "))
        assertFalse(SslProperties.missingCertificate(SslMode.VERIFY_CA, "/ca/db.pem"))
        assertFalse(SslProperties.missingCertificate(SslMode.REQUIRED, null))
        assertFalse(SslProperties.missingCertificate(SslMode.DISABLED, null))
    }

    @Test
    fun `building a verifying configuration without a certificate fails loudly`() {
        val error = runCatching { SslProperties.propertiesFor(SslMode.VERIFY_CA, null) }
            .exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `a tunnelled connection defaults to plain, a direct one to full verification`() {
        assertEquals(SslMode.DISABLED, SslMode.defaultFor(tunnelled = true))
        assertEquals(SslMode.VERIFY_IDENTITY, SslMode.defaultFor(tunnelled = false))
    }

    @Test
    fun `an unknown stored mode falls back to disabled rather than crashing`() {
        assertEquals(SslMode.VERIFY_CA, SslMode.fromName("VERIFY_CA"))
        assertEquals(SslMode.DISABLED, SslMode.fromName("PREFERRED"))
        assertEquals(SslMode.DISABLED, SslMode.fromName(null))
    }
}
