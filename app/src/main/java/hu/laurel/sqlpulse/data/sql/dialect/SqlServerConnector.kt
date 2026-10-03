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
 *  - VERIFY_CA / VERIFY_IDENTITY: `encrypt=true`, certificate validated by [VerifyingTrustManager].
 *    With a CA file the chain must lead to it; without one, to the phone's own trust store — which
 *    is what Azure SQL (a public CA) needs and what the MySQL modes do not offer. VERIFY_IDENTITY
 *    additionally requires the certificate to name the host.
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
            // A server that forces encryption encrypts anyway, and the driver would then insist on
            // validating its certificate against the phone's store: for a mode that asked for no
            // checks the certificate is simply not looked at. Never worse than plaintext.
            SslMode.DISABLED -> mapOf("encrypt" to "false", "trustServerCertificate" to "true")

            SslMode.REQUIRED -> mapOf("encrypt" to "true", "trustServerCertificate" to "true")

            // The driver validates the host name only with its own trust manager: a custom one
            // replaces that check entirely (measured), so the manager does both jobs — the chain,
            // and, for VERIFY_IDENTITY only, the name.
            SslMode.VERIFY_CA, SslMode.VERIFY_IDENTITY -> mapOf(
                "encrypt" to "true",
                "trustServerCertificate" to "false",
                "trustManagerClass" to VerifyingTrustManager::class.java.name,
                "trustManagerConstructorArg" to VerifyingTrustManager.argument(
                    caPath = config.caCertificatePath,
                    host = config.host.takeIf { config.sslMode == SslMode.VERIFY_IDENTITY },
                ),
            )
        }
    }
}

/**
 * The trust manager the driver loads by name for the verifying TLS modes.
 *
 * mssql-jdbc takes a trust store as a Java keystore file, which the app does not have, but it can
 * load a trust manager by class name with one String argument ([argument] builds it; the class is
 * named in proguard-rules.pro so R8 keeps the constructor). The chain is checked by the platform's
 * own [X509TrustManager] over the CA certificates of the imported PEM file — or over the phone's
 * trust store when there is no file, which is what a server with a public certificate (Azure SQL)
 * needs. When a host is given, the certificate must also name it ([HostNames]).
 */
class VerifyingTrustManager(argument: String) : X509TrustManager {

    private val host: String?
    private val delegate: X509TrustManager

    init {
        val fields = argument.lines().associate { it.substringBefore('=') to it.substringAfter('=', "") }
        host = fields["host"]?.takeIf { it.isNotBlank() }
        val caPath = fields["ca"]?.takeIf { it.isNotBlank() }
        val store = caPath?.let { path ->
            val certificates = FileInputStream(path).use {
                CertificateFactory.getInstance("X.509").generateCertificates(it)
            }
            if (certificates.isEmpty()) throw CertificateException("no certificate in $path")
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certificates.forEachIndexed { index, certificate -> setCertificateEntry("ca$index", certificate) }
            }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        delegate = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkServerTrusted(chain, authType)
        val name = host ?: return
        if (!HostNames.matches(chain.first(), name)) {
            throw CertificateException("the certificate does not name the host \"$name\"")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    companion object {
        /** The one String the driver hands to the constructor: `ca=<path>` and `host=<name>` lines. */
        fun argument(caPath: String?, host: String?): String =
            listOfNotNull(
                caPath?.takeIf { it.isNotBlank() }?.let { "ca=$it" },
                host?.takeIf { it.isNotBlank() }?.let { "host=$it" },
            ).joinToString("\n")
    }
}

/**
 * Whether a certificate names a host: subject alternative names first (DNS names with a
 * single-label leading `*` wildcard, and IP addresses), the subject's common name only when the
 * certificate has no DNS names at all — the order RFC 6125 gives.
 */
object HostNames {

    fun matches(certificate: X509Certificate, host: String): Boolean {
        val alternatives = certificate.subjectAlternativeNames.orEmpty()
        val dnsNames = alternatives.filter { it[0] == 2 }.map { it[1].toString() }
        val ipNames = alternatives.filter { it[0] == 7 }.map { it[1].toString() }
        if (isIpAddress(host)) return ipNames.any { it.equals(host, ignoreCase = true) }
        if (dnsNames.isNotEmpty()) return dnsNames.any { matchesName(it, host) }
        val commonName = Regex("CN=([^,]+)").find(certificate.subjectX500Principal.getName("RFC2253"))
            ?.groupValues?.get(1)
        return commonName != null && matchesName(commonName, host)
    }

    /** A pattern like `*.database.windows.net` matches one label in place of the star, no more. */
    fun matchesName(pattern: String, host: String): Boolean {
        val p = pattern.lowercase().trimEnd('.')
        val h = host.lowercase().trimEnd('.')
        if (!p.startsWith("*.")) return p == h
        val suffix = p.substring(1) // ".database.windows.net"
        return h.endsWith(suffix) && h.length > suffix.length &&
            !h.substring(0, h.length - suffix.length).contains('.')
    }

    private fun isIpAddress(host: String): Boolean =
        host.contains(':') || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
}
