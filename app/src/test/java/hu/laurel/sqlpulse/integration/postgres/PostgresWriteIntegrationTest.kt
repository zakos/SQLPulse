package hu.laurel.sqlpulse.integration.postgres

import hu.laurel.sqlpulse.data.connection.WriteUnlockStore
import hu.laurel.sqlpulse.data.csv.CsvImport
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.NotEditableReason
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.ResultEditabilities
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.RowChangedException
import hu.laurel.sqlpulse.data.sql.RowEditor
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.UnexpectedRowCountException
import hu.laurel.sqlpulse.data.sql.WriteGate
import hu.laurel.sqlpulse.data.sql.WriteImpact
import hu.laurel.sqlpulse.data.sql.WriteKind
import hu.laurel.sqlpulse.data.sql.dialect.PostgresDialect
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Writing to PostgreSQL the way the app does: row edits built by RowSqlBuilder with the dialect,
 * the optimistic check, the row-count guard, the DML preview and editable results — each held up
 * against what the server then does.
 */
class PostgresWriteIntegrationTest {

    private lateinit var fixture: PostgresFixture
    private lateinit var schema: SchemaRepository
    private lateinit var editor: RowEditor
    private val d = PostgresDialect

    @Before
    fun connect() {
        fixture = PostgresFixture()
        schema = SchemaRepository(fixture.manager)
        editor = RowEditor(fixture.manager, WriteGate(fixture.manager, WriteUnlockStore()), io.mockk.mockk(relaxed = true))
        fixture.execute(
            """
            CREATE TABLE ${fixture.t("items")} (
                id integer PRIMARY KEY,
                name varchar(50) NOT NULL,
                qty integer,
                price numeric(10,2) NOT NULL,
                active boolean NOT NULL DEFAULT true,
                seen timestamptz,
                doc jsonb,
                uid uuid,
                note text
            )
            """.trimIndent(),
            """
            INSERT INTO ${fixture.t("items")} (id, name, qty, price, note) VALUES
                (1, 'apple', 1, 1.50, NULL),
                (2, 'avocado', 3, 2.25, 'ripe'),
                (3, 'banana', 5, 0.99, 'a, b where c'),
                (4, 'árvíztűrő', NULL, 10.00, 'ő'),
                (5, 'cherry', 8, 12.10, NULL),
                (6, 'date', 13, 7.00, 'sweet')
            """.trimIndent(),
            "CREATE TABLE ${fixture.t("flags")} (item_id integer PRIMARY KEY)",
            "INSERT INTO ${fixture.t("flags")} VALUES (2), (6)",
            "CREATE TABLE ${fixture.t("loose")} (grp integer NOT NULL, label text)",
            "INSERT INTO ${fixture.t("loose")} VALUES (1, 'x'), (1, 'y'), (2, 'z')",
        )
    }

    @After
    fun disconnect() {
        if (this::fixture.isInitialized) fixture.close()
    }

    private fun column(column: String, id: Int): String? =
        fixture.scalar("SELECT ${fixture.q(column)}::text FROM ${fixture.t("items")} WHERE id = $id")

    private fun edit(column: String, id: Int, old: String?, new: String?) =
        editor.prepareUpdate(fixture.schema, "items", mapOf("id" to id.toString()), column, old, new)

    // ------------------------------------------------------------------ row editing (§7.6)

    @Test
    fun `an edit changes one cell of one row, whatever the column's type`() {
        val cases = listOf(
            Triple("name", "apple", "Äpfel \"quoted\" 😀"),
            Triple("qty", "1", "42"),
            Triple("price", "1.50", "3.14"),
            Triple("active", "true", "false"),
            Triple("seen", null, "2024-05-06 07:08:09+02"),
            Triple("doc", null, "{\"k\": [1, 2]}"),
            Triple("uid", null, "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
        )
        for ((column, old, new) in cases) {
            assertEquals(1, runBlocking { editor.execute(edit(column, 1, old ?: column(column, 1), new)) })
            assertTrue("$column was not written", column(column, 1) != old)
        }
        assertEquals("Äpfel \"quoted\" 😀", column("name", 1))
        assertEquals("3.14", column("price", 1))
        assertEquals("false", column("active", 1))
        assertEquals("{\"k\": [1, 2]}", column("doc", 1))
        // Nothing else moved.
        assertEquals("avocado", column("name", 2))
        assertEquals("6", fixture.scalar("SELECT count(*) FROM ${fixture.t("items")}"))
    }

    @Test
    fun `NULL can be written and replaced, the guard says IS NOT DISTINCT FROM`() {
        assertEquals(1, runBlocking { editor.execute(edit("qty", 2, "3", null)) })
        assertNull(column("qty", 2))
        // From NULL to a value: the guard has to match the NULL it read.
        assertEquals(1, runBlocking { editor.execute(edit("qty", 2, null, "7")) })
        assertEquals("7", column("qty", 2))
    }

    @Test
    fun `an edit of a row somebody else changed reports it instead of overwriting`() {
        val mine = edit("note", 2, "ripe", "mine")
        fixture.execute("UPDATE ${fixture.t("items")} SET note = 'theirs' WHERE id = 2")
        try {
            runBlocking { editor.execute(mine) }
            fail("the edit overwrote a value it had not seen")
        } catch (e: RowChangedException) {
            assertEquals("theirs", e.currentValue)
            assertTrue(e.rowExists)
        }
        assertEquals("theirs", column("note", 2))
        assertEquals(1, runBlocking { editor.overwrite(mine) })
        assertEquals("mine", column("note", 2))
    }

    @Test
    fun `an edit of a row that was deleted says the row is gone`() {
        val late = edit("note", 3, "a, b where c", "late")
        fixture.execute("DELETE FROM ${fixture.t("items")} WHERE id = 3")
        try {
            runBlocking { editor.execute(late) }
            fail("an edit of a missing row went through")
        } catch (e: RowChangedException) {
            assertEquals(false, e.rowExists)
        }
    }

    @Test
    fun `the undo takes the edit back, a delete removes exactly the row it names, an insert adds one`() {
        val e = edit("note", 4, "ő", "rewritten")
        runBlocking { editor.execute(e) }
        assertEquals("rewritten", column("note", 4))
        runBlocking { editor.execute(e.undo!!) }
        assertEquals("ő", column("note", 4))

        assertEquals(1, runBlocking { editor.execute(editor.prepareDelete(fixture.schema, "items", mapOf("id" to "5"))) })
        assertNull(column("name", 5))

        val insert = editor.prepareInsert(
            fixture.schema, "items",
            mapOf("id" to "9", "name" to "fig", "price" to "4.20", "seen" to "2024-01-02 03:04:05", "active" to "false"),
        )
        assertEquals(1, runBlocking { editor.execute(insert) })
        assertEquals("fig", column("name", 9))
        assertEquals("false", column("active", 9))
    }

    @Test
    fun `an edit whose key matches several rows is refused and rolled back`() {
        val ambiguous = editor.prepareUpdate(fixture.schema, "loose", mapOf("grp" to "1"), "grp", "1", "9")
        try {
            runBlocking { editor.execute(ambiguous) }
            fail("two rows matched and the edit went through")
        } catch (expected: UnexpectedRowCountException) {
            assertEquals(2, expected.affected)
        }
        assertEquals("2", fixture.scalar("SELECT count(*) FROM ${fixture.t("loose")} WHERE grp = 1"))
    }

    @Test
    fun `a rolled-back transaction leaves the table as it was`() {
        val connection = fixture.session.take()
        try {
            connection.autoCommit = false
            connection.createStatement().use { it.execute("DELETE FROM ${fixture.t("items")}") }
            connection.rollback()
        } finally {
            connection.autoCommit = true
            fixture.session.giveBack(connection)
        }
        assertEquals("6", fixture.scalar("SELECT count(*) FROM ${fixture.t("items")}"))
    }

    @Test
    fun `a CSV import fills the columns it names and leaves identity and serial columns to the server`() {
        fixture.execute(
            "CREATE TABLE ${fixture.t("imported")} (id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                "n serial, name text NOT NULL, born date, score numeric(5,1), ok boolean)",
        )
        val columns = runBlocking { schema.structure(fixture.schema, "imported").columns }
        val header = listOf("Name", "born", "SCORE", "ok", "extra")
        val match = CsvImport.match(header, columns)
        // The server fills id and n by itself, so neither blocks the import.
        assertTrue(match.canImport)
        assertEquals(listOf("extra"), match.unmatched)
        val statements = CsvImport.statements(
            fixture.schema, "imported", match, header,
            listOf(listOf("Ádám", "2001-02-03", "9.5", "true", "x"), listOf("Béla", null, null, "f", "y")),
            d,
        )
        fixture.use { connection ->
            connection.autoCommit = false
            statements.forEach { statement ->
                connection.prepareStatement(statement.sql).use { prepared ->
                    statement.parameters.forEachIndexed { index, value -> prepared.setString(index + 1, value) }
                    assertEquals(1, prepared.executeUpdate())
                }
            }
            connection.commit()
            connection.autoCommit = true
        }
        assertEquals("2", fixture.scalar("SELECT count(*) FROM ${fixture.t("imported")}"))
        assertEquals("2001-02-03", fixture.scalar("SELECT born::text FROM ${fixture.t("imported")} WHERE name = 'Ádám'"))
        assertEquals("false", fixture.scalar("SELECT ok::text FROM ${fixture.t("imported")} WHERE name = 'Béla'"))
        assertEquals("2", fixture.scalar("SELECT max(id) FROM ${fixture.t("imported")}"))
    }

    // ------------------------------------------------------------------ the editor's guards

    @Test
    fun `the editor's pipeline runs a limited read with bound parameters on the server`() {
        val sql = "SELECT id, name FROM ${fixture.t("items")} WHERE qty > :min AND note IS DISTINCT FROM ':not a param' ORDER BY id"
        val limited = d.applyDefaultLimit(sql, 2)
        assertTrue(limited.limitAdded)
        val bound = d.bindParameters(limited.sql)
        assertEquals(listOf("min"), bound.parameterOrder)
        val table = fixture.use { connection ->
            connection.prepareStatement(bound.sql).use { statement ->
                statement.setLong(1, 2)
                statement.executeQuery().use { ResultTable.from(it, 100) }
            }
        }
        assertEquals(listOf(CellValue.Number("2"), CellValue.Number("3")), table.rows.map { it[0] })
    }

    @Test
    fun `the guards classify what PostgreSQL would run`() {
        assertEquals(StatementKind.WRITE, d.classify("WITH moved AS (DELETE FROM t RETURNING *) SELECT * FROM moved"))
        assertEquals(StatementKind.WRITE, d.classify("EXPLAIN ANALYZE DELETE FROM t"))
        assertEquals(StatementKind.READ, d.classify("EXPLAIN SELECT 1"))
        assertEquals(StatementKind.OTHER, d.classify("SELECT * INTO copy FROM t"))
        // The server agrees that a plain EXPLAIN does not run it, and ANALYZE does.
        fixture.execute("CREATE TABLE ${fixture.t("scratch")} AS SELECT generate_series(1, 3) AS n")
        fixture.execute("EXPLAIN DELETE FROM ${fixture.t("scratch")}")
        assertEquals("3", fixture.scalar("SELECT count(*) FROM ${fixture.t("scratch")}"))
        fixture.execute("EXPLAIN ANALYZE DELETE FROM ${fixture.t("scratch")}")
        assertEquals("0", fixture.scalar("SELECT count(*) FROM ${fixture.t("scratch")}"))
        assertTrue(d.isUnguardedWrite("DELETE FROM t"))
        assertTrue(d.isUnguardedWrite("EXPLAIN ANALYZE DELETE FROM t"))
        assertEquals(false, d.isUnguardedWrite("DELETE FROM t WHERE id = 1"))
        assertEquals(SqlGuards.classify("INSERT INTO t VALUES (1)"), d.classify("INSERT INTO t VALUES (1)"))
    }

    // ------------------------------------------------------------------ the DML preview

    private fun countOf(sql: String): Long? {
        val count = d.writeCountQuery(sql) ?: return null
        return fixture.use { it.createStatement().use { s -> s.executeQuery(count).use { r -> r.next(); r.getLong(1) } } }
    }

    /** Runs [sql] in a transaction that is rolled back and returns the row count the server reports. */
    private fun realCount(sql: String): Int = fixture.session.take().let { connection ->
        try {
            connection.autoCommit = false
            // `execute`, not `executeUpdate`: a DELETE … RETURNING hands back its rows as a result set.
            connection.createStatement().use { statement ->
                if (statement.execute(sql)) {
                    statement.resultSet.use { rows -> generateSequence { if (rows.next()) 1 else null }.count() }
                } else {
                    statement.updateCount
                }
            }.also { connection.rollback() }
        } finally {
            connection.autoCommit = true
            fixture.session.giveBack(connection)
        }
    }

    @Test
    fun `the count is the number of rows the real statement then changes`() {
        val t = fixture.t("items")
        val statements = listOf(
            "UPDATE $t SET note = 'x' WHERE qty > 2",
            "UPDATE $t SET note = 'x'",
            "DELETE FROM $t WHERE name = 'árvíztűrő'",
            "DELETE FROM $t WHERE note LIKE '%where%' RETURNING id",
            "UPDATE $t AS i SET qty = qty + 1 WHERE i.id IN (SELECT item_id FROM ${fixture.t("flags")})",
            "UPDATE $t SET \"note\" = 'x' WHERE \"id\" = 3",
            "DELETE FROM $t WHERE id = 1 -- a comment with a ; in it",
            "DELETE FROM $t WHERE note = \$\$ it's; a dollar string \$\$",
        )
        for (sql in statements) {
            val counted = countOf(sql)
            assertNotNull("no count for: $sql", counted)
            assertEquals(sql, realCount(sql).toLong(), counted)
        }
    }

    @Test
    fun `statements whose count would not be the rows they change are refused`() {
        val t = fixture.t("items")
        listOf(
            "UPDATE $t SET qty = 0 FROM ${fixture.t("flags")} f WHERE f.item_id = $t.id",
            "DELETE FROM ONLY $t WHERE id = 1",
            "DELETE FROM $t USING ${fixture.t("flags")} f WHERE f.item_id = $t.id",
            "WITH x AS (SELECT 1) DELETE FROM $t",
            "INSERT INTO $t (id, name, price) VALUES (50, 'x', 1)",
            "UPDATE $t SET qty = 0 WHERE id IN (SELECT 1 FROM pg_sleep(1))",
            "UPDATE $t SET qty = 0 WHERE (SELECT pg_advisory_lock(1)) IS NOT NULL",
            "DELETE FROM $t WHERE id = 1; DELETE FROM $t",
        ).forEach { assertNull(it, d.writeCountQuery(it)) }
    }

    @Test
    fun `the preview shows the old rows and, for an update, the new values the server would write`() {
        val t = fixture.t("items")
        val sql = "UPDATE $t SET qty = qty * 10, \"note\" = upper(name) WHERE qty > 2 AND id <> 6"
        val preview = d.writePreviewQuery(sql, 20)
        assertNotNull(preview)
        assertEquals(WriteKind.UPDATE, preview!!.kind)
        assertEquals(listOf("qty", "note"), preview.changedColumns)
        val table = fixture.use { it.createStatement().use { s -> s.executeQuery(preview.sql).use { r -> ResultTable.from(r, 20) } } }
        assertEquals(realCount(sql), table.rowCount)
        val labels = table.columns.map { it.label }
        assertTrue(labels.toString(), "qty (new)" in labels && "note (new)" in labels)
        val row = table.rows.first { it[labels.indexOf("id")] == CellValue.Number("2") }
        assertEquals(CellValue.Number("3"), row[labels.indexOf("qty")])
        assertEquals(CellValue.Number("30"), row[labels.indexOf("qty (new)")])
        assertEquals(CellValue.Text("AVOCADO"), row[labels.indexOf("note (new)")])
    }

    @Test
    fun `the preview of a delete is the rows it would remove, and a limit caps it`() {
        val t = fixture.t("items")
        val preview = d.writePreviewQuery("DELETE FROM $t WHERE qty > 2 RETURNING *", 2)!!
        assertEquals(WriteKind.DELETE, preview.kind)
        val table = fixture.use { it.createStatement().use { s -> s.executeQuery(preview.sql).use { r -> ResultTable.from(r, 20) } } }
        assertEquals(2, table.rowCount)
        assertTrue(d.writePreviewQuery("UPDATE $t SET qty = 1, qty = 2", 20) == null)
        assertTrue(d.writePreviewQuery("UPDATE $t SET qty = 1, note = qty::text", 20) == null)
        assertTrue(WriteImpact.countQuery("UPDATE `a` SET b = 1") != null) // the MySQL default is untouched
    }

    // ------------------------------------------------------------------ editable results

    private fun verdict(sql: String): ResultEditability {
        val analysed = d.resultEditability(sql)
        val editable = analysed as? ResultEditability.Editable ?: return analysed
        val structure = runBlocking { schema.structure(editable.database ?: fixture.schema, editable.table) }
        val labels = fixture.use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery(sql).use { r -> (1..r.metaData.columnCount).map { r.metaData.getColumnLabel(it) } }
            }
        }
        return ResultEditabilities.confirm(editable, fixture.schema, labels, structure)
    }

    @Test
    fun `a plain select of a keyed table is editable, quoted names and aliases included`() {
        val target = (verdict("SELECT name AS who, id FROM ${fixture.t("items")} ORDER BY id") as ResultEditability.Confirmed).target
        assertEquals(listOf<String?>("name", "id"), target.columns)
        assertEquals(mapOf("id" to 1), target.key)
        assertEquals(fixture.schema, target.database)

        fixture.execute("CREATE TABLE ${fixture.t("Q \"x\"")} (\"The Key\" int PRIMARY KEY, \"The Value\" text)")
        val quoted = verdict("SELECT \"The Key\", \"The Value\" FROM ${fixture.t("Q \"x\"")}")
        assertTrue(quoted.toString(), quoted is ResultEditability.Confirmed)
        assertEquals("Q \"x\"", (quoted as ResultEditability.Confirmed).target.table)
    }

    @Test
    fun `results that cannot be mapped onto one row of one table say why`() {
        val t = fixture.t("items")
        mapOf(
            "SELECT i.name FROM $t i JOIN ${fixture.t("flags")} f ON f.item_id = i.id" to NotEditableReason.JOIN,
            "SELECT qty, count(*) FROM $t GROUP BY qty" to NotEditableReason.GROUP_BY,
            "SELECT DISTINCT ON (qty) id, qty FROM $t" to NotEditableReason.DISTINCT,
            "SELECT id FROM $t UNION SELECT item_id FROM ${fixture.t("flags")}" to NotEditableReason.UNION,
            "WITH x AS (SELECT * FROM $t) SELECT * FROM x" to NotEditableReason.CTE,
            "SELECT id, upper(name) FROM $t" to NotEditableReason.EXPRESSION,
            "SELECT id, name::text FROM $t" to NotEditableReason.EXPRESSION,
            "SELECT 1" to NotEditableReason.NO_TABLE,
        ).forEach { (sql, reason) -> assertEquals(sql, ResultEditability.NotEditable(reason), d.resultEditability(sql)) }
        assertEquals(
            ResultEditability.NotEditable(NotEditableReason.NO_KEY),
            verdict("SELECT * FROM ${fixture.t("loose")}"),
        )
        assertEquals(
            ResultEditability.NotEditable(NotEditableReason.KEY_NOT_SELECTED),
            verdict("SELECT name FROM ${fixture.t("items")}"),
        )
    }

    @Test
    fun `a view or materialized view has no key and so no edit`() {
        fixture.execute("CREATE VIEW ${fixture.t("v")} AS SELECT id, name FROM ${fixture.t("items")}")
        assertEquals(ResultEditability.NotEditable(NotEditableReason.NO_KEY), verdict("SELECT * FROM ${fixture.t("v")}"))
    }
}
