package hu.laurel.sqlpulse.data.writelog

import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.WriteLogEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.export.ResultSerializer
import hu.laurel.sqlpulse.data.sql.ParameterValue
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteLogTest {

    private val prod = ConnectionEntity(
        id = 4, name = "Számlázó éles", color = "Production", sshHost = "j", sshUser = "u",
        sshKeyId = null, dbHost = "h", database = "billing", dbUser = "app", environment = "PRODUCTION",
    )

    private fun entry(
        id: Long = 0,
        time: Long = 1_700_000_000_000,
        environment: String = "PRODUCTION",
        source: WriteSource = WriteSource.SQL_EDITOR,
        statement: String = "UPDATE t SET a = 1 WHERE id = 2",
        connectionName: String = "Számlázó éles",
        affected: Int? = 1,
        error: String? = null,
    ) = WriteLogEntity(
        id = id, time = time, connectionId = 4, connectionName = connectionName, connectionColor = "Production",
        environment = environment, database = "billing", source = source.name, statement = statement,
        affectedRows = affected, outcome = if (error == null) "OK" else "FAILED", error = error,
        durationMs = 12, inTransaction = false,
    )

    @Test
    fun `a successful write is recorded with the connection copied by value`() {
        val built = WriteLogEntries.build(
            time = 5, connection = prod, database = "billing", source = WriteSource.ROW_EDIT,
            statement = "  UPDATE a SET b = 'x' WHERE id = '1'  ", affectedRows = 1, failure = null,
            durationMs = 7, inTransaction = true,
        )
        assertEquals("Számlázó éles", built.connectionName)
        assertEquals("Production", built.connectionColor)
        assertEquals("PRODUCTION", built.environment)
        assertEquals(4L, built.connectionId)
        assertEquals("ROW_EDIT", built.source)
        assertEquals("OK", built.outcome)
        assertNull(built.error)
        assertEquals("UPDATE a SET b = 'x' WHERE id = '1'", built.statement)
        assertTrue(built.inTransaction)
    }

    @Test
    fun `a failure keeps the class and first message line only`() {
        val failure = SQLException("Duplicate entry 'a' for key 'PRIMARY'\nstatement: INSERT ... password")
        val built = WriteLogEntries.build(
            time = 5, connection = prod, database = null, source = WriteSource.SQL_EDITOR,
            statement = "INSERT INTO t VALUES (1)", affectedRows = null, failure = failure,
            durationMs = -3, inTransaction = false,
        )
        assertEquals("FAILED", built.outcome)
        assertEquals("SQLException: Duplicate entry 'a' for key 'PRIMARY'", built.error)
        assertEquals(0L, built.durationMs)
        assertNull(built.affectedRows)
    }

    @Test
    fun `no session and an unknown environment fall back to unset without guessing production`() {
        val built = WriteLogEntries.build(
            time = 1, connection = null, database = null, source = WriteSource.UNDO, statement = "x",
            affectedRows = null, failure = null, durationMs = 0, inTransaction = false,
        )
        assertEquals("UNSET", built.environment)
        assertNull(built.connectionId)
        assertEquals("", built.connectionName)
    }

    @Test
    fun `an enormous statement is cut`() {
        val built = WriteLogEntries.build(
            time = 1, connection = prod, database = null, source = WriteSource.SQL_EDITOR,
            statement = "x".repeat(100_000), affectedRows = 0, failure = null, durationMs = 0, inTransaction = false,
        )
        assertEquals(WriteLogEntries.MAX_STATEMENT_CHARS, built.statement.length)
    }

    @Test
    fun `bound values are written next to the statement and cannot start a line of their own`() {
        val text = WriteLogEntries.withParameters(
            sql = "UPDATE t SET a = :a WHERE id = :id AND b = :a",
            order = listOf("a", "id", "a"),
            values = mapOf(
                "a" to ParameterValue("it's\nDROP"),
                "id" to ParameterValue("42"),
            ),
        )
        assertEquals(
            "UPDATE t SET a = :a WHERE id = :id AND b = :a\n-- :a = 'it''s\\nDROP'\n-- :id = '42'",
            text,
        )
        assertEquals("DELETE FROM t WHERE id = 1", WriteLogEntries.withParameters("DELETE FROM t WHERE id = 1 ", emptyList(), emptyMap()))
    }

    @Test
    fun `a csv import is one entry that says how many rows`() {
        val text = WriteLogEntries.csvImport("shop", "items", 1200, "INSERT INTO `shop`.`items` (`a`) VALUES (?)")
        assertTrue(text.startsWith("-- CSV import into `shop`.`items`: 1200 rows"))
        assertTrue(text.endsWith("VALUES (?)"))
    }

    @Test
    fun `retention cutoff is the given number of days before now`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(1_000 * day - 90 * day, WriteLogRetention.cutoff(1_000 * day, 90))
        // Zero or negative must not mean "delete everything from the future".
        assertEquals(1_000 * day - day, WriteLogRetention.cutoff(1_000 * day, 0))
        assertEquals(90, WriteLogRetention.DEFAULT_DAYS)
        assertEquals(5_000, WriteLogRetention.MAX_ENTRIES)
    }

    @Test
    fun `filters combine and the search looks in statement, connection and error`() {
        val all = listOf(
            entry(1, environment = "PRODUCTION", statement = "DELETE FROM orders WHERE id = 9"),
            entry(2, environment = "TEST", source = WriteSource.CSV_IMPORT, connectionName = "Teszt"),
            entry(3, environment = "PRODUCTION", source = WriteSource.UNDO, error = "SQLException: lock wait timeout"),
        )
        assertEquals(listOf(1L, 2L, 3L), WriteLogFilter.apply(all, null, null, "").map { it.id })
        assertEquals(listOf(1L, 3L), WriteLogFilter.apply(all, ConnectionEnvironment.PRODUCTION, null, "").map { it.id })
        assertEquals(listOf(3L), WriteLogFilter.apply(all, ConnectionEnvironment.PRODUCTION, WriteSource.UNDO, "").map { it.id })
        assertEquals(listOf(1L), WriteLogFilter.apply(all, null, null, " delete ").map { it.id })
        assertEquals(listOf(2L), WriteLogFilter.apply(all, null, null, "teszt").map { it.id })
        assertEquals(listOf(3L), WriteLogFilter.apply(all, null, null, "LOCK WAIT").map { it.id })
        assertTrue(WriteLogFilter.apply(all, ConnectionEnvironment.DEVELOPMENT, null, "").isEmpty())
    }

    @Test
    fun `csv export has one row per entry with quoted statements and ISO times`() {
        val table = WriteLogExport.toTable(
            listOf(
                entry(1, statement = "UPDATE t SET a = 'x,y'\n-- :a = 'x,y'"),
                entry(2, affected = null, error = "SQLException: boom"),
            ),
        )
        val csv = ResultSerializer.serialize(table, ExportFormat.CSV)
        val lines = csv.trim().split("\n")
        assertTrue(lines.first().startsWith("time,connection,environment,database,source,affected_rows,outcome"))
        assertTrue(csv.contains("2023-11-14T22:13:20Z"))
        assertTrue("a statement with commas and newlines must be quoted", csv.contains("\"UPDATE t SET a = 'x,y'"))
        assertEquals(2, table.rowCount)
    }

    @Test
    fun `json export keeps nulls and booleans typed`() {
        val json = ResultSerializer.serialize(
            WriteLogExport.toTable(listOf(entry(1, affected = null))),
            ExportFormat.JSON,
        )
        assertTrue(json.contains("\"affected_rows\""))
        assertTrue(json.contains("null"))
        assertTrue(json.contains("\"in_transaction\": false") || json.contains("\"in_transaction\":false"))
        assertFalse(json.contains("password"))
    }
}
