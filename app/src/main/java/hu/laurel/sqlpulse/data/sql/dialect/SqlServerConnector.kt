package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SslMode
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Opens an SQL Server / Azure SQL connection with mssql-jdbc.
 *
 * TLS, from the app's [SslMode] (mssql-jdbc encrypts by default, so every branch says what it
 * wants rather than leaving it to the default):
 *  - DISABLED: `encrypt=false`. Only the login packet is encrypted then; the mode exists for a
 *    link something else already encrypts (the SSH tunnel). An Azure SQL server refuses it.
 *  - REQUIRED: `encrypt=true`, `trustServerCertificate=true` — encrypted, certificate unchecked.
 *  - VERIFY_CA / VERIFY_IDENTITY: `encrypt=true`, certificate validated. With a CA file the chain
 *    must lead to it ([PemTrustManager]); without one, to the phone's own trust store — which is
 *    what Azure SQL (a public CA) needs and what the MySQL modes do not offer. VERIFY_IDENTITY
 *    additionally names the host the certificate must match (`hostNameInCertificate`).
 *
 * Not offered: `encrypt=strict` (TDS 8) and Azure AD authentication (docs/tobb-motor-terv.md).
 */
class SqlServerConnector(private val config: JdbcConfig) : EngineConnector {

    override fun open(): Connection {
        val properties = Properties().apply {
            setProperty("user", config.user)
            config.password?.let { setProperty("password", it) }
            if (config.database.isNotBlank()) setProperty("databaseName", config.database)
            setProperty("applicationName", "SQLPulse")
            // loginTimeout is in seconds, socketTimeout in milliseconds.
            setProperty("loginTimeout", ((config.connectTimeoutMs + 999) / 1000).coerceAtLeast(1).toString())
            setProperty("socketTimeout", config.socketTimeoutMs.toString())
            setProperty("socketKeepAlive", "true")
            // §11: a dropped connection is never retried behind the user's back.
            setProperty("connectRetryCount", "0")
            tlsProperties(config).forEach { (key, value) -> setProperty(key, value) }
        }
        // The server and port go in the URL, the rest as properties, so a password or a database
        // name containing `;` or `}` cannot break the URL's own syntax.
        val url = "jdbc:sqlserver://${config.host}:${config.port}"
        // The driver class is named rather than discovered: DriverManager's service lookup is not
        // reliable on Android.
        return (driver.connect(url, properties)
            ?: throw SQLException("the driver did not accept $url")).apply {
            // mssql-jdbc ignores this (isReadOnly() stays false); the app's statement guard is
            // what protects a read-only connection. Kept so a future driver that honours it does.
            isReadOnly = config.readOnly
            autoCommit = true
        }
    }

    internal companion object {
        private val driver: Driver by lazy { com.microsoft.sqlserver.jdbc.SQLServerDriver() }

        /** The TLS part of the connection properties; separate so it can be unit-tested. */
        fun tlsProperties(config: JdbcConfig): Map<String, String> = when (config.sslMode) {
            SslMode.DISABLED -> mapOf("encrypt" to "false")

            SslMode.REQUIRED -> mapOf("encrypt" to "true", "trustServerCertificate" to "true")

            SslMode.VERIFY_CA, SslMode.VERIFY_IDENTITY -> buildMap {
                put("encrypt", "true")
                put("trustServerCertificate", "false")
                config.caCertificatePath?.takeIf { it.isNotBlank() }?.let { path ->
                    put("trustManagerClass", PemTrustManager::class.java.name)
                    put("trustManagerConstructorArg", path)
                }
                // Without this the driver compares the certificate with the server name it was
                // given, which for VERIFY_CA is exactly the check that mode does not make.
                if (config.sslMode == SslMode.VERIFY_IDENTITY) put("hostNameInCertificate", config.host)
            }
        }
    }
}

/**
 * Trusts the CA certificate(s) in one PEM file — the app's imported CA — and nothing else.
 *
 * mssql-jdbc takes a trust store as a Java keystore file, which the app does not have; it can also
 * load a trust manager by class name with one String argument, and that is this class (named in
 * proguard-rules.pro so R8 keeps the constructor). The chain check is the platform's own
 * [X509TrustManager] over an in-memory keystore holding just these certificates.
 */
class PemTrustManager(pemPath: String) : X509TrustManager {

    private val delegate: X509TrustManager = run {
        val certificates = FileInputStream(pemPath).use {
            CertificateFactory.getInstance("X.509").generateCertificates(it)
        }
        if (certificates.isEmpty()) throw CertificateException("no certificate in $pemPath")
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certificates.forEachIndexed { index, certificate -> store.setCertificateEntry("ca$index", certificate) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkServerTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
