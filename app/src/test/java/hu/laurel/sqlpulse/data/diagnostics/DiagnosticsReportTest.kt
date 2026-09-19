package hu.laurel.sqlpulse.data.diagnostics

import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.sql.JdbcDriverKind
import hu.laurel.sqlpulse.data.sql.SqlFailure
import hu.laurel.sqlpulse.data.sql.SqlFailureKind
import hu.laurel.sqlpulse.data.sql.SslMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {

    /**
     * Every secret a connection can carry, each one a string that appears nowhere else.
     *
     * The test below hands all of them to the builder and looks for them in the output. Marked
     * strings rather than realistic ones so that a hit is unambiguous: "db.example.com" could
     * plausibly be a substring of something innocent, `HOSTNAME-9f3a` could not.
     */
    private val secrets = listOf(
        "HOSTNAME-9f3a",
        "JUMPHOST-4c81",
        "SSHHOST-2b77",
        "DBUSER-7e19",
        "SSHUSER-1d05",
        "DBPASSWORD-6a2c",
        "SSHPASSWORD-8f40",
        "DATABASE-3c66",
        "CONNECTIONNAME-5b12",
        "FINGERPRINT-0e93",
        "PRIVATEKEY-4477",
        "CAPEM-9931",
        "CAPATH-2205",
        "QUERYTEXT-7d58",
        "SERVERMESSAGE-1a64",
        "CURRENTUSER-3f27",
        "SERVERHOSTNAME-8c15",
    )

    private val connectionCarryingSecrets = ConnectionFacts(
        useSshTunnel = true,
        hasJumpHost = true,
        sshAuthMethod = "KEY",
        sslMode = SslMode.VERIFY_IDENTITY,
        hasCaCertificate = true,
        readOnly = false,
        environment = ConnectionEnvironment.PRODUCTION,
        connectTimeoutSeconds = 10,
        queryTimeoutSeconds = 30,
        driver = JdbcDriverKind.MODERN,
        name = "CONNECTIONNAME-5b12",
        dbHost = "HOSTNAME-9f3a",
        dbPort = 3307,
        database = "DATABASE-3c66",
        dbUser = "DBUSER-7e19",
        dbPassword = "DBPASSWORD-6a2c",
        sshHost = "SSHHOST-2b77",
        sshUser = "SSHUSER-1d05",
        sshPassword = "SSHPASSWORD-8f40",
        jumpHost = "JUMPHOST-4c81",
        sshKeyFingerprint = "SHA256:FINGERPRINT-0e93",
        privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nPRIVATEKEY-4477\n-----END-----",
        caCertificatePath = "/data/ca/CAPATH-2205.pem",
        caCertificatePem = "-----BEGIN CERTIFICATE-----\nCAPEM-9931\n-----END CERTIFICATE-----",
    )

    /** What `ServerRepository.overview()` really returns, hostname and account included. */
    private val serverCarryingSecrets = ServerFacts(
        listOf(
            "version" to "8.0.36-0ubuntu0.22.04.1",
            "hostname" to "SERVERHOSTNAME-8c15",
            "build" to "MySQL Community Server (GPL)",
            "current_user_name" to "CURRENTUSER-3f27",
            "max_connections" to "151",
            "read_only" to "0",
            "time_zone" to "SYSTEM",
            "charset" to "utf8mb4",
            "Uptime" to "820391",
            "Threads_connected" to "14",
            "Threads_running" to "2",
            "Ssl_version" to "TLSv1.3",
            "Ssl_cipher" to "TLS_AES_256_GCM_SHA384",
        ),
    )

    private val errorCarryingSecrets = LastErrorFacts(
        failure = SqlFailure(
            kind = SqlFailureKind.PRIVILEGE,
            errorCode = 1142,
            sqlState = "42000",
            serverMessage = "SELECT command denied to user 'DBUSER-7e19'@'HOSTNAME-9f3a' " +
                "for table 'DATABASE-3c66.wages' — SERVERMESSAGE-1a64",
        ),
        atMs = 1_000_000L,
        sql = "SELECT QUERYTEXT-7d58 FROM wages",
    )

    private val fullSource = DiagnosticsSource(
        app = AppFacts(
            versionName = "2.0.1",
            versionCode = 201,
            androidRelease = "14",
            androidSdk = 34,
            manufacturer = "Google",
            model = "Pixel 8",
        ),
        connection = connectionCarryingSecrets,
        server = serverCarryingSecrets,
        lastError = errorCarryingSecrets,
        nowMs = 1_090_000L,
    )

    @Test
    fun `a report built from a connection carrying secrets contains none of them`() {
        val text = Diagnostics.report(fullSource).asText()

        secrets.forEach { secret ->
            assertTrue(
                "the report contains $secret:\n$text",
                !text.contains(secret, ignoreCase = true),
            )
        }
        // Not only the marked words: the shapes they came in must be gone too.
        listOf("BEGIN OPENSSH", "BEGIN CERTIFICATE", "SELECT", "denied", "3307", "SHA256:")
            .forEach { fragment ->
                assertTrue(
                    "the report contains '$fragment':\n$text",
                    !text.contains(fragment),
                )
            }
    }

    /**
     * The guarantee stated as an invariant over the keys themselves, so it holds for keys added
     * later as well: nothing is named after a password or a private key at all, and a key that
     * mentions a host or an account may only ever carry a flag or a count — `connection.jump_host`
     * says whether there was one, never which.
     */
    @Test
    fun `no key can carry a host, an account or a secret`() {
        val keys = DiagnosticsField.entries.map { it.key } + ServerVariable.entries.map { it.key }

        keys.forEach { key ->
            listOf("password", "passphrase", "secret", "private", "sql_text", "query_text")
                .forEach { word -> assertTrue("key '$key' mentions '$word'", !key.contains(word)) }
        }

        val report = Diagnostics.report(fullSource)
        report.lines
            .filter { line ->
                listOf("host", "user", "account", "address", "database")
                    .any { line.key.key.contains(it) }
            }
            .forEach { line ->
                assertTrue(
                    "'${line.key.key}' carries a value that could be a name: ${line.value}",
                    line.value is DiagnosticsValue.Flag || line.value is DiagnosticsValue.Count,
                )
            }
    }

    @Test
    fun `the facts a bug report needs are all there`() {
        val report = Diagnostics.report(fullSource)
        val byKey = report.lines.associate { it.key.key to it.value }

        assertEquals(DiagnosticsValue.Token.of("2.0.1"), byKey["app.version"])
        assertEquals(DiagnosticsValue.Count(201), byKey["app.build"])
        assertEquals(DiagnosticsValue.Token.of("14"), byKey["android.release"])
        assertEquals(DiagnosticsValue.Count(34), byKey["android.sdk"])
        assertEquals(DiagnosticsValue.Token.of("Google Pixel 8"), byKey["device.model"])
        assertEquals(DiagnosticsValue.Choice("MODERN"), byKey["connection.driver"])
        assertEquals(DiagnosticsValue.Choice("VERIFY_IDENTITY"), byKey["tls.mode"])
        assertEquals(DiagnosticsValue.Flag(true), byKey["connection.tunnelled"])
        assertEquals(DiagnosticsValue.Flag(true), byKey["connection.jump_host"])
        assertEquals(DiagnosticsValue.Flag(false), byKey["connection.read_only"])
        assertEquals(DiagnosticsValue.Choice("PRODUCTION"), byKey["connection.environment"])
        assertEquals(DiagnosticsValue.Token.of("TLSv1.3"), byKey["tls.negotiated_version"])
        assertEquals(
            DiagnosticsValue.Token.of("TLS_AES_256_GCM_SHA384"),
            byKey["tls.negotiated_cipher"],
        )
        assertEquals(DiagnosticsValue.Token.of("8.0.36-0ubuntu0.22.04.1"), byKey["server.version"])
        assertEquals(DiagnosticsValue.Token.of("151"), byKey["server.max_connections"])
        assertEquals(DiagnosticsValue.Choice("PRIVILEGE"), byKey["error.kind"])
        assertEquals(DiagnosticsValue.Count(1142), byKey["error.code"])
        assertEquals(DiagnosticsValue.Token.of("42000"), byKey["error.sqlstate"])
        assertEquals(DiagnosticsValue.Count(90), byKey["error.age_s"])
    }

    @Test
    fun `the server variables that are not on the list never appear`() {
        val report = Diagnostics.report(fullSource)
        val serverKeys = report.linesIn(DiagnosticsSection.SERVER).map { it.key.key }

        assertTrue(serverKeys.isNotEmpty())
        assertTrue(serverKeys.none { it.contains("hostname") })
        assertTrue(serverKeys.none { it.contains("current_user") })
        // Thirteen variables in, eleven recognised: the hostname and the account dropped,
        // and nothing invented — the version, the build and both TLS lines are all present.
        assertEquals(11, serverKeys.size)
    }

    @Test
    fun `an unknown value reads as unknown rather than as empty`() {
        val text = Diagnostics.report(
            DiagnosticsSource(app = AppFacts(versionName = null, versionCode = 0)),
        ).asText()

        assertTrue(text.contains("app.version: -"))
        assertTrue(text.startsWith(DiagnosticsReport.HEADER))
        assertTrue(text.contains(DiagnosticsReport.NOTE))
        // No connection, no server, no error: those sections are left out entirely.
        assertTrue(!text.contains("[connection]"))
        assertTrue(!text.contains("[server]"))
        assertTrue(!text.contains("[error]"))
    }

    @Test
    fun `a token refuses anything that is not a short technical string`() {
        assertEquals(DiagnosticsValue.Absent, DiagnosticsValue.Token.of(null))
        assertEquals(DiagnosticsValue.Absent, DiagnosticsValue.Token.of("  "))
        // An account, an address, a port, a statement, a line break, and something too long.
        assertEquals(DiagnosticsValue.Absent, DiagnosticsValue.Token.of("root@10.0.0.4"))
        assertEquals(DiagnosticsValue.Absent, DiagnosticsValue.Token.of("db.example.com:3306"))
        assertEquals(DiagnosticsValue.Absent, DiagnosticsValue.Token.of("SELECT 1; DROP"))
        assertEquals(DiagnosticsValue.Absent, DiagnosticsValue.Token.of("two\nlines"))
        assertEquals(DiagnosticsValue.Absent, DiagnosticsValue.Token.of("x".repeat(65)))
        // And accepts what it is for.
        assertTrue(DiagnosticsValue.Token.of("8.0.36-0ubuntu0.22.04.1") is DiagnosticsValue.Token)
        assertTrue(DiagnosticsValue.Token.of("TLS_AES_256_GCM_SHA384") is DiagnosticsValue.Token)
        assertTrue(DiagnosticsValue.Token.of("MySQL Community Server (GPL)") is DiagnosticsValue.Token)
    }

    @Test
    fun `a sqlstate that is not a sqlstate is dropped`() {
        val odd = fullSource.copy(
            lastError = errorCarryingSecrets.copy(
                failure = errorCarryingSecrets.failure.copy(sqlState = "not a state at all"),
            ),
        )

        val byKey = Diagnostics.report(odd).lines.associate { it.key.key to it.value }
        assertEquals(DiagnosticsValue.Absent, byKey["error.sqlstate"])
        // The classification survives: it is the part that says what to do.
        assertEquals(DiagnosticsValue.Choice("PRIVILEGE"), byKey["error.kind"])
    }

    @Test
    fun `a clock that went backwards does not produce a negative age`() {
        val backwards = fullSource.copy(nowMs = 0)

        val byKey = Diagnostics.report(backwards).lines.associate { it.key.key to it.value }
        assertEquals(DiagnosticsValue.Count(0), byKey["error.age_s"])
    }

    @Test
    fun `an error nobody timed reports no age rather than an invented one`() {
        val untimed = fullSource.copy(lastError = errorCarryingSecrets.copy(atMs = null))

        val byKey = Diagnostics.report(untimed).lines.associate { it.key.key to it.value }
        assertEquals(DiagnosticsValue.Absent, byKey["error.age_s"])
        assertEquals(DiagnosticsValue.Count(1142), byKey["error.code"])
    }

    @Test
    fun `what is shown is what is copied`() {
        val report = Diagnostics.report(fullSource)

        // asText is the only rendering there is, so the screen cannot drift from the clipboard.
        assertEquals(report.asText(), Diagnostics.report(fullSource).asText())
        assertEquals(
            report.lines.size,
            report.asText().lines().count { it.contains(": ") },
        )
    }
}
