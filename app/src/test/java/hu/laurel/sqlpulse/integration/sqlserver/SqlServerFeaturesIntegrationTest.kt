package hu.laurel.sqlpulse.integration.sqlserver

import hu.laurel.sqlpulse.data.schema.StorageRepository
import hu.laurel.sqlpulse.data.schema.StorageSource
import hu.laurel.sqlpulse.data.search.DatabaseSearch
import hu.laurel.sqlpulse.data.search.DatabaseSearchRepository
import hu.laurel.sqlpulse.data.search.SearchMode
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlServerDialect
import hu.laurel.sqlpulse.integration.EngineBackend
import hu.laurel.sqlpulse.integration.EngineFeaturesBase
import hu.laurel.sqlpulse.integration.EngineSide
import hu.laurel.sqlpulse.integration.IntegrationSessions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Database search, schema comparison and the storage screen on a real SQL Server. */
class SqlServerFeaturesIntegrationTest : EngineFeaturesBase() {

    override fun connect(): EngineBackend {
        val config = SqlServerTestServer.configOrNull()
        assumeTrue("no ${SqlServerTestServer.URL_VARIABLE}, so there is no server to talk to", config != null)
        val session: SqlSession = SqlServerTestServer.open(config!!)
        val manager = IntegrationSessions.manager(session, config.database, dialect = SqlServerDialect)
        val stamp = System.nanoTime()
        val sideA = EngineSide(manager, "sp_ft_a_$stamp")
        val sideB = EngineSide(manager, "sp_ft_b_$stamp")
        return object : EngineBackend {
            override val dialect: SqlDialect = SqlServerDialect
            override val a = sideA
            override val b = sideB
            override fun execute(side: EngineSide, vararg statements: String) {
                session.use { c -> statements.forEach { sql -> c.createStatement().use { it.execute(sql) } } }
            }

            override fun close() {
                // Objects first, then the schema; a schema with objects in it cannot be dropped.
                for (side in listOf(sideA, sideB)) {
                    runCatching {
                        session.use { c ->
                            val names = c.createStatement().use { statement ->
                                statement.executeQuery(
                                    "SELECT o.name, o.type FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id " +
                                        "WHERE s.name = '${side.namespace}' AND o.type IN ('U', 'V') ORDER BY CASE o.type WHEN 'V' THEN 0 ELSE 1 END, o.create_date DESC",
                                ).use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2).trim()) } }
                            }
                            // Foreign keys are not used by these fixtures, so creation order reversed is enough.
                            names.forEach { (name, type) ->
                                val kind = if (type == "V") "VIEW" else "TABLE"
                                c.createStatement().use { it.execute("DROP $kind ${SqlServerDialect.qualify(side.namespace, name)}") }
                            }
                            c.createStatement().use { it.execute("DROP SCHEMA ${SqlServerDialect.quoteIdentifier(side.namespace)}") }
                        }
                    }
                }
                session.close()
            }
        }.also { backend ->
            backend.execute(sideA, "CREATE SCHEMA ${SqlServerDialect.quoteIdentifier(sideA.namespace)}")
            backend.execute(sideB, "CREATE SCHEMA ${SqlServerDialect.quoteIdentifier(sideB.namespace)}")
        }
    }

    private fun t(side: EngineSide, table: String) = SqlServerDialect.qualify(side.namespace, table)

    /** Unicode literals need the N prefix, or the accents are lost on the way in. */
    private fun nText(value: String?): String = value?.let { "N'" + it.replace("'", "''") + "'" } ?: "NULL"

    override fun searchFixture(side: EngineSide, backend: EngineBackend) {
        val docs = t(side, "docs")
        backend.execute(
            side,
            "CREATE TABLE $docs (id INT PRIMARY KEY, title NVARCHAR(100) NULL, body NVARCHAR(MAX) NULL, qty INT NULL, payload VARBINARY(MAX) NULL)",
            *docsRows { id, title, body, qty ->
                "INSERT INTO $docs (id, title, body, qty) VALUES ($id, ${nText(title)}, ${nText(body)}, ${qty ?: "NULL"})"
            }.toTypedArray(),
        )
    }

    override fun manyRows(): Array<String> = Array(12) {
        "INSERT INTO ${t(backend.a, "docs")} (id, title) VALUES (${100 + it}, N'filler $it')"
    }

    override fun diffFixture(backend: EngineBackend) {
        val a = backend.a
        val b = backend.b
        backend.execute(
            a,
            "CREATE TABLE ${t(a, "customers")} (id INT PRIMARY KEY, email VARCHAR(100) NOT NULL, name NVARCHAR(50) NULL, referrer INT NULL)",
            // The primary keys are unnamed on purpose: SQL Server makes up PK__widgets__<random> on each side.
            "CREATE TABLE ${t(a, "widgets")} (id INT IDENTITY(1,1) PRIMARY KEY, qty INT NOT NULL DEFAULT 0, " +
                "label NVARCHAR(MAX) NULL DEFAULT 'abc', created DATETIME2 NOT NULL DEFAULT GETDATE(), amount DECIMAL(10,2) NULL)",
            "CREATE TABLE ${t(a, "legacy")} (id INT PRIMARY KEY)",
        )
        backend.execute(
            b,
            "CREATE TABLE ${t(b, "customers")} (id INT PRIMARY KEY, email VARCHAR(255) NOT NULL, name NVARCHAR(50) NOT NULL, referrer INT NULL)",
            "CREATE TABLE ${t(b, "widgets")} (id INT IDENTITY(1,1) PRIMARY KEY, qty INT NOT NULL DEFAULT (0), " +
                "label NVARCHAR(MAX) NULL DEFAULT N'abc', created DATETIME2 NOT NULL DEFAULT (getdate()), amount NUMERIC(10,2) NULL)",
            "CREATE TABLE ${t(b, "extra")} (id INT PRIMARY KEY)",
        )
    }

    override fun storageFixture(side: EngineSide, backend: EngineBackend) {
        val big = t(side, "big")
        val counter = t(side, "counter")
        backend.execute(
            side,
            "CREATE TABLE $big (id INT IDENTITY(1,1) PRIMARY KEY, a INT NOT NULL, b VARCHAR(40) NOT NULL, payload VARCHAR(200) NOT NULL)",
            "INSERT INTO $big (a, b, payload) SELECT TOP 200 ROW_NUMBER() OVER (ORDER BY (SELECT NULL)), 'row', REPLICATE('x', 100) " +
                "FROM sys.all_objects x CROSS JOIN sys.all_objects y",
            "CREATE INDEX idx_a_b ON $big (a, b)",
            // The first value is 30000, so the next one is 30001 of a possible 32767.
            "CREATE TABLE $counter (id SMALLINT IDENTITY(30000, 1) PRIMARY KEY, v INT)",
            "INSERT INTO $counter (v) VALUES (1)",
            "CREATE VIEW ${t(side, "v_big")} AS SELECT id FROM $big",
        )
    }

    @Test
    fun `text ntext and uniqueidentifier columns are searched`() {
        val side = backend.a
        val old = t(side, "old")
        backend.execute(
            side,
            "CREATE TABLE $old (id INT PRIMARY KEY, note NTEXT NULL, memo TEXT NULL, token UNIQUEIDENTIFIER NULL, amount MONEY NULL)",
            "INSERT INTO $old VALUES (1, N'Needle in ntext', 'also in text', '6F9619FF-8B86-D011-B42D-00C04FC964FF', 12.5)",
        )
        val repository = DatabaseSearchRepository(side.manager)
        fun cellsFor(term: String): List<String> = runBlocking {
            val columns = repository.columns(side.namespace).getValue("old")
            val plan = DatabaseSearch.plan(side.namespace, "old", columns, term, SearchMode.CONTAINS, 5, SqlServerDialect)!!
            // A row carries every searched cell that has a value; the ones holding the term are the hits.
            repository.search(plan).flatMap { row -> row.cells.filter { DatabaseSearch.matchRange(it.second, term, SearchMode.CONTAINS) != null }.map { it.first } }
        }
        assertEquals(listOf("note"), cellsFor("NEEDLE"))
        assertEquals(listOf("memo"), cellsFor("also in"))
        assertEquals(listOf("token"), cellsFor("8b86-d011"))
        // 12.5 is a number term, so the money column is looked in as well.
        assertEquals(listOf("amount"), cellsFor("12.5"))
    }

    @Test
    fun `a text column is cut to the cell limit with a statement that runs`() {
        val side = backend.a
        val docs = t(side, "docs")
        searchFixture(side, backend)
        val columns = runBlocking { DatabaseSearchRepository(side.manager).columns(side.namespace) }.getValue("docs")
        val plan = DatabaseSearch.plan(side.namespace, "docs", columns, "x", SearchMode.CONTAINS, 5, SqlServerDialect)!!
        assertTrue(plan.sql, plan.sql.contains("OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY"))
        assertTrue(plan.sql, plan.sql.contains("FROM $docs"))
    }

    @Test
    fun `index use counts from the server start`() {
        storageFixture(backend.a, backend)
        val snapshot = runBlocking { StorageRepository(backend.a.manager).load(backend.a.namespace) }
        val unused = snapshot.unused
        assertTrue("$unused", unused is StorageSource.Loaded)
        assertTrue("uptime ${snapshot.uptimeSeconds}", (snapshot.uptimeSeconds ?: -1) >= 0)
    }
}
