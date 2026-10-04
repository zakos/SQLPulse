package hu.laurel.sqlpulse.data.export

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.PostgresDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlServerDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqliteDialect
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FullExportTest {

    private lateinit var connection: Connection

    @Before
    fun open() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After
    fun close() = connection.close()

    private fun numbers(count: Int) =
        "WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM c WHERE n < $count) " +
            "SELECT n AS id, 'row ' || n AS label, CASE WHEN n % 3 = 0 THEN NULL ELSE n * 1.5 END AS price FROM c"

    private fun export(
        sql: String,
        format: ExportFormat = ExportFormat.CSV,
        limits: ExportLimits = ExportLimits(),
        shouldContinue: () -> Boolean = { true },
    ): Pair<String, FullExportOutcome> {
        val out = ByteArrayOutputStream()
        val outcome = connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                FullExport.write(rows, format, out, tableName = "t", limits = limits, shouldContinue = shouldContinue)
            }
        }
        return out.toString(Charsets.UTF_8) to outcome
    }

    // ---------------------------------------------------------------- the decision

    private fun shown(rows: Int, limitAdded: Boolean = false, truncated: Boolean = false) = ResultTable(
        columns = listOf(ColumnMeta("a", CellType.NUMBER, "INT", null)),
        rows = List(rows) { listOf<CellValue>(CellValue.Number("$it")) },
        truncated = truncated,
        limitAdded = limitAdded,
    )

    @Test
    fun `offered for a select the app cut with its own limit when the result came back full`() {
        assertTrue(FullExport.canOffer("SELECT * FROM t", MySqlDialect, shown(500, limitAdded = true), 500))
    }

    @Test
    fun `not offered when the app limit left room, the user limited it, or nothing was cut`() {
        // 40 rows under a 500 limit: that was the whole result.
        assertFalse(FullExport.canOffer("SELECT * FROM t", MySqlDialect, shown(40, limitAdded = true), 500))
        // The user's own LIMIT is the result they asked for.
        assertFalse(FullExport.canOffer("SELECT * FROM t LIMIT 500", MySqlDialect, shown(500), 500))
        assertFalse(FullExport.canOffer("SELECT * FROM t", MySqlDialect, null, 500))
    }

    @Test
    fun `offered when the reader itself truncated the result`() {
        assertTrue(FullExport.canOffer("SHOW TABLES", MySqlDialect, shown(500, truncated = true), 500))
    }

    @Test
    fun `never for a write, a plan or a select into`() {
        val full = shown(500, limitAdded = true)
        assertFalse(FullExport.canOffer("DELETE FROM t WHERE id > 3", MySqlDialect, full, 500))
        assertFalse(FullExport.canOffer("UPDATE t SET a = 1 WHERE id = 2", MySqlDialect, full, 500))
        assertFalse(FullExport.canOffer("EXPLAIN SELECT * FROM t", MySqlDialect, full, 500))
        assertFalse(FullExport.canOffer("SELECT * INTO backup FROM t", PostgresDialect, full, 500))
        assertFalse(FullExport.canOffer("SELECT * INTO #copy FROM t", SqlServerDialect, full, 500))
        assertFalse(FullExport.canOffer("SELECT a INTO OUTFILE '/tmp/x' FROM t", MySqlDialect, full, 500))
        assertFalse(FullExport.canOffer("WITH x AS (SELECT 1) UPDATE t SET a = 1", MySqlDialect, full, 500))
    }

    @Test
    fun `the word into inside a string or a comment does not stop the offer`() {
        val full = shown(500, limitAdded = true)
        assertTrue(FullExport.canOffer("SELECT * FROM t WHERE note = 'look into it'", MySqlDialect, full, 500))
        assertTrue(FullExport.canOffer("SELECT * FROM t -- into the void", SqliteDialect, full, 500))
    }

    // ---------------------------------------------------------------- the writer

    @Test
    fun `streams more rows than any screen limit and counts them`() {
        val (text, outcome) = export(numbers(2_500))
        assertEquals(2_500, outcome.rows)
        assertNull(outcome.cap)
        assertFalse(outcome.cancelled)
        assertEquals(2_501, text.trimEnd().lines().size) // header + rows
        assertEquals(text.toByteArray().size.toLong(), outcome.bytes)
    }

    @Test
    fun `the streamed file equals the whole-table serializer for every format but markdown`() {
        val sql = numbers(40)
        val table = connection.createStatement().use { s -> s.executeQuery(sql).use { ResultTable.from(it, 100) } }
        listOf(ExportFormat.CSV, ExportFormat.TSV, ExportFormat.JSON, ExportFormat.SQL).forEach { format ->
            val (streamed, _) = export(sql, format)
            assertEquals(format.name, ResultSerializer.serialize(table, format, "t", MySqlDialect), streamed)
        }
    }

    @Test
    fun `markdown streams as an unpadded table`() {
        val (text, _) = export("SELECT 1 AS a, 'x|y' AS b", ExportFormat.MARKDOWN)
        assertEquals("| a | b |\n| -- | -- |\n| 1 | x\\|y |\n", text)
    }

    @Test
    fun `the row cap stops the file and says so, leaving valid json`() {
        val (text, outcome) = export(numbers(100), ExportFormat.JSON, ExportLimits(maxRows = 10))
        assertEquals(10, outcome.rows)
        assertEquals(ExportCap.ROWS, outcome.cap)
        assertTrue(text.startsWith("[{") && text.endsWith("}]"))
        assertEquals(10, Regex("\\{\"id\"").findAll(text).count())
    }

    @Test
    fun `a result of exactly the cap is whole, not capped`() {
        val (_, outcome) = export(numbers(10), limits = ExportLimits(maxRows = 10))
        assertEquals(10, outcome.rows)
        assertNull(outcome.cap)
    }

    @Test
    fun `the size cap stops the file once it is reached`() {
        val (text, outcome) = export(numbers(5_000), limits = ExportLimits(maxBytes = 2_000))
        assertEquals(ExportCap.BYTES, outcome.cap)
        assertTrue(outcome.bytes >= 2_000)
        // One row past the limit at most: the check runs after each row is written.
        assertTrue(outcome.bytes < 2_100)
        assertEquals(outcome.rows + 1, text.trimEnd().lines().size.toLong())
    }

    @Test
    fun `a cancel stops at the next row and the file is not closed off`() {
        var asked = 0
        val (text, outcome) = export(numbers(1_000), ExportFormat.JSON, shouldContinue = { ++asked <= 25 })
        assertTrue(outcome.cancelled)
        assertEquals(25, outcome.rows)
        assertFalse(text.endsWith("]"))
    }

    @Test
    fun `progress is reported in batches and once at the end`() {
        val seen = mutableListOf<Long>()
        val out = ByteArrayOutputStream()
        connection.createStatement().use { s ->
            s.executeQuery(numbers(1_300)).use { rows ->
                FullExport.write(rows, ExportFormat.CSV, out, onProgress = { r, _ -> seen += r })
            }
        }
        assertEquals(listOf(500L, 1_000L, 1_300L), seen)
    }

    @Test
    fun `a NULL stays empty in csv and null in json`() {
        val (csv, _) = export("SELECT 1 AS a, NULL AS b")
        assertEquals("a,b\r\n1,\r\n", csv)
        val (json, _) = export("SELECT 1 AS a, NULL AS b", ExportFormat.JSON)
        assertEquals("[{\"a\":1,\"b\":null}]", json)
    }
}
