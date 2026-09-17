package hu.laurel.sqlpulse.data.sql

import javax.inject.Inject
import javax.inject.Singleton

/**
 * A pending row edit: what will run, what the user is shown, and how to take it back (§7.6).
 *
 * [undo] is the exact inverse statement, built from the value that was there before. It is only
 * valid while the row still looks the way it did, which is why the offer expires after ten seconds.
 */
data class RowEdit(
    val statement: PreparedSql,
    val undo: PreparedSql?,
    /** The statement with its values written in, for the confirmation dialog. Display only. */
    val preview: String,
    val kind: EditKind,
)

enum class EditKind { UPDATE, DELETE, INSERT }

/** More or fewer rows changed than the single row that was being edited. */
class UnexpectedRowCountException(val affected: Int) :
    Exception("the statement affected $affected rows")

/**
 * Runs row edits inside a transaction (§7.6). Nothing here decides whether an edit is allowed —
 * that is the read-only flag and the MySQL grants (§3); this only makes sure that what runs is
 * exactly one row, and that it can be taken back.
 */
@Singleton
class RowEditor @Inject constructor(
    private val sessions: SqlSessionManager,
) {

    fun prepareUpdate(
        database: String,
        table: String,
        key: Map<String, String?>,
        column: String,
        oldValue: String?,
        newValue: String?,
    ): RowEdit {
        val statement = RowSqlBuilder.update(database, table, key, column, newValue)
        return RowEdit(
            statement = statement,
            undo = RowSqlBuilder.update(database, table, key, column, oldValue),
            preview = RowSqlBuilder.render(statement),
            kind = EditKind.UPDATE,
        )
    }

    /**
     * A delete has no undo: putting the row back would need every column, and a row that was
     * deleted with a trigger or a cascade cannot be reconstructed honestly. The spec asks for a
     * two-step confirmation instead, and for the table name to be typed on a production connection.
     */
    fun prepareDelete(database: String, table: String, key: Map<String, String?>): RowEdit {
        val statement = RowSqlBuilder.delete(database, table, key)
        return RowEdit(
            statement = statement,
            undo = null,
            preview = RowSqlBuilder.render(statement),
            kind = EditKind.DELETE,
        )
    }

    fun prepareInsert(database: String, table: String, values: Map<String, String?>): RowEdit {
        val statement = RowSqlBuilder.insert(database, table, values)
        return RowEdit(
            statement = statement,
            undo = null,
            preview = RowSqlBuilder.render(statement),
            kind = EditKind.INSERT,
        )
    }

    /**
     * Runs [statement] in a transaction and rolls back unless exactly one row changed. A typo in a
     * key that matched three rows is a bug, not something to commit and apologise for (§11).
     */
    suspend fun execute(statement: PreparedSql): Int = sessions.withConnection { connection ->
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val affected = connection.prepareStatement(statement.sql).use { prepared ->
                statement.parameters.forEachIndexed { index, value ->
                    prepared.setString(index + 1, value)
                }
                prepared.executeUpdate()
            }
            if (affected != 1) {
                connection.rollback()
                throw UnexpectedRowCountException(affected)
            }
            connection.commit()
            affected
        } catch (e: Exception) {
            // §11: a half-finished write is rolled back and the user is told it did not happen.
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    companion object {
        /** §7.6: the edit can be taken back from the bottom bar for ten seconds. */
        const val UNDO_WINDOW_MS = 10_000L
    }
}
