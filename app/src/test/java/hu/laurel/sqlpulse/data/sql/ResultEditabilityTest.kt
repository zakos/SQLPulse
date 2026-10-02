package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.SchemaIndex
import hu.laurel.sqlpulse.data.schema.TableStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ResultEditabilityTest {

    private inline fun <reified T> assertIs(value: Any?, message: String = ""): T {
        if (value !is T) fail("$message expected ${T::class.simpleName} but was $value")
        return value as T
    }


    private fun editable(sql: String): ResultEditability.Editable =
        assertIs<ResultEditability.Editable>(ResultEditabilities.analyse(sql), sql)

    private fun reason(sql: String): NotEditableReason =
        assertIs<ResultEditability.NotEditable>(ResultEditabilities.analyse(sql), sql).reason

    private fun column(name: String, pk: Boolean = false, nullable: Boolean = !pk, extra: String? = null) =
        SchemaColumn(name, "int", nullable, null, pk, extra, null)

    private val users = TableStructure(
        columns = listOf(column("id", pk = true), column("name"), column("email", nullable = false)),
        indexes = listOf(SchemaIndex("PRIMARY", true, listOf("id")), SchemaIndex("uq_email", true, listOf("email"))),
        foreignKeys = emptyList(),
    )

    // --- Text analysis -------------------------------------------------------------------------

    @Test
    fun `star from one table is editable`() {
        val result = editable("SELECT * FROM users")
        assertEquals("users", result.table)
        assertNull(result.database)
        assertEquals(listOf<SelectItem>(SelectItem.Star), result.columnMapping)
    }

    @Test
    fun `plain columns, aliases, qualified names and backticks`() {
        val result = editable("select u.id, `name` as n, u.email mail from `shop`.`users` u where u.id > 3 order by 1 limit 10;")
        assertEquals("shop", result.database)
        assertEquals("users", result.table)
        assertEquals("u", result.alias)
        assertEquals(
            listOf(
                SelectItem.Column("id", null),
                SelectItem.Column("name", "n"),
                SelectItem.Column("email", "mail"),
            ),
            result.columnMapping,
        )
    }

    @Test
    fun `table alias star and AS alias`() {
        val result = editable("SELECT u.* FROM users AS u")
        assertEquals(listOf<SelectItem>(SelectItem.Star), result.columnMapping)
        assertEquals("u", result.alias)
    }

    @Test
    fun `comments and string literals do not confuse the analysis`() {
        editable("/* head */ SELECT id -- JOIN\n, name FROM users WHERE name = 'a JOIN b; GROUP BY' # tail")
        editable("SELECT id FROM users WHERE note = 'x -- y' AND k = \"UNION\"")
    }

    @Test
    fun `where clause subqueries and select modifiers are fine`() {
        editable("SELECT * FROM users WHERE id IN (SELECT user_id FROM orders GROUP BY user_id HAVING COUNT(*) > 2)")
        editable("SELECT SQL_NO_CACHE * FROM users FOR UPDATE")
        editable("SELECT * FROM users USE INDEX (PRIMARY) WHERE id = 1")
        editable("SELECT * FROM users u FORCE INDEX FOR ORDER BY (idx_name) ORDER BY name")
    }

    @Test
    fun `join in any spelling`() {
        assertEquals(NotEditableReason.JOIN, reason("SELECT u.id FROM users u JOIN orders o ON o.user_id = u.id"))
        assertEquals(NotEditableReason.JOIN, reason("SELECT * FROM users LEFT JOIN orders USING (id)"))
        assertEquals(NotEditableReason.JOIN, reason("SELECT * FROM users, orders"))
        assertEquals(NotEditableReason.JOIN, reason("SELECT * FROM users NATURAL JOIN orders"))
        assertEquals(NotEditableReason.JOIN, reason("SELECT STRAIGHT_JOIN * FROM users"))
    }

    @Test
    fun `derived table`() {
        assertEquals(NotEditableReason.SUBQUERY_IN_FROM, reason("SELECT * FROM (SELECT * FROM users) x"))
    }

    @Test
    fun `grouping, distinct, having, union`() {
        assertEquals(NotEditableReason.DISTINCT, reason("SELECT DISTINCT name FROM users"))
        assertEquals(NotEditableReason.GROUP_BY, reason("SELECT name FROM users GROUP BY name"))
        assertEquals(NotEditableReason.HAVING, reason("SELECT name FROM users HAVING name > 'a'"))
        assertEquals(NotEditableReason.UNION, reason("SELECT id FROM a UNION SELECT id FROM b"))
        assertEquals(NotEditableReason.UNION, reason("SELECT id FROM a UNION ALL SELECT id FROM b"))
    }

    @Test
    fun `aggregates and window functions`() {
        assertEquals(NotEditableReason.AGGREGATE, reason("SELECT COUNT(*) FROM users"))
        assertEquals(NotEditableReason.AGGREGATE, reason("SELECT max(id) FROM users"))
        assertEquals(NotEditableReason.WINDOW, reason("SELECT id, ROW_NUMBER() OVER (ORDER BY id) FROM users"))
        assertEquals(NotEditableReason.WINDOW, reason("SELECT SUM(id) OVER w FROM users WINDOW w AS (ORDER BY id)"))
    }

    @Test
    fun `cte and non selects`() {
        assertEquals(NotEditableReason.CTE, reason("WITH x AS (SELECT 1) SELECT * FROM x"))
        assertEquals(NotEditableReason.NOT_SELECT, reason("SHOW TABLES"))
        assertEquals(NotEditableReason.NOT_SELECT, reason("EXPLAIN SELECT * FROM users"))
        assertEquals(NotEditableReason.NOT_SELECT, reason("UPDATE users SET a = 1"))
        assertEquals(NotEditableReason.NOT_SELECT, reason("SELECT * INTO OUTFILE '/x' FROM users"))
        assertEquals(NotEditableReason.NOT_SELECT, reason(""))
        assertEquals(NotEditableReason.MULTIPLE_STATEMENTS, reason("SELECT 1 FROM a; SELECT 2 FROM b"))
    }

    @Test
    fun `no table`() {
        assertEquals(NotEditableReason.NO_TABLE, reason("SELECT 1"))
    }

    @Test
    fun `expressions in the select list`() {
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT id + 1 FROM users"))
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT UPPER(name) FROM users"))
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT id, 'x' FROM users"))
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT NULL FROM users"))
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT 1 FROM users"))
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT NOT x FROM users"))
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT (SELECT 1) FROM users"))
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT CASE WHEN id > 1 THEN 1 END FROM users"))
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT :p FROM users"))
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT name, id * 2 AS twice FROM users"))
        // A qualifier that names some other table cannot belong to this FROM.
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT o.id FROM users u"))
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT users.id FROM users u"))
    }

    @Test
    fun `structural reasons win over expressions`() {
        assertEquals(NotEditableReason.JOIN, reason("SELECT COUNT(*) FROM a JOIN b ON 1"))
        assertEquals(NotEditableReason.GROUP_BY, reason("SELECT COUNT(*) FROM a GROUP BY x"))
    }

    // --- Schema confirmation -------------------------------------------------------------------

    private fun confirm(sql: String, labels: List<String>, structure: TableStructure = users, db: String? = "shop") =
        ResultEditabilities.confirm(editable(sql), db, labels, structure)

    private fun target(result: ResultEditability) = assertIs<ResultEditability.Confirmed>(result).target

    private fun why(result: ResultEditability) = assertIs<ResultEditability.NotEditable>(result).reason

    @Test
    fun `star expands to the table's columns and the primary key is found`() {
        val target = target(confirm("SELECT * FROM users", listOf("id", "name", "email")))
        assertEquals("shop", target.database)
        assertEquals("users", target.table)
        assertEquals(listOf<String?>("id", "name", "email"), target.columns)
        assertEquals(mapOf("id" to 0), target.key)
    }

    @Test
    fun `aliases map by position and qualified database wins`() {
        val target = target(confirm("SELECT name AS n, id AS ident FROM other.users", listOf("n", "ident"), db = "shop"))
        assertEquals(listOf<String?>("name", "id"), target.columns)
        assertEquals(mapOf("id" to 1), target.key)
    }

    @Test
    fun `qualified database in the statement overrides the session database`() {
        val target = target(confirm("SELECT id FROM archive.users", listOf("id"), db = "shop"))
        assertEquals("archive", target.database)
    }

    @Test
    fun `missing primary key column is not editable`() {
        // email is a NOT NULL unique key, so the result is still editable through it ...
        val viaEmail = target(confirm("SELECT name, email FROM users", listOf("name", "email")))
        assertEquals(mapOf("email" to 1), viaEmail.key)
        // ... but with neither present there is no way to name one row.
        assertEquals(NotEditableReason.KEY_NOT_SELECTED, why(confirm("SELECT name FROM users", listOf("name"))))
    }

    @Test
    fun `nullable unique index is no key`() {
        val structure = TableStructure(
            columns = listOf(column("id", pk = true), column("code")),
            indexes = listOf(SchemaIndex("uq_code", true, listOf("code"))),
            foreignKeys = emptyList(),
        )
        assertEquals(NotEditableReason.KEY_NOT_SELECTED, why(confirm("SELECT code FROM users", listOf("code"), structure)))
    }

    @Test
    fun `composite key must be complete`() {
        val structure = TableStructure(
            columns = listOf(column("a", pk = true), column("b", pk = true), column("v")),
            indexes = emptyList(),
            foreignKeys = emptyList(),
        )
        assertEquals(NotEditableReason.KEY_NOT_SELECTED, why(confirm("SELECT a, v FROM t", listOf("a", "v"), structure)))
        assertEquals(mapOf("a" to 0, "b" to 1), target(confirm("SELECT a, b FROM t", listOf("a", "b"), structure)).key)
    }

    @Test
    fun `table without any key`() {
        val structure = TableStructure(listOf(column("a"), column("b")), emptyList(), emptyList())
        assertEquals(NotEditableReason.NO_KEY, why(confirm("SELECT * FROM t", listOf("a", "b"), structure)))
    }

    @Test
    fun `unknown table and unknown column`() {
        assertEquals(
            NotEditableReason.UNKNOWN_TABLE,
            why(confirm("SELECT * FROM ghost", listOf("id"), TableStructure(emptyList(), emptyList(), emptyList()))),
        )
        assertEquals(NotEditableReason.UNKNOWN_COLUMN, why(confirm("SELECT nope FROM users", listOf("nope"))))
    }

    @Test
    fun `no current database`() {
        assertEquals(NotEditableReason.NO_DATABASE, why(confirm("SELECT * FROM users", listOf("id", "name", "email"), db = null)))
    }

    @Test
    fun `result that does not match the statement is refused`() {
        // The text promises three columns; the server returned two (say, an invisible column).
        assertEquals(NotEditableReason.COLUMN_MISMATCH, why(confirm("SELECT * FROM users", listOf("id", "name"))))
        // Same count, different labels: the analysis misread the statement.
        assertEquals(NotEditableReason.COLUMN_MISMATCH, why(confirm("SELECT id, name FROM users", listOf("id", "other"))))
    }

    @Test
    fun `generated columns are shown but not writable`() {
        val structure = TableStructure(
            columns = listOf(column("id", pk = true), column("total", extra = "STORED GENERATED")),
            indexes = emptyList(),
            foreignKeys = emptyList(),
        )
        val target = target(confirm("SELECT * FROM t", listOf("id", "total"), structure))
        assertEquals(listOf("id", null), target.columns)
    }

    @Test
    fun `duplicate selection of the key uses its first position`() {
        val target = target(confirm("SELECT id, id AS again FROM users", listOf("id", "again")))
        assertEquals(mapOf("id" to 0), target.key)
    }
}
