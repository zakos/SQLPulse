package hu.laurel.sqlpulse.data.export

import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlSyntax
import java.io.OutputStream
import java.sql.ResultSet

/** Why a full export stopped before the end of the result. */
enum class ExportCap { ROWS, BYTES }

data class ExportLimits(
    val maxRows: Long = FullExport.MAX_ROWS,
    val maxBytes: Long = FullExport.MAX_BYTES,
)

data class FullExportOutcome(
    val rows: Long,
    val bytes: Long,
    /** Non-null when the file holds only the first part of the result because a hard cap was hit. */
    val cap: ExportCap?,
    val cancelled: Boolean,
)

/**
 * "Full result" export: the decision whether to offer it, and the writer that streams the rows.
 *
 * The screen's own export writes the rows on screen, which the row limit has already cut. This
 * re-runs the statement as the user typed it (so without the limit the app added) and writes each
 * row to the file as it comes off the wire, so the result never has to fit in memory.
 */
object FullExport {

    /** A phone is not where a ten-million-row dump should be made; past this the file stops, with a message. */
    const val MAX_ROWS = 1_000_000L
    const val MAX_BYTES = 200L * 1024 * 1024

    /** Rows between flushes, cancellation checks and progress reports. */
    const val BATCH = 500

    /**
     * True when a "full result" choice makes sense: the statement is a plain read that is safe to
     * send a second time, and the rows on screen are probably not all of it.
     *
     * Never for a write (re-running one would do it twice), a plan request, or a SELECT that
     * stores its result somewhere (`SELECT ... INTO`, which is an INSERT in disguise on every
     * engine). "Probably not all of it" is a result the app cut with its own LIMIT and that came
     * back full, or one the reader itself truncated.
     */
    fun canOffer(sql: String, dialect: SqlDialect, shown: ResultTable?, rowLimit: Int): Boolean {
        if (shown == null) return false
        return isRerunnableRead(sql, dialect) && mayHaveMore(shown, rowLimit)
    }

    fun isRerunnableRead(sql: String, dialect: SqlDialect): Boolean {
        if (dialect.classify(sql) != StatementKind.READ) return false
        val stripped = SqlGuards.strip(sql, dialect.grammar)
        // MySQL's dialect does not flag EXPLAIN as a plan request, so the word is checked here too:
        // a plan has nothing to gain from being streamed.
        if (dialect.isExplain(sql) || stripped.trimStart().startsWith("explain", ignoreCase = true)) return false
        return !INTO.containsMatchIn(stripped)
    }

    fun mayHaveMore(shown: ResultTable, rowLimit: Int): Boolean =
        shown.truncated || (shown.limitAdded && shown.rowCount >= rowLimit)

    private val INTO = Regex("(?i)\\binto\\b")

    /**
     * Streams [resultSet] into [out] in [format].
     *
     * [shouldContinue] is asked every row: a false answer ends the export at once, which is how a
     * cancel from the screen reaches the loop. [onProgress] hears the totals every [BATCH] rows.
     * Nothing here closes [out] or the result set; the caller owns both.
     */
    fun write(
        resultSet: ResultSet,
        format: ExportFormat,
        out: OutputStream,
        tableName: String? = null,
        syntax: SqlSyntax = MySqlDialect,
        limits: ExportLimits = ExportLimits(),
        shouldContinue: () -> Boolean = { true },
        onProgress: (rows: Long, bytes: Long) -> Unit = { _, _ -> },
    ): FullExportOutcome {
        val columns = ResultTable.columnsOf(resultSet)
        val writer = ResultSerializer.RowWriter(format, columns, tableName, syntax)
        val sink = out.buffered(64 * 1024)
        var bytes = 0L
        var rows = 0L
        var cap: ExportCap? = null
        var cancelled = false

        fun put(text: String) {
            if (text.isEmpty()) return
            val encoded = text.toByteArray(Charsets.UTF_8)
            sink.write(encoded)
            bytes += encoded.size
        }

        put(writer.header())
        while (true) {
            if (!shouldContinue()) {
                cancelled = true
                break
            }
            if (!resultSet.next()) break
            if (rows >= limits.maxRows) {
                cap = ExportCap.ROWS
                break
            }
            put(writer.row(ResultTable.readRow(resultSet, columns)))
            rows++
            if (bytes >= limits.maxBytes) {
                // Only a cap if something was left unwritten; a result that ends exactly here is whole.
                if (resultSet.next()) cap = ExportCap.BYTES
                break
            }
            if (rows % BATCH == 0L) onProgress(rows, bytes)
        }
        // A cancelled file is thrown away, so closing it properly is wasted work; a capped one is
        // shared, and a JSON array without its bracket would not open anywhere.
        if (!cancelled) put(writer.footer())
        sink.flush()
        onProgress(rows, bytes)
        return FullExportOutcome(rows, bytes, cap, cancelled)
    }
}
