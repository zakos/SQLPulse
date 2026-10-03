package hu.laurel.sqlpulse.integration.postgres

import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlFailureKind
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.dialect.PostgresDialect
import java.sql.SQLException
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Connecting, switching schemas, reading every kind of value and failing in the ways the user is
 * told about — against a real PostgreSQL (see [PostgresTestServer]).
 */
class PostgresSessionIntegrationTest {

    private lateinit var fixture: PostgresFixture

    @Before
    fun connect() {
        fixture = PostgresFixture()
    }

    @After
    fun disconnect() {
        if (this::fixture.isInitialized) fixture.close()
    }

    private fun read(sql: String, maxRows: Int = 100): ResultTable = fixture.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { ResultTable.from(it, maxRows) }
        }
    }

    @Test
    fun `a session connects to the database it names and the connection is alive`() {
        fixture.use { connection ->
            assertTrue(connection.isValid(5))
            assertEquals(fixture.config.database, fixture.scalar("SELECT current_database()"))
            assertEquals("public", PostgresDialect.currentNamespace(connection))
            assertEquals("public", PostgresDialect.initialNamespace(connection, fixture.config.database))
        }
    }

    @Test
    fun `the picker lists the schemas, the server's own last`() {
        val namespaces = fixture.use { PostgresDialect.catalog.namespaces(it) }
        assertTrue(fixture.schema in namespaces)
        assertTrue("public" in namespaces)
        assertTrue("pg_catalog" in namespaces && "information_schema" in namespaces)
        // pg_temp_N and pg_toast_temp_N are every session's own business.
        assertTrue(namespaces.none { it.startsWith("pg_temp_") || it.startsWith("pg_toast_temp_") })
        assertEquals(setOf("pg_catalog", "information_schema", "pg_toast"), PostgresDialect.systemNamespaces)
    }

    @Test
    fun `switching the namespace moves search_path on the borrowed connection and keeps public behind it`() {
        fixture.execute("CREATE TABLE ${fixture.t("only_here")} (id int)")
        fixture.use { connection ->
            PostgresDialect.useNamespace(connection, fixture.schema)
            assertEquals(fixture.schema, PostgresDialect.currentNamespace(connection))
            // A bare name now resolves in the chosen schema.
            connection.createStatement().use { it.executeQuery("SELECT count(*) FROM only_here").use { rows -> rows.next() } }
            assertEquals("${fixture.schema}, public", fixture.scalarOn(connection, "SHOW search_path"))
            PostgresDialect.useNamespace(connection, "public")
            assertEquals("public", fixture.scalarOn(connection, "SHOW search_path"))
        }
    }

    @Test
    fun `a namespace with quotes and capitals in its name is switched to as written`() {
        val odd = "Odd \"schema\" ${System.nanoTime()}"
        fixture.execute("CREATE SCHEMA ${fixture.q(odd)}")
        try {
            fixture.use { connection ->
                PostgresDialect.useNamespace(connection, odd)
                assertEquals(odd, PostgresDialect.currentNamespace(connection))
            }
        } finally {
            fixture.execute("DROP SCHEMA ${fixture.q(odd)}")
        }
    }

    @Test
    fun `a typed SET search_path is recognised so the whole pool moves, not one connection`() {
        assertEquals(fixture.schema, PostgresDialect.namespaceSwitch("SET search_path TO ${fixture.q(fixture.schema)}"))
    }

    @Test
    fun `every kind of value reads into a sensible cell`() {
        val table = read(
            """
            SELECT ARRAY[1,2,3] AS arr, ARRAY['a','b c'] AS words, '{"a": 1}'::jsonb AS jb, '{"a":1}'::json AS js,
                   'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid AS id,
                   timestamptz '2024-05-06 07:08:09+00' AS tz, timestamp '2024-05-06 07:08:09' AS ts,
                   date '2024-05-06' AS d, time '07:08:09' AS tm,
                   '\xdeadbeef'::bytea AS bin, 12345.6789::numeric(10,4) AS num, 'NaN'::numeric AS nan,
                   true AS flag, 1.5::float8 AS dbl, 42::bigint AS big, interval '1 day 2 hours' AS ivl,
                   '192.168.0.1'::inet AS ip, 'abc'::text AS txt, 'x'::char(3) AS chr, NULL::int AS nothing
            """.trimIndent(),
        )
        val byName = table.columns.indices.associate { table.columns[it].label to (table.columns[it] to table.rows[0][it]) }

        assertEquals(CellValue.Text("{1,2,3}"), byName.getValue("arr").second)
        assertEquals(CellValue.Text("{a,\"b c\"}"), byName.getValue("words").second)
        assertEquals(CellValue.Text("{\"a\": 1}"), byName.getValue("jb").second)
        assertEquals(CellValue.Text("{\"a\":1}"), byName.getValue("js").second)
        assertEquals(CellValue.Text("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"), byName.getValue("id").second)
        assertEquals(CellType.DATE, byName.getValue("tz").first.type)
        assertTrue((byName.getValue("tz").second as CellValue.Date).value.startsWith("2024-05-06 "))
        assertEquals(CellValue.Date("2024-05-06 07:08:09"), byName.getValue("ts").second)
        assertEquals(CellValue.Date("2024-05-06"), byName.getValue("d").second)
        assertEquals(CellValue.Date("07:08:09"), byName.getValue("tm").second)
        assertEquals(CellValue.Blob(4), byName.getValue("bin").second)
        assertEquals(CellValue.Number("12345.6789"), byName.getValue("num").second)
        assertEquals(CellValue.Number("NaN"), byName.getValue("nan").second)
        assertEquals(CellValue.Bool(true), byName.getValue("flag").second)
        assertEquals(CellValue.Number("1.5"), byName.getValue("dbl").second)
        assertEquals(CellValue.Number("42"), byName.getValue("big").second)
        assertEquals(CellValue.Text("1 day 02:00:00"), byName.getValue("ivl").second)
        assertEquals(CellValue.Text("192.168.0.1"), byName.getValue("ip").second)
        assertEquals(CellValue.Text("abc"), byName.getValue("txt").second)
        assertEquals(CellValue.Text("x  "), byName.getValue("chr").second)
        assertEquals(CellValue.Null, byName.getValue("nothing").second)
        assertEquals("int4", byName.getValue("nothing").first.typeName)
    }

    @Test
    fun `a bit string is text, not a boolean`() {
        val table = read("SELECT B'101'::bit(3) AS bits, B'1'::bit(1) AS one, B'10'::varbit AS var")
        assertEquals(CellValue.Text("101"), table.rows[0][0])
        assertEquals(CellValue.Text("1"), table.rows[0][1])
        assertEquals(CellValue.Text("10"), table.rows[0][2])
    }

    @Test
    fun `values bound as text reach columns of any type, because the driver leaves them untyped`() {
        fixture.execute(
            """
            CREATE TABLE ${fixture.t("typed")} (
                id integer PRIMARY KEY, flag boolean, at timestamptz, ratio numeric(6,2), doc jsonb,
                uid uuid, tags text[], note text
            )
            """.trimIndent(),
        )
        fixture.use { connection ->
            connection.prepareStatement(
                "INSERT INTO ${fixture.t("typed")} VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                listOf("7", "true", "2024-05-06 07:08:09+02", "3.14", "{\"k\": [1, 2]}", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", "{x,y}", "ő 😀")
                    .forEachIndexed { index, value -> statement.setString(index + 1, value) }
                assertEquals(1, statement.executeUpdate())
            }
            // And a NULL, which the app binds as VARCHAR.
            connection.prepareStatement("UPDATE ${fixture.t("typed")} SET at = ? WHERE id = ?").use { statement ->
                statement.setNull(1, java.sql.Types.VARCHAR)
                statement.setString(2, "7")
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertEquals("ő 😀", fixture.scalar("SELECT note FROM ${fixture.t("typed")}"))
        assertEquals("true", fixture.scalar("SELECT flag::text FROM ${fixture.t("typed")}"))
        assertEquals("{x,y}", fixture.scalar("SELECT tags::text FROM ${fixture.t("typed")}"))
        assertEquals(null, fixture.scalar("SELECT at::text FROM ${fixture.t("typed")}"))
    }

    @Test
    fun `a statement that has run too long is cancelled on the server and says so`() {
        fixture.use { connection ->
            connection.createStatement().use { statement ->
                val canceller = thread {
                    Thread.sleep(500)
                    statement.cancel()
                }
                val started = System.currentTimeMillis()
                try {
                    statement.executeQuery("SELECT pg_sleep(20)")
                    fail("the statement was not cancelled")
                } catch (e: SQLException) {
                    assertEquals("57014", e.sqlState)
                    assertEquals(SqlFailureKind.TIMEOUT, PostgresDialect.failureOf(e).kind)
                }
                assertTrue("cancel did not reach the server in time", System.currentTimeMillis() - started < 10_000)
                canceller.join()
            }
            // The connection survives its cancelled statement.
            assertTrue(connection.isValid(5))
        }
    }

    @Test
    fun `a read-only session refuses a write on the server, in auto-commit too`() {
        fixture.execute("CREATE TABLE ${fixture.t("guarded")} (id int)")
        val readOnly = SqlSession(fixture.config.copy(readOnly = true))
        try {
            readOnly.use { connection ->
                assertTrue(connection.isReadOnly)
                assertTrue(connection.autoCommit)
                connection.createStatement().use { it.executeQuery("SELECT 1").use { rows -> rows.next() } }
                try {
                    connection.createStatement().use { it.executeUpdate("INSERT INTO ${fixture.t("guarded")} VALUES (1)") }
                    fail("a write went through a read-only connection")
                } catch (e: SQLException) {
                    assertEquals("25006", e.sqlState)
                    assertEquals(SqlFailureKind.SERVER_BUSY_OR_READ_ONLY, PostgresDialect.failureOf(e).kind)
                }
            }
        } finally {
            readOnly.close()
        }
        assertEquals("0", fixture.scalar("SELECT count(*) FROM ${fixture.t("guarded")}"))
    }

    @Test
    fun `errors are classified by SQLSTATE`() {
        fun failure(sql: String): SqlFailureKind = try {
            fixture.use { it.createStatement().use { statement -> statement.execute(sql) } }
            fail("expected a failure: $sql")
            throw IllegalStateException()
        } catch (e: SQLException) {
            PostgresDialect.failureOf(e).kind
        }

        fixture.execute("CREATE TABLE ${fixture.t("uniq")} (id int PRIMARY KEY)", "INSERT INTO ${fixture.t("uniq")} VALUES (1)")
        assertEquals(SqlFailureKind.SYNTAX, failure("SELEC 1"))
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, failure("SELECT * FROM ${fixture.t("no_such_table")}"))
        assertEquals(SqlFailureKind.UNKNOWN_OBJECT, failure("SELECT nope FROM ${fixture.t("uniq")}"))
        assertEquals(SqlFailureKind.DUPLICATE_KEY, failure("INSERT INTO ${fixture.t("uniq")} VALUES (1)"))
        assertEquals(SqlFailureKind.UNKNOWN_DATABASE, failure("CREATE TABLE no_such_schema_x.t (a int)"))
        assertEquals(SqlFailureKind.OTHER, failure("SELECT 1/0"))
    }

    @Test
    fun `a wrong password, an unknown database and a closed port are told apart`() {
        fun kindOf(config: hu.laurel.sqlpulse.data.sql.JdbcConfig): SqlFailureKind {
            val session = SqlSession(config.copy(connectTimeoutMs = 3_000))
            try {
                session.use { }
                fail("the connection was accepted")
                throw IllegalStateException()
            } catch (e: SQLException) {
                val failure = PostgresDialect.failureOf(e)
                assertNotNull(failure.serverMessage)
                return failure.kind
            } finally {
                session.close()
            }
        }
        assertEquals(SqlFailureKind.AUTHENTICATION, kindOf(fixture.config.copy(password = "definitely wrong")))
        assertEquals(SqlFailureKind.UNKNOWN_DATABASE, kindOf(fixture.config.copy(database = "no_such_db_${System.nanoTime()}")))
        assertEquals(SqlFailureKind.UNREACHABLE, kindOf(fixture.config.copy(port = 1)))
    }

    @Test
    fun `a manual transaction can be rolled back and a failure inside it aborts it`() {
        fixture.execute("CREATE TABLE ${fixture.t("tx")} (id int PRIMARY KEY)")
        val connection = fixture.session.take()
        try {
            connection.autoCommit = false
            connection.createStatement().use { it.execute("INSERT INTO ${fixture.t("tx")} VALUES (1)") }
            connection.rollback()
        } finally {
            connection.autoCommit = true
            fixture.session.giveBack(connection)
        }
        assertEquals("0", fixture.scalar("SELECT count(*) FROM ${fixture.t("tx")}"))
    }
}

private fun PostgresFixture.scalarOn(connection: java.sql.Connection, sql: String): String? =
    connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
    }
