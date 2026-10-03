package hu.laurel.sqlpulse.integration.sqlite

import hu.laurel.sqlpulse.data.csv.CsvImport
import hu.laurel.sqlpulse.data.csv.ColumnMatch as CsvColumnMatch
import hu.laurel.sqlpulse.data.schema.ColumnMatch
import hu.laurel.sqlpulse.data.schema.RowFilter
import hu.laurel.sqlpulse.data.schema.RowLinks
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.Expected
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.RowSqlBuilder
import hu.laurel.sqlpulse.data.sql.SqlFailureKind
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.WriteKind
import hu.laurel.sqlpulse.data.sql.dialect.SqliteDialect
import hu.laurel.sqlpulse.integration.sqlite.SqliteFixture.run
import hu.laurel.sqlpulse.integration.sqlite.SqliteFixture.scalar
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Opening, reading, editing and failing against a real SQLite file: the statements the app builds
 * ([RowSqlBuilder], [RowLinks], [CsvImport], the dialect's own) run on the real driver, which is
 * what the unit tests around their text cannot say. Always runs; the "server" is a temp file.
 */
class SqliteSessionIntegrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var file: File

    private val dialect = SqliteDialect

    @Before
    fun makeFile() {
        file = SqliteFixture.create(folder.newFile("fixture.sqlite"))
    }

    private fun <T> withSession(readOnly: Boolean = false, block: (Connection) -> T): T {
        val session = SqliteFixture.session(file, readOnly)
        try {
            return session.use(block)
        } finally {
            session.close()
        }
    }

    private fun fails(block: () -> Unit): SQLException {
        try {
            block()
        } catch (e: SQLException) {
            return e
        }
        fail("expected the statement to fail")
        throw AssertionError()
    }

    private fun CellValue.text(): String? = when (this) {
        CellValue.Null -> null
        is CellValue.Text -> value
        is CellValue.Number -> value
        is CellValue.Date -> value
        is CellValue.Bool -> value.toString()
        is CellValue.Blob -> "<blob $sizeBytes>"
    }

    private fun Connection.rows(sql: String): ResultTable =
        createStatement().use { statement -> statement.executeQuery(sql).use { ResultTable.from(it, 100) } }

    // ------------------------------------------------------------ opening

    @Test
    fun `a read-only connection reads and cannot write`() {
        withSession(readOnly = true) { connection ->
            assertEquals("3", connection.scalar("SELECT count(*) FROM customer"))
            val error = fails { connection.createStatement().use { it.executeUpdate("DELETE FROM customer") } }
            assertEquals(SqlFailureKind.SERVER_BUSY_OR_READ_ONLY, dialect.failureOf(error).kind)
        }
        // And the file is untouched.
        withSession { assertEquals("3", it.scalar("SELECT count(*) FROM customer")) }
    }

    @Test
    fun `a missing file is reported, not created`() {
        val missing = File(folder.root, "gone.sqlite")
        val session = hu.laurel.sqlpulse.data.sql.SqlSession(SqliteFixture.config(missing))
        val error = fails { session.use { } }
        assertTrue(error.message!!.contains("not on this device"))
        assertEquals(SqlFailureKind.UNKNOWN_DATABASE, dialect.failureOf(error).kind)
        assertFalse(missing.exists())
    }

    @Test
    fun `the initial namespace is main and the current one too`() {
        withSession {
            assertEquals("main", dialect.initialNamespace(it, ""))
            assertEquals("main", dialect.currentNamespace(it))
            dialect.useNamespace(it, "main")
        }
    }

    @Test
    fun `a file that is not a database fails with SQLite's own message`() {
        val junk = folder.newFile("junk.sqlite").apply { writeText("this is not a database, it is a sentence long enough to fill a header") }
        val session = hu.laurel.sqlpulse.data.sql.SqlSession(SqliteFixture.config(junk, readOnly = true))
        val error = fails { session.use { it.createStatement().use { s -> s.executeQuery("SELECT * FROM sqlite_schema") } } }
        assertTrue(error.message!!.contains("not a database", ignoreCase = true))
    }

    @Test
    fun `a database in WAL mode is read from its main file alone`() {
        val wal = folder.newFile("wal.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${wal.absolutePath}").use { connection ->
            connection.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("CREATE TABLE t (a INTEGER PRIMARY KEY, b TEXT)")
                it.execute("INSERT INTO t (b) VALUES ('one'), ('two')")
                // What the file picker hands over is the main file; fold the log into it first.
                it.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
        val copy = folder.newFile("wal-copy.sqlite")
        wal.copyTo(copy, overwrite = true)

        val header = hu.laurel.sqlpulse.data.connection.SqliteFile.inspect(copy)
        assertTrue((header as hu.laurel.sqlpulse.data.connection.SqliteFile.Header.Valid).walMode)

        val session = hu.laurel.sqlpulse.data.sql.SqlSession(SqliteFixture.config(copy, readOnly = true))
        try {
            session.use { assertEquals("2", it.scalar("SELECT count(*) FROM t")) }
        } finally {
            session.close()
        }
    }

    // ------------------------------------------------------------ reading

    @Test
    fun `a result keeps NULLs, dates as written and blobs as sizes`() {
        withSession { connection ->
            connection.createStatement().use { it.execute("UPDATE customer SET photo = x'00ff10' WHERE id = 1") }
            val table = connection.rows("SELECT id, name, born, score, photo FROM customer ORDER BY id")
            val ada = table.rows[0]
            assertEquals("1", (ada[0] as CellValue.Number).value)
            assertEquals("Ada", ada[1].text())
            assertEquals("1815-12-10", ada[2].text())
            assertEquals("9.5", ada[3].text())
            assertTrue(ada[4] is CellValue.Blob)
            val bela = table.rows[1]
            assertTrue(bela[2] is CellValue.Null)
            assertTrue(bela[3] is CellValue.Null)
            assertTrue(bela[4] is CellValue.Null)
        }
    }

    @Test
    fun `paging, NULL-safe equality and the blob preview run as the dialect writes them`() {
        withSession { connection ->
            connection.createStatement().use { it.execute("UPDATE customer SET photo = x'00ff10aa' WHERE id = 1") }
            val page = connection.rows(dialect.limit("SELECT id FROM customer ORDER BY id", 2, offset = 1, ordered = true))
            assertEquals(listOf("2", "3"), page.rows.map { it[0].text() })

            // IS matches a NULL on both sides, which = never does.
            val nulls = connection.prepareStatement(
                "SELECT count(*) FROM customer WHERE ${dialect.nullSafeEquals(dialect.quoteIdentifier("email"))}",
            ).use { statement ->
                statement.setString(1, null)
                statement.executeQuery().use { it.next(); it.getInt(1) }
            }
            assertEquals(1, nulls)

            val blob = connection.rows(
                "SELECT ${dialect.blobLengthAndHead(dialect.quoteIdentifier("photo"), 2)} FROM customer WHERE id = 1",
            )
            assertEquals("4", blob.rows[0][0].text())
        }
    }

    @Test
    fun `a pragma runs as a read and a table plan as rows of text`() {
        withSession(readOnly = true) { connection ->
            assertEquals(StatementKind.READ, dialect.classify("PRAGMA table_info(customer)"))
            assertEquals(7, connection.rows("PRAGMA table_info(customer)").rows.size)
            val plan = dialect.explain("SELECT * FROM customer WHERE id = 1")!!
            assertEquals(StatementKind.READ, dialect.classify(plan))
            val rows = connection.rows(plan)
            assertTrue(rows.columns.any { it.label == "detail" })
            assertTrue(rows.rows.isNotEmpty())
        }
    }

    @Test
    fun `the default limit is added to a read and the result reports it`() {
        withSession { connection ->
            val limited = dialect.applyDefaultLimit("SELECT * FROM customer", 2)
            assertTrue(limited.limitAdded)
            assertEquals(2, connection.rows(limited.sql).rows.size)
            assertFalse(dialect.applyDefaultLimit("SELECT * FROM customer LIMIT 1", 2).limitAdded)
        }
    }

    // ------------------------------------------------------------ row editing

    @Test
    fun `an update succeeds while the value is as it was read and does nothing once it is not`() {
        withSession { connection ->
            val fresh = RowSqlBuilder.update(
                "main", "customer", mapOf("id" to "1"), "name", "Ada L.", Expected.of("Ada"), dialect,
            )
            assertEquals(1, connection.run(fresh))
            assertEquals("Ada L.", connection.scalar("SELECT name FROM customer WHERE id = 1"))
            // The same edit again: the value is no longer "Ada".
            assertEquals(0, connection.run(fresh))
        }
    }

    @Test
    fun `a value that was NULL when read can still be guarded`() {
        withSession { connection ->
            val edit = RowSqlBuilder.update(
                "main", "customer", mapOf("id" to "2"), "email", "bela@example.com", Expected.of(null), dialect,
            )
            assertEquals(1, connection.run(edit))
            assertEquals("bela@example.com", connection.scalar("SELECT email FROM customer WHERE id = 2"))
        }
    }

    @Test
    fun `text bound for a numeric column is stored as a number`() {
        withSession { connection ->
            connection.run(RowSqlBuilder.update("main", "orders", mapOf("id" to "1"), "total", "200", syntax = dialect))
            assertEquals("integer", connection.scalar("SELECT typeof(total) FROM orders WHERE id = 1"))
            connection.run(RowSqlBuilder.update("main", "orders", mapOf("id" to "1"), "total", "12.5", syntax = dialect))
            assertEquals("real", connection.scalar("SELECT typeof(total) FROM orders WHERE id = 1"))
        }
    }

    @Test
    fun `a row with a composite key is deleted by both columns`() {
        withSession { connection ->
            val delete = RowSqlBuilder.delete("main", "line", mapOf("order_id" to "1", "n" to "2"), dialect)
            assertEquals(1, connection.run(delete))
            assertEquals("1", connection.scalar("SELECT count(*) FROM line"))
        }
    }

    @Test
    fun `an insert that leaves the key out gets the next one`() {
        withSession { connection ->
            connection.run(RowSqlBuilder.insert("main", "customer", mapOf("name" to "Cecil"), dialect))
            assertEquals("4", connection.scalar("SELECT id FROM customer WHERE name = 'Cecil'"))
            // Quotes in a name are the builder's to double, and the value is bound.
            connection.run(RowSqlBuilder.insert("main", "customer", mapOf("name" to "O'Neil \"x\""), dialect))
            assertEquals("O'Neil \"x\"", connection.scalar("SELECT name FROM customer WHERE id = 5"))
        }
    }

    @Test
    fun `an identifier that needs quoting is quoted`() {
        withSession { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE \"we\"\"ird name\" (\"a b\" INTEGER PRIMARY KEY, x)") }
            connection.run(RowSqlBuilder.insert("main", "we\"ird name", mapOf("a b" to "7", "x" to "y"), dialect))
            assertEquals("y", connection.scalar("SELECT x FROM ${dialect.qualify("main", "we\"ird name")}"))
        }
    }

    @Test
    fun `a CSV import writes its rows`() {
        withSession { connection ->
            val columns = hu.laurel.sqlpulse.data.sql.dialect.SqliteCatalog.columns(connection, "main", "customer")
            val match: CsvColumnMatch = CsvImport.match(listOf("name", "email"), columns)
            // The key is a rowid alias and so does not block the import.
            assertTrue(match.canImport)
            val statements = CsvImport.statements(
                "main", "customer", match, listOf("name", "email"),
                listOf(listOf("Dóra", "dora@example.com"), listOf("Elek", null)), dialect,
            )
            statements.forEach { connection.run(it) }
            assertEquals("5", connection.scalar("SELECT count(*) FROM customer"))
        }
    }

    @Test
    fun `following a link reads the child rows of a key`() {
        withSession { connection ->
            val filter = RowFilter(listOf(ColumnMatch("order_id", "1")))
            val select = RowLinks.selectRows("main", "line", filter, syntax = dialect)
            val rows = connection.prepareStatement(select.sql).use { statement ->
                select.parameters.forEachIndexed { i, v -> statement.setString(i + 1, v) }
                statement.executeQuery().use { ResultTable.from(it, 100) }
            }
            assertEquals(2, rows.rows.size)
            val count = RowLinks.countRows("main", "line", filter, dialect)
            assertEquals("2", connection.prepareStatement(count.sql).use { statement ->
                count.parameters.forEachIndexed { i, v -> statement.setString(i + 1, v) }
                statement.executeQuery().use { it.next(); it.getString(1) }
            })
        }
    }

    // ------------------------------------------------------------ the write preview

    @Test
    fun `the preview counts what an update and a delete would touch`() {
        withSession { connection ->
            val update = "UPDATE orders SET total = total + 1 WHERE customer_id = 1"
            assertEquals("2", connection.scalar(dialect.writeCountQuery(update)!!))
            val preview = dialect.writePreviewQuery(update, 20)!!
            assertEquals(WriteKind.UPDATE, preview.kind)
            val rows = connection.rows(preview.sql)
            assertEquals(2, rows.rows.size)
            // Old values first, then the new total the write would store.
            assertEquals("50", rows.rows[0][3].text())
            assertEquals("51", rows.rows[0][4].text())

            val delete = "DELETE FROM orders WHERE total > 60"
            assertEquals("2", connection.scalar(dialect.writeCountQuery(delete)!!))
            assertEquals(2, connection.rows(dialect.writePreviewQuery(delete, 20)!!.sql).rows.size)
            // What it said is what happens (the foreign key from line is cascade-free but
            // enforced: order 2 has no lines, so the delete goes through).
            assertEquals(2, connection.createStatement().use { it.executeUpdate(delete) })
        }
    }

    @Test
    fun `forms the count cannot honestly describe are refused`() {
        assertNull(dialect.writeCountQuery("UPDATE OR REPLACE orders SET id = 1 WHERE id = 2"))
        assertNull(dialect.writeCountQuery("UPDATE orders SET total = c.x FROM c WHERE orders.id = c.id"))
        assertNull(dialect.writeCountQuery("DELETE FROM orders WHERE id = 1 RETURNING id"))
        assertNull(dialect.writeCountQuery("INSERT INTO tag (label) VALUES ('x')"))
        assertNotNull(dialect.writeCountQuery("DELETE FROM orders WHERE id IN (SELECT id FROM orders WHERE total > 1)"))
    }

    // ------------------------------------------------------------ failures

    @Test
    fun `a duplicate key is a duplicate key`() {
        withSession { connection ->
            val error = fails { connection.run(RowSqlBuilder.insert("main", "customer", mapOf("name" to "Dup", "email" to "ada@example.com"), dialect)) }
            assertEquals(SqlFailureKind.DUPLICATE_KEY, dialect.failureOf(error).kind)
            val key = fails { connection.run(RowSqlBuilder.insert("main", "customer", mapOf("id" to "1", "name" to "Dup"), dialect)) }
            assertEquals(SqlFailureKind.DUPLICATE_KEY, dialect.failureOf(key).kind)
        }
    }

    @Test
    fun `foreign keys are enforced, and cascade where declared`() {
        withSession { connection ->
            val orphan = fails { connection.createStatement().use { it.executeUpdate("INSERT INTO orders (customer_id) VALUES (99)") } }
            assertTrue(dialect.failureOf(orphan).serverMessage.contains("FOREIGN KEY", ignoreCase = true))
            connection.createStatement().use { it.executeUpdate("DELETE FROM customer WHERE id = 2") }
            assertEquals("0", connection.scalar("SELECT count(*) FROM orders WHERE customer_id = 2"))
        }
    }

    @Test
    fun `missing objects and bad syntax are told apart`() {
        withSession { connection ->
            val table = fails { connection.rows("SELECT * FROM nope") }
            assertEquals(SqlFailureKind.UNKNOWN_OBJECT, dialect.failureOf(table).kind)
            val column = fails { connection.rows("SELECT nope FROM customer") }
            assertEquals(SqlFailureKind.UNKNOWN_OBJECT, dialect.failureOf(column).kind)
            val syntax = fails { connection.rows("SELEC 1") }
            assertEquals(SqlFailureKind.SYNTAX, dialect.failureOf(syntax).kind)
            // The driver's own sentence is kept verbatim.
            assertTrue(dialect.failureOf(table).serverMessage.contains("no such table: nope"))
        }
    }

    @Test
    fun `a second writer waits for the lock and then reports it as a lock`() {
        // Not a pooled connection: hold a write transaction open on a plain one.
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("UPDATE customer SET name = name") }
            val session = SqliteFixture.session(file)
            try {
                val error = fails {
                    session.use { it.createStatement().use { s -> s.executeUpdate("UPDATE customer SET name = name") } }
                }
                assertEquals(SqlFailureKind.LOCK, dialect.failureOf(error).kind)
            } finally {
                session.close()
                holder.rollback()
            }
        }
    }

    @Test
    fun `a running statement can be cancelled and is reported as an interruption`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val session = SqliteFixture.session(file, readOnly = true)
            try {
                val connection = session.take()
                val statement: Statement = connection.createStatement()
                val running = executor.submit<SQLException?> {
                    try {
                        statement.executeQuery(
                            "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) FROM c",
                        ).use { it.next() }
                        null
                    } catch (e: SQLException) {
                        e
                    }
                }
                Thread.sleep(300)
                statement.cancel()
                val error = running.get(10, TimeUnit.SECONDS)
                assertNotNull("the statement should have been interrupted", error)
                assertEquals(SqlFailureKind.TIMEOUT, dialect.failureOf(error!!).kind)
                statement.close()
                session.giveBack(connection)
            } finally {
                session.close()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    // ------------------------------------------------------------ what the guards say

    @Test
    fun `what the editor may send is decided before it reaches the file`() {
        assertEquals(StatementKind.OTHER, dialect.classify("PRAGMA user_version = 3"))
        assertEquals(StatementKind.OTHER, dialect.classify("CREATE TABLE x (a)"))
        assertEquals(StatementKind.WRITE, dialect.classify("replace into tag values ('a', 1)"))
        assertTrue(dialect.isUnguardedWrite("DELETE FROM tag"))
        assertFalse(dialect.isUnguardedWrite("DELETE FROM tag WHERE weight = 1"))
        assertEquals("SELECT 1", SqlGuards.strip("SELECT 1", dialect.grammar))
    }
}
