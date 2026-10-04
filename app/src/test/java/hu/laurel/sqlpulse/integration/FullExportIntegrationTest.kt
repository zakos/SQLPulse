package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.export.FullExport
import hu.laurel.sqlpulse.data.export.ResultSerializer
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqliteDialect
import hu.laurel.sqlpulse.integration.sqlite.SqliteFixture
import java.io.ByteArrayOutputStream
import java.sql.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The full-result export against real databases: more rows than the editor's row limit, written
 * by re-running the user's own statement. The SQLite half always runs (the "server" is a temp
 * file); the MySQL/MariaDB half needs [TestServer.URL_VARIABLE] like the rest of this package.
 */
class FullExportIntegrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val screenLimit = 500
    private val total = 1_500

    /** What the editor does: the limit the app adds, and what lands on screen. */
    private fun onScreen(connection: Connection, sql: String, dialect: hu.laurel.sqlpulse.data.sql.dialect.SqlDialect): ResultTable {
        val limited = dialect.applyDefaultLimit(sql, screenLimit)
        assertTrue("the app adds its limit to the statement", limited.limitAdded)
        return connection.createStatement().use { s ->
            s.executeQuery(limited.sql).use { ResultTable.from(it, screenLimit).copy(limitAdded = true) }
        }
    }

    private fun fullExport(
        connection: Connection,
        sql: String,
        format: ExportFormat,
        streaming: Boolean,
        syntax: hu.laurel.sqlpulse.data.sql.dialect.SqlSyntax = MySqlDialect,
    ): Pair<String, Long> {
        val out = ByteArrayOutputStream()
        val rows = connection.createStatement().use { s ->
            // MariaDB Connector/J streams on a positive fetch size and refuses the old
            // Integer.MIN_VALUE trick, which only Connector/J 5.1 understands.
            if (streaming) s.fetchSize = 1_000
            s.executeQuery(sql).use { FullExport.write(it, format, out, tableName = "export_t", syntax = syntax).rows }
        }
        return out.toString(Charsets.UTF_8) to rows
    }

    @Test
    fun `sqlite exports every row of a statement the screen cut at the limit`() {
        val file = SqliteFixture.create(
            folder.newFile("big.sqlite"),
            listOf(
                "CREATE TABLE export_t (id INTEGER PRIMARY KEY, label TEXT, note TEXT)",
                "WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM c WHERE n < $total) " +
                    "INSERT INTO export_t SELECT n, 'label ő ' || n, CASE WHEN n % 7 = 0 THEN NULL ELSE 'a,\"b\"' END FROM c",
            ),
        )
        val session = SqliteFixture.session(file)
        try {
            session.use { connection ->
                val sql = "SELECT id, label, note FROM export_t ORDER BY id"
                val shown = onScreen(connection, sql, SqliteDialect)
                assertEquals(screenLimit, shown.rowCount)
                assertTrue(FullExport.canOffer(sql, SqliteDialect, shown, screenLimit))

                // The statement the exporter sends is the user's text, with no LIMIT of the app's.
                val (csv, rows) = fullExport(connection, sql, ExportFormat.CSV, streaming = false, syntax = SqliteDialect)
                assertEquals(total.toLong(), rows)
                assertEquals(total + 1, csv.trimEnd().lines().size)

                val everything = connection.createStatement().use { s -> s.executeQuery(sql).use { ResultTable.from(it, 10_000) } }
                assertEquals(ResultSerializer.serialize(everything, ExportFormat.CSV, "export_t", SqliteDialect), csv)
                val (inserts, _) = fullExport(connection, sql, ExportFormat.SQL, streaming = false, syntax = SqliteDialect)
                assertEquals(ResultSerializer.serialize(everything, ExportFormat.SQL, "export_t", SqliteDialect), inserts)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `mysql or mariadb exports every row of a statement the screen cut at the limit`() {
        val config = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", config != null)
        val table = "sqlpulse_it_full_${System.nanoTime()}"
        val session = SqlSession(config!!)
        try {
            session.use { connection ->
                connection.createStatement().use {
                    it.execute("CREATE TABLE `$table` (id INT PRIMARY KEY, label VARCHAR(40) NOT NULL, note TEXT NULL) DEFAULT CHARSET=utf8mb4")
                }
                try {
                    connection.autoCommit = false
                    connection.prepareStatement("INSERT INTO `$table` VALUES (?, ?, ?)").use { insert ->
                        for (n in 1..total) {
                            insert.setInt(1, n)
                            insert.setString(2, "label ő $n")
                            if (n % 7 == 0) insert.setNull(3, java.sql.Types.VARCHAR) else insert.setString(3, "a,\"b\"")
                            insert.addBatch()
                        }
                        insert.executeBatch()
                    }
                    connection.commit()
                    connection.autoCommit = true

                    val sql = "SELECT id, label, note FROM `$table` ORDER BY id"
                    val shown = onScreen(connection, sql, MySqlDialect)
                    assertEquals(screenLimit, shown.rowCount)
                    assertTrue(FullExport.canOffer(sql, MySqlDialect, shown, screenLimit))

                    // Streaming mode (the exporter's hint for MySQL) must not change a single byte.
                    val (csv, rows) = fullExport(connection, sql, ExportFormat.CSV, streaming = true)
                    assertEquals(total.toLong(), rows)
                    val everything = connection.createStatement().use { s -> s.executeQuery(sql).use { ResultTable.from(it, 10_000) } }
                    assertEquals(ResultSerializer.serialize(everything, ExportFormat.CSV, table, MySqlDialect), csv)
                    val (json, _) = fullExport(connection, sql, ExportFormat.JSON, streaming = true)
                    assertEquals(ResultSerializer.serialize(everything, ExportFormat.JSON, table, MySqlDialect), json)
                } finally {
                    connection.autoCommit = true
                    connection.createStatement().use { it.execute("DROP TABLE IF EXISTS `$table`") }
                }
            }
        } finally {
            session.close()
        }
    }
}
