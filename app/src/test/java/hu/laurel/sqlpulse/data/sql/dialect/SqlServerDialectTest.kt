package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.ColumnMatch
import hu.laurel.sqlpulse.data.schema.RowFilter
import hu.laurel.sqlpulse.data.schema.RowLinks
import hu.laurel.sqlpulse.data.sql.ColumnFilter
import hu.laurel.sqlpulse.data.sql.ColumnSort
import hu.laurel.sqlpulse.data.sql.Expected
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.NotEditableReason
import hu.laurel.sqlpulse.data.sql.QueryExecutor
import hu.laurel.sqlpulse.data.sql.ReadOnlyConnectionException
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.RowSqlBuilder
import hu.laurel.sqlpulse.data.sql.SqlFailureKind
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.TableQuery
import hu.laurel.sqlpulse.data.sql.UnsupportedStatementException
import hu.laurel.sqlpulse.data.sql.WriteKind
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.security.cert.CertificateException
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SqlServerDialect, pinned the way MySqlDialectTest pins MySQL: the SQL it builds word for word,
 * the grammar on T-SQL's awkward corners, and — because the driver ignores `setReadOnly` — the
 * statement guard that is the only read-only protection this engine has.
 */
class SqlServerDialectTest {

    private val dialect = SqlServerDialect

    // ---------------------------------------------------------------- names, literals, paging

    @Test
    fun `names and literals are written the way T-SQL reads them`() {
        assertEquals("[a]]b]", dialect.quoteIdentifier("a]b"))
        assertEquals("[a`\"b]", dialect.quoteIdentifier("a`\"b"))
        assertEquals("[sales].[order items]", dialect.qualify("sales", "order items"))
        // No backslash escape in T-SQL: only the quote is doubled.
        assertEquals("'it''s a \\ path'", dialect.stringLiteral("it's a \\ path"))
        assertEquals("EXISTS (SELECT [c] INTERSECT SELECT ?)", dialect.nullSafeEquals("[c]"))
        assertEquals(" ESCAPE '\\'", dialect.likeEscape)
        assertEquals("DATALENGTH([b]), SUBSTRING([b], 1, 4096)", dialect.blobLengthAndHead("[b]", 4096))
        assertNull("EXPLAIN is not offered", dialect.explain("SELECT 1"))
        assertEquals("a placeholder is exactly one question mark", 1, dialect.nullSafeEquals("[c]").count { it == '?' })
    }

    @Test
    fun `paging is OFFSET FETCH, with a no-op ORDER BY when the select has none`() {
        assertEquals(
            "SELECT * FROM t ORDER BY (SELECT NULL) OFFSET 200 ROWS FETCH NEXT 100 ROWS ONLY",
            dialect.limit("SELECT * FROM t", 100, 200),
        )
        assertEquals(
            "SELECT * FROM t ORDER BY (SELECT NULL) OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY",
            dialect.limit("SELECT * FROM t", 20),
        )
        assertEquals(
            "SELECT * FROM t ORDER BY [a] DESC OFFSET 5 ROWS FETCH NEXT 5 ROWS ONLY",
            dialect.limit("SELECT * FROM t ORDER BY [a] DESC", 5, 5, ordered = true),
        )
    }

    // ---------------------------------------------------------------- grammar

    @Test
    fun `bracketed names, doubled quotes and comments are read as T-SQL reads them`() {
        val grammar = dialect.grammar
        // A closing bracket doubled is part of the name; the statement inside the name is not a statement.
        assertEquals(StatementKind.READ, dialect.classify("SELECT * FROM [we]] DELETE FROM t ] x"))
        assertEquals(StatementKind.READ, dialect.classify("SELECT [delete], [update] FROM [insert]"))
        assertEquals(StatementKind.READ, dialect.classify("SELECT \"delete\" FROM t"))
        assertEquals(StatementKind.READ, dialect.classify("SELECT 'it''s DELETE FROM t'"))
        // Backslash is an ordinary character: the quote after it ends the string.
        assertEquals(StatementKind.WRITE, dialect.classify("SELECT 'a\\' DELETE FROM t --'"))
        // A # is an operator or a temp table prefix, never a comment.
        assertEquals(StatementKind.WRITE, dialect.classify("SELECT 1 # x\nDELETE FROM t"))
        assertEquals(StatementKind.READ, dialect.classify("SELECT * FROM #temp"))
        assertEquals("SELECT   FROM t", SqlGuards.strip("SELECT [a]] b] FROM t", grammar))
        assertEquals(listOf("id"), dialect.parameters("SELECT * FROM t WHERE [a:b] = 'x:y' AND id = :id -- :no"))
        assertEquals(
            "SELECT * FROM t WHERE x = ? AND geography::Point(1, 2, 4326) IS NOT NULL",
            dialect.bindParameters("SELECT * FROM t WHERE x = :x AND geography::Point(1, 2, 4326) IS NOT NULL").sql,
        )
    }

    // ---------------------------------------------------------------- statement classification

    @Test
    fun `reads are reads, including words that merely look like writes`() {
        for (sql in listOf(
            "SELECT * FROM t",
            "select a from [limit_log] where note = 'delete me'",
            "  (SELECT * FROM t) ",
            "WITH c AS (SELECT 1 AS a) SELECT * FROM c",
            "SELECT 1 -- DROP TABLE x",
            "/* DELETE FROM t */ SELECT 1",
            "SELECT inserted_at, updated_by, deleted FROM t",
            "SELECT * FROM t WITH (NOLOCK) WHERE a = 1",
        )) {
            assertEquals(sql, StatementKind.READ, dialect.classify(sql))
        }
    }

    @Test
    fun `writes are writes, even when they hide behind a read, a CTE or a missing semicolon`() {
        for (sql in listOf(
            "INSERT INTO t (a) VALUES (1)",
            "INSERT INTO t SELECT * FROM u",
            "UPDATE t SET a = 1 WHERE id = 2",
            "DELETE FROM t WHERE id = 2",
            "MERGE t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = 1;",
            "WITH c AS (SELECT 1 AS a) DELETE FROM t WHERE id IN (SELECT a FROM c)",
            "WITH c AS (SELECT 1 AS a) UPDATE t SET a = 1",
            // T-SQL needs no terminator: this is two statements.
            "SELECT 1 DELETE FROM t",
            "SELECT 1; UPDATE t SET a = 1",
            "(SELECT 1) DELETE FROM t",
            "SELECT 1\nINSERT INTO t VALUES (1)",
        )) {
            assertEquals(sql, StatementKind.WRITE, dialect.classify(sql))
        }
    }

    @Test
    fun `everything else is refused, SELECT INTO and dynamic SQL included`() {
        for (sql in listOf(
            "",
            "   ",
            "USE other",
            "SET NOCOUNT ON",
            "DECLARE @x INT = 1",
            "BEGIN TRAN",
            "COMMIT",
            "EXEC sp_who",
            "EXECUTE ('DELETE FROM t')",
            "SELECT 1; EXEC('DELETE FROM t')",
            "SELECT * INTO copy_of_t FROM t",
            "WITH c AS (SELECT 1 AS a) SELECT a INTO x FROM c",
            "CREATE TABLE x (a INT)",
            "ALTER TABLE t ADD b INT",
            "DROP TABLE t",
            "SELECT 1 DROP TABLE t",
            "TRUNCATE TABLE t",
            "GRANT SELECT ON t TO u",
            "SELECT 1 SHUTDOWN",
            "SELECT * FROM OPENROWSET('SQLNCLI', 'x', 'DELETE FROM t')",
            "SELECT * FROM OPENQUERY(linked, 'DELETE FROM t')",
            "WAITFOR DELAY '00:00:05'",
            "BACKUP DATABASE d TO DISK = 'x'",
            "KILL 55",
            "DBCC CHECKDB",
        )) {
            assertEquals(sql, StatementKind.OTHER, dialect.classify(sql))
        }
    }

    @Test
    fun `an unguarded UPDATE or DELETE is one without a top level WHERE, statement by statement`() {
        for (sql in listOf(
            "UPDATE t SET a = 1",
            "DELETE FROM t",
            "DELETE TOP (5) FROM t",
            "UPDATE t SET a = (SELECT 1 WHERE 1 = 1)",
            "WITH c AS (SELECT 1 AS a WHERE 1 = 1) DELETE FROM t",
            "SELECT 1 DELETE FROM t",
            "DELETE FROM t WHERE x = 1; DELETE FROM u",
            "UPDATE t SET a = 1; DELETE FROM t WHERE a = 1",
        )) {
            assertTrue(sql, dialect.isUnguardedWrite(sql))
        }
        for (sql in listOf(
            "UPDATE t SET a = 1 WHERE id = 2",
            "DELETE FROM t WHERE id = 1",
            "DELETE FROM t WHERE note = 'no where here' AND id = 1",
            "INSERT INTO t VALUES (1)",
            "SELECT * FROM t",
            "SELECT 1; SELECT 2",
            "",
        )) {
            assertFalse(sql, dialect.isUnguardedWrite(sql))
        }
    }

    // ---------------------------------------------------------------- the read-only guard

    /**
     * mssql-jdbc ignores `setReadOnly`, so for a read-only SQL Server connection the executor's
     * guard is the protection. Every statement that is, or hides, a write must stop there — before
     * a connection is even borrowed (the session manager here would throw on one).
     */
    @Test
    fun `a read-only SQL Server connection never sends a write or anything unsupported`() {
        val sessions = mockk<SqlSessionManager>()
        every { sessions.dialect() } returns dialect
        val executor = QueryExecutor(
            sessions, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            Dispatchers.Unconfined,
        )
        val writes = listOf(
            "INSERT INTO t VALUES (1)",
            "UPDATE t SET a = 1 WHERE id = 1",
            "DELETE FROM t WHERE id = 1",
            "MERGE t USING s ON t.id = s.id WHEN MATCHED THEN DELETE;",
            "WITH c AS (SELECT 1 AS a) DELETE FROM t",
            "SELECT 1 DELETE FROM t",
            "SELECT 1; UPDATE t SET a = 1 WHERE id = 1",
            "(SELECT 1) INSERT INTO t VALUES (1)",
        )
        for (sql in writes) {
            try {
                runBlocking { executor.run(connectionId = 1, sql = sql, readOnly = true) }
                fail("a read-only connection ran: $sql")
            } catch (e: ReadOnlyConnectionException) {
                // The expected refusal.
            }
        }
        val unsupported = listOf(
            "EXEC sp_executesql N'DELETE FROM t'",
            "SELECT * INTO copy FROM t",
            "SELECT 1 DROP TABLE t",
            "TRUNCATE TABLE t",
            "SELECT * FROM OPENROWSET('SQLNCLI', 'x', 'DELETE FROM t')",
            "USE master",
            "SET IDENTITY_INSERT t ON",
        )
        for (sql in unsupported) {
            try {
                runBlocking { executor.run(connectionId = 1, sql = sql, readOnly = true) }
                fail("a read-only connection ran: $sql")
            } catch (e: UnsupportedStatementException) {
                // Refused as not a query, which is just as final.
            }
        }
    }

    @Test
    fun `the connector hands the read-only flag to a driver that ignores it, and says so`() {
        // The flag still goes to the driver, in case a future version honours it; what this test
        // pins is that the dialect does not claim to be enforcing it: it has no read-only feature.
        assertTrue(dialect.connectable)
        assertTrue(dialect.connector(JdbcConfig("h", 1433, "d", "u", null, true, engine = DatabaseEngine.SQLSERVER)) is SqlServerConnector)
    }

    // ---------------------------------------------------------------- default row limit

    @Test
    fun `the default limit is a TOP on the outermost SELECT`() {
        fun limited(sql: String) = dialect.applyDefaultLimit(sql, 500)
        assertEquals("SELECT TOP (500) * FROM t", limited("SELECT * FROM t;").sql)
        assertTrue(limited("SELECT * FROM t").limitAdded)
        assertEquals("select TOP (500) a from t", limited("select a from t").sql)
        assertEquals("SELECT DISTINCT TOP (500) a FROM t", limited("SELECT DISTINCT a FROM t").sql)
        assertEquals("SELECT ALL TOP (500) a FROM t", limited("SELECT ALL a FROM t").sql)
        assertEquals("SELECT TOP (500) 1", limited("SELECT 1").sql)
        assertEquals("SELECT TOP (500) [top] FROM t ORDER BY [top]", limited("SELECT [top] FROM t ORDER BY [top]").sql)
        assertEquals("SELECT TOP (500) 'x top y' FROM t", limited("SELECT 'x top y' FROM t").sql)
        assertEquals(
            "WITH c AS (SELECT 1 AS a) SELECT TOP (500) a FROM c",
            limited("WITH c AS (SELECT 1 AS a) SELECT a FROM c").sql,
        )
        // A TOP inside a derived table is not the statement's own.
        assertEquals(
            "SELECT TOP (500) * FROM (SELECT TOP 3 a FROM t) x",
            limited("SELECT * FROM (SELECT TOP 3 a FROM t) x").sql,
        )
        assertEquals("SELECT TOP (500) * FROM t FOR XML PATH", limited("SELECT * FROM t FOR XML PATH").sql)
        assertEquals(
            "SELECT TOP (500) * FROM t OPTION (MAXDOP 1)",
            limited("SELECT * FROM t OPTION (MAXDOP 1)").sql,
        )
    }

    @Test
    fun `a statement that already limits itself, or is no plain read, is left alone`() {
        for (sql in listOf(
            "SELECT TOP 5 * FROM t",
            "SELECT TOP (5) PERCENT * FROM t",
            "SELECT DISTINCT TOP (5) a FROM t",
            "SELECT * FROM t ORDER BY a OFFSET 5 ROWS FETCH NEXT 5 ROWS ONLY",
            "SELECT * FROM t ORDER BY a OFFSET 5 ROWS",
            "UPDATE t SET a = 1 WHERE id = 1",
            "EXEC sp_who",
            "SELECT a FROM t; SELECT b FROM u",
            "(SELECT 1) UNION (SELECT 2)",
            "SELECT a FROM t UNION SELECT a FROM u",
        )) {
            val result = dialect.applyDefaultLimit(sql, 500)
            assertFalse(sql, result.limitAdded)
            assertEquals(sql, sql.trim(), result.sql)
        }
    }

    @Test
    fun `a UNION with an ORDER BY is capped as a whole by OFFSET FETCH`() {
        val result = dialect.applyDefaultLimit("SELECT a FROM t UNION SELECT a FROM u ORDER BY a", 500)
        assertEquals("SELECT a FROM t UNION SELECT a FROM u ORDER BY a OFFSET 0 ROWS FETCH NEXT 500 ROWS ONLY", result.sql)
        assertTrue(result.limitAdded)
    }

    // ---------------------------------------------------------------- write impact

    @Test
    fun `the count and the preview are derived from the same table and WHERE`() {
        assertEquals(
            "SELECT COUNT(*) FROM [dbo].[t] WHERE id = 2",
            dialect.writeCountQuery("UPDATE [dbo].[t] SET a = 1 WHERE id = 2"),
        )
        val update = dialect.writePreviewQuery("UPDATE [dbo].[t] SET a = 1, [b c] = a + 1 WHERE id = 2;", 20)!!
        assertEquals(
            "SELECT TOP (20) *, (1) AS [a (new)], (a + 1) AS [b c (new)] FROM [dbo].[t] WHERE id = 2",
            update.sql,
        )
        assertEquals(WriteKind.UPDATE, update.kind)
        assertEquals(listOf("a", "b c"), update.changedColumns)

        assertEquals("SELECT COUNT(*) FROM t", dialect.writeCountQuery("DELETE FROM t"))
        val delete = dialect.writePreviewQuery("DELETE FROM t WHERE note = 'x; y'", 20)!!
        assertEquals("SELECT TOP (20) * FROM t WHERE note = 'x; y'", delete.sql)
        assertEquals(WriteKind.DELETE, delete.kind)
        // All right-hand sides read the old row in T-SQL, so a swap needs no refusal.
        assertNotNull(dialect.writePreviewQuery("UPDATE t SET a = b, b = a WHERE id = 1", 20))
    }

    @Test
    fun `what cannot be counted with confidence is refused`() {
        for (sql in listOf(
            "INSERT INTO t VALUES (1)",
            "SELECT 1",
            "DELETE t FROM t JOIN u ON u.id = t.id",
            "DELETE FROM t FROM t JOIN u ON u.id = t.id",
            "UPDATE t SET a = 1 FROM t JOIN u ON u.id = t.id",
            "UPDATE TOP (5) t SET a = 1",
            "DELETE TOP (5) FROM t",
            "UPDATE t SET a = 1 OUTPUT inserted.a",
            "DELETE FROM t OUTPUT deleted.a",
            "UPDATE t SET a = 1; DELETE FROM u",
            "UPDATE t SET a = (SELECT NEXT VALUE FOR seq)",
            "UPDATE t WITH (ROWLOCK) SET a = 1",
            "DELETE FROM t OPTION (MAXDOP 1)",
            "WITH c AS (SELECT 1 AS a) DELETE FROM t",
            "UPDATE t, u SET a = 1",
            "DELETE FROM a.b.c.d.e",
            "",
        )) {
            assertNull(sql, dialect.writeCountQuery(sql))
            assertNull(sql, dialect.writePreviewQuery(sql, 20))
        }
        // The count can be right where the preview cannot.
        assertNotNull(dialect.writeCountQuery("UPDATE t SET @v = a WHERE id = 1"))
        assertNull(dialect.writePreviewQuery("UPDATE t SET @v = a WHERE id = 1", 20))
        assertNull(dialect.writePreviewQuery("UPDATE t SET a = 1, A = 2", 20))
        assertNull(dialect.writePreviewQuery("UPDATE t SET a += 1", 20))
        assertNull(dialect.writePreviewQuery("DELETE FROM t", 0))
    }

    // ---------------------------------------------------------------- result editability

    @Test
    fun `a single-table select is analysed through the shared reader, brackets and TOP aside`() {
        val editable = dialect.resultEditability("SELECT TOP (10) [id], [na]]me] AS n, \"x\" FROM [sales].[we]]ird] ORDER BY 1")
        assertTrue(editable.toString(), editable is ResultEditability.Editable)
        editable as ResultEditability.Editable
        assertEquals("sales", editable.database)
        assertEquals("we]ird", editable.table)
        assertEquals(3, editable.columnMapping.size)

        val star = dialect.resultEditability("SELECT * FROM dbo.t") as ResultEditability.Editable
        assertEquals("dbo", star.database)
        assertEquals("t", star.table)
    }

    @Test
    fun `what T-SQL can say that the reader would misread is refused`() {
        fun reason(sql: String) = (dialect.resultEditability(sql) as ResultEditability.NotEditable).reason
        assertEquals(NotEditableReason.UNSUPPORTED, reason("SELECT * FROM #temp"))
        assertEquals(NotEditableReason.UNSUPPORTED, reason("SELECT * FROM t FOR XML PATH"))
        assertEquals(NotEditableReason.UNSUPPORTED, reason("SELECT * FROM t OPTION (RECOMPILE)"))
        assertEquals(NotEditableReason.UNSUPPORTED, reason("SELECT * FROM t WITH (NOLOCK)"))
        assertEquals(NotEditableReason.JOIN, reason("SELECT * FROM t JOIN u ON u.id = t.id"))
        assertEquals(NotEditableReason.DISTINCT, reason("SELECT DISTINCT a FROM t"))
        assertEquals(NotEditableReason.GROUP_BY, reason("SELECT a, COUNT(*) FROM t GROUP BY a"))
        assertEquals(NotEditableReason.CTE, reason("WITH c AS (SELECT 1 AS a) SELECT * FROM c"))
        assertEquals(NotEditableReason.NOT_SELECT, reason("UPDATE t SET a = 1"))
        assertEquals(NotEditableReason.EXPRESSION, reason("SELECT id, UPPER(name) FROM t"))
    }

    // ---------------------------------------------------------------- builders through the dialect

    @Test
    fun `row statements are built with brackets and the INTERSECT guard`() {
        val key = linkedMapOf<String, String?>("id" to "7")
        assertEquals(
            "UPDATE [shop].[orders] SET [note] = ? WHERE [id] = ? AND EXISTS (SELECT [note] INTERSECT SELECT ?)",
            RowSqlBuilder.update("shop", "orders", key, "note", "new", Expected.of("old"), dialect).sql,
        )
        assertEquals("DELETE FROM [shop].[orders] WHERE [id] = ?", RowSqlBuilder.delete("shop", "orders", key, dialect).sql)
        assertEquals(
            "INSERT INTO [shop].[orders] ([a], [b]) VALUES (?, ?)",
            RowSqlBuilder.insert("shop", "orders", linkedMapOf("a" to "1", "b" to null), dialect).sql,
        )
        assertEquals(
            "SELECT [note] FROM [shop].[orders] WHERE [id] = ?",
            RowSqlBuilder.selectValue("shop", "orders", key, "note", dialect).sql,
        )
        val insert = RowSqlBuilder.insert("s", "t", linkedMapOf("a" to "it's", "b" to null), dialect)
        assertEquals("INSERT INTO [s].[t] ([a], [b]) VALUES ('it''s', NULL)", RowSqlBuilder.render(insert, dialect))
    }

    @Test
    fun `table pages and key walks are built with brackets and OFFSET FETCH`() {
        val filter = ColumnFilter("name", "50%", also = listOf("id" to "3"))
        assertEquals(" WHERE [name] LIKE ? ESCAPE '\\' AND [id] = ?", TableQuery.where(filter, dialect))
        assertEquals(" ORDER BY [name] DESC", TableQuery.orderBy(ColumnSort("name", true), dialect))
        val rows = RowFilter(listOf(ColumnMatch("parent_id", "4")))
        assertEquals(
            "SELECT * FROM [shop].[items] WHERE [parent_id] = ? ORDER BY (SELECT NULL) OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY",
            RowLinks.selectRows("shop", "items", rows, 20, dialect).sql,
        )
        assertEquals(
            "SELECT COUNT(*) FROM [shop].[items] WHERE [parent_id] = ?",
            RowLinks.countRows("shop", "items", rows, dialect).sql,
        )
    }

    // ---------------------------------------------------------------- scripts

    @Test
    fun `a GO line is recognised so it can be refused instead of sent`() {
        assertTrue(TSql.hasBatchSeparator("SELECT 1\nGO\nSELECT 2"))
        assertTrue(TSql.hasBatchSeparator("SELECT 1\n  go 5\n"))
        assertFalse(TSql.hasBatchSeparator("SELECT 'a\nGO\nb'"))
        assertFalse(TSql.hasBatchSeparator("SELECT 1 -- GO\n"))
        assertFalse(TSql.hasBatchSeparator("SELECT go FROM t"))
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun `server errors are classified by number, not by MySQL's table`() {
        fun kind(code: Int, state: String? = "S0001", message: String = "m") =
            dialect.failureOf(SQLException(message, state, code)).kind
        assertEquals(SqlFailureKind.AUTHENTICATION, kind(18456, message = "Login failed for user 'x'."))
        assertEquals(SqlFailureKind.PRIVILEGE, kind(229))
        assertEquals(SqlFailureKind.PRIVILEGE, kind(230))
        assertEquals(SqlFailureKind.PRIVILEGE, kind(916))
        assertEquals(SqlFailureKind.UNKNOWN_DATABASE, kind(4060))
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, kind(208))
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, kind(207))
        assertEquals(SqlFailureKind.SYNTAX, kind(102))
        assertEquals(SqlFailureKind.SYNTAX, kind(156))
        assertEquals(SqlFailureKind.LOCK, kind(1205))
        assertEquals(SqlFailureKind.LOCK, kind(1222))
        assertEquals(SqlFailureKind.DUPLICATE_KEY, kind(2627))
        assertEquals(SqlFailureKind.DUPLICATE_KEY, kind(2601))
        assertEquals(SqlFailureKind.SERVER_BUSY_OR_READ_ONLY, kind(40501))
        assertEquals(SqlFailureKind.SERVER_BUSY_OR_READ_ONLY, kind(3906))
        // MySQL's 1045 / 1146 mean nothing here.
        assertEquals(SqlFailureKind.OTHER, kind(1045))
        assertEquals(SqlFailureKind.OTHER, kind(1146))
    }

    @Test
    fun `driver errors without a number are told apart by their wording`() {
        fun kind(message: String, state: String? = "08S01") =
            dialect.failureOf(SQLException(message, state, 0)).kind
        assertEquals(
            SqlFailureKind.UNREACHABLE,
            kind("The TCP/IP connection to the host db, port 1433 has failed. Error: \"Connection refused\"."),
        )
        assertEquals(
            SqlFailureKind.TLS,
            kind("The driver could not establish a secure connection to SQL Server by using Secure Sockets Layer (SSL) encryption. Error: PKIX path building failed"),
        )
        assertEquals(SqlFailureKind.TIMEOUT, kind("The query has timed out.", "HY008"))
        assertEquals(SqlFailureKind.AUTHENTICATION, kind("Login failed for user 'sa'."))
    }

    // ---------------------------------------------------------------- catalog helpers

    @Test
    fun `column types are spelled the way T-SQL declares them`() {
        fun t(name: String, length: Int = 0, precision: Int = 0, scale: Int = 0) =
            SqlServerTypes.declared(name, length, precision, scale)
        assertEquals("int", t("int", 4, 10))
        assertEquals("varchar(100)", t("varchar", 100))
        assertEquals("varchar(max)", t("varchar", -1))
        assertEquals("nvarchar(50)", t("nvarchar", 100))
        assertEquals("nvarchar(max)", t("nvarchar", -1))
        assertEquals("nchar(3)", t("nchar", 6))
        assertEquals("varbinary(max)", t("varbinary", -1))
        assertEquals("decimal(10,2)", t("decimal", 9, 10, 2))
        assertEquals("datetime2(7)", t("datetime2", 8, 27, 7))
        assertEquals("datetimeoffset(3)", t("datetimeoffset", 10, 30, 3))
        assertEquals("float", t("float", 8, 53))
        assertEquals("float(24)", t("float", 4, 24))
        assertEquals("money", t("money", 8, 19, 4))
        assertEquals("uniqueidentifier", t("uniqueidentifier", 16))
    }

    @Test
    fun `stored defaults and checks lose only the parentheses that enclose the whole expression`() {
        assertEquals("0", SqlServerTypes.unwrap("((0))"))
        assertEquals("getdate()", SqlServerTypes.unwrap("(getdate())"))
        assertEquals("'n/a'", SqlServerTypes.unwrap("('n/a')"))
        assertEquals("([a])+([b])", SqlServerTypes.unwrap("([a])+([b])"))
        assertEquals("[qty]>=(0)", SqlServerTypes.unwrap("([qty]>=(0))"))
        assertEquals("'(' + x", SqlServerTypes.unwrap("('(' + x)"))
        assertEquals("plain", SqlServerTypes.unwrap("plain"))
    }

    // ---------------------------------------------------------------- engine facts

    @Test
    fun `SQL Server is connectable and lists only what is built and tested`() {
        assertTrue(dialect.connectable)
        assertSame(dialect, SqlDialects.forEngine(DatabaseEngine.SQLSERVER))
        assertSame(SqlServerCatalog, dialect.catalog)
        assertEquals(DatabaseEngine.SQLSERVER, dialect.engine)
        assertEquals(
            setOf(
                EngineFeature.ROW_EDITING, EngineFeature.EDITABLE_RESULTS, EngineFeature.WRITE_PREVIEW,
                EngineFeature.CSV_IMPORT, EngineFeature.ROW_LINKS, EngineFeature.SCHEMA_MAP,
                EngineFeature.ROUTINES, EngineFeature.TRIGGERS,
                EngineFeature.SERVER_ACTIVITY, EngineFeature.REPLICATION, EngineFeature.SLOW_QUERIES, EngineFeature.PULSE,
            ),
            dialect.features,
        )
        assertFalse(dialect.supports(EngineFeature.EXPLAIN))
        assertTrue(dialect.supports(EngineFeature.SERVER_ACTIVITY))
        assertEquals(setOf("sys", "INFORMATION_SCHEMA", "guest"), dialect.systemNamespaces)
        // USE moves one pooled connection and not the others: it is not intercepted, and not a query.
        assertNull(dialect.namespaceSwitch("USE other"))
    }

    // ---------------------------------------------------------------- TLS

    private fun config(mode: SslMode, ca: String? = null, host: String = "db.example.test") =
        JdbcConfig(host, 1433, "d", "u", "p", false, sslMode = mode, caCertificatePath = ca, engine = DatabaseEngine.SQLSERVER)

    @Test
    fun `the TLS mode becomes encrypt and trust properties`() {
        assertEquals(
            mapOf("encrypt" to "false", "trustServerCertificate" to "true"),
            SqlServerConnector.tlsProperties(config(SslMode.DISABLED)),
        )
        assertEquals(
            mapOf("encrypt" to "true", "trustServerCertificate" to "true"),
            SqlServerConnector.tlsProperties(config(SslMode.REQUIRED)),
        )
        val ca = SqlServerConnector.tlsProperties(config(SslMode.VERIFY_CA, "/ca.pem"))
        assertEquals("true", ca["encrypt"])
        assertEquals("false", ca["trustServerCertificate"])
        assertEquals(VerifyingTrustManager::class.java.name, ca["trustManagerClass"])
        assertEquals("ca=/ca.pem", ca["trustManagerConstructorArg"])

        val identity = SqlServerConnector.tlsProperties(config(SslMode.VERIFY_IDENTITY, "/ca.pem"))
        assertEquals("ca=/ca.pem\nhost=db.example.test", identity["trustManagerConstructorArg"])

        // Azure SQL: a public CA, so no file — the phone's own store, but still verified.
        val azure = SqlServerConnector.tlsProperties(config(SslMode.VERIFY_IDENTITY, null, "x.database.windows.net"))
        assertEquals("true", azure["encrypt"])
        assertEquals("false", azure["trustServerCertificate"])
        assertEquals("host=x.database.windows.net", azure["trustManagerConstructorArg"])
    }

    @Test
    fun `host names match by SAN, wildcard one label deep, then IP, then the common name`() {
        assertTrue(HostNames.matchesName("db.example.test", "DB.Example.Test."))
        assertTrue(HostNames.matchesName("*.database.windows.net", "myserver.database.windows.net"))
        assertFalse(HostNames.matchesName("*.database.windows.net", "a.b.database.windows.net"))
        assertFalse(HostNames.matchesName("*.database.windows.net", "database.windows.net"))
        assertFalse(HostNames.matchesName("db.example.test", "other.example.test"))

        val leaf = TestCertificates.parse(TestCertificates.LEAF)
        assertTrue(HostNames.matches(leaf, "db.example.test"))
        assertTrue(HostNames.matches(leaf, "anything.wild.test"))
        assertTrue(HostNames.matches(leaf, "10.1.2.3"))
        assertFalse(HostNames.matches(leaf, "10.1.2.4"))
        assertFalse(HostNames.matches(leaf, "localhost"))
        // With a SAN present the common name is not consulted; without one it is.
        val commonName = TestCertificates.parse(TestCertificates.COMMON_NAME_ONLY)
        assertTrue(HostNames.matches(commonName, "only-cn.example.test"))
        assertFalse(HostNames.matches(commonName, "db.example.test"))
    }

    @Test
    fun `the trust manager accepts a chain from its CA, and checks the name only when asked`() {
        val ca = File.createTempFile("test-ca", ".pem").apply { writeText(TestCertificates.CA); deleteOnExit() }
        val chain = arrayOf(TestCertificates.parse(TestCertificates.LEAF))

        // CA only: any host name passes, the chain has to be this CA's.
        val caOnly = VerifyingTrustManager(VerifyingTrustManager.argument(ca.path, null))
        caOnly.checkServerTrusted(chain, "ECDHE_ECDSA")

        val named = VerifyingTrustManager(VerifyingTrustManager.argument(ca.path, "db.example.test"))
        named.checkServerTrusted(chain, "ECDHE_ECDSA")

        val wrongName = VerifyingTrustManager(VerifyingTrustManager.argument(ca.path, "elsewhere.example.test"))
        try {
            wrongName.checkServerTrusted(chain, "ECDHE_ECDSA")
            fail("a certificate that does not name the host must be refused")
        } catch (expected: CertificateException) {
            assertTrue(expected.message!!.contains("elsewhere.example.test"))
        }

        val otherCa = File.createTempFile("other", ".pem").apply { writeText(TestCertificates.OTHER_CA); deleteOnExit() }
        try {
            VerifyingTrustManager(VerifyingTrustManager.argument(otherCa.path, null)).checkServerTrusted(chain, "ECDHE_ECDSA")
            fail("a chain from another CA must be refused")
        } catch (expected: CertificateException) {
            // Refused.
        }
    }
}
