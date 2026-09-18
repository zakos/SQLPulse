package hu.laurel.sqlpulse.data.csv

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.di.IoDispatcher
import java.io.BufferedReader
import java.io.InputStreamReader
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
 * Two steps, deliberately: first the file is streamed in and matched against the table, and the
 * screen shows what would happen; only then is anything written. An import that turns out to fill
 * the wrong columns is worth finding out about before it runs, not after.
 */
@Singleton
class CsvImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SqlSessionManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Reads the file and works out what importing it would do, without ever holding the file.
     *
     * The stream is parsed record by record, so what stays in memory is the rows that will be
     * written and nothing else — a 300 MB export chosen by mistake is refused on its row count
     * rather than by running the phone out of memory first.
     */
    suspend fun plan(uri: Uri, columns: List<SchemaColumn>): ImportPlan = withContext(io) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("the file could not be opened")
        input.use { stream ->
            // The buffer is sized for the separator guess, which reads the head of the file and
            // then puts it back: a BufferedReader can only honour a mark that fits in its buffer.
            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8), LOOKAHEAD)
            val separator = CsvParser.guessSeparator(reader, LOOKAHEAD)

            var header: List<String> = emptyList()
            val rows = mutableListOf<List<String?>>()
            var total = 0
            val malformed = CsvParser.stream(reader, separator, onHeader = { header = it }) { row ->
                total++
                // Past the limit rows are counted but not kept, so the message can name the size
                // of the file the person actually chose without that file being held.
                if (total <= MAX_ROWS) rows += row
            }
            if (total > MAX_ROWS) throw ImportTooLargeException(total, MAX_ROWS)

            val table = CsvTable(header, rows, malformed)
            ImportPlan(table, CsvImport.match(header, columns), separator)
        }
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
         * A phone is not the place for a bulk load: the parsed rows are held in memory until the
         * import is confirmed, and one transaction of this size is already long for a connection
         * over a tunnel.
         */
        const val MAX_ROWS = 5_000

        /** How much of the file the separator guess may look at before it has to give it back. */
        const val LOOKAHEAD = 64 * 1024
    }
}
