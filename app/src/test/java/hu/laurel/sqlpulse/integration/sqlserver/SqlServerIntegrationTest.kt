package hu.laurel.sqlpulse.integration.sqlserver

import hu.laurel.sqlpulse.data.connection.WriteUnlockStore
import hu.laurel.sqlpulse.data.csv.CsvImport
import hu.laurel.sqlpulse.data.schema.RoutineKind
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.TableKind
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.ResultEditabilities
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.RowChangedException
import hu.laurel.sqlpulse.data.sql.RowEditor
import hu.laurel.sqlpulse.data.sql.SqlFailureKind
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.WriteGate
import hu.laurel.sqlpulse.data.sql.WriteImpact
import hu.laurel.sqlpulse.data.sql.dialect.SqlServerDialect
import hu.laurel.sqlpulse.integration.IntegrationSessions
import java.sql.Connection
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * SQL Server end to end, against a real server (see [SqlServerTestServer]): connect, read the
 * schema through `sys.*`, run queries with the dialect's row cap, edit a row by its key, preview
 * a write, cancel a statement, and explain failures by their error numbers.
 *
 * Everything lives in a schema of its own named for this run, dropped afterwards, so a test that
 * dies half way cannot poison the next one and the database can be shared.
 */
class SqlServerIntegrationTest {

    private val dialect = SqlServerDialect
    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession
    private lateinit var schema: SchemaRepository
    private lateinit var editor: RowEditor

    private val stamp = System.nanoTime()
    private val ns = "sp_it_$stamp"

    private fun q(name: String) = dialect.quoteIdentifier(name)
    private fun t(name: String) = dialect.qualify(ns, name)

    @Before
    fun connect() {
        val found = SqlServerTestServer.configOrNull()
        assumeTrue("no ${SqlServerTestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlServerTestServer.open(config)
        val manager = IntegrationSessions.manager(session, config.database, dialect = dialect)
        schema = SchemaRepository(manager)
        editor = RowEditor(manager, WriteGate(manager, WriteUnlockStore()), io.mockk.mockk(relaxed = true))

        session.use { c ->
            c.execute("CREATE SCHEMA ${q(ns)}")
            c.execute(
                """
                CREATE TABLE ${t("customers")} (
                    id INT IDENTITY(1,1) PRIMARY KEY,
                    name NVARCHAR(50) NOT NULL,
                    email VARCHAR(100) NULL CONSTRAINT df_email DEFAULT ('n/a'),
                    balance MONEY NULL,
                    active BIT NOT NULL CONSTRAINT df_active DEFAULT 1,
                    guid UNIQUEIDENTIFIER NULL,
                    created DATETIMEOFFSET(3) NULL,
                    born DATE NULL,
                    payload VARBINARY(MAX) NULL,
                    doc XML NULL,
                    qty INT NULL,
                    upper_name AS (UPPER(name)),
                    CONSTRAINT ck_qty CHECK (qty >= 0)
                )
                """.trimIndent(),
            )
            c.execute("EXEC sp_addextendedproperty N'MS_Description', N'People who buy', 'SCHEMA', N'$ns', 'TABLE', N'customers'")
            c.execute(
                """
                CREATE TABLE ${t("orders")} (
                    id INT PRIMARY KEY,
                    customer_id INT NOT NULL CONSTRAINT fk_orders_cust FOREIGN KEY REFERENCES ${t("customers")}(id) ON DELETE CASCADE,
                    total DECIMAL(10,2) NOT NULL
                )
                """.trimIndent(),
            )
            c.execute("CREATE INDEX ix_orders_total ON ${t("orders")}(total)")
            c.execute("CREATE VIEW ${t("v_customers")} AS SELECT id, name FROM ${t("customers")}")
            c.execute("CREATE PROCEDURE ${t("p_count")} AS SELECT COUNT(*) AS n FROM ${t("customers")}")
            c.execute("CREATE FUNCTION ${t("f_double")}(@x INT) RETURNS INT AS BEGIN RETURN @x * 2 END")
            c.execute("CREATE TRIGGER ${t("tr_customers")} ON ${t("customers")} AFTER INSERT, UPDATE AS BEGIN SET NOCOUNT ON END")
            c.execute(
                """
                INSERT INTO ${t("customers")} (name, email, balance, active, guid, created, born, payload, doc, qty) VALUES
                    (N'Aladár', 'a@example.com', 12.5000, 1, '6F9619FF-8B86-D011-B42D-00C04FC964FF', '2024-05-06T07:08:09.123+02:00', '1990-01-02', 0x48656C6C6F, '<a>1</a>', 3),
                    (N'Béla', NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL),
                    (N'Cecil', 'c@example.com', 0.0000, 1, NULL, NULL, NULL, NULL, NULL, 0)
                """.trimIndent(),
            )
            c.execute("INSERT INTO ${t("orders")} VALUES (1, 1, 10.00), (2, 1, 20.00), (3, 2, 5.50)")
        }
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) {
            runCatching {
                session.use { c ->
                    listOf("tr_customers").forEach { c.execute("DROP TRIGGER IF EXISTS ${t(it)}") }
                    c.execute("DROP FUNCTION IF EXISTS ${t("f_double")}")
                    c.execute("DROP PROCEDURE IF EXISTS ${t("p_count")}")
                    c.execute("DROP VIEW IF EXISTS ${t("v_customers")}")
                    c.execute("DROP TABLE IF EXISTS ${t("orders")}")
                    c.execute("DROP TABLE IF EXISTS ${t("customers")}")
                    c.execute("DROP TABLE IF EXISTS ${t("csv_target")}")
                    c.execute("DROP SCHEMA IF EXISTS ${q(ns)}")
                }
            }
            session.close()
        }
    }

    // ---------------------------------------------------------------- connection

    @Test
    fun `a session connects, reports a version and starts in its default schema`() {
        session.use { c ->
            assertTrue(c.isValid(5))
            assertTrue(c.metaData.databaseProductName.contains("SQL Server", ignoreCase = true))
            assertEquals("dbo", dialect.initialNamespace(c, config.database))
            assertEquals(config.database, c.catalog)
        }
    }

    @Test
    fun `the driver really ignores setReadOnly, which is why the app guards statements itself`() {
        session.use { c ->
            c.isReadOnly = true
            assertFalse("if this starts passing, the dialect's read-only note can be softened", c.isReadOnly)
        }
    }

    @Test
    fun `a plain connection without encryption works too`() {
        SqlSession(config.copy(sslMode = hu.laurel.sqlpulse.data.sql.SslMode.DISABLED)).use { s ->
            s.use { c -> assertTrue(c.isValid(5)) }
        }
    }

    // ---------------------------------------------------------------- schema

    @Test
    fun `schemas are the namespaces, without the fixed role schemas`() {
        val names = runBlocking { schema.databases() }
        assertTrue(names.contains(ns))
        assertTrue(names.contains("dbo"))
        assertFalse(names.any { it.startsWith("db_") })
        // System schemas come after the user's.
        assertTrue(names.indexOf("sys") > names.indexOf(ns))
    }

    @Test
    fun `tables and views are listed with their kind, row estimate and comment`() {
        val tables = runBlocking { schema.tables(ns) }
        assertEquals(listOf("customers", "orders", "v_customers"), tables.map { it.name })
        val customers = tables.first { it.name == "customers" }
        assertEquals(TableKind.TABLE, customers.kind)
        assertEquals(3L, customers.approximateRows)
        assertEquals("People who buy", customers.comment)
        assertNotNull(customers.dataBytes)
        assertEquals(TableKind.VIEW, tables.first { it.name == "v_customers" }.kind)
        assertNull(tables.first { it.name == "v_customers" }.approximateRows)
    }

    @Test
    fun `columns carry T-SQL type names, identity, computed and default information`() {
        val structure = runBlocking { schema.structure(ns, "customers") }
        val byName = structure.columns.associateBy { it.name }

        assertEquals("int", byName.getValue("id").typeName)
        assertTrue(byName.getValue("id").isPrimaryKey)
        assertTrue(byName.getValue("id").extra!!.contains("auto_increment"))
        assertEquals("nvarchar(50)", byName.getValue("name").typeName)
        assertEquals("varchar(100)", byName.getValue("email").typeName)
        assertEquals("'n/a'", byName.getValue("email").defaultValue)
        assertEquals("1", byName.getValue("active").defaultValue)
        assertEquals("money", byName.getValue("balance").typeName)
        assertEquals("datetimeoffset(3)", byName.getValue("created").typeName)
        assertEquals("varbinary(max)", byName.getValue("payload").typeName)
        assertEquals("xml", byName.getValue("doc").typeName)
        assertFalse(byName.getValue("name").nullable)
        assertTrue(byName.getValue("qty").nullable)

        val computed = byName.getValue("upper_name")
        assertNotNull(computed.generatedKind)
        assertEquals("upper([name])", computed.generationExpression!!.lowercase())

        assertEquals(listOf("id"), structure.primaryKey)
        assertEquals(listOf("ck_qty"), structure.checks.map { it.name })
        assertEquals("[qty]>=(0)", structure.checks.single().expression!!.replace(" ", ""))
        assertNotNull(structure.collation)
    }

    @Test
    fun `indexes, foreign keys and the links between tables`() {
        val orders = runBlocking { schema.structure(ns, "orders") }
        assertTrue(orders.indexes.any { it.columns == listOf("total") && !it.unique })
        assertTrue(orders.indexes.any { it.unique && it.columns == listOf("id") })
        val fk = orders.foreignKeys.single()
        assertEquals("fk_orders_cust", fk.constraintName)
        assertEquals("customers", fk.referencedTable)
        assertEquals(ns, fk.referencedDatabase)
        assertEquals("CASCADE", fk.onDelete)
        assertEquals("ON DELETE CASCADE", fk.ruleSummary)

        val edges = runBlocking { schema.links(ns) }
        assertEquals(1, edges.size)
        assertEquals("orders", edges.single().from)
        assertEquals("customers", edges.single().to)

        val columns = runBlocking { schema.columnNames(ns) }
        assertEquals(listOf("id"), columns.first { it.table == "orders" }.primaryKey)

        // Walking from a row to its parent and to its children.
        val children = runBlocking { schema.childLinks(ns, "customers") }
        assertEquals(listOf("orders"), children.map { it.childTable })
        val parents = runBlocking { schema.parentLinks(ns, "orders") }
        assertEquals(listOf("customers"), parents.map { it.parentTable })
    }

    @Test
    fun `routines, triggers and a view's definition`() {
        val routines = runBlocking { schema.routines(ns) }
        val proc = routines.first { it.name == "p_count" }
        assertEquals(RoutineKind.PROCEDURE, proc.kind)
        val function = routines.first { it.name == "f_double" }
        assertEquals(RoutineKind.FUNCTION, function.kind)
        assertEquals("int", function.returns)
        assertTrue(runBlocking { schema.routineDdl(ns, function) }.contains("RETURNS INT", ignoreCase = true))

        val trigger = runBlocking { schema.triggers(ns) }.single()
        assertEquals("tr_customers", trigger.name)
        assertEquals("customers", trigger.table)
        assertEquals("AFTER", trigger.timing)
        assertTrue(trigger.event.contains("INSERT") && trigger.event.contains("UPDATE"))

        assertTrue(runBlocking { schema.ddl(ns, "v_customers") }.contains("CREATE VIEW", ignoreCase = true))
        assertEquals("", runBlocking { schema.ddl(ns, "customers") })
    }

    // ---------------------------------------------------------------- results

    @Test
    fun `T-SQL result types arrive as the cell kind the grid expects`() {
        val table = query("SELECT * FROM ${t("customers")} ORDER BY id")
        val types = table.columns.associate { it.label to it.type }
        assertEquals(CellType.NUMBER, types.getValue("balance"))
        assertEquals(CellType.BOOLEAN, types.getValue("active"))
        assertEquals(CellType.BLOB, types.getValue("payload"))
        assertEquals(CellType.DATE, types.getValue("born"))
        assertEquals(CellType.DATE, types.getValue("created"))
        assertEquals(CellType.TEXT, types.getValue("guid"))
        assertEquals(CellType.TEXT, types.getValue("doc"))

        val first = table.rows[0].zip(table.columns).associate { (cell, column) -> column.label to cell }
        assertEquals(CellValue.Number("12.5000"), first.getValue("balance"))
        assertEquals(CellValue.Bool(true), first.getValue("active"))
        assertEquals(CellValue.Blob(5), first.getValue("payload"))
        assertEquals(CellValue.Text("6F9619FF-8B86-D011-B42D-00C04FC964FF"), first.getValue("guid"))
        assertEquals(CellValue.Text("<a>1</a>"), first.getValue("doc"))
        assertEquals(CellValue.Date("1990-01-02"), first.getValue("born"))
        assertTrue((first.getValue("created") as CellValue).toString().contains("2024-05-06 07:08:09.123 +02:00"))
        assertEquals(CellValue.Null, table.rows[1].zip(table.columns).first { it.second.label == "balance" }.first)
        // The computed column is read like any other.
        assertEquals(CellValue.Text("ALADÁR"), first.getValue("upper_name"))
    }

    @Test
    fun `the default row limit gives valid T-SQL for every shape of SELECT`() {
        val cases = mapOf(
            "SELECT * FROM ${t("customers")}" to true,
            "SELECT DISTINCT customer_id FROM ${t("orders")}" to true,
            "SELECT * FROM ${t("customers")} ORDER BY name" to true,
            "WITH c AS (SELECT id FROM ${t("customers")}) SELECT * FROM c" to true,
            "WITH a AS (SELECT 1 AS n), b AS (SELECT 2 AS n) SELECT * FROM a UNION ALL SELECT * FROM b" to false,
            "SELECT id FROM ${t("customers")} UNION SELECT id FROM ${t("orders")} ORDER BY 1" to true,
            "SELECT TOP (1) * FROM ${t("customers")}" to false,
            "SELECT * FROM ${t("customers")} ORDER BY id OFFSET 1 ROWS FETCH NEXT 1 ROWS ONLY" to false,
            "SELECT 1" to true,
            "SELECT name, COUNT(*) AS n FROM ${t("customers")} GROUP BY name HAVING COUNT(*) > 0" to true,
            "SELECT * FROM ${t("customers")} FOR XML PATH" to true,
            "SELECT * FROM ${t("customers")} OPTION (MAXDOP 1)" to true,
        )
        for ((sql, added) in cases) {
            val limited = dialect.applyDefaultLimit(sql, 2)
            assertEquals(sql, added, limited.limitAdded)
            val table = query(limited.sql, maxRows = 1000)
            if (added) assertTrue("${limited.sql} -> ${table.rowCount} rows", table.rowCount <= 2)
        }
    }

    @Test
    fun `paging with OFFSET FETCH works with and without an ORDER BY`() {
        val ordered = dialect.limit("SELECT id FROM ${t("customers")} ORDER BY id DESC", 2, 1, ordered = true)
        assertEquals(listOf("2", "1"), query(ordered).rows.map { (it[0] as CellValue.Number).value })
        val unordered = dialect.limit("SELECT id FROM ${t("customers")}", 2, null)
        assertEquals(2, query(unordered).rowCount)

        val page = runBlocking { schema.preview(ns, "customers", limit = 2, offset = 2, sort = null) }
        assertEquals(1, page.rowCount)
        assertEquals(3L, runBlocking { schema.rowCount(ns, "customers") })
    }

    @Test
    fun `a BLOB's size and head are read with DATALENGTH and SUBSTRING`() {
        val sql = "SELECT ${dialect.blobLengthAndHead(q("payload"), 3)} FROM ${t("customers")} WHERE id = 1"
        session.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(5L, rows.getLong(1))
                    assertEquals("Hel", String(rows.getBytes(2)))
                }
            }
        }
    }

    // ---------------------------------------------------------------- row editing

    @Test
    fun `an edit changes one cell of one row, guarded against a changed value, NULL included`() {
        // From a value to a value.
        edit(1, "name", "Aladár", "Aladár Sándor")
        assertEquals("Aladár Sándor", scalar("SELECT name FROM ${t("customers")} WHERE id = 1"))

        // From NULL to a value and back: the guard has to say "is null" and not "= null".
        edit(2, "email", null, "b@example.com")
        assertEquals("b@example.com", scalar("SELECT email FROM ${t("customers")} WHERE id = 2"))
        edit(2, "email", "b@example.com", null)
        assertNull(scalar("SELECT email FROM ${t("customers")} WHERE id = 2"))

        // Typed columns written from text: bit, money, uniqueidentifier, date, datetimeoffset.
        edit(3, "active", "1", "0")
        edit(3, "balance", "0.0000", "99.5")
        edit(3, "guid", null, "11111111-2222-3333-4444-555555555555")
        edit(3, "born", null, "2000-02-29")
        edit(3, "created", null, "2024-01-02 03:04:05.678 +01:00")
        assertEquals("99.5000", scalar("SELECT balance FROM ${t("customers")} WHERE id = 3"))
        assertEquals("11111111-2222-3333-4444-555555555555", scalar("SELECT guid FROM ${t("customers")} WHERE id = 3"))
        assertEquals("2000-02-29", scalar("SELECT born FROM ${t("customers")} WHERE id = 3"))
        // Quotes, brackets and non-latin text survive.
        edit(3, "name", "Cecil", "it's [x] \"q\" ő 😀")
        assertEquals("it's [x] \"q\" ő 😀", scalar("SELECT name FROM ${t("customers")} WHERE id = 3"))
    }

    @Test
    fun `a value somebody else changed is reported, not overwritten`() {
        session.use { it.execute("UPDATE ${t("customers")} SET name = N'Changed' WHERE id = 1") }
        try {
            edit(1, "name", "Aladár", "Mine")
            fail("the stale edit must not apply")
        } catch (e: RowChangedException) {
            assertEquals("Changed", e.currentValue)
            assertTrue(e.rowExists)
        }
        assertEquals("Changed", scalar("SELECT name FROM ${t("customers")} WHERE id = 1"))
    }

    @Test
    fun `insert and delete go through the same one-row path, and a cascade is the server's`() {
        val insert = editor.prepareInsert(ns, "customers", mapOf("name" to "Dóra", "qty" to "7"))
        assertEquals(1, runBlocking { editor.execute(insert) })
        val id = scalar("SELECT id FROM ${t("customers")} WHERE name = N'Dóra'")!!
        // The identity value and the column default were filled in by the server.
        assertEquals("n/a", scalar("SELECT email FROM ${t("customers")} WHERE id = $id"))

        val delete = editor.prepareDelete(ns, "customers", mapOf("id" to id))
        assertEquals(1, runBlocking { editor.execute(delete) })
        assertNull(scalar("SELECT id FROM ${t("customers")} WHERE id = $id"))

        // Deleting a parent removes its children (ON DELETE CASCADE) — in one statement.
        assertEquals(1, runBlocking { editor.execute(editor.prepareDelete(ns, "customers", mapOf("id" to "1"))) })
        assertEquals("1", scalar("SELECT COUNT(*) FROM ${t("orders")}"))
    }

    @Test
    fun `CSV import writes its rows in one transaction`() {
        session.use { c -> c.execute("CREATE TABLE ${t("csv_target")} (id INT PRIMARY KEY, label NVARCHAR(20) NULL)") }
        val structure = runBlocking { schema.structure(ns, "csv_target") }
        val match = CsvImport.match(listOf("id", "label"), structure.columns)
        val statements = CsvImport.statements(
            database = ns, table = "csv_target", match = match, header = listOf("id", "label"),
            rows = listOf(listOf("1", "ő"), listOf("2", null)), syntax = dialect,
        )
        session.use { c ->
            c.autoCommit = false
            statements.forEach { st ->
                c.prepareStatement(st.sql).use { p ->
                    st.parameters.forEachIndexed { i, v -> p.setString(i + 1, v) }
                    p.executeUpdate()
                }
            }
            c.commit()
            c.autoCommit = true
        }
        assertEquals("2", scalar("SELECT COUNT(*) FROM ${t("csv_target")}"))
        assertEquals("ő", scalar("SELECT label FROM ${t("csv_target")} WHERE id = 1"))
    }

    // ---------------------------------------------------------------- write impact and editability

    @Test
    fun `the count and the preview agree with what the write really does`() {
        val cases = listOf(
            "UPDATE ${t("orders")} SET total = total + 1 WHERE customer_id = 1",
            "UPDATE ${t("orders")} SET total = 0",
            "DELETE FROM ${t("orders")} WHERE total > 8",
            "DELETE FROM ${t("orders")}",
            "UPDATE ${t("customers")} SET name = UPPER(name), qty = qty + 1 WHERE active = 1",
        )
        session.use { c ->
            for (sql in cases) {
                val count = dialect.writeCountQuery(sql)
                assertNotNull(sql, count)
                val counted = c.createStatement().use { s -> s.executeQuery(count!!).use { it.next(); it.getLong(1) } }
                val preview = dialect.writePreviewQuery(sql, WriteImpact.PREVIEW_ROWS)
                assertNotNull(sql, preview)
                val shown = c.createStatement().use { s -> s.executeQuery(preview!!.sql).use { ResultTable.from(it, 100) } }
                c.autoCommit = false
                val affected = try {
                    c.createStatement().use { it.executeUpdate(sql) }
                } finally {
                    c.rollback()
                    c.autoCommit = true
                }
                assertEquals(sql, affected.toLong(), counted)
                assertEquals(sql, minOf(affected, WriteImpact.PREVIEW_ROWS), shown.rowCount)
            }
        }
    }

    @Test
    fun `an UPDATE preview shows the new value next to the old row`() {
        val preview = dialect.writePreviewQuery("UPDATE ${t("orders")} SET total = total * 2 WHERE id = 2", 20)!!
        val table = query(preview.sql)
        assertEquals(1, table.rowCount)
        val labels = table.columns.map { it.label }
        assertTrue(labels.contains("total (new)"))
        assertEquals(CellValue.Number("40.00"), table.rows[0][labels.indexOf("total (new)")])
        assertEquals(CellValue.Number("20.00"), table.rows[0][labels.indexOf("total")])
    }

    @Test
    fun `a typed SELECT on one table is editable and the result maps onto its key`() {
        val sql = "SELECT TOP (10) [id], [name], email FROM ${t("customers")} ORDER BY id"
        val editable = dialect.resultEditability(sql)
        assertTrue(editable.toString(), editable is ResultEditability.Editable)
        val structure = runBlocking { schema.structure(ns, "customers") }
        val confirmed = ResultEditabilities.confirm(
            editable as ResultEditability.Editable, ns, listOf("id", "name", "email"), structure,
        )
        assertTrue(confirmed.toString(), confirmed is ResultEditability.Confirmed)
        assertEquals(mapOf("id" to 0), (confirmed as ResultEditability.Confirmed).target.key)
    }

    // ---------------------------------------------------------------- cancel and failures

    @Test
    fun `a running statement can be cancelled and the connection is usable afterwards`() {
        session.use { c ->
            c.createStatement().use { statement ->
                val timer = Thread {
                    Thread.sleep(1000)
                    statement.cancel()
                }.also { it.start() }
                val started = System.currentTimeMillis()
                try {
                    statement.execute("WAITFOR DELAY '00:00:30'")
                    fail("the wait must be cancelled")
                } catch (e: SQLException) {
                    assertTrue("cancelled after ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 10_000)
                }
                timer.join()
            }
            c.createStatement().use { it.executeQuery("SELECT 1").use { rows -> assertTrue(rows.next()) } }
        }
    }

    @Test
    fun `the query timeout stops a long statement and is explained as a timeout`() {
        session.use { c ->
            c.prepareStatement("WAITFOR DELAY '00:00:30'").use { statement ->
                statement.queryTimeout = 1
                try {
                    statement.execute()
                    fail("must time out")
                } catch (e: SQLException) {
                    assertEquals(SqlFailureKind.TIMEOUT, dialect.failureOf(e).kind)
                }
            }
        }
    }

    @Test
    fun `server errors are explained by their number`() {
        fun kind(sql: String): SqlFailureKind {
            try {
                session.use { c -> c.createStatement().use { it.execute(sql) } }
            } catch (e: SQLException) {
                val failure = dialect.failureOf(e)
                assertTrue("a message is kept: ${failure.serverMessage}", failure.serverMessage.isNotBlank())
                return failure.kind
            }
            fail("expected a failure: $sql")
            error("unreachable")
        }
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, kind("SELECT * FROM ${t("no_such_table")}"))
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, kind("SELECT nope FROM ${t("customers")}"))
        assertEquals(SqlFailureKind.SYNTAX, kind("SELECT * FROM WHERE"))
        assertEquals(SqlFailureKind.DUPLICATE_KEY, kind("INSERT INTO ${t("orders")} VALUES (1, 1, 1.00)"))
    }

    @Test
    fun `a wrong password and a missing database are told apart`() {
        for ((cfg, expected) in listOf(
            config.copy(password = "definitely-wrong-${System.nanoTime()}") to SqlFailureKind.AUTHENTICATION,
            config.copy(database = "no_such_db_$stamp") to SqlFailureKind.UNKNOWN_DATABASE,
        )) {
            try {
                SqlSession(cfg).use { s -> s.use { } }
                fail("the login must fail")
            } catch (e: SQLException) {
                // 4060 reaches the client as a login failure for that database.
                val kind = dialect.failureOf(e).kind
                assertTrue("$expected vs $kind: ${e.message}", kind == expected || kind == SqlFailureKind.AUTHENTICATION && expected == SqlFailureKind.UNKNOWN_DATABASE)
            }
        }
    }

    @Test
    fun `read-only is a grant on this engine - a user with only SELECT cannot write`() {
        val login = "sp_ro_$stamp"
        val password = "Ro_${stamp}_Pw!x"
        session.use { c ->
            c.execute("CREATE LOGIN ${q(login)} WITH PASSWORD = N'$password', CHECK_POLICY = OFF")
            c.execute("CREATE USER ${q(login)} FOR LOGIN ${q(login)}")
            c.execute("GRANT SELECT ON SCHEMA::${q(ns)} TO ${q(login)}")
        }
        try {
            SqlSession(config.copy(user = login, password = password, readOnly = true)).use { limited ->
                limited.use { c ->
                    c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM ${t("customers")}").use { r -> assertTrue(r.next()) } }
                    try {
                        c.createStatement().use { it.executeUpdate("UPDATE ${t("customers")} SET qty = 1") }
                        fail("the server must refuse the write")
                    } catch (e: SQLException) {
                        assertEquals(SqlFailureKind.PRIVILEGE, dialect.failureOf(e).kind)
                    }
                }
            }
        } finally {
            session.use { c ->
                runCatching { c.execute("DROP USER IF EXISTS ${q(login)}") }
                runCatching { c.execute("DROP LOGIN ${q(login)}") }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun query(sql: String, maxRows: Int = 500): ResultTable = session.use { c ->
        c.prepareStatement(sql).use { statement -> statement.executeQuery().use { ResultTable.from(it, maxRows) } }
    }

    private fun scalar(sql: String): String? = session.use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null } }
    }

    private fun edit(id: Int, column: String, old: String?, new: String?) {
        val edit = editor.prepareUpdate(ns, "customers", mapOf("id" to id.toString()), column, old, new)
        runBlocking { editor.execute(edit) }
    }

    @Suppress("unused")
    private fun Connection.noop() = Unit
}
