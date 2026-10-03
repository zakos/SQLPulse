package hu.laurel.sqlpulse.integration.sqlserver

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SqlFailureKind
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.data.sql.dialect.SqlServerDialect
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The certificate-verifying modes, against a server that has a certificate signed by a CA of our
 * own. Needs a second server configured for it, so it is skipped unless both
 * `SQLPULSE_TEST_MSSQL_TLS_URL` (like the plain URL, with the password in the usual variable) and
 * `SQLPULSE_TEST_MSSQL_CA` (the CA as a PEM file) are set. The server certificate must name
 * `sqlserver.test` and the IP 127.0.0.1.
 */
class SqlServerTlsIntegrationTest {

    private lateinit var base: JdbcConfig
    private lateinit var caPath: String

    @Before
    fun configure() {
        val url = System.getenv("SQLPULSE_TEST_MSSQL_TLS_URL")?.takeIf { it.isNotBlank() }
        val ca = System.getenv("SQLPULSE_TEST_MSSQL_CA")?.takeIf { it.isNotBlank() }
        assumeTrue("no TLS test server", url != null && ca != null)
        caPath = ca!!
        val address = url!!.substringAfter("://").substringBefore('/')
        base = SqlServerTestServer.configOrNull()!!.copy(
            host = address.substringBefore(':'),
            port = address.substringAfter(':').toInt(),
            database = "master",
        )
    }

    private fun connects(config: JdbcConfig) {
        SqlSession(config).use { s ->
            s.use { c -> c.createStatement().use { it.executeQuery("SELECT 1").use { rows -> assertTrue(rows.next()) } } }
        }
    }

    private fun failure(config: JdbcConfig): SQLException {
        try {
            connects(config)
        } catch (e: SQLException) {
            return e
        }
        fail("the connection must be refused")
        error("unreachable")
    }

    @Test
    fun `encrypted without checking the certificate connects`() {
        connects(base.copy(sslMode = SslMode.REQUIRED))
    }

    @Test
    fun `the CA's file is enough to verify the chain`() {
        connects(base.copy(sslMode = SslMode.VERIFY_CA, caCertificatePath = caPath))
    }

    @Test
    fun `CA verification does not look at the host name`() {
        connects(base.copy(host = "localhost", sslMode = SslMode.VERIFY_CA, caCertificatePath = caPath))
    }

    @Test
    fun `identity verification passes when the certificate names the host`() {
        connects(base.copy(sslMode = SslMode.VERIFY_IDENTITY, caCertificatePath = caPath))
    }

    @Test
    fun `identity verification refuses a host the certificate does not name`() {
        val error = failure(base.copy(host = "localhost", sslMode = SslMode.VERIFY_IDENTITY, caCertificatePath = caPath))
        assertEquals(error.message, SqlFailureKind.TLS, SqlServerDialect.failureOf(error).kind)
    }

    @Test
    fun `verifying without the CA fails for a certificate no public CA signed`() {
        val error = failure(base.copy(sslMode = SslMode.VERIFY_CA, caCertificatePath = null))
        assertEquals(error.message, SqlFailureKind.TLS, SqlServerDialect.failureOf(error).kind)
    }

    @Test
    fun `without encryption a server that forces it still talks, and one that refuses would not`() {
        // ForceEncryption=1 on this server: the client's wish for none is overruled, not honoured.
        connects(base.copy(sslMode = SslMode.DISABLED))
    }
}
