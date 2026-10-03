package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.sql.JdbcDriverKind
import java.sql.Connection

/**
 * Opens one JDBC connection for a session's pool (SqlSession).
 *
 * One instance per session, made by [SqlDialect.connector] from the session's JdbcConfig, so a
 * connector may remember what it learned on the first connection — MySQL's does: which of its
 * two drivers the server speaks. The pool, its size, validation and closing stay in SqlSession
 * and are the same for every engine.
 *
 * Whatever comes back must already honour JdbcConfig.readOnly as far as the driver can (MySQL:
 * `setReadOnly`; SQL Server's driver ignores that flag, see docs/tobb-motor-terv.md) and be in
 * auto-commit mode: a manual transaction is the session manager's to start.
 */
interface EngineConnector {

    fun open(): Connection

    /**
     * Which MySQL driver the session settled on, for the diagnostics report; null before the
     * first connection and for every other engine.
     */
    val settled: JdbcDriverKind? get() = null
}
