package hu.laurel.sqlpulse.integration.postgres

import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.PostgresDialect
import hu.laurel.sqlpulse.integration.IntegrationSessions
import java.sql.Connection
import org.junit.Assume.assumeTrue

/**
 * The PostgreSQL server these tests talk to, if there is one — the same arrangement as
 * [hu.laurel.sqlpulse.integration.TestServer]: with the variable unset every test in this package
 * is skipped by `Assume`, so the ordinary unit-test run is unaffected.
 *
 * `SQLPULSE_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/sqlpulse_test`, plus
 * `SQLPULSE_TEST_POSTGRES_USER` (default `postgres`) and `SQLPULSE_TEST_POSTGRES_PASSWORD`.
 * Every test works in a schema of its own (see [PostgresFixture]) and drops it afterwards, so the
 * database itself is left as it was found.
 */
object PostgresTestServer {

    const val URL_VARIABLE = "SQLPULSE_TEST_POSTGRES_URL"

    fun configOrNull(): JdbcConfig? {
        val url = System.getenv(URL_VARIABLE)?.takeIf { it.isNotBlank() } ?: return null
        val address = url.substringAfter("://", "").takeIf { it.isNotBlank() } ?: return null
        val hostAndPort = address.substringBefore('/')
        return JdbcConfig(
            host = hostAndPort.substringBefore(':'),
            port = hostAndPort.substringAfter(':', "5432").toIntOrNull() ?: 5432,
            database = address.substringAfter('/', "").substringBefore('?').ifBlank { "postgres" },
            user = System.getenv("SQLPULSE_TEST_POSTGRES_USER")?.takeIf { it.isNotBlank() } ?: "postgres",
            password = System.getenv("SQLPULSE_TEST_POSTGRES_PASSWORD"),
            // These tests create and drop their own objects.
            readOnly = false,
            engine = DatabaseEngine.POSTGRESQL,
        )
    }

    fun requireConfig(): JdbcConfig {
        val found = configOrNull()
        assumeTrue("no $URL_VARIABLE, so there is no PostgreSQL server to talk to", found != null)
        return found!!
    }
}

/**
 * A session on the test server plus a schema of its own to work in, as the app's repositories see
 * it (a manager whose current namespace is that schema).
 */
class PostgresFixture(val config: JdbcConfig = PostgresTestServer.requireConfig()) : AutoCloseable {

    val schema = "sqlpulse_it_${System.nanoTime()}"
    val session = SqlSession(config)
    val manager = IntegrationSessions.manager(session, schema, dialect = PostgresDialect)

    init {
        session.use { it.execute("CREATE SCHEMA ${q(schema)}") }
    }

    fun q(name: String) = PostgresDialect.quoteIdentifier(name)

    /** `schema.table`, both quoted. */
    fun t(table: String) = PostgresDialect.qualify(schema, table)

    fun <T> use(block: (Connection) -> T): T = session.use(block)

    fun execute(vararg statements: String) = session.use { connection -> statements.forEach { connection.execute(it) } }

    /** A single value of the first row of [sql], as text. */
    fun scalar(sql: String): String? = session.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    override fun close() {
        runCatching { session.use { it.execute("DROP SCHEMA IF EXISTS ${q(schema)} CASCADE") } }
        session.close()
    }
}

fun Connection.execute(sql: String) {
    createStatement().use { it.execute(sql) }
}
