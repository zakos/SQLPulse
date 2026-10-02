package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.connection.WriteUnlockStore
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.quoteIdentifier
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.NotEditableReason
import hu.laurel.sqlpulse.data.sql.ResultEditTarget
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.ResultEditabilities
import hu.laurel.sqlpulse.data.sql.RowChangedException
import hu.laurel.sqlpulse.data.sql.RowEditor
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.UnexpectedRowCountException
import hu.laurel.sqlpulse.data.sql.WriteGate
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Editing a query result in place, end to end: the SELECT the user ran, the verdict
 * [ResultEditabilities] gives with the real table structure, and the UPDATE [RowEditor] then
 * sends. Against a real server (see [TestServer]).
 *
 * The steps are those of `QueryResultEditing` — analyse the text, read the structure, confirm
 * against the labels the server returned, take the key from the row, prepare and execute — minus
 * the view model around them, which needs Android. What only a server can say is whether the
 * structure it reports is enough for the verdict, and whether the statement built from a shown
 * row changes that row and no other.
 */
class ResultEditingIntegrationTest {

    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession
    private lateinit var schema: SchemaRepository
    private lateinit var editor: RowEditor

    private val stamp = System.nanoTime()
    private val people = "sqlpulse_it_people_$stamp"
    private val loose = "sqlpulse_it_loose_$stamp"
    private val unique = "sqlpulse_it_unique_$stamp"
    private val orders = "sqlpulse_it_orders_$stamp"
    private val generated = "sqlpulse_it_generated_$stamp"

    @Before
    fun connect() {
        val found = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlSession(config)
        val manager = IntegrationSessions.manager(session, config.database)
        schema = SchemaRepository(manager)
        editor = RowEditor(manager, WriteGate(manager, WriteUnlockStore()))

        session.use { connection ->
            connection.execute(
                """
                CREATE TABLE ${q(people)} (
                    id INT PRIMARY KEY,
                    email VARCHAR(100) NOT NULL,
                    name VARCHAR(100) NULL,
                    bio TEXT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent(),
            )
            connection.execute(
                """
                INSERT INTO ${q(people)} VALUES
                    (1, 'a@example.com', 'Aladár', NULL),
                    (2, 'b@example.com', 'Béla', 'first line'),
                    (3, 'c@example.com', NULL, 'third')
                """.trimIndent(),
            )
            // No key at all, and a "key" that is not one: a result of these cannot be edited
            // because a row cannot be told from its neighbour.
            connection.execute(
                "CREATE TABLE ${q(loose)} (grp INT NOT NULL, label VARCHAR(20)) ENGINE=InnoDB",
            )
            connection.execute("INSERT INTO ${q(loose)} VALUES (1, 'x'), (1, 'y'), (2, 'z')")
            // A NOT NULL unique index is as good as a primary key.
            connection.execute(
                "CREATE TABLE ${q(unique)} (code VARCHAR(10) NOT NULL, label VARCHAR(20), UNIQUE KEY u_code (code)) " +
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            )
            connection.execute("INSERT INTO ${q(unique)} VALUES ('é1', 'one'), ('b2', 'two')")
            connection.execute(
                "CREATE TABLE ${q(orders)} (id INT PRIMARY KEY, person_id INT NOT NULL, total INT NOT NULL) ENGINE=InnoDB",
            )
            connection.execute("INSERT INTO ${q(orders)} VALUES (1, 1, 10), (2, 2, 20)")
            connection.execute(
                "CREATE TABLE ${q(generated)} (id INT PRIMARY KEY, qty INT NOT NULL, double_qty INT AS (qty * 2) VIRTUAL) ENGINE=InnoDB",
            )
            connection.execute("INSERT INTO ${q(generated)} (id, qty) VALUES (1, 4)")
        }
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) {
            runCatching {
                session.use { connection ->
                    listOf(people, loose, unique, orders, generated).forEach {
                        connection.execute("DROP TABLE IF EXISTS ${q(it)}")
                    }
                }
            }
            session.close()
        }
    }

    @Test
    fun `an editable SELECT changes one cell of one row and nothing else`() {
        val before = rowsOf(people)
        val target = confirmed("SELECT * FROM ${q(people)} ORDER BY id")

        val affected = editCell("SELECT * FROM ${q(people)} ORDER BY id", target, rowIndex = 1, columnName = "name", newValue = "Béla Bartók")

        assertEquals(1, affected)
        val after = rowsOf(people)
        assertEquals("Béla Bartók", after.getValue("2").getValue("name"))
        // Every other cell of every row is what it was.
        assertEquals(before.getValue("1"), after.getValue("1"))
        assertEquals(before.getValue("3"), after.getValue("3"))
        assertEquals(before.getValue("2") - "name", after.getValue("2") - "name")
    }

    @Test
    fun `the key may be anywhere in the select list and columns may be renamed`() {
        val sql = "SELECT name AS who, email, id FROM ${q(people)} ORDER BY id"
        val target = confirmed(sql)
        assertEquals(listOf<String?>("name", "email", "id"), target.columns)
        assertEquals(mapOf("id" to 2), target.key)

        editCell(sql, target, rowIndex = 2, columnName = "who", newValue = "Cecil")
        assertEquals("Cecil", rowsOf(people).getValue("3").getValue("name"))
    }

    @Test
    fun `NULL can be written and replaced, and quotes and non-latin text survive`() {
        val sql = "SELECT id, name, bio FROM ${q(people)} ORDER BY id"
        val target = confirmed(sql)

        editCell(sql, target, rowIndex = 0, columnName = "name", newValue = null)
        assertNull(rowsOf(people).getValue("1").getValue("name"))

        // From NULL to a value: the guard has to say "is NULL", not "= NULL".
        editCell(sql, target, rowIndex = 2, columnName = "name", newValue = "it's \"quoted\" \\ 100% _ ő 😀")
        assertEquals("it's \"quoted\" \\ 100% _ ő 😀", rowsOf(people).getValue("3").getValue("name"))
    }

    @Test
    fun `a table with a not null unique index is editable by it`() {
        // A WHERE rather than an ORDER BY: the unique index decides the order the rows come in.
        val sql = "SELECT * FROM ${q(unique)} WHERE code = 'é1'"
        val target = confirmed(sql)
        assertEquals(mapOf("code" to 0), target.key)

        editCell(sql, target, rowIndex = 0, columnName = "label", newValue = "uno", keyValues = mapOf("code" to "é1"))
        val labels = session.use { connection ->
            scalarMap(connection, "SELECT code, label FROM ${q(unique)}")
        }
        assertEquals(mapOf("é1" to "uno", "b2" to "two"), labels)
    }

    @Test
    fun `a qualified table name is edited where it says`() {
        val sql = "SELECT id, name FROM ${q(config.database)}.${q(people)}"
        val target = confirmed(sql)
        assertEquals(config.database, target.database)
        editCell(sql, target, rowIndex = 0, columnName = "name", newValue = "qualified")
        assertEquals("qualified", rowsOf(people).getValue("1").getValue("name"))
    }

    @Test
    fun `a generated column is shown but not writable`() {
        val sql = "SELECT * FROM ${q(generated)}"
        val target = confirmed(sql)
        assertEquals(listOf<String?>("id", "qty", null), target.columns)
    }

    @Test
    fun `results that cannot be mapped onto one row of one table are refused with a reason`() {
        // The text analysis alone: nothing about these needs the server.
        mapOf(
            "SELECT p.name, o.total FROM ${q(people)} p JOIN ${q(orders)} o ON o.person_id = p.id" to NotEditableReason.JOIN,
            "SELECT person_id, SUM(total) FROM ${q(orders)} GROUP BY person_id" to NotEditableReason.GROUP_BY,
            "SELECT DISTINCT name FROM ${q(people)}" to NotEditableReason.DISTINCT,
            "SELECT id FROM ${q(people)} UNION SELECT id FROM ${q(orders)}" to NotEditableReason.UNION,
            "WITH t AS (SELECT * FROM ${q(people)}) SELECT * FROM t" to NotEditableReason.CTE,
            "SELECT id, UPPER(name) FROM ${q(people)}" to NotEditableReason.EXPRESSION,
            "SELECT COUNT(*) FROM ${q(people)}" to NotEditableReason.AGGREGATE,
            "SELECT 1" to NotEditableReason.NO_TABLE,
            "UPDATE ${q(people)} SET name = 'x'" to NotEditableReason.NOT_SELECT,
        ).forEach { (sql, reason) ->
            assertEquals(sql, ResultEditability.NotEditable(reason), ResultEditabilities.analyse(sql))
        }
    }

    @Test
    fun `the schema decides the rest, and refuses what the text could not`() {
        // No key, a key the select list leaves out, a name the table does not have, and a result
        // whose labels are not the columns the statement promises.
        assertRefused("SELECT * FROM ${q(loose)}", NotEditableReason.NO_KEY)
        assertRefused("SELECT name, email FROM ${q(people)}", NotEditableReason.KEY_NOT_SELECTED)
        assertRefused("SELECT id, nope FROM ${q(people)}", NotEditableReason.UNKNOWN_COLUMN, resultLabels = listOf("id", "nope"))
        assertRefused(
            "SELECT id, name FROM ${q(people)}",
            NotEditableReason.COLUMN_MISMATCH,
            resultLabels = listOf("id", "something else"),
        )
    }

    @Test
    fun `a table that does not exist has no structure to confirm against`() {
        val structure = runBlocking { schema.structure(config.database, "sqlpulse_it_nothing_$stamp") }
        val editable = ResultEditabilities.analyse("SELECT * FROM ${q("sqlpulse_it_nothing_$stamp")}")
            as ResultEditability.Editable
        assertEquals(
            ResultEditability.NotEditable(NotEditableReason.UNKNOWN_TABLE),
            ResultEditabilities.confirm(editable, config.database, listOf("id"), structure),
        )
    }

    @Test
    fun `an edit whose key matches several rows is refused and rolled back`() {
        // Not reachable through a confirmed result, which always has a key; this is the net under
        // it, for a key that is wrong or a table that changed shape underneath the screen.
        val before = session.use { scalarMap(it, "SELECT label, grp FROM ${q(loose)}") }
        // The guard names the value being replaced, so it is the key that has to be the ambiguous
        // part: both rows of group 1 match, and both would be changed.
        val ambiguous = editor.prepareUpdate(config.database, loose, mapOf("grp" to "1"), "grp", "1", "9")
        try {
            runBlocking { editor.execute(ambiguous) }
            fail("two rows matched and the edit went through")
        } catch (expected: UnexpectedRowCountException) {
            assertEquals(2, expected.affected)
        }
        // Nothing of either attempt is left behind.
        assertEquals(before.toSortedMap(), session.use { scalarMap(it, "SELECT label, grp FROM ${q(loose)}") }.toSortedMap())
    }

    @Test
    fun `an edit of a row somebody else changed reports it instead of overwriting`() {
        val sql = "SELECT * FROM ${q(people)} ORDER BY id"
        val target = confirmed(sql)
        val shown = resultRows(sql)
        val edit = prepare(target, shown, rowIndex = 1, columnName = "name", newValue = "mine")

        session.use { it.execute("UPDATE ${q(people)} SET name = 'theirs' WHERE id = 2") }

        try {
            runBlocking { editor.execute(edit) }
            fail("the edit overwrote a value it had not seen")
        } catch (e: RowChangedException) {
            assertEquals("theirs", e.currentValue)
            assertTrue(e.rowExists)
        }
        assertEquals("theirs", rowsOf(people).getValue("2").getValue("name"))

        // And the way out, once the user has been told what they would overwrite.
        assertEquals(1, runBlocking { editor.overwrite(edit) })
        assertEquals("mine", rowsOf(people).getValue("2").getValue("name"))
    }

    @Test
    fun `an edit of a row that was deleted says the row is gone`() {
        val sql = "SELECT * FROM ${q(people)} ORDER BY id"
        val edit = prepare(confirmed(sql), resultRows(sql), rowIndex = 0, columnName = "name", newValue = "late")
        session.use { it.execute("DELETE FROM ${q(people)} WHERE id = 1") }
        try {
            runBlocking { editor.execute(edit) }
            fail("an edit of a missing row went through")
        } catch (e: RowChangedException) {
            assertEquals(false, e.rowExists)
        }
    }

    @Test
    fun `the undo takes the edit back, and a delete removes exactly the row it names`() {
        val sql = "SELECT * FROM ${q(people)} ORDER BY id"
        val target = confirmed(sql)
        val edit = prepare(target, resultRows(sql), rowIndex = 1, columnName = "bio", newValue = "rewritten")
        runBlocking { editor.execute(edit) }
        assertEquals("rewritten", rowsOf(people).getValue("2").getValue("bio"))

        runBlocking { editor.execute(edit.undo!!) }
        assertEquals("first line", rowsOf(people).getValue("2").getValue("bio"))

        val delete = editor.prepareDelete(target.database, target.table, mapOf("id" to "3"))
        assertEquals(1, runBlocking { editor.execute(delete) })
        assertEquals(setOf("1", "2"), rowsOf(people).keys)
    }

    // ------------------------------------------------------------------------------------------

    /** The verdict for [sql] against the live structure, which must be that the result is editable. */
    private fun confirmed(sql: String): ResultEditTarget {
        val verdict = verdict(sql, resultLabels = null)
        assertTrue("expected an editable result for: $sql, got $verdict", verdict is ResultEditability.Confirmed)
        return (verdict as ResultEditability.Confirmed).target
    }

    private fun assertRefused(sql: String, reason: NotEditableReason, resultLabels: List<String>? = null) {
        assertEquals(sql, ResultEditability.NotEditable(reason), verdict(sql, resultLabels))
    }

    /** What the query screen would decide; [resultLabels] defaults to what the server really returns. */
    private fun verdict(sql: String, resultLabels: List<String>?): ResultEditability {
        val editable = ResultEditabilities.analyse(sql) as ResultEditability.Editable
        val database = editable.database ?: config.database
        val structure = runBlocking { schema.structure(database, editable.table) }
        val labels = resultLabels ?: resultLabels(sql)
        return ResultEditabilities.confirm(editable, config.database, labels, structure)
    }

    private fun resultLabels(sql: String): List<String> = session.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                (1..rows.metaData.columnCount).map { rows.metaData.getColumnLabel(it) }
            }
        }
    }

    /** The rows as the grid holds them: text, NULL as null. */
    private fun resultRows(sql: String): List<List<String?>> = session.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) })
                }
            }
        }
    }

    /** Prepares an edit of one shown cell the way `QueryResultEditing.prepareCellEdit` does. */
    private fun prepare(
        target: ResultEditTarget,
        shown: List<List<String?>>,
        rowIndex: Int,
        columnName: String,
        newValue: String?,
        columnIndex: Int? = null,
    ) = run {
        val position = columnIndex ?: target.columns.indexOfFirst { it.equals(columnName, ignoreCase = true) }
        require(position >= 0) { "no column $columnName in ${target.columns}" }
        editor.prepareUpdate(
            database = target.database,
            table = target.table,
            key = target.key.mapValues { (_, at) -> shown[rowIndex][at] },
            column = target.columns[position]!!,
            oldValue = shown[rowIndex][position],
            newValue = newValue,
        )
    }

    /**
     * Edits a cell of the result of [sql]. [columnName] is the *result's* label for it, which for
     * a renamed column is not the table's.
     */
    private fun editCell(
        sql: String,
        target: ResultEditTarget,
        rowIndex: Int,
        columnName: String,
        newValue: String?,
        keyValues: Map<String, String?>? = null,
    ): Int {
        val shown = resultRows(sql)
        val labels = resultLabels(sql)
        val position = labels.indexOfFirst { it.equals(columnName, ignoreCase = true) }
        val edit = prepare(target, shown, rowIndex, columnName, newValue, columnIndex = position)
        if (keyValues != null) {
            // The test names the key it expects the grid to have read, so a wrong key position in
            // the target fails here rather than editing some other row quietly.
            assertEquals(keyValues, target.key.mapValues { (_, at) -> shown[rowIndex][at] })
        }
        return runBlocking { editor.execute(edit) }
    }

    /** The people table as text, by id, then by column. */
    private fun rowsOf(table: String): Map<String, Map<String, String?>> = session.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM ${q(table)} ORDER BY 1").use { rows ->
                val meta = rows.metaData
                buildMap {
                    while (rows.next()) {
                        put(
                            rows.getString(1),
                            (1..meta.columnCount).associate { meta.getColumnLabel(it) to rows.getString(it) },
                        )
                    }
                }
            }
        }
    }

    private fun scalarMap(connection: Connection, sql: String): Map<String, String?> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
            }
        }

    private fun q(name: String) = quoteIdentifier(name)

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}
