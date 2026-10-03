package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.ColumnMatch
import hu.laurel.sqlpulse.data.schema.RowFilter
import hu.laurel.sqlpulse.data.schema.RowLinks
import hu.laurel.sqlpulse.data.sql.ColumnFilter
import hu.laurel.sqlpulse.data.sql.ColumnSort
import hu.laurel.sqlpulse.data.sql.Expected
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.NotEditableReason
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.RowSqlBuilder
import hu.laurel.sqlpulse.data.sql.SelectItem
import hu.laurel.sqlpulse.data.sql.SqlFailureKind
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.TableQuery
import hu.laurel.sqlpulse.data.sql.WriteImpact
import hu.laurel.sqlpulse.data.sql.WriteKind
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PostgreSQL's dialect, modelled on [MySqlDialectTest]: every piece of SQL text the app builds
 * for this engine is pinned literally, and the grammar is held against the tricky cases of this
 * engine (dollar quotes, `::` casts, `"identifiers"`, `#` as an operator).
 */
class PostgresDialectTest {

    private val d = PostgresDialect

    // ------------------------------------------------------------------ names, literals, paging

    @Test
    fun `names and literals are written the way PostgreSQL reads them`() {
        assertEquals("\"a\"\"b\"", d.quoteIdentifier("a\"b"))
        assertEquals("\"Shop\".\"Order Items\"", d.qualify("Shop", "Order Items"))
        assertEquals("'it''s a \\ path'", d.stringLiteral("it's a \\ path"))
        assertEquals("\"c\" IS NOT DISTINCT FROM ?", d.nullSafeEquals("\"c\""))
        assertEquals("", d.likeEscape)
        assertEquals("CAST(\"c\" AS text)", d.likeOperand("\"c\""))
        assertEquals("octet_length(\"b\"), substring(\"b\" from 1 for 4096)", d.blobLengthAndHead("\"b\"", 4096))
        assertEquals("EXPLAIN (FORMAT JSON) SELECT 1", d.explain("SELECT 1"))
    }

    @Test
    fun `paging is LIMIT and OFFSET`() {
        assertEquals("SELECT * FROM t LIMIT 100 OFFSET 200", d.limit("SELECT * FROM t", 100, 200))
        assertEquals("SELECT * FROM t LIMIT 20", d.limit("SELECT * FROM t", 20))
        assertEquals("SELECT * FROM t ORDER BY a LIMIT 20", d.limit("SELECT * FROM t ORDER BY a", 20, ordered = true))
    }

    // ------------------------------------------------------------------ the grammar

    @Test
    fun `a backslash is an ordinary character and hash is an operator`() {
        assertEquals(StatementKind.READ, d.classify("SELECT 'C:\\' FROM t"))
        // `#` is bitwise XOR: nothing after it is a comment, so the DELETE stays visible.
        assertEquals(StatementKind.WRITE, d.classify("DELETE FROM t WHERE a = 1 # 2"))
        assertEquals("SELECT 1 # 2 FROM t", SqlGuards.strip("SELECT 1 # 2 FROM t", d.grammar))
    }

    @Test
    fun `dollar quoted bodies and quoted identifiers hide their contents`() {
        val sql = "SELECT \$\$ DELETE FROM t; ' \$\$, \$fn\$ x \$fn\$, \"delete me\" FROM t WHERE id = \$1"
        assertEquals(StatementKind.READ, d.classify(sql))
        assertEquals("SELECT  ,  ,   FROM t WHERE id = \$1", SqlGuards.strip(sql, d.grammar))
    }

    @Test
    fun `read statements include SHOW and TABLE, writes include MERGE, the rest is out of scope`() {
        assertEquals(StatementKind.READ, d.classify("SHOW search_path"))
        assertEquals(StatementKind.READ, d.classify("TABLE items"))
        assertEquals(StatementKind.READ, d.classify("VALUES (1), (2)"))
        assertEquals(StatementKind.READ, d.classify("  (SELECT 1) UNION (SELECT 2)"))
        assertEquals(StatementKind.WRITE, d.classify("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DELETE"))
        assertEquals(StatementKind.OTHER, d.classify("CREATE TABLE x (a int)"))
        assertEquals(StatementKind.OTHER, d.classify("COPY t FROM '/etc/passwd'"))
        assertEquals(StatementKind.OTHER, d.classify("DO \$\$ BEGIN DELETE FROM t; END \$\$"))
        assertEquals(StatementKind.OTHER, d.classify("CALL purge()"))
        assertEquals(StatementKind.OTHER, d.classify(""))
    }

    @Test
    fun `a data-modifying CTE is a write whatever its last statement is`() {
        assertEquals(StatementKind.WRITE, d.classify("WITH gone AS (DELETE FROM t RETURNING *) SELECT * FROM gone"))
        assertEquals(StatementKind.WRITE, d.classify("WITH c AS (SELECT 1) UPDATE t SET a = 1"))
        assertEquals(StatementKind.READ, d.classify("WITH c AS (SELECT 1) SELECT * FROM c"))
    }

    @Test
    fun `SELECT INTO creates a table and is not a read`() {
        assertEquals(StatementKind.OTHER, d.classify("SELECT * INTO copy FROM t"))
        assertEquals(StatementKind.OTHER, d.classify("select a, b into temp x from t where c = 1"))
        // The word inside a string, an identifier or a comment is nothing.
        assertEquals(StatementKind.READ, d.classify("SELECT 'into', \"into\" FROM t -- into"))
        assertEquals(StatementKind.WRITE, d.classify("INSERT INTO t SELECT * FROM u"))
    }

    @Test
    fun `EXPLAIN ANALYZE runs the statement, so an explained write is a write`() {
        assertEquals(StatementKind.READ, d.classify("EXPLAIN SELECT 1"))
        assertEquals(StatementKind.READ, d.classify("EXPLAIN DELETE FROM t"))
        assertEquals(StatementKind.READ, d.classify("EXPLAIN (FORMAT JSON) DELETE FROM t"))
        assertEquals(StatementKind.READ, d.classify("EXPLAIN ANALYZE SELECT 1"))
        assertEquals(StatementKind.WRITE, d.classify("EXPLAIN ANALYZE DELETE FROM t WHERE id = 1"))
        assertEquals(StatementKind.WRITE, d.classify("explain analyse update t set a = 1 where id = 1"))
        assertEquals(StatementKind.WRITE, d.classify("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) INSERT INTO t VALUES (1)"))
        assertEquals(StatementKind.WRITE, d.classify("EXPLAIN (ANALYZE true) WITH x AS (SELECT 1) DELETE FROM t"))
        assertEquals(StatementKind.WRITE, d.classify("EXPLAIN ANALYZE VERBOSE DELETE FROM t WHERE id = 1"))
        // The text of the explained statement is what matters, not a word inside a literal.
        assertEquals(StatementKind.READ, d.classify("EXPLAIN ANALYZE SELECT 'delete from t'"))
    }

    @Test
    fun `a write without a WHERE is caught, explained or not, and RETURNING is not a guard`() {
        assertTrue(d.isUnguardedWrite("UPDATE t SET a = 1"))
        assertTrue(d.isUnguardedWrite("DELETE FROM t RETURNING *"))
        assertTrue(d.isUnguardedWrite("EXPLAIN ANALYZE DELETE FROM t"))
        assertTrue(d.isUnguardedWrite("DELETE FROM t USING u"))
        assertFalse(d.isUnguardedWrite("EXPLAIN DELETE FROM t"))
        assertFalse(d.isUnguardedWrite("DELETE FROM t WHERE id = 1"))
        assertFalse(d.isUnguardedWrite("UPDATE t SET a = 'where' WHERE id = 1"))
        assertTrue(d.isUnguardedWrite("UPDATE t SET a = 'where'"))
        assertFalse(d.isUnguardedWrite("INSERT INTO t VALUES (1)"))
        assertFalse(d.isUnguardedWrite("WITH c AS (SELECT 1 WHERE true) UPDATE t SET a = 1 WHERE id = 2"))
        assertTrue(d.isUnguardedWrite("WITH c AS (SELECT 1 WHERE true) UPDATE t SET a = 1"))
    }

    // ------------------------------------------------------------------ the default limit

    @Test
    fun `the default limit is appended as LIMIT`() {
        val limited = d.applyDefaultLimit("SELECT * FROM t;", 500)
        assertEquals("SELECT * FROM t LIMIT 500", limited.sql)
        assertTrue(limited.limitAdded)
        assertEquals("WITH c AS (SELECT 1) SELECT * FROM c LIMIT 500", d.applyDefaultLimit("WITH c AS (SELECT 1) SELECT * FROM c", 500).sql)
        assertEquals("SELECT * FROM t ORDER BY a OFFSET 5 LIMIT 500", d.applyDefaultLimit("SELECT * FROM t ORDER BY a OFFSET 5", 500).sql)
    }

    @Test
    fun `a statement that limits itself is left alone, in every spelling`() {
        for (sql in listOf(
            "SELECT * FROM t LIMIT 5",
            "SELECT * FROM t LIMIT ALL",
            "SELECT * FROM t LIMIT :n",
            "SELECT * FROM t ORDER BY a FETCH FIRST 5 ROWS ONLY",
            "select * from t offset 10 rows fetch next 5 rows only",
            "SELECT * FROM (SELECT * FROM u LIMIT 5) x",
        )) {
            val result = d.applyDefaultLimit(sql, 500)
            assertEquals(sql, sql, result.sql)
            assertFalse(sql, result.limitAdded)
        }
    }

    @Test
    fun `only reads that return rows are limited`() {
        assertFalse(d.applyDefaultLimit("SHOW search_path", 500).limitAdded)
        assertFalse(d.applyDefaultLimit("EXPLAIN SELECT 1", 500).limitAdded)
        assertFalse(d.applyDefaultLimit("VALUES (1)", 500).limitAdded)
        assertFalse(d.applyDefaultLimit("DELETE FROM t WHERE id = 1", 500).limitAdded)
        assertEquals("SELECT 'limit 5' FROM t LIMIT 500", d.applyDefaultLimit("SELECT 'limit 5' FROM t", 500).sql)
    }

    @Test
    fun `a trailing line comment does not swallow the added LIMIT`() {
        assertEquals("SELECT 1 -- one\nLIMIT 500", d.applyDefaultLimit("SELECT 1 -- one", 500).sql)
        assertEquals("SELECT 1\n-- one\nLIMIT 500", d.applyDefaultLimit("SELECT 1\n-- one", 500).sql)
    }

    // ------------------------------------------------------------------ the namespace

    @Test
    fun `SET search_path with one name moves the session`() {
        assertEquals("shop", d.namespaceSwitch("SET search_path TO shop"))
        assertEquals("shop", d.namespaceSwitch("set search_path = shop;"))
        assertEquals("shop", d.namespaceSwitch("SET SESSION search_path TO shop"))
        assertEquals("shop", d.namespaceSwitch("SET SCHEMA 'shop'"))
        assertEquals("Odd \"name\"", d.namespaceSwitch("SET search_path TO \"Odd \"\"name\"\"\""))
        // A bare name is folded to lower case, as the server does.
        assertEquals("shop", d.namespaceSwitch("SET search_path TO SHOP"))
    }

    @Test
    fun `anything else about search_path is sent as it is`() {
        assertNull(d.namespaceSwitch("SET search_path TO a, b"))
        assertNull(d.namespaceSwitch("SET search_path TO DEFAULT"))
        assertNull(d.namespaceSwitch("SET LOCAL search_path TO a"))
        assertNull(d.namespaceSwitch("SHOW search_path"))
        assertNull(d.namespaceSwitch("USE shop"))
        assertNull(d.namespaceSwitch("SELECT 1"))
    }

    // ------------------------------------------------------------------ parameters

    @Test
    fun `placeholders are found outside quotes, casts and dollar strings`() {
        val sql = "SELECT :a::text, ':b', \$\$:c\$\$, \"x:d\", time '12:30', :e FROM t WHERE x = :a -- :f"
        assertEquals(listOf("a", "e"), d.parameters(sql))
        val bound = d.bindParameters(sql)
        assertEquals(
            "SELECT ?::text, ':b', \$\$:c\$\$, \"x:d\", time '12:30', ? FROM t WHERE x = ? -- :f",
            bound.sql,
        )
        assertEquals(listOf("a", "e", "a"), bound.parameterOrder)
    }

    // ------------------------------------------------------------------ the statements the app builds

    @Test
    fun `row statements are key based, with no LIMIT, and the optimistic check is NULL-safe`() {
        val key = linkedMapOf<String, String?>("id" to "7")
        assertEquals(
            "UPDATE \"shop\".\"orders\" SET \"note\" = ? WHERE \"id\" = ? AND \"note\" IS NOT DISTINCT FROM ?",
            RowSqlBuilder.update("shop", "orders", key, "note", "new", Expected.of("old"), d).sql,
        )
        assertEquals(
            "UPDATE \"shop\".\"orders\" SET \"Weird \"\"col\"\"\" = ? WHERE \"id\" = ?",
            RowSqlBuilder.update("shop", "orders", key, "Weird \"col\"", "new", syntax = d).sql,
        )
        assertEquals(
            "DELETE FROM \"shop\".\"orders\" WHERE \"id\" = ? AND \"k2\" = ?",
            RowSqlBuilder.delete("shop", "orders", linkedMapOf("id" to "7", "k2" to "x"), d).sql,
        )
        assertEquals(
            "INSERT INTO \"shop\".\"orders\" (\"a\", \"b\") VALUES (?, ?)",
            RowSqlBuilder.insert("shop", "orders", linkedMapOf("a" to "1", "b" to null), d).sql,
        )
        assertEquals(
            "SELECT \"note\" FROM \"shop\".\"orders\" WHERE \"id\" = ?",
            RowSqlBuilder.selectValue("shop", "orders", key, "note", d).sql,
        )
        val insert = RowSqlBuilder.insert("s", "t", linkedMapOf("a" to "it's \\", "b" to null), d)
        assertEquals("INSERT INTO \"s\".\"t\" (\"a\", \"b\") VALUES ('it''s \\', NULL)", RowSqlBuilder.render(insert, d))
    }

    @Test
    fun `table page fragments cast the column for a contains filter`() {
        val filter = ColumnFilter("name", "50%", also = listOf("id" to "3"))
        assertEquals(" WHERE CAST(\"name\" AS text) LIKE ? AND \"id\" = ?", TableQuery.where(filter, d))
        assertEquals(" WHERE \"name\" = ?", TableQuery.where(ColumnFilter("name", "x", exact = true), d))
        assertEquals(" ORDER BY \"name\" DESC", TableQuery.orderBy(ColumnSort("name", true), d))
        // The pattern's % and _ are escaped with a backslash, which is LIKE's default escape here.
        assertEquals(listOf("%50\\%%", "3"), TableQuery.whereParameters(filter))
        val rows = RowFilter(listOf(ColumnMatch("parent_id", "4")))
        assertEquals(
            "SELECT * FROM \"shop\".\"items\" WHERE \"parent_id\" = ? LIMIT 20",
            RowLinks.selectRows("shop", "items", rows, 20, d).sql,
        )
        assertEquals(
            "SELECT COUNT(*) FROM \"shop\".\"items\" WHERE \"parent_id\" = ?",
            RowLinks.countRows("shop", "items", rows, d).sql,
        )
    }

    // ------------------------------------------------------------------ the DML preview

    @Test
    fun `the count query keeps the table and the WHERE and drops what is output`() {
        assertEquals("SELECT COUNT(*) FROM \"s\".\"t\" WHERE id = 1", d.writeCountQuery("DELETE FROM \"s\".\"t\" WHERE id = 1"))
        assertEquals("SELECT COUNT(*) FROM t", d.writeCountQuery("DELETE FROM t"))
        assertEquals("SELECT COUNT(*) FROM t WHERE a > 1", d.writeCountQuery("UPDATE t SET a = 0 WHERE a > 1 RETURNING id"))
        assertEquals("SELECT COUNT(*) FROM t i WHERE i.a > 1", d.writeCountQuery("UPDATE t i SET a = 0 WHERE i.a > 1"))
        assertEquals("SELECT COUNT(*) FROM t WHERE a = '#x' AND b = \$\$;\$\$", d.writeCountQuery("DELETE FROM t WHERE a = '#x' AND b = \$\$;\$\$"))
        // `#` is an operator here: it does not start a comment that would swallow the WHERE's tail.
        assertEquals("SELECT COUNT(*) FROM t WHERE (a # 1) = 2", d.writeCountQuery("DELETE FROM t WHERE (a # 1) = 2"))
        assertEquals("SELECT COUNT(*) FROM t", d.writeCountQuery("DELETE FROM t RETURNING *"))
        // The MySQL default is untouched by all of this.
        assertEquals("SELECT COUNT(*) FROM t WHERE a = 1", WriteImpact.countQuery("DELETE FROM t WHERE a = 1 # a comment in MySQL"))
    }

    @Test
    fun `what cannot be counted honestly is refused`() {
        for (sql in listOf(
            "UPDATE t SET a = 1 FROM u WHERE u.id = t.id",
            "DELETE FROM ONLY t WHERE a = 1",
            "DELETE FROM t USING u WHERE u.id = t.id",
            "WITH x AS (SELECT 1) DELETE FROM t",
            "INSERT INTO t VALUES (1)",
            "MERGE INTO t USING s ON true WHEN MATCHED THEN DELETE",
            "DELETE FROM t WHERE a IN (SELECT 1 FOR UPDATE)",
            "DELETE FROM t WHERE a IN (SELECT 1 FOR NO KEY UPDATE)",
            "DELETE FROM t WHERE a IN (SELECT 1 FOR KEY SHARE)",
            "DELETE FROM t WHERE (SELECT pg_sleep(1)) IS NULL",
            "DELETE FROM t WHERE (SELECT pg_advisory_lock(1)) IS NULL",
            "DELETE FROM t WHERE (SELECT pg_try_advisory_xact_lock(1))",
            "DELETE FROM t WHERE x = nextval('s')",
            "DELETE FROM t WHERE x = set_config('a', 'b', false) ",
            "DELETE FROM t WHERE a := 1",
            "DELETE FROM t WHERE id = 1; DELETE FROM t",
        )) {
            assertNull(sql, d.writeCountQuery(sql))
            assertNull(sql, d.writePreviewQuery(sql, 20))
        }
    }

    @Test
    fun `the preview of an update adds the new values, quoted the PostgreSQL way`() {
        val preview = d.writePreviewQuery("UPDATE \"s\".\"t\" SET qty = qty * 2, \"Note\" = upper(name) WHERE id = 1", 20)!!
        assertEquals(WriteKind.UPDATE, preview.kind)
        assertEquals(listOf("qty", "Note"), preview.changedColumns)
        assertEquals(
            "SELECT *, (qty * 2) AS \"qty (new)\", (upper(name)) AS \"Note (new)\" FROM \"s\".\"t\" WHERE id = 1 LIMIT 20",
            preview.sql,
        )
        // A bare column name is folded to lower case, which is the column the server will use.
        assertEquals(listOf("qty"), d.writePreviewQuery("UPDATE t SET QTY = 1 WHERE id = 1", 5)!!.changedColumns)
    }

    @Test
    fun `the preview of a delete is a SELECT of the same rows`() {
        val preview = d.writePreviewQuery("DELETE FROM t WHERE qty > 2 RETURNING *", 5)!!
        assertEquals(WriteKind.DELETE, preview.kind)
        assertEquals("SELECT * FROM t WHERE qty > 2 LIMIT 5", preview.sql)
    }

    @Test
    fun `a SET list that reads the old row differently from the new one gets no preview`() {
        assertNull(d.writePreviewQuery("UPDATE t SET a = 1, a = 2", 20))
        assertNull(d.writePreviewQuery("UPDATE t SET a = 1, b = a + 1", 20))
        assertNull(d.writePreviewQuery("UPDATE t SET \"A\" = 1, \"b\" = \"A\" + 1", 20))
        assertNull(d.writePreviewQuery("UPDATE t SET (a, b) = (1, 2)", 20))
        assertNull(d.writePreviewQuery("UPDATE t SET a[1] = 2", 20))
    }

    // ------------------------------------------------------------------ editable results

    @Test
    fun `a plain select maps onto its table, double quotes are names`() {
        val editable = d.resultEditability("SELECT \"Id\", name AS \"who\" FROM \"shop\".\"Order Items\" o")
            as ResultEditability.Editable
        assertEquals("shop", editable.database)
        assertEquals("Order Items", editable.table)
        assertEquals("o", editable.alias)
        assertEquals(listOf(SelectItem.Column("Id", null), SelectItem.Column("name", "who")), editable.columnMapping)
        assertEquals(SelectItem.Star, (d.resultEditability("SELECT * FROM t") as ResultEditability.Editable).columnMapping.single())
        // `--` comments need no blank after them, and `#` is no comment at all.
        assertTrue(d.resultEditability("SELECT a, b FROM t --keep it simple") is ResultEditability.Editable)
        assertTrue(d.resultEditability("SELECT a # b FROM t") is ResultEditability.NotEditable)
    }

    @Test
    fun `results that are not one table's rows are refused with a reason`() {
        mapOf(
            "SELECT a FROM t JOIN u ON u.id = t.id" to NotEditableReason.JOIN,
            "SELECT a FROM t, u" to NotEditableReason.JOIN,
            "SELECT a, count(*) FROM t GROUP BY a" to NotEditableReason.GROUP_BY,
            "SELECT DISTINCT ON (a) a, b FROM t" to NotEditableReason.DISTINCT,
            "SELECT a FROM t UNION SELECT a FROM u" to NotEditableReason.UNION,
            "WITH c AS (SELECT 1) SELECT * FROM c" to NotEditableReason.CTE,
            "SELECT a::text FROM t" to NotEditableReason.EXPRESSION,
            "SELECT a + 1 FROM t" to NotEditableReason.EXPRESSION,
            "SELECT a, b FROM t WHERE x = 1 FOR UPDATE" to null,
            "SELECT 1" to NotEditableReason.NO_TABLE,
            "UPDATE t SET a = 1" to NotEditableReason.NOT_SELECT,
            "SELECT * FROM (SELECT 1) x" to NotEditableReason.SUBQUERY_IN_FROM,
            "SELECT a FROM t; SELECT b FROM t" to NotEditableReason.MULTIPLE_STATEMENTS,
        ).forEach { (sql, reason) ->
            if (reason == null) {
                assertTrue(sql, d.resultEditability(sql) is ResultEditability.Editable)
            } else {
                assertEquals(sql, ResultEditability.NotEditable(reason), d.resultEditability(sql))
            }
        }
    }

    // ------------------------------------------------------------------ errors

    private fun kind(state: String?, message: String = "boom", cause: Throwable? = null) =
        d.failureOf(SQLException(message, state, cause)).kind

    @Test
    fun `errors are classified by SQLSTATE`() {
        assertEquals(SqlFailureKind.AUTHENTICATION, kind("28P01", "FATAL: password authentication failed for user \"x\""))
        assertEquals(SqlFailureKind.AUTHENTICATION, kind("28000", "FATAL: no pg_hba.conf entry for host"))
        assertEquals(SqlFailureKind.PRIVILEGE, kind("42501", "permission denied for table t"))
        assertEquals(SqlFailureKind.UNKNOWN_DATABASE, kind("3D000", "FATAL: database \"x\" does not exist"))
        assertEquals(SqlFailureKind.UNKNOWN_DATABASE, kind("3F000", "schema \"x\" does not exist"))
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, kind("42P01", "relation \"x\" does not exist"))
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, kind("42703", "column \"x\" does not exist"))
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, kind("42883", "function x() does not exist"))
        assertEquals(SqlFailureKind.SYNTAX, kind("42601", "syntax error at or near \"SELEC\""))
        assertEquals(SqlFailureKind.LOCK, kind("40P01", "deadlock detected"))
        assertEquals(SqlFailureKind.LOCK, kind("55P03", "could not obtain lock on row"))
        assertEquals(SqlFailureKind.DUPLICATE_KEY, kind("23505", "duplicate key value violates unique constraint"))
        assertEquals(SqlFailureKind.TIMEOUT, kind("57014", "canceling statement due to user request"))
        assertEquals(SqlFailureKind.SERVER_BUSY_OR_READ_ONLY, kind("25006", "cannot execute INSERT in a read-only transaction"))
        assertEquals(SqlFailureKind.SERVER_BUSY_OR_READ_ONLY, kind("53300", "sorry, too many clients already"))
        assertEquals(SqlFailureKind.OTHER, kind("22012", "division by zero"))
        assertEquals(SqlFailureKind.OTHER, kind("23503", "violates foreign key constraint"))
    }

    @Test
    fun `a connection that never opened is told apart by its cause`() {
        val refused = java.net.ConnectException("Connection refused")
        assertEquals(SqlFailureKind.UNREACHABLE, kind("08001", "The connection attempt failed.", refused))
        assertEquals(SqlFailureKind.TIMEOUT, kind("08001", "The connection attempt failed.", java.net.SocketTimeoutException("connect timed out")))
        assertEquals(SqlFailureKind.UNREACHABLE, kind("08001", "The connection attempt failed.", java.net.UnknownHostException("nope.invalid")))
        assertEquals(SqlFailureKind.TLS, kind("08004", "The server does not support SSL."))
        assertEquals(
            SqlFailureKind.TLS,
            kind("08001", "The connection attempt failed.", javax.net.ssl.SSLHandshakeException("PKIX path building failed: unable to find valid certification path")),
        )
        assertEquals(SqlFailureKind.CONNECTION_LOST, kind("08006", "An I/O error occurred while sending to the backend."))
        assertEquals(SqlFailureKind.CONNECTION_LOST, kind("08003", "This connection has been closed."))
        // No SQLSTATE at all: the text is all there is.
        assertEquals(SqlFailureKind.UNREACHABLE, kind(null, "Connection refused"))
        assertEquals(SqlFailureKind.OTHER, kind(null, "something unforeseen"))
    }

    @Test
    fun `the failure keeps the server's sentence and the SQLSTATE`() {
        val failure = d.failureOf(SQLException("ERROR: relation \"x\" does not exist", "42P01"))
        assertEquals("ERROR: relation \"x\" does not exist", failure.serverMessage)
        assertEquals("42P01", failure.sqlState)
    }

    // ------------------------------------------------------------------ the connection

    @Test
    fun `the connection URL is built from the host, the port and the database`() {
        fun url(host: String = "db.example.com", database: String = "shop") =
            PostgresConnector.urlFor(JdbcConfig(host, 5432, database, "u", null, false))
        assertEquals("jdbc:postgresql://db.example.com:5432/shop", url())
        assertEquals("jdbc:postgresql://[::1]:5432/shop", url(host = "::1"))
        assertEquals("jdbc:postgresql://[::1]:5432/shop", url(host = "[::1]"))
        assertEquals("jdbc:postgresql://db.example.com:5432/my%20db%2Fx", url(database = "my db/x"))
        // No database named: the server falls back to the user's own, as psql does.
        assertEquals("jdbc:postgresql://db.example.com:5432/", url(database = ""))
    }

    @Test
    fun `the properties carry the timeouts in seconds and keep the driver off Android's missing classes`() {
        val props = PostgresConnector.propertiesFor(
            JdbcConfig("h", 5432, "d", "alice", "s3cret", false, connectTimeoutMs = 10_500, socketTimeoutMs = 30_000),
        )
        assertEquals("alice", props.getProperty("user"))
        assertEquals("s3cret", props.getProperty("password"))
        assertEquals("SQLPulse", props.getProperty("ApplicationName"))
        assertEquals("11", props.getProperty("connectTimeout"))
        assertEquals("11", props.getProperty("loginTimeout"))
        assertEquals("30", props.getProperty("socketTimeout"))
        assertEquals("unspecified", props.getProperty("stringtype"))
        assertEquals("0", props.getProperty("prepareThreshold"))
        assertEquals("disable", props.getProperty("gssEncMode"))
        assertEquals("disable", props.getProperty("sslmode"))
        assertNull(props.getProperty("readOnlyMode"))
        assertNull(PostgresConnector.propertiesFor(JdbcConfig("h", 1, "d", "u", null, false)).getProperty("password"))
    }

    @Test
    fun `a read-only connection puts the whole session in read-only mode`() {
        val props = PostgresConnector.propertiesFor(JdbcConfig("h", 5432, "d", "u", null, readOnly = true))
        assertEquals("always", props.getProperty("readOnlyMode"))
    }

    @Test
    fun `TLS modes map onto sslmode and the CA file onto sslrootcert`() {
        assertEquals(mapOf("sslmode" to "disable"), PostgresConnector.sslProperties(SslMode.DISABLED, null))
        assertEquals(mapOf("sslmode" to "require"), PostgresConnector.sslProperties(SslMode.REQUIRED, "/ca.pem"))
        assertEquals(
            mapOf("sslmode" to "verify-ca", "sslrootcert" to "/ca.pem"),
            PostgresConnector.sslProperties(SslMode.VERIFY_CA, "/ca.pem"),
        )
        assertEquals(
            mapOf("sslmode" to "verify-full", "sslrootcert" to "/ca.pem"),
            PostgresConnector.sslProperties(SslMode.VERIFY_IDENTITY, "/ca.pem"),
        )
        // A verifying mode without its CA is a configuration error, never a silent downgrade.
        val refused = runCatching { PostgresConnector.sslProperties(SslMode.VERIFY_CA, null) }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }

    // ------------------------------------------------------------------ what the engine is

    @Test
    fun `PostgreSQL is connectable and lists only what works`() {
        assertEquals(DatabaseEngine.POSTGRESQL, d.engine)
        assertTrue(d.connectable)
        assertSame(PostgresDialect, SqlDialects.forEngine(DatabaseEngine.POSTGRESQL))
        assertSame(PostgresCatalog, d.catalog)
        assertEquals(setOf("pg_catalog", "information_schema", "pg_toast"), d.systemNamespaces)
        assertTrue(d.connector(JdbcConfig("h", 1, "d", "u", null, true, engine = DatabaseEngine.POSTGRESQL)) is PostgresConnector)
        // Not offered: screens that read MySQL-only things (docs/tobb-motor-terv.md, 5).
        for (feature in listOf(
            EngineFeature.DATABASE_SEARCH, EngineFeature.SCHEMA_DIFF,
            EngineFeature.SERVER_ACTIVITY, EngineFeature.PULSE, EngineFeature.SLOW_QUERIES,
            EngineFeature.REPLICATION, EngineFeature.STORAGE, EngineFeature.EVENTS,
        )) {
            assertFalse("$feature is not implemented for PostgreSQL", d.supports(feature))
        }
        for (feature in listOf(
            EngineFeature.ROW_EDITING, EngineFeature.CSV_IMPORT, EngineFeature.ROW_LINKS, EngineFeature.SCHEMA_MAP,
            EngineFeature.TABLE_DDL, EngineFeature.WRITE_PREVIEW, EngineFeature.EDITABLE_RESULTS, EngineFeature.EXPLAIN,
            EngineFeature.ROUTINES, EngineFeature.TRIGGERS,
        )) {
            assertTrue("$feature works", d.supports(feature))
        }
    }
}
