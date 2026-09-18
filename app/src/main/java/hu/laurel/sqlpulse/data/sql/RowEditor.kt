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
    /**
     * The same statement without the "and the value is still what I saw" condition.
     *
     * Kept so that an edit refused because someone else got there first can still be carried out,
     * once the user has been told what they would be overwriting.
     */
    val unguarded: PreparedSql? = null,
    /** Reads the column back, to say what it holds now. */
    val conflictProbe: PreparedSql? = null,
    /** What the update is trying to write, so an already-written value is not read as a conflict. */
    val newValue: String? = null,
)

enum class EditKind { UPDATE, DELETE, INSERT }

/** More or fewer rows changed than the single row that was being edited. */
class UnexpectedRowCountException(val affected: Int) :
    Exception("the statement affected $affected rows")

/**
 * The row changed between being read and being written (§7.6).
 *
 * @param currentValue what the column holds now, or null when the row is gone entirely.
 * @param rowExists false when someone deleted the row rather than changing it.
 */
class RowChangedException(val currentValue: String?, val rowExists: Boolean) :
    Exception("the row changed since it was read")

/**
 * Runs row edits inside a transaction (§7.6). Nothing here decides whether an edit is allowed —
 * that is the read-only flag and the MySQL grants (§3); this only makes sure that what runs is
 * exactly one row, and that it can be taken back.
 */
@Singleton
class RowEditor @Inject constructor(
    private val sessions: SqlSessionManager,
) {

    /**
     * An UPDATE that only applies while the column still holds [oldValue].
     *
     * The preview shows the plain statement: the guard is not part of what the user asked for, and
     * a WHERE clause naming the old value twice would only make the dialog harder to read.
     */
    fun prepareUpdate(
        database: String,
        table: String,
        key: Map<String, String?>,
        column: String,
        oldValue: String?,
        newValue: String?,
    ): RowEdit {
        val plain = RowSqlBuilder.update(database, table, key, column, newValue)
        return RowEdit(
            statement = RowSqlBuilder.update(
                database = database,
                table = table,
                key = key,
                column = column,
                newValue = newValue,
                expectedValue = Expected.of(oldValue),
            ),
            // The undo only applies while the value is still the one we wrote, for the same reason.
            undo = RowSqlBuilder.update(
                database = database,
                table = table,
                key = key,
                column = column,
                newValue = oldValue,
                expectedValue = Expected.of(newValue),
            ),
            preview = RowSqlBuilder.render(plain),
            kind = EditKind.UPDATE,
            unguarded = plain,
            conflictProbe = RowSqlBuilder.selectValue(database, table, key, column),
            newValue = newValue,
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
     * Runs an edit, and works out what happened when nothing did.
     *
     * A guarded UPDATE that changes no rows means the row moved underneath us: either somebody
     * else wrote to it, or it is gone. Both are worth saying out loud, with what the column holds
     * now, rather than reporting "0 rows changed" and leaving the user to guess.
     */
    suspend fun execute(edit: RowEdit): Int {
        try {
            return execute(edit.statement)
        } catch (e: UnexpectedRowCountException) {
            if (e.affected != 0 || edit.conflictProbe == null) throw e
            val current = readValue(edit.conflictProbe)
            // Somebody may have written exactly what we were about to write, and a driver that
            // counts changed rather than matched rows reports that as nothing done. The column
            // holds what was asked for, so there is nothing to complain about.
            if (current.rowExists && current.value == edit.newValue) return 0
            throw RowChangedException(currentValue = current.value, rowExists = current.rowExists)
        }
    }

    /** Reruns an edit without its "the value is still what I saw" condition. */
    suspend fun overwrite(edit: RowEdit): Int =
        execute(edit.unguarded ?: edit.statement)

    private suspend fun readValue(probe: PreparedSql): ProbeResult = sessions.withConnection { connection ->
        connection.prepareStatement(probe.sql).use { prepared ->
            probe.parameters.forEachIndexed { index, value -> prepared.setString(index + 1, value) }
            prepared.executeQuery().use { rows ->
                if (rows.next()) ProbeResult(rows.getString(1), rowExists = true) else ProbeResult(null, false)
            }
        }
    }

    private data class ProbeResult(val value: String?, val rowExists: Boolean)

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
