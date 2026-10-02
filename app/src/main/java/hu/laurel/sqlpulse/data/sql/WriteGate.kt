package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.connection.WriteUnlockStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The production write policy for writes that do not go through [QueryExecutor]: row edits and
 * CSV imports.
 *
 * Each screen already asks before it writes, but a check that lives only in a dialog is one
 * refactor away from being skipped. Checking here, next to the JDBC call, means a locked
 * production connection or a read-only one cannot be written to from any screen.
 */
@Singleton
class WriteGate @Inject constructor(
    private val sessions: SqlSessionManager,
    private val writeUnlock: WriteUnlockStore,
) {

    /** Throws [ReadOnlyConnectionException] or [WritesLockedException] when this write may not run. */
    fun check() {
        // No open session means the write would fail on its own; the policy has nothing to add.
        val connection = sessions.currentConnection() ?: return
        if (connection.readOnly) throw ReadOnlyConnectionException()
        val access = writeUnlock.writeAccess(
            connectionId = connection.id,
            environment = ConnectionEnvironment.fromName(connection.environment),
            readOnly = connection.readOnly,
        )
        if (!access.allowed) throw WritesLockedException(access)
    }
}
