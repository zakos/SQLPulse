package hu.laurel.sqlpulse.data.sql

/**
 * How the JDBC connection is protected.
 *
 * MySQL's own list has five modes; this offers four. There is no PREFERRED, because MariaDB
 * Connector/J has no equivalent: its modes either require TLS or disable it, and a "use TLS if the
 * server happens to offer it" setting that silently falls back to plaintext is exactly the kind of
 * thing that looks safe and is not. Choosing between REQUIRED and DISABLED is the same decision,
 * made visibly.
 */
enum class SslMode {
    /** No TLS. Only sensible inside a tunnel, where the tunnel is the encryption. */
    DISABLED,

    /** TLS required, certificate not checked: protects against passive listening only. */
    REQUIRED,

    /** TLS required and the server certificate must be signed by the configured CA. */
    VERIFY_CA,

    /** As VERIFY_CA, and the certificate must also match the host being connected to. */
    VERIFY_IDENTITY,
    ;

    val verifiesCertificate: Boolean get() = this == VERIFY_CA || this == VERIFY_IDENTITY

    companion object {
        /**
         * The default for a new connection: inside a tunnel the tunnel already encrypts, and a
         * direct connection should be verified rather than merely encrypted.
         */
        fun defaultFor(tunnelled: Boolean): SslMode = if (tunnelled) DISABLED else VERIFY_IDENTITY

        fun fromName(name: String?): SslMode =
            entries.firstOrNull { it.name == name } ?: DISABLED
    }
}

/** Maps a mode onto MariaDB Connector/J connection properties. */
object SslProperties {

    /**
     * @param caCertificatePath a PEM file with the CA that signed the server certificate, needed
     *   by the verifying modes. Android's own trust store is not used: a database server's
     *   certificate is usually signed by an internal CA that no public store knows.
     */
    fun propertiesFor(mode: SslMode, caCertificatePath: String?): Map<String, String> = when (mode) {
        SslMode.DISABLED -> mapOf("sslMode" to "disable")
        SslMode.REQUIRED -> mapOf("sslMode" to "trust")
        SslMode.VERIFY_CA -> verifying("verify-ca", caCertificatePath)
        SslMode.VERIFY_IDENTITY -> verifying("verify-full", caCertificatePath)
    }

    /** True when the mode needs a CA file that has not been configured. */
    fun missingCertificate(mode: SslMode, caCertificatePath: String?): Boolean =
        mode.verifiesCertificate && caCertificatePath.isNullOrBlank()

    private fun verifying(driverMode: String, caCertificatePath: String?): Map<String, String> {
        require(!caCertificatePath.isNullOrBlank()) {
            "$driverMode needs a CA certificate"
        }
        return mapOf("sslMode" to driverMode, "serverSslCert" to caCertificatePath)
    }
}
