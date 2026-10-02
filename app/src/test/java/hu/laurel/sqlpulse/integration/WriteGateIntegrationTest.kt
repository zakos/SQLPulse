package hu.laurel.sqlpulse.integration

import android.content.Context
import hu.laurel.sqlpulse.data.connection.WriteUnlockStore
import hu.laurel.sqlpulse.data.csv.CsvImport
import hu.laurel.sqlpulse.data.csv.CsvImporter
import hu.laurel.sqlpulse.data.csv.CsvParser
import hu.laurel.sqlpulse.data.csv.ImportPlan
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.quoteIdentifier
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.ReadOnlyConnectionException
import hu.laurel.sqlpulse.data.sql.RowEditor
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.WriteGate
import hu.laurel.sqlpulse.data.sql.WritesLockedException
import io.mockk.mockk
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The production write policy where it counts: in front of a real table. [WriteGate] sits next to
 * the JDBC call of [RowEditor] and [CsvImporter], so what is checked here is that a refused write
 * leaves the rows exactly as they were, and that the same write goes through once the connection
 * allows it (see [TestServer]).
 *
 * The session manager is the one piece that is not JDBC, so it is the [IntegrationSessions] stand-in
 * that says which environment the connection is and runs everything else on the real server.
 */
class WriteGateIntegrationTest {

    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession

    private val table = "sqlpulse_it_gate_${System.nanoTime()}"

    @Before
    fun connect() {
        val found = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlSession(config)
        session.use { connection ->
            connection.execute(
                "CREATE TABLE ${q(table)} (id INT PRIMARY KEY, name VARCHAR(50) NULL) " +
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            )
            connection.execute("INSERT INTO ${q(table)} VALUES (1, 'one'), (2, 'two')")
        }
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) {
            runCatching { session.use { it.execute("DROP TABLE IF EXISTS ${q(table)}") } }
            session.close()
        }
    }

    // --- Row edits ----------------------------------------------------------------------------

    @Test
    fun `a locked production connection refuses a row edit and the row is untouched`() {
        val editor = editorFor("PRODUCTION", WriteUnlockStore())
        try {
            runBlocking { editor.execute(editor.prepareUpdate(config.database, table, mapOf("id" to "1"), "name", "one", "changed")) }
            fail("a locked production connection was written to")
        } catch (expected: WritesLockedException) {
            // The policy answered before any statement went to the server.
        }
        assertEquals(mapOf("1" to "one", "2" to "two"), names())
    }

    @Test
    fun `a locked production connection refuses a delete and an insert as well`() {
        val editor = editorFor("PRODUCTION", WriteUnlockStore())
        listOf(
            editor.prepareDelete(config.database, table, mapOf("id" to "1")),
            editor.prepareInsert(config.database, table, mapOf("id" to "3", "name" to "three")),
        ).forEach { edit ->
            try {
                runBlocking { editor.execute(edit) }
                fail("a locked production connection was written to: ${edit.kind}")
            } catch (expected: WritesLockedException) {
                // As above.
            }
        }
        assertEquals(mapOf("1" to "one", "2" to "two"), names())
    }

    @Test
    fun `an unlocked production connection takes the same edit`() {
        val unlock = WriteUnlockStore().apply { unlock(CONNECTION_ID) }
        val editor = editorFor("PRODUCTION", unlock)

        runBlocking { editor.execute(editor.prepareUpdate(config.database, table, mapOf("id" to "1"), "name", "one", "changed")) }

        assertEquals(mapOf("1" to "changed", "2" to "two"), names())
    }

    @Test
    fun `unlocking another connection does not open this one, and locking again closes it`() {
        val unlock = WriteUnlockStore().apply { unlock(CONNECTION_ID + 1) }
        val editor = editorFor("PRODUCTION", unlock)
        val edit = editor.prepareUpdate(config.database, table, mapOf("id" to "2"), "name", "two", "second")
        try {
            runBlocking { editor.execute(edit) }
            fail("the unlock of another connection opened this one")
        } catch (expected: WritesLockedException) {
            // Expected.
        }

        unlock.unlock(CONNECTION_ID)
        runBlocking { editor.execute(edit) }
        assertEquals("second", names().getValue("2"))

        unlock.lock(CONNECTION_ID)
        try {
            runBlocking { editor.execute(editor.prepareDelete(config.database, table, mapOf("id" to "2"))) }
            fail("a re-locked connection was written to")
        } catch (expected: WritesLockedException) {
            // Expected.
        }
        assertEquals(setOf("1", "2"), names().keys)
    }

    @Test
    fun `a read-only connection refuses even when it is a development one`() {
        val editor = editorFor("DEVELOPMENT", WriteUnlockStore(), readOnly = true)
        try {
            runBlocking { editor.execute(editor.prepareDelete(config.database, table, mapOf("id" to "1"))) }
            fail("a read-only connection was written to")
        } catch (expected: ReadOnlyConnectionException) {
            // Expected.
        }
        assertEquals(setOf("1", "2"), names().keys)
    }

    @Test
    fun `a development connection needs no unlock`() {
        val editor = editorFor("DEVELOPMENT", WriteUnlockStore())
        runBlocking { editor.execute(editor.prepareInsert(config.database, table, mapOf("id" to "3", "name" to "three"))) }
        assertEquals(setOf("1", "2", "3"), names().keys)
    }

    // --- CSV import ---------------------------------------------------------------------------

    @Test
    fun `a locked production connection refuses a CSV import and nothing is inserted`() {
        val importer = importerFor("PRODUCTION", WriteUnlockStore())
        val plan = plan("id,name\n3,három\n4,négy\n")
        try {
            runBlocking { importer.execute(config.database, table, plan) }
            fail("a locked production connection was imported into")
        } catch (expected: WritesLockedException) {
            // Expected.
        }
        assertEquals(setOf("1", "2"), names().keys)
    }

    @Test
    fun `an unlocked production connection imports the same file`() {
        val unlock = WriteUnlockStore().apply { unlock(CONNECTION_ID) }
        val importer = importerFor("PRODUCTION", unlock)
        val plan = plan("id,name\n3,három\n4,négy ő 😀\n")

        val written = runBlocking { importer.execute(config.database, table, plan) }

        assertEquals(2, written)
        assertEquals(
            mapOf("1" to "one", "2" to "two", "3" to "három", "4" to "négy ő 😀"),
            names(),
        )
    }

    @Test
    fun `a read-only connection refuses a CSV import`() {
        val importer = importerFor("DEVELOPMENT", WriteUnlockStore(), readOnly = true)
        val plan = plan("id,name\n3,x\n")
        try {
            runBlocking { importer.execute(config.database, table, plan) }
            fail("a read-only connection was imported into")
        } catch (expected: ReadOnlyConnectionException) {
            // Expected.
        }
        assertEquals(setOf("1", "2"), names().keys)
    }

    @Test
    fun `an import that fails half way leaves nothing of the file behind`() {
        val importer = importerFor("DEVELOPMENT", WriteUnlockStore())
        // The third row repeats a key. What the importer promises is one transaction: the two rows
        // before it are not left in the table.
        val plan = plan("id,name\n3,three\n4,four\n1,again\n")
        try {
            runBlocking { importer.execute(config.database, table, plan) }
            fail("a duplicate key went through")
        } catch (expected: java.sql.SQLException) {
            // Expected: the server refuses the duplicate.
        }
        assertEquals(mapOf("1" to "one", "2" to "two"), names())
    }

    // ------------------------------------------------------------------------------------------

    private fun editorFor(environment: String, unlock: WriteUnlockStore, readOnly: Boolean = false): RowEditor {
        val manager = IntegrationSessions.manager(session, config.database, environment, readOnly, CONNECTION_ID)
        return RowEditor(manager, WriteGate(manager, unlock))
    }

    private fun importerFor(environment: String, unlock: WriteUnlockStore, readOnly: Boolean = false): CsvImporter {
        val manager = IntegrationSessions.manager(session, config.database, environment, readOnly, CONNECTION_ID)
        // The context is only for reading the file, which `plan` below does without it.
        return CsvImporter(mockk<Context>(), manager, WriteGate(manager, unlock), Dispatchers.Unconfined)
    }

    /**
     * What the import screen holds after reading a file: the parsed rows matched against the
     * table's columns. `CsvImporter.plan` does the same through a content resolver, which is the
     * one part that needs Android; everything from the plan on is the real code.
     */
    private fun plan(csv: String): ImportPlan {
        val parsed = CsvParser.parse(csv, separator = ',')
        val columns = listOf(
            SchemaColumn("id", "int(11)", nullable = false, defaultValue = null, isPrimaryKey = true, extra = null, comment = null),
            SchemaColumn("name", "varchar(50)", nullable = true, defaultValue = null, isPrimaryKey = false, extra = null, comment = null),
        )
        return ImportPlan(parsed, CsvImport.match(parsed.header, columns), ',')
    }

    private fun names(): Map<String, String?> = session.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, name FROM ${q(table)} ORDER BY id").use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
            }
        }
    }

    private fun q(name: String) = quoteIdentifier(name)

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private companion object {
        const val CONNECTION_ID = 7L
    }
}
