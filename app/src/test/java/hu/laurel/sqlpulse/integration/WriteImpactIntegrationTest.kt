package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.schema.quoteIdentifier
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.WriteImpact
import hu.laurel.sqlpulse.data.sql.WriteKind
import java.sql.Connection
import java.sql.ResultSet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The DML preview: the SELECTs [WriteImpact] derives from an UPDATE or a DELETE, run on a real
 * server (see [TestServer]) and held against what the real statement then does.
 *
 * Every case runs the write inside a transaction that is rolled back, so the comparison is made
 * on the server's own behaviour — its row count, its evaluation of the SET list — and the table
 * is the same afterwards. The unit tests say the derived text looks right; only a server can say
 * the number and the new values are the ones it would have written.
 */
class WriteImpactIntegrationTest {

    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession

    /** Unique per run, so a test that fails half way cannot poison the next one. */
    private val stamp = System.nanoTime()
    private val items = "sqlpulse_it_items_$stamp"
    private val flags = "sqlpulse_it_flags_$stamp"

    @Before
    fun connect() {
        val found = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlSession(config)
        session.use { connection ->
            connection.execute(
                """
                CREATE TABLE ${q(items)} (
                    id INT PRIMARY KEY,
                    name VARCHAR(50) NOT NULL,
                    qty INT NULL,
                    price DECIMAL(10,2) NOT NULL,
                    note TEXT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent(),
            )
            connection.execute("CREATE TABLE ${q(flags)} (item_id INT PRIMARY KEY) ENGINE=InnoDB")
            connection.execute(
                """
                INSERT INTO ${q(items)} (id, name, qty, price, note) VALUES
                    (1, 'apple', 1, 1.50, NULL),
                    (2, 'avocado', 3, 2.25, 'ripe'),
                    (3, 'banana', 5, 0.99, 'a, b where c'),
                    (4, 'árvíztűrő', NULL, 10.00, 'ő'),
                    (5, 'cherry', 8, 12.10, NULL),
                    (6, 'date', 13, 7.00, 'sweet')
                """.trimIndent(),
            )
            connection.execute("INSERT INTO ${q(flags)} VALUES (2), (6)")
        }
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) {
            runCatching {
                session.use {
                    it.execute("DROP TABLE IF EXISTS ${q(flags)}")
                    it.execute("DROP TABLE IF EXISTS ${q(items)}")
                }
            }
            session.close()
        }
    }

    @Test
    fun `an update with a WHERE previews the rows and the values it then writes`() {
        verifyUpdate("UPDATE ${q(items)} SET qty = qty + 1 WHERE qty > 2", expectedRows = 4)
    }

    @Test
    fun `an update of several columns with functions and a literal full of keywords`() {
        verifyUpdate(
            "UPDATE ${q(items)} SET name = CONCAT(name, '-x'), price = ROUND(price * 1.5, 2), " +
                "note = 'x, y where z' WHERE id IN (2, 3, 4)",
            expectedRows = 3,
        )
    }

    @Test
    fun `an update through an alias, written with the database in front`() {
        verifyUpdate(
            "UPDATE ${q(config.database)}.${q(items)} AS i SET i.qty = 0 WHERE i.id <= 2",
            expectedRows = 2,
        )
    }

    @Test
    fun `an update of NULLs to values and values to NULL`() {
        verifyUpdate("UPDATE ${q(items)} SET qty = IFNULL(qty, 100), note = NULL WHERE id IN (4, 5)", expectedRows = 2)
    }

    @Test
    fun `an update without a WHERE is the whole table`() {
        verifyUpdate("UPDATE ${q(items)} SET qty = 0", expectedRows = 6)
    }

    @Test
    fun `an update with a subquery in its WHERE`() {
        verifyUpdate(
            "UPDATE ${q(items)} SET qty = qty * 2 WHERE id IN (SELECT item_id FROM ${q(flags)})",
            expectedRows = 2,
        )
    }

    @Test
    fun `an ORDER BY is dropped from the preview and the statement still matches the same rows`() {
        verifyUpdate("UPDATE ${q(items)} SET qty = 9 WHERE qty IS NOT NULL ORDER BY id DESC", expectedRows = 5)
    }

    @Test
    fun `a delete previews the rows it then removes`() {
        verifyDelete("DELETE FROM ${q(items)} WHERE qty IS NULL OR qty > 5", expectedRows = 3)
    }

    @Test
    fun `a delete with a LIKE pattern`() {
        verifyDelete("DELETE FROM ${q(items)} WHERE name LIKE 'a%'", expectedRows = 3)
    }

    @Test
    fun `a delete with a subquery and one without any WHERE`() {
        verifyDelete("DELETE FROM ${q(items)} WHERE id IN (SELECT item_id FROM ${q(flags)})", expectedRows = 2)
        verifyDelete("DELETE FROM ${q(items)}", expectedRows = 6)
    }

    @Test
    fun `a WHERE that matches nothing counts zero and previews nothing`() {
        verifyUpdate("UPDATE ${q(items)} SET qty = 1 WHERE id = 999", expectedRows = 0)
        verifyDelete("DELETE FROM ${q(items)} WHERE id = 999", expectedRows = 0)
    }

    @Test
    fun `the preview shows at most the limit while the count says how many there really are`() {
        val sql = "UPDATE ${q(items)} SET qty = 0"
        val preview = WriteImpact.previewQuery(sql, limit = 2)!!
        session.use { connection ->
            assertEquals(6L, count(connection, WriteImpact.countQuery(sql)!!))
            assertEquals(2, rows(connection, preview.sql).size)
        }
    }

    @Test
    fun `statements that cannot be derived with confidence stay refused`() {
        // The refusals are the point of the module: a number that may be wrong is worse than none.
        // Each of these is a statement the server would happily run, so what is asserted is that
        // no SELECT is derived, not that the SELECT would have failed.
        val refused = listOf(
            "UPDATE ${q(items)} SET qty = 0 WHERE id > 1 LIMIT 2",
            "DELETE FROM ${q(items)} WHERE id > 1 ORDER BY id LIMIT 2",
            "UPDATE ${q(items)} i JOIN ${q(flags)} f ON f.item_id = i.id SET i.qty = 0",
            "UPDATE ${q(items)}, ${q(flags)} SET qty = 0 WHERE id = item_id",
            "DELETE i FROM ${q(items)} i JOIN ${q(flags)} f ON f.item_id = i.id",
            "DELETE FROM ${q(items)} USING ${q(items)} JOIN ${q(flags)} ON item_id = id",
            "INSERT INTO ${q(flags)} VALUES (1)",
            "UPDATE ${q(items)} SET qty = 1; DELETE FROM ${q(items)}",
        )
        refused.forEach { sql ->
            assertNull(sql, WriteImpact.countQuery(sql))
            assertNull(sql, WriteImpact.previewQuery(sql))
        }
        // An update whose SET list reads what an earlier item wrote: MySQL would see the new
        // value, the SELECT the old one, so there is no honest preview — but the count is fine.
        val dependent = "UPDATE ${q(items)} SET qty = qty + 1, price = qty WHERE id = 1"
        assertNotNull(WriteImpact.countQuery(dependent))
        assertNull(WriteImpact.previewQuery(dependent))
        // The server really does evaluate left to right, which is the reason for the refusal.
        session.use { connection ->
            connection.autoCommit = false
            try {
                connection.execute(dependent)
                assertEquals("2.00", scalar(connection, "SELECT price FROM ${q(items)} WHERE id = 1"))
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Runs the derived count and preview, then the update itself, and compares them. */
    private fun verifyUpdate(sql: String, expectedRows: Int) {
        val countSql = WriteImpact.countQuery(sql)
        val preview = WriteImpact.previewQuery(sql)
        assertNotNull("no count query for: $sql", countSql)
        assertNotNull("no preview query for: $sql", preview)
        assertEquals(WriteKind.UPDATE, preview!!.kind)

        val connection = session.take()
        try {
            connection.autoCommit = false
            val before = snapshot(connection)

            val counted = count(connection, countSql!!)
            val previewed = rows(connection, preview.sql)
            assertEquals(expectedRows.toLong(), counted)
            assertEquals(expectedRows, previewed.size)

            // What the preview promised, by id: the old values, then each new value.
            val promised = previewed.associateBy { it.getValue("id") }
            val affected = connection.createStatement().use { it.executeUpdate(sql) }
            // The count query's whole claim. MySQL reports changed rows rather than matched ones
            // by default, so an update that writes a value a row already had says less than the
            // count; it must never say more.
            assertTrue("count $counted, update changed $affected", affected <= counted)

            val after = snapshot(connection)
            promised.forEach { (id, row) ->
                preview.changedColumns.forEach { column ->
                    assertEquals(
                        "new $column of row $id for: $sql",
                        row.getValue(column + WriteImpact.NEW_SUFFIX),
                        after.getValue(id).getValue(column),
                    )
                    // And the preview's old side is what the row held before the write.
                    assertEquals(before.getValue(id).getValue(column), row.getValue(column))
                }
            }
            // No row outside the preview changed.
            (after.keys - promised.keys).forEach { id ->
                assertEquals("row $id should be untouched by: $sql", before.getValue(id), after.getValue(id))
            }

            connection.rollback()
            assertEquals("the rollback restores the table", before, snapshot(connection))
        } finally {
            runCatching { connection.rollback() }
            connection.autoCommit = true
            session.giveBack(connection)
        }
    }

    private fun verifyDelete(sql: String, expectedRows: Int) {
        val countSql = WriteImpact.countQuery(sql)
        val preview = WriteImpact.previewQuery(sql)
        assertNotNull("no count query for: $sql", countSql)
        assertNotNull("no preview query for: $sql", preview)
        assertEquals(WriteKind.DELETE, preview!!.kind)

        val connection = session.take()
        try {
            connection.autoCommit = false
            val before = snapshot(connection)

            val counted = count(connection, countSql!!)
            val previewed = rows(connection, preview.sql)
            assertEquals(expectedRows.toLong(), counted)
            assertEquals(expectedRows, previewed.size)

            val affected = connection.createStatement().use { it.executeUpdate(sql) }
            assertEquals("count against rows deleted for: $sql", counted, affected.toLong())

            val after = snapshot(connection)
            // Exactly the previewed rows are gone, with the values the preview showed.
            val gone = before.keys - after.keys
            assertEquals(previewed.map { it.getValue("id") }.toSet(), gone)
            previewed.forEach { row -> assertEquals(before.getValue(row.getValue("id")), row) }

            connection.rollback()
            assertEquals(before, snapshot(connection))
        } finally {
            runCatching { connection.rollback() }
            connection.autoCommit = true
            session.giveBack(connection)
        }
    }

    /** Every row of the items table as text, by id. Text on purpose: that is what the grid compares. */
    private fun snapshot(connection: Connection): Map<String?, Map<String, String?>> =
        rows(connection, "SELECT * FROM ${q(items)} ORDER BY id").associateBy { it.getValue("id") }

    private fun rows(connection: Connection, sql: String): List<Map<String, String?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList {
                    while (rows.next()) add(rows.asMap())
                }
            }
        }

    private fun ResultSet.asMap(): Map<String, String?> {
        val meta = metaData
        // Labels, not names: the preview's new values are aliases.
        return (1..meta.columnCount).associate { meta.getColumnLabel(it) to getString(it) }
    }

    private fun count(connection: Connection, sql: String): Long =
        scalar(connection, sql)!!.toLong()

    private fun scalar(connection: Connection, sql: String): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                assertTrue(rows.next())
                rows.getString(1)
            }
        }

    private fun q(name: String) = quoteIdentifier(name)

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}
