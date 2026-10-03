package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.sql.Expected
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.RowSqlBuilder
import hu.laurel.sqlpulse.data.sql.SqlFailureKind
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.StatementKind
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What SqliteDialect says about SQL text, pinned literally (the MySqlDialectTest of this engine).
 * What the real driver does with that text is in `integration/sqlite/`.
 */
class SqliteDialectTest {

    private val dialect = SqliteDialect

    @Test
    fun `the engine is registered and connectable with a feature list that stays honest`() {
        assertSame(dialect, SqlDialects.forEngine(DatabaseEngine.SQLITE))
        assertEquals(DatabaseEngine.SQLITE, dialect.engine)
        assertTrue(dialect.connectable)
        // Not listed because they do not work for a file: server screens, plan tree, and the three
        // that still read MySQL's catalog or analysis directly.
        for (missing in listOf(
            EngineFeature.SERVER_ACTIVITY, EngineFeature.REPLICATION, EngineFeature.SLOW_QUERIES,
            EngineFeature.PULSE, EngineFeature.STORAGE, EngineFeature.EVENTS, EngineFeature.ROUTINES,
            EngineFeature.DATABASE_SEARCH,
            EngineFeature.SCHEMA_DIFF,
        )) {
            assertFalse("$missing must not be offered", dialect.supports(missing))
        }
        for (present in listOf(
            EngineFeature.ROW_EDITING, EngineFeature.CSV_IMPORT, EngineFeature.TABLE_DDL,
            EngineFeature.ROW_LINKS, EngineFeature.SCHEMA_MAP, EngineFeature.TRIGGERS,
            EngineFeature.WRITE_PREVIEW, EngineFeature.EXPLAIN, EngineFeature.EDITABLE_RESULTS,
        )) {
            assertTrue("$present should be offered", dialect.supports(present))
        }
    }

    // ------------------------------------------------------------ text

    @Test
    fun `names are double quoted and a quote is doubled`() {
        assertEquals("\"customer\"", dialect.quoteIdentifier("customer"))
        assertEquals("\"we\"\"ird\"", dialect.quoteIdentifier("we\"ird"))
        assertEquals("\"main\".\"my table\"", dialect.qualify("main", "my table"))
    }

    @Test
    fun `a literal doubles its quote and leaves the backslash alone`() {
        assertEquals("'it''s'", dialect.stringLiteral("it's"))
        assertEquals("'a\\b'", dialect.stringLiteral("a\\b"))
    }

    @Test
    fun `null-safe equality, LIKE escape, paging and the blob preview`() {
        assertEquals("\"email\" IS ?", dialect.nullSafeEquals("\"email\""))
        assertEquals(" ESCAPE '\\'", dialect.likeEscape)
        assertEquals("SELECT * FROM t LIMIT 10", dialect.limit("SELECT * FROM t", 10))
        assertEquals("SELECT * FROM t LIMIT 10 OFFSET 20", dialect.limit("SELECT * FROM t", 10, 20))
        assertEquals("length(\"p\"), substr(\"p\", 1, 64)", dialect.blobLengthAndHead("\"p\"", 64))
    }

    @Test
    fun `the statements the row editor builds are the plain ANSI ones`() {
        val update = RowSqlBuilder.update(
            "main", "customer", mapOf("id" to "1"), "name", "Ada", Expected.of("old"), dialect,
        )
        assertEquals(
            "UPDATE \"main\".\"customer\" SET \"name\" = ? WHERE \"id\" = ? AND \"name\" IS ?",
            update.sql,
        )
        assertEquals(listOf<String?>("Ada", "1", "old"), update.parameters)
        assertEquals(
            "DELETE FROM \"main\".\"line\" WHERE \"order_id\" = ? AND \"n\" = ?",
            RowSqlBuilder.delete("main", "line", mapOf("order_id" to "1", "n" to "2"), dialect).sql,
        )
        assertEquals(
            "INSERT INTO \"main\".\"tag\" (\"label\") VALUES (?)",
            RowSqlBuilder.insert("main", "tag", mapOf("label" to "x"), dialect).sql,
        )
    }

    @Test
    fun `EXPLAIN is the plan statement`() {
        assertEquals("EXPLAIN QUERY PLAN SELECT 1", dialect.explain("SELECT 1"))
    }

    @Test
    fun `there is no namespace to switch and the namespace is main`() {
        assertNull(dialect.namespaceSwitch("USE other"))
        assertEquals(setOf("temp"), dialect.systemNamespaces)
        assertEquals(listOf("main"), listOf(SqliteCatalog.MAIN))
    }

    // ------------------------------------------------------------ grammar

    @Test
    fun `all three quote styles hide their contents from the scanners`() {
        val stripped = SqlGuards.strip("SELECT \"delete\", `drop`, [update], 'insert' FROM t", dialect.grammar)
        assertFalse(stripped.contains("delete"))
        assertFalse(stripped.contains("drop"))
        assertFalse(stripped.contains("update"))
        assertFalse(stripped.contains("insert"))
        assertEquals(StatementKind.READ, dialect.classify("SELECT [delete me] FROM `update`"))
    }

    @Test
    fun `a backslash is an ordinary character in a string and a hash is not a comment`() {
        // In MySQL the backslash would escape the quote and swallow the rest; here the string ends.
        assertEquals(StatementKind.WRITE, dialect.classify("UPDATE t SET a = 'x\\' WHERE id = 1"))
        assertEquals(listOf("id"), dialect.parameters("SELECT '#' , a # b\nFROM t WHERE id = :id"))
    }

    @Test
    fun `statements are classified`() {
        assertEquals(StatementKind.READ, dialect.classify("select 1"))
        assertEquals(StatementKind.READ, dialect.classify("VALUES (1), (2)"))
        assertEquals(StatementKind.READ, dialect.classify("EXPLAIN QUERY PLAN SELECT 1"))
        assertEquals(StatementKind.READ, dialect.classify("WITH c AS (SELECT 1) SELECT * FROM c"))
        assertEquals(StatementKind.WRITE, dialect.classify("WITH c AS (SELECT 1) DELETE FROM t"))
        for (write in listOf("INSERT INTO t VALUES (1)", "UPDATE t SET a = 1", "DELETE FROM t", "REPLACE INTO t VALUES (1)")) {
            assertEquals(write, StatementKind.WRITE, dialect.classify(write))
        }
        for (other in listOf("CREATE TABLE t (a)", "DROP TABLE t", "ALTER TABLE t ADD b", "VACUUM", "ATTACH 'x' AS y", "REINDEX", "ANALYZE")) {
            assertEquals(other, StatementKind.OTHER, dialect.classify(other))
        }
    }

    @Test
    fun `a pragma is a read only when it asks and does not set`() {
        for (read in listOf(
            "PRAGMA table_info(customer)", "pragma table_info('customer')", "PRAGMA main.table_xinfo(t)",
            "PRAGMA index_list(t)", "PRAGMA foreign_key_list(t)", "PRAGMA foreign_keys", "PRAGMA user_version",
            "PRAGMA database_list;", "PRAGMA integrity_check", "PRAGMA quick_check(5)", "PRAGMA journal_mode",
            "PRAGMA compile_options", "-- note\nPRAGMA page_count",
        )) {
            assertEquals(read, StatementKind.READ, dialect.classify(read))
        }
        for (set in listOf(
            "PRAGMA user_version = 5", "PRAGMA user_version(5)", "PRAGMA journal_mode = DELETE",
            "PRAGMA journal_mode(WAL)", "PRAGMA writable_schema = 1", "PRAGMA foreign_keys = OFF",
            "PRAGMA writable_schema", "PRAGMA schema_version = 1", "PRAGMA secure_delete",
            "PRAGMA table_info(t) = 1",
        )) {
            assertEquals(set, StatementKind.OTHER, dialect.classify(set))
        }
    }

    @Test
    fun `a limit is added to a plain read and nowhere else`() {
        assertEquals("SELECT * FROM t LIMIT 500", dialect.applyDefaultLimit("SELECT * FROM t;", 500).sql)
        assertTrue(dialect.applyDefaultLimit("SELECT * FROM t", 500).limitAdded)
        assertFalse(dialect.applyDefaultLimit("SELECT * FROM t LIMIT 3", 500).limitAdded)
        assertFalse(dialect.applyDefaultLimit("PRAGMA table_info(t)", 500).limitAdded)
        assertFalse(dialect.applyDefaultLimit("DELETE FROM t", 500).limitAdded)
        assertFalse(dialect.applyDefaultLimit("EXPLAIN QUERY PLAN SELECT * FROM t", 500).limitAdded)
    }

    @Test
    fun `an unguarded write is found through the quote styles`() {
        assertTrue(dialect.isUnguardedWrite("DELETE FROM [t]"))
        assertTrue(dialect.isUnguardedWrite("UPDATE \"t\" SET a = 'where'"))
        assertFalse(dialect.isUnguardedWrite("UPDATE t SET a = 1 WHERE id = 1"))
        assertFalse(dialect.isUnguardedWrite("INSERT INTO t VALUES (1)"))
    }

    @Test
    fun `the count query follows MySQL's for the plain forms and refuses SQLite's extras`() {
        assertEquals(
            "SELECT COUNT(*) FROM \"t\" WHERE a = 1",
            dialect.writeCountQuery("DELETE FROM \"t\" WHERE a = 1"),
        )
        assertEquals("SELECT COUNT(*) FROM t", dialect.writeCountQuery("UPDATE t SET a = 1"))
        for (refused in listOf(
            "UPDATE OR IGNORE t SET a = 1 WHERE b = 2",
            "UPDATE t SET a = x.a FROM x WHERE t.id = x.id",
            "UPDATE t SET a = 1 WHERE id = 1 RETURNING a",
            "DELETE FROM t WHERE id = 1 RETURNING *",
            "DELETE FROM t INDEXED BY idx WHERE a = 1",
        )) {
            assertNull(refused, dialect.writeCountQuery(refused))
            assertNull(refused, dialect.writePreviewQuery(refused, 20))
        }
        // A FROM inside a subquery is not UPDATE ... FROM.
        assertEquals(
            "SELECT COUNT(*) FROM t WHERE id IN (SELECT id FROM u)",
            dialect.writeCountQuery("UPDATE t SET a = 1 WHERE id IN (SELECT id FROM u)"),
        )
    }

    @Test
    fun `a plain select maps onto its table and the other quote styles are names too`() {
        val editable = dialect.resultEditability("SELECT id, [name] AS who FROM main.`customer`")
        assertTrue(editable.toString(), editable is hu.laurel.sqlpulse.data.sql.ResultEditability.Editable)
        editable as hu.laurel.sqlpulse.data.sql.ResultEditability.Editable
        assertEquals("main", editable.database)
        assertEquals("customer", editable.table)
        assertEquals(
            hu.laurel.sqlpulse.data.sql.ResultEditability.NotEditable(hu.laurel.sqlpulse.data.sql.NotEditableReason.EXPRESSION),
            dialect.resultEditability("SELECT id, upper(name) FROM t"),
        )
    }

    // ------------------------------------------------------------ errors

    private fun failure(code: Int, message: String) = dialect.failureOf(SQLException(message, null, code))

    @Test
    fun `result codes decide the explanation`() {
        assertEquals(SqlFailureKind.LOCK, failure(5, "[SQLITE_BUSY] The database file is locked (database is locked)").kind)
        assertEquals(SqlFailureKind.LOCK, failure(6, "[SQLITE_LOCKED] database table is locked").kind)
        assertEquals(SqlFailureKind.SERVER_BUSY_OR_READ_ONLY, failure(8, "[SQLITE_READONLY] attempt to write a readonly database").kind)
        assertEquals(SqlFailureKind.TIMEOUT, failure(9, "[SQLITE_INTERRUPT] interrupted").kind)
        assertEquals(SqlFailureKind.UNKNOWN_DATABASE, failure(14, "[SQLITE_CANTOPEN] unable to open database file").kind)
        assertEquals(SqlFailureKind.PRIVILEGE, failure(23, "not authorized").kind)
    }

    @Test
    fun `extended constraint codes and messages find the duplicate`() {
        assertEquals(SqlFailureKind.DUPLICATE_KEY, failure(2067, "UNIQUE constraint failed: customer.email").kind)
        assertEquals(SqlFailureKind.DUPLICATE_KEY, failure(1555, "UNIQUE constraint failed: customer.id").kind)
        // Without the extended code the message still says it.
        assertEquals(SqlFailureKind.DUPLICATE_KEY, failure(19, "[SQLITE_CONSTRAINT] UNIQUE constraint failed: t.a").kind)
        assertEquals(SqlFailureKind.OTHER, failure(787, "FOREIGN KEY constraint failed").kind)
        assertEquals(SqlFailureKind.OTHER, failure(1299, "NOT NULL constraint failed: t.a").kind)
    }

    @Test
    fun `SQLITE_ERROR is split by what it says`() {
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, failure(1, "[SQLITE_ERROR] SQL error or missing database (no such table: x)").kind)
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, failure(1, "no such column: y").kind)
        assertEquals(SqlFailureKind.SYNTAX, failure(1, "[SQLITE_ERROR] SQL error or missing database (near \"SELEC\": syntax error)").kind)
        assertEquals(SqlFailureKind.SYNTAX, failure(1, "incomplete input").kind)
        assertEquals(SqlFailureKind.OTHER, failure(1, "something else entirely").kind)
        assertEquals(SqlFailureKind.OTHER, failure(26, "file is not a database").kind)
    }

    // ------------------------------------------------------------ catalog helpers

    @Test
    fun `a default is unquoted only when it is a string literal`() {
        assertEquals("n/a", SqliteCatalog.unquoteDefault("'n/a'"))
        assertEquals("it's", SqliteCatalog.unquoteDefault("'it''s'"))
        assertEquals("0", SqliteCatalog.unquoteDefault("0"))
        assertEquals("CURRENT_TIMESTAMP", SqliteCatalog.unquoteDefault("CURRENT_TIMESTAMP"))
        assertEquals("(1 + 2)", SqliteCatalog.unquoteDefault("(1 + 2)"))
        assertNull(SqliteCatalog.unquoteDefault(null))
    }

    @Test
    fun `a trigger's timing and event are read off its text`() {
        assertEquals("AFTER" to "INSERT", SqliteCatalog.triggerShape("CREATE TRIGGER t AFTER INSERT ON x BEGIN SELECT 1; END"))
        assertEquals("BEFORE" to "UPDATE", SqliteCatalog.triggerShape("create trigger t before update of a on x begin select 1; end"))
        assertEquals("INSTEAD OF" to "DELETE", SqliteCatalog.triggerShape("CREATE TRIGGER t INSTEAD   OF DELETE ON v BEGIN SELECT 1; END"))
        // No timing written: BEFORE, as SQLite defines it.
        assertEquals("BEFORE" to "DELETE", SqliteCatalog.triggerShape("CREATE TRIGGER t DELETE ON x BEGIN SELECT 1; END"))
    }

    @Test
    fun `the connector refuses a config with no file`() {
        val config = JdbcConfig("", 0, "", "", null, readOnly = true, engine = DatabaseEngine.SQLITE)
        val error = runCatching { dialect.connector(config).open() }.exceptionOrNull() as SQLException
        assertEquals(SqlFailureKind.UNKNOWN_DATABASE, dialect.failureOf(error).kind)
    }
}
