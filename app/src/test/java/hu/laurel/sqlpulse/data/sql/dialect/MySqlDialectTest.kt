package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.ColumnMatch
import hu.laurel.sqlpulse.data.schema.RowFilter
import hu.laurel.sqlpulse.data.schema.RowLinks
import hu.laurel.sqlpulse.data.sql.ColumnFilter
import hu.laurel.sqlpulse.data.sql.ColumnSort
import hu.laurel.sqlpulse.data.sql.Expected
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.ResultEditabilities
import hu.laurel.sqlpulse.data.sql.RowSqlBuilder
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.TableQuery
import hu.laurel.sqlpulse.data.sql.WriteImpact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * MySqlDialect must say exactly what the app said before engines existed.
 *
 * Two kinds of check: every statement-level answer is compared with the object that used to give
 * it (SqlGuards, WriteImpact, ResultEditabilities) over a corpus of awkward statements, and every
 * piece of SQL the builders write is pinned to the literal text MySQL has always been sent. The
 * builders' own test classes (RowSqlBuilderTest, RowLinksTest, …) still run through their MySQL
 * defaults; this pins that passing the dialect explicitly changes nothing.
 */
class MySqlDialectTest {

    private val dialect = MySqlDialect

    private val corpus = listOf(
        "SELECT * FROM t",
        "select a from `limit_log` where note = 'delete me'",
        "SELECT 1; DELETE FROM t",
        "  (SELECT * FROM t) ",
        "SELECT * FROM t LIMIT 5",
        "SELECT * FROM t LIMIT :n",
        "SELECT * FROM t -- LIMIT 5\n",
        "SELECT '#not a comment' # a comment\nFROM t",
        "SELECT 'it''s', \"dq\", `bt``x`, 'back\\'slash' FROM t",
        "WITH c AS (SELECT 1) SELECT * FROM c",
        "WITH c AS (SELECT 1) UPDATE t SET a = 1",
        "WITH RECURSIVE `x y` AS (SELECT 1) DELETE FROM t WHERE id IN (SELECT * FROM `x y`)",
        "UPDATE t SET a = 1",
        "UPDATE t SET a = 1 WHERE id = 2",
        "UPDATE t SET a = a + 1, b = 'x' WHERE id = :id",
        "UPDATE t SET a = SLEEP(1) WHERE id = 2",
        "DELETE FROM `db`.`t` WHERE id = 2",
        "DELETE FROM t",
        "DELETE FROM t WHERE id = 1 LIMIT 1",
        "INSERT INTO t (a) VALUES (1)",
        "REPLACE INTO t VALUES (1)",
        "SHOW TABLES",
        "DESC t",
        "EXPLAIN SELECT 1",
        "CREATE TABLE x (a int)",
        "USE shop",
        "use `odd name`;",
        "SELECT a, b AS c FROM t WHERE x = 1",
        "SELECT t.* FROM t JOIN u ON u.id = t.u",
        "SELECT COUNT(*) FROM t",
        "SELECT * FROM t WHERE time = '12:30' AND x = :x AND y::text = 'a'",
        "/* lead */ SELECT 1",
        "",
    )

    @Test
    fun `statement answers are SqlGuards' answers`() {
        for (sql in corpus) {
            assertEquals(sql, SqlGuards.classify(sql), dialect.classify(sql))
            assertEquals(sql, SqlGuards.isUnguardedWrite(sql), dialect.isUnguardedWrite(sql))
            assertEquals(sql, SqlGuards.useTarget(sql), dialect.namespaceSwitch(sql))
            assertEquals(sql, SqlGuards.bindParameters(sql), dialect.bindParameters(sql))
            assertEquals(sql, SqlGuards.parameters(sql), dialect.parameters(sql))
            for (limit in listOf(1, 500)) {
                assertEquals(sql, SqlGuards.applyDefaultLimit(sql, limit), dialect.applyDefaultLimit(sql, limit))
            }
        }
    }

    @Test
    fun `write impact and editability answers are the old objects' answers`() {
        for (sql in corpus) {
            assertEquals(sql, WriteImpact.countQuery(sql), dialect.writeCountQuery(sql))
            assertEquals(sql, WriteImpact.previewQuery(sql, 20), dialect.writePreviewQuery(sql, 20))
            assertEquals(sql, ResultEditabilities.analyse(sql), dialect.resultEditability(sql))
        }
    }

    @Test
    fun `the default limit is still appended as LIMIT`() {
        val limited = dialect.applyDefaultLimit("SELECT * FROM t;", 500)
        assertEquals("SELECT * FROM t LIMIT 500", limited.sql)
        assertTrue(limited.limitAdded)
    }

    @Test
    fun `names and literals are written the way MySQL reads them`() {
        assertEquals("`a``b`", dialect.quoteIdentifier("a`b"))
        assertEquals("`shop`.`order items`", dialect.qualify("shop", "order items"))
        assertEquals("'it''s a \\\\ path'", dialect.stringLiteral("it's a \\ path"))
        assertEquals("`c` <=> ?", dialect.nullSafeEquals("`c`"))
        assertEquals("", dialect.likeEscape)
        assertEquals("LENGTH(`b`), SUBSTRING(`b`, 1, 4096)", dialect.blobLengthAndHead("`b`", 4096))
        assertEquals("EXPLAIN FORMAT=JSON SELECT 1", dialect.explain("SELECT 1"))
    }

    @Test
    fun `paging is LIMIT and OFFSET`() {
        assertEquals("SELECT * FROM t LIMIT 100 OFFSET 200", dialect.limit("SELECT * FROM t", 100, 200))
        assertEquals("SELECT * FROM t LIMIT 20", dialect.limit("SELECT * FROM t", 20))
    }

    /** The exact statements the row editor has always sent, built through the dialect. */
    @Test
    fun `row statements are byte for byte what they were`() {
        val key = linkedMapOf<String, String?>("id" to "7")
        assertEquals(
            "UPDATE `shop`.`orders` SET `note` = ? WHERE `id` = ? AND `note` <=> ?",
            RowSqlBuilder.update("shop", "orders", key, "note", "new", Expected.of("old"), dialect).sql,
        )
        assertEquals(
            RowSqlBuilder.update("shop", "orders", key, "note", "new", Expected.of("old")),
            RowSqlBuilder.update("shop", "orders", key, "note", "new", Expected.of("old"), dialect),
        )
        assertEquals(
            "DELETE FROM `shop`.`orders` WHERE `id` = ?",
            RowSqlBuilder.delete("shop", "orders", key, dialect).sql,
        )
        assertEquals(
            "INSERT INTO `shop`.`orders` (`a`, `b`) VALUES (?, ?)",
            RowSqlBuilder.insert("shop", "orders", linkedMapOf("a" to "1", "b" to null), dialect).sql,
        )
        assertEquals(
            "SELECT `note` FROM `shop`.`orders` WHERE `id` = ?",
            RowSqlBuilder.selectValue("shop", "orders", key, "note", dialect).sql,
        )
        val insert = RowSqlBuilder.insert("s", "t", linkedMapOf("a" to "it's", "b" to null))
        assertEquals("INSERT INTO `s`.`t` (`a`, `b`) VALUES ('it''s', NULL)", RowSqlBuilder.render(insert, dialect))
    }

    @Test
    fun `table page fragments are byte for byte what they were`() {
        val filter = ColumnFilter("name", "50%", also = listOf("id" to "3"))
        assertEquals(" WHERE `name` LIKE ? AND `id` = ?", TableQuery.where(filter, dialect))
        assertEquals(TableQuery.where(filter), TableQuery.where(filter, dialect))
        assertEquals(" ORDER BY `name` DESC", TableQuery.orderBy(ColumnSort("name", true), dialect))
        val rows = RowFilter(listOf(ColumnMatch("parent_id", "4")))
        assertEquals(
            "SELECT * FROM `shop`.`items` WHERE `parent_id` = ? LIMIT 20",
            RowLinks.selectRows("shop", "items", rows, 20, dialect).sql,
        )
        assertEquals(
            "SELECT COUNT(*) FROM `shop`.`items` WHERE `parent_id` = ?",
            RowLinks.countRows("shop", "items", rows, dialect).sql,
        )
    }

    @Test
    fun `MySQL has every feature and is connectable`() {
        assertTrue(dialect.connectable)
        assertEquals(EngineFeature.entries.toSet(), dialect.features)
        assertSame(MySqlCatalog, dialect.catalog)
        assertEquals(setOf("information_schema", "mysql", "performance_schema", "sys"), dialect.systemNamespaces)
        assertTrue(dialect.connector(JdbcConfig("h", 1, "d", "u", null, true)) is MySqlConnector)
    }

    @Test
    fun `a saved connection without a database starts with none`() {
        // MySQL's initial namespace is the saved one and never costs a round trip, so the
        // connection is not even touched (a null here would throw if it were).
        val noConnection = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(java.sql.Connection::class.java),
        ) { _, method, _ -> fail("touched the connection: ${method.name}"); null } as java.sql.Connection
        assertEquals("shop", dialect.initialNamespace(noConnection, "shop"))
        assertEquals(null, dialect.initialNamespace(noConnection, "  "))
    }

    @Test
    fun `the registry hands MySQL its dialect and an unfinished engine its stub`() {
        assertSame(MySqlDialect, SqlDialects.forEngine(DatabaseEngine.MYSQL))
        // The finished engines have their own tests; only the remaining stubs must refuse.
        for (engine in DatabaseEngine.entries - DatabaseEngine.MYSQL - DatabaseEngine.POSTGRESQL - DatabaseEngine.SQLSERVER) {
            val stub = SqlDialects.forEngine(engine)
            assertEquals(engine, stub.engine)
            // An engine whose phase-2 work has landed has its own test; only what is still a stub
            // is held to the stub's contract.
            if (stub !is UnsupportedDialect) continue
            assertFalse("$engine is not finished", stub.connectable)
            assertTrue(stub.features.isEmpty())
            val refused = runCatching { stub.connector(JdbcConfig("h", 1, "d", "u", null, true, engine = engine)) }
            assertTrue("$engine must refuse to connect", refused.exceptionOrNull() is EngineNotSupportedException)
        }
    }

    @Test
    fun `engine names are stable and unknown ones are MySQL`() {
        // Stored in the connection table and in backups: renaming one would orphan saved rows.
        assertEquals(listOf("MYSQL", "POSTGRESQL", "SQLSERVER", "SQLITE"), DatabaseEngine.entries.map { it.name })
        assertEquals(DatabaseEngine.MYSQL, DatabaseEngine.fromName(null))
        assertEquals(DatabaseEngine.MYSQL, DatabaseEngine.fromName("ORACLE"))
        assertEquals(DatabaseEngine.SQLITE, DatabaseEngine.fromName("SQLITE"))
        assertEquals(3306, DatabaseEngine.MYSQL.defaultPort)
        assertEquals(5432, DatabaseEngine.POSTGRESQL.defaultPort)
        assertEquals(1433, DatabaseEngine.SQLSERVER.defaultPort)
        assertFalse(DatabaseEngine.SQLITE.hasServer)
    }
}
