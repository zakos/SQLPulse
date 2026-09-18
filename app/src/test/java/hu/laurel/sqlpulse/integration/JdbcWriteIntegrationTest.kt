package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.schema.quoteIdentifier
import hu.laurel.sqlpulse.data.sql.Expected
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.PreparedSql
import hu.laurel.sqlpulse.data.sql.RowSqlBuilder
import hu.laurel.sqlpulse.data.sql.SqlSession
import java.sql.Connection
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Writing: transactions, the "has somebody else changed this row" guard, and values too big to
 * pass through comfortably. Against a real server (see [TestServer]).
 *
 * The statements are the ones the app builds — [RowSqlBuilder] — so what is tested is that MySQL
 * agrees with what the unit tests assert about their text.
 */
class JdbcWriteIntegrationTest {

    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession

    /** Unique per run, so a test that fails half way cannot poison the next one. */
    private val table = "sqlpulse_it_${System.nanoTime()}"

    @Before
    fun connect() {
        val found = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlSession(config)
        session.use { connection ->
            connection.execute(
                """
                CREATE TABLE ${qualified()} (
                    id INT PRIMARY KEY,
                    note VARCHAR(255) NULL,
                    story LONGTEXT NULL,
                    payload LONGBLOB NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent(),
            )
        }
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) {
            runCatching { session.use { it.execute("DROP TABLE IF EXISTS ${qualified()}") } }
            session.close()
        }
    }

    @Test
    fun `a transaction that is rolled back leaves the table as it was`() {
        insert(id = 1, note = "kept")

        val connection = session.take()
        try {
            connection.autoCommit = false
            connection.applyStatement(RowSqlBuilder.insert(config.database, table, values(2, "gone")))
            connection.applyStatement(RowSqlBuilder.insert(config.database, table, values(3, "gone too")))
            // Halfway through, something fails — a duplicate key, a lost tunnel, a cancelled
            // import. What the app promises is that none of it is left behind.
            connection.rollback()
        } finally {
            connection.autoCommit = true
            session.giveBack(connection)
        }

        assertEquals(1, countRows())
        assertEquals("kept", noteOf(1))
    }

    @Test
    fun `a transaction that is committed keeps every row of it`() {
        val connection = session.take()
        try {
            connection.autoCommit = false
            (1..3).forEach { id ->
                connection.applyStatement(RowSqlBuilder.insert(config.database, table, values(id, "row $id")))
            }
            connection.commit()
        } finally {
            connection.autoCommit = true
            session.giveBack(connection)
        }

        assertEquals(3, countRows())
    }

    @Test
    fun `an update whose value changed underneath it affects no rows at all`() {
        insert(id = 1, note = "original")

        // The edit the user started: change "original" to "mine", as long as it is still
        // "original".
        val guarded = RowSqlBuilder.update(
            database = config.database,
            table = table,
            key = mapOf("id" to "1"),
            column = "note",
            newValue = "mine",
            expectedValue = Expected.of("original"),
        )

        // Somebody else gets there first.
        session.use { it.applyStatement(
            RowSqlBuilder.update(
                database = config.database,
                table = table,
                key = mapOf("id" to "1"),
                column = "note",
                newValue = "theirs",
                expectedValue = Expected.of("original"),
            ),
        ) }

        val affected = session.use { it.applyStatement(guarded) }
        // Zero rows, not an error and not a silent overwrite: this is the whole lost-update guard.
        assertEquals(0, affected)
        assertEquals("theirs", noteOf(1))

        // And the probe that tells the user what they would be overwriting reads that value back.
        val current = session.use { connection ->
            val probe = RowSqlBuilder.selectValue(config.database, table, mapOf("id" to "1"), "note")
            connection.prepareStatement(probe.sql).use { statement ->
                probe.parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }
        assertEquals("theirs", current)
    }

    @Test
    fun `the same update without the guard goes through, which is the way out of a conflict`() {
        insert(id = 1, note = "original")
        session.use { it.applyStatement(
            RowSqlBuilder.update(
                database = config.database,
                table = table,
                key = mapOf("id" to "1"),
                column = "note",
                newValue = "theirs",
            ),
        ) }
        assertEquals("theirs", noteOf(1))
    }

    @Test
    fun `a large TEXT survives the round trip unchanged`() {
        val story = "árvíztűrő tükörfúrógép ".repeat(50_000) // ~1 MB once encoded.
        session.use { connection ->
            connection.prepareStatement(
                "INSERT INTO ${qualified()} (id, story) VALUES (?, ?)",
            ).use { statement ->
                statement.setInt(1, 1)
                statement.setString(2, story)
                statement.executeUpdate()
            }
        }

        val read = session.use { connection ->
            connection.prepareStatement("SELECT story FROM ${qualified()} WHERE id = ?")
                .use { statement ->
                    statement.setInt(1, 1)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getString(1)
                    }
                }
        }
        assertEquals(story.length, read.length)
        assertEquals(story, read)
    }

    @Test
    fun `a large BLOB is previewed by its length and its first bytes, not by fetching it`() {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        session.use { connection ->
            connection.prepareStatement(
                "INSERT INTO ${qualified()} (id, payload) VALUES (?, ?)",
            ).use { statement ->
                statement.setInt(1, 1)
                statement.setBytes(2, payload)
                statement.executeUpdate()
            }
        }

        // The query SchemaRepository.blobBytes runs: the server reports the size and sends only
        // the slice that is shown. Two megabytes must not come down the wire for sixteen lines of
        // hex, which is exactly what a phone on a tunnel cannot afford.
        val preview = 4096
        session.use { connection ->
            connection.prepareStatement(
                "SELECT LENGTH(payload), SUBSTRING(payload, 1, $preview) FROM ${qualified()} " +
                    "WHERE id = ?",
            ).use { statement ->
                statement.setInt(1, 1)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(payload.size.toLong(), rows.getLong(1))
                    val bytes = rows.getBytes(2)
                    assertEquals(preview, bytes.size)
                    assertArrayEquals(payload.copyOf(preview), bytes)
                    // More is left on the server, which is what the preview says out loud.
                    assertTrue(rows.getLong(1) > bytes.size)
                }
            }
        }
    }

    @Test
    fun `a NULL BLOB previews as nothing rather than as an empty one that was truncated`() {
        insert(id = 1, note = "no payload")
        session.use { connection ->
            connection.prepareStatement(
                "SELECT LENGTH(payload), SUBSTRING(payload, 1, 4096) FROM ${qualified()} " +
                    "WHERE id = ?",
            ).use { statement ->
                statement.setInt(1, 1)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    // LENGTH(NULL) is NULL, which the driver reports as 0 — the same as empty.
                    assertEquals(0L, rows.getLong(1))
                    assertTrue(rows.wasNull())
                    // And the slice is NULL too, so there is nothing to call truncated.
                    assertNull(rows.getBytes(2))
                }
            }
        }
    }

    private fun values(id: Int, note: String?) = mapOf("id" to id.toString(), "note" to note)

    private fun qualified() =
        "${quoteIdentifier(config.database)}.${quoteIdentifier(table)}"

    private fun insert(id: Int, note: String?) {
        session.use { it.applyStatement(RowSqlBuilder.insert(config.database, table, values(id, note))) }
    }

    private fun countRows(): Int = session.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM ${qualified()}").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun noteOf(id: Int): String? = session.use { connection ->
        connection.prepareStatement("SELECT note FROM ${qualified()} WHERE id = ?")
            .use { statement ->
                statement.setInt(1, id)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
    }

    /** Runs one of the app's prepared statements and says how many rows it changed. */
    private fun Connection.applyStatement(prepared: PreparedSql): Int =
        prepareStatement(prepared.sql).use { statement ->
            prepared.parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeUpdate()
        }

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}
