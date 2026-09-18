package hu.laurel.sqlpulse.data.`import`

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** The file is bigger than an import from a phone should be. */
class ImportTooLargeException(val rows: Int, val limit: Int) :
    Exception("$rows rows is more than the $limit this screen imports")

/** What was read from the file, before anything is written. */
data class ImportPlan(
    val table: CsvTable,
    val match: ColumnMatch,
    val separator: Char,
) {
    val rowCount: Int get() = table.rows.size
}

/**
 * Reads a CSV file and writes it into a table (research summary, §1.2).
 *
 * Two steps, deliberately: first the file is read and matched against the table, and the screen
 * shows what would happen; only then is anything written. An import that turns out to fill the
 * wrong columns is worth finding out about before it runs, not after.
 */
@Singleton
class CsvImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SqlSessionManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun plan(uri: Uri, columns: List<SchemaColumn>): ImportPlan = withContext(io) {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("the file could not be opened")

        val separator = CsvParser.guessSeparator(text)
        val table = CsvParser.parse(text, separator)
        if (table.rows.size > MAX_ROWS) throw ImportTooLargeException(table.rows.size, MAX_ROWS)
        ImportPlan(table, CsvImport.match(table.header, columns), separator)
    }

    /**
     * Writes the rows in one transaction: either the file is imported or nothing is.
     *
     * A file is one thing to the person importing it, so a failure half way through — a duplicate
     * key on row 400 — must not leave the table with 399 rows nobody asked for.
     */
    suspend fun execute(database: String, table: String, plan: ImportPlan): Int {
        val statements = CsvImport.statements(
            database = database,
            table = table,
            match = plan.match,
            header = plan.table.header,
            rows = plan.table.rows,
        )
        if (statements.isEmpty()) return 0

        return sessions.withConnection { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                var written = 0
                statements.forEach { statement ->
                    connection.prepareStatement(statement.sql).use { prepared ->
                        statement.parameters.forEachIndexed { index, value ->
                            prepared.setString(index + 1, value)
                        }
                        written += prepared.executeUpdate()
                    }
                }
                connection.commit()
                written
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                runCatching { connection.autoCommit = previousAutoCommit }
            }
        }
    }

    private companion object {
        /**
         * A phone is not the place for a bulk load: everything is held in memory, and one
         * transaction of this size is already long for a connection over a tunnel.
         */
        const val MAX_ROWS = 5_000
    }
}
