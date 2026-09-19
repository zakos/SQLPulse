package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import java.sql.Connection
import java.sql.Driver
import java.util.Properties

/**
 * The MySQL server these tests talk to, if there is one.
 *
 * Everything here is a plain JVM test: both drivers are ordinary jars, so a unit test can open a
 * real connection — no emulator, no Robolectric. What it cannot do is require a server, because
 * the ordinary `testDebugUnitTest` run has none. So the address comes from the environment, and
 * with [URL_VARIABLE] unset every test in this package is skipped by `Assume` before it connects.
 *
 * `SQLPULSE_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/sqlpulse_test ./gradlew testDebugUnitTest`
 * is all it takes to run them against a server of your own.
 */
object TestServer {

    const val URL_VARIABLE = "SQLPULSE_TEST_MYSQL_URL"

    /** The user the legacy driver is tried as; see [legacyDriverConfigured]. */
    private const val LEGACY_VARIABLE = "SQLPULSE_TEST_MYSQL_LEGACY"

    /**
     * The configuration, or null when no server was named — which is the normal case and means
     * "skip", not "fail".
     */
    fun configOrNull(): JdbcConfig? {
        val url = System.getenv(URL_VARIABLE)?.takeIf { it.isNotBlank() } ?: return null
        // jdbc:mysql://host:port/database — the scheme is ignored, because which driver speaks to
        // the server is the session's decision, not the URL's.
        val address = url.substringAfter("://", "").takeIf { it.isNotBlank() } ?: return null
        val hostAndPort = address.substringBefore('/')
        val database = address.substringAfter('/', "").substringBefore('?')
        return JdbcConfig(
            host = hostAndPort.substringBefore(':'),
            port = hostAndPort.substringAfter(':', "3306").toIntOrNull() ?: 3306,
            database = database.ifBlank { "test" },
            user = System.getenv("SQLPULSE_TEST_MYSQL_USER")?.takeIf { it.isNotBlank() } ?: "root",
            password = System.getenv("SQLPULSE_TEST_MYSQL_PASSWORD"),
            // These tests create and drop their own tables, so the flag the app usually sets is
            // deliberately off; what it guards is covered by the unit tests around SqlGuards.
            readOnly = false,
            // The same shape as the app's ordinary connection: a loopback address that something
            // else has already secured. Without this the session refuses to hand MySQL 8 a
            // password over an unencrypted link — correctly, which is what the app does in front
            // of a user, and what these tests would otherwise all fail on.
            tunnelled = true,
        )
    }

    /**
     * Whether the old driver is expected to be able to log in.
     *
     * MySQL 8 authenticates with caching_sha2_password by default, which a 2019 driver has never
     * heard of; the workflow creates a mysql_native_password user for it and sets this variable.
     */
    fun legacyDriverConfigured(): Boolean = System.getenv(LEGACY_VARIABLE)?.lowercase() == "true"

    /**
     * Opens a connection with one named driver, bypassing the session's own choice — used to show
     * that the driver the fallback falls back to really can reach the same server.
     */
    fun connectWith(driver: Driver, scheme: String, config: JdbcConfig): Connection {
        val properties = Properties().apply {
            setProperty("user", config.user)
            config.password?.let { setProperty("password", it) }
            setProperty("connectTimeout", config.connectTimeoutMs.toString())
            if (scheme == "mysql") {
                setProperty("useUnicode", "true")
                setProperty("characterEncoding", "UTF-8")
            }
        }
        val url = "jdbc:$scheme://${config.host}:${config.port}/${config.database}"
        return driver.connect(url, properties) ?: error("the driver did not accept $url")
    }
}
