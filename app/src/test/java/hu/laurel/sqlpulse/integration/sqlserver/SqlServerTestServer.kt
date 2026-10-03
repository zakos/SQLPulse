package hu.laurel.sqlpulse.integration.sqlserver

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import java.sql.Connection

/**
 * The SQL Server these tests talk to, if there is one — the MySQL tests' `TestServer`, for T-SQL.
 *
 * With [URL_VARIABLE] unset every test in this package is skipped by `Assume` before it connects,
 * which is the normal state of the plain `testDebugUnitTest` run.
 *
 * `SQLPULSE_TEST_MSSQL_URL=jdbc:sqlserver://127.0.0.1:1433/sqlpulse_test`, with
 * `SQLPULSE_TEST_MSSQL_USER` (default `sa`) and `SQLPULSE_TEST_MSSQL_PASSWORD`. The database is
 * created when it does not exist, which needs the login to be allowed to (sa is).
 * `SQLPULSE_TEST_MSSQL_TLS=disabled` turns the encryption off; the default is `REQUIRED`
 * (encrypted, certificate not checked: the container's certificate is self-signed).
 */
object SqlServerTestServer {

    const val URL_VARIABLE = "SQLPULSE_TEST_MSSQL_URL"

    fun configOrNull(): JdbcConfig? {
        val url = System.getenv(URL_VARIABLE)?.takeIf { it.isNotBlank() } ?: return null
        val address = url.substringAfter("://", "").takeIf { it.isNotBlank() } ?: return null
        val hostAndPort = address.substringBefore('/').substringBefore(';')
        val database = address.substringAfter('/', "").substringBefore('?').substringBefore(';')
        val tls = System.getenv("SQLPULSE_TEST_MSSQL_TLS")?.uppercase()
        return JdbcConfig(
            host = hostAndPort.substringBefore(':'),
            port = hostAndPort.substringAfter(':', "1433").toIntOrNull() ?: 1433,
            database = database.ifBlank { "sqlpulse_test" },
            user = System.getenv("SQLPULSE_TEST_MSSQL_USER")?.takeIf { it.isNotBlank() } ?: "sa",
            password = System.getenv("SQLPULSE_TEST_MSSQL_PASSWORD"),
            // These tests create and drop their own objects, so the flag is off; the driver
            // ignores it anyway (see SqlServerDialect).
            readOnly = false,
            sslMode = tls?.let { SslMode.fromName(it) } ?: SslMode.REQUIRED,
            engine = DatabaseEngine.SQLSERVER,
        )
    }

    /** Creates the test database when it is missing, through `master`. */
    fun ensureDatabase(config: JdbcConfig) {
        SqlSession(config.copy(database = "master")).use { session ->
            session.use { connection ->
                val name = config.database.replace("]", "]]")
                connection.createStatement().use {
                    it.execute("IF DB_ID(N'${config.database.replace("'", "''")}') IS NULL CREATE DATABASE [$name]")
                }
            }
        }
    }

    fun open(config: JdbcConfig): SqlSession {
        ensureDatabase(config)
        return SqlSession(config)
    }
}

/** Runs one statement, for fixtures. */
fun Connection.execute(sql: String) {
    createStatement().use { it.execute(sql) }
}
