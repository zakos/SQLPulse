package hu.laurel.sqlpulse.integration.postgres

import hu.laurel.sqlpulse.data.sql.SqlFailureKind
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.data.sql.dialect.PostgresDialect
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * TLS through the connector's `sslmode` / `sslrootcert` mapping, against a server that has TLS on.
 *
 * Needs `SQLPULSE_TEST_POSTGRES_SSL_CA` (a PEM file with the CA that signed the server's
 * certificate, which must be valid for the host in the URL) and, for the wrong-CA case,
 * `SQLPULSE_TEST_POSTGRES_SSL_OTHER_CA` (any unrelated CA). Without them every test here is skipped.
 */
class PostgresTlsIntegrationTest {

    private lateinit var base: hu.laurel.sqlpulse.data.sql.JdbcConfig
    private lateinit var ca: String

    @Before
    fun setUp() {
        base = PostgresTestServer.requireConfig()
        val path = System.getenv("SQLPULSE_TEST_POSTGRES_SSL_CA")?.takeIf { it.isNotBlank() }
        assumeTrue("no SQLPULSE_TEST_POSTGRES_SSL_CA, so there is no TLS server to talk to", path != null)
        ca = path!!
    }

    private fun usesTls(mode: SslMode, caPath: String?): Boolean {
        val session = SqlSession(base.copy(sslMode = mode, caCertificatePath = caPath))
        try {
            return session.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")
                        .use { rows -> rows.next() && rows.getBoolean(1) }
                }
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `disabled stays in the clear and require encrypts without a CA`() {
        assertEquals(false, usesTls(SslMode.DISABLED, null))
        assertEquals(true, usesTls(SslMode.REQUIRED, null))
    }

    @Test
    fun `verifying modes accept the server certificate against the CA file`() {
        assertEquals(true, usesTls(SslMode.VERIFY_CA, ca))
        // The test certificate carries 127.0.0.1 as a subject alternative name.
        assertEquals(true, usesTls(SslMode.VERIFY_IDENTITY, ca))
    }

    @Test
    fun `a certificate the CA did not sign is refused and reported as a TLS problem`() {
        val other = System.getenv("SQLPULSE_TEST_POSTGRES_SSL_OTHER_CA")?.takeIf { it.isNotBlank() }
        assumeTrue("no SQLPULSE_TEST_POSTGRES_SSL_OTHER_CA", other != null)
        for (mode in listOf(SslMode.VERIFY_CA, SslMode.VERIFY_IDENTITY)) {
            val session = SqlSession(base.copy(sslMode = mode, caCertificatePath = other))
            try {
                session.use { }
                fail("$mode accepted a certificate from an unknown CA")
            } catch (e: SQLException) {
                assertEquals(mode.name, SqlFailureKind.TLS, PostgresDialect.failureOf(e).kind)
            } finally {
                session.close()
            }
        }
    }
}
