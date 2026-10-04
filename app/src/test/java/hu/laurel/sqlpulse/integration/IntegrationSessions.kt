package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialect
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

/**
 * A [SqlSessionManager] whose JDBC half is real and whose Android half is not.
 *
 * The app's repositories (schema, search, row editing, CSV import) only ever reach the server
 * through `withConnection`, so handing them a manager that runs the block on a pooled connection
 * of a real [SqlSession] exercises their SQL against a real server without a tunnel, Room or
 * Hilt. What the manager adds on top — reconnecting, the open transaction — is covered elsewhere.
 */
object IntegrationSessions {

    /**
     * @param environment the name of a `ConnectionEnvironment`; matters only to the write gate.
     * @param connectionId the id the write unlock is keyed on.
     * @param dialect the engine the repositories should speak; another engine's integration tests
     *   pass theirs, with a session opened for that engine.
     */
    fun manager(
        session: SqlSession,
        database: String,
        environment: String = "DEVELOPMENT",
        readOnly: Boolean = false,
        connectionId: Long = 7,
        dialect: SqlDialect = MySqlDialect,
    ): SqlSessionManager {
        val manager = mockk<SqlSessionManager>()
        coEvery { manager.withConnection(any<(Connection) -> Any?>()) } coAnswers {
            val block = firstArg<(Connection) -> Any?>()
            session.use { connection ->
                // The real manager points the connection at the session's selected database.
                dialect.useNamespace(connection, database)
                block(connection)
            }
        }
        every { manager.queryTimeoutSeconds() } returns 30
        every { manager.dialect() } returns dialect
        every { manager.currentConnection() } returns ConnectionEntity(
            id = connectionId, name = "it", color = "Amber", sshHost = "s", sshUser = "u", sshKeyId = null,
            dbHost = "h", database = database, dbUser = "u", readOnly = readOnly, environment = environment,
            engine = dialect.engine.name,
        )
        return manager
    }
}
