package hu.laurel.sqlpulse.integration.sqlite

import hu.laurel.sqlpulse.data.schema.StorageRepository
import hu.laurel.sqlpulse.data.sql.SqlSession
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqliteDialect
import hu.laurel.sqlpulse.integration.EngineBackend
import hu.laurel.sqlpulse.integration.EngineFeaturesBase
import hu.laurel.sqlpulse.integration.EngineSide
import hu.laurel.sqlpulse.integration.IntegrationSessions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Database search, schema comparison and the storage screen on real SQLite files. No server, so
 * these always run. The comparison puts two files side by side (SQLite has one namespace per file).
 */
class SqliteFeaturesIntegrationTest : EngineFeaturesBase() {

    @get:Rule
    val folder = TemporaryFolder()

    override val hasCounterHeadroom = false
    override val hasUnusedIndexStats = false

    // SQLite files a VARCHAR(100) and a TEXT under one affinity, so the type that differs here is
    // one that crosses affinities.
    override val emailTypeA = "VARCHAR(100)"
    override val emailTypeB = "BLOB"

    override fun connect(): EngineBackend {
        val sessions = mutableListOf<SqlSession>()
        fun side(name: String): Pair<EngineSide, SqlSession> {
            val file = SqliteFixture.create(folder.newFile("$name.sqlite"), emptyList())
            val session = SqliteFixture.session(file)
            sessions += session
            return EngineSide(IntegrationSessions.manager(session, "main", dialect = SqliteDialect), "main") to session
        }
        val (sideA, sessionA) = side("a")
        val (sideB, sessionB) = side("b")
        return object : EngineBackend {
            override val dialect: SqlDialect = SqliteDialect
            override val a = sideA
            override val b = sideB
            override fun execute(side: EngineSide, vararg statements: String) {
                val session = if (side === sideA) sessionA else sessionB
                session.use { c -> statements.forEach { sql -> c.createStatement().use { it.execute(sql) } } }
            }

            override fun close() = sessions.forEach { it.close() }
        }
    }

    override fun searchFixture(side: EngineSide, backend: EngineBackend) {
        backend.execute(
            side,
            "CREATE TABLE docs (id INTEGER PRIMARY KEY, title TEXT, body TEXT, qty INTEGER, payload BLOB)",
            *docsRows { id, title, body, qty ->
                "INSERT INTO docs (id, title, body, qty) VALUES ($id, ${sqlText(title)}, ${sqlText(body)}, ${qty ?: "NULL"})"
            }.toTypedArray(),
        )
    }

    override fun manyRows(): Array<String> = Array(12) {
        "INSERT INTO docs (id, title) VALUES (${100 + it}, 'filler $it')"
    }

    override fun diffFixture(backend: EngineBackend) {
        backend.execute(
            backend.a,
            "CREATE TABLE customers (id INTEGER PRIMARY KEY, email VARCHAR(100) NOT NULL, name VARCHAR(50), referrer INT)",
            // INT/INTEGER, VARCHAR(20)/TEXT and the two spellings of the current time are one column each.
            "CREATE TABLE widgets (id INTEGER PRIMARY KEY, qty INT NOT NULL DEFAULT 0, label VARCHAR(20) DEFAULT 'abc', " +
                "created TEXT DEFAULT CURRENT_TIMESTAMP, ratio REAL)",
            "CREATE TABLE legacy (id INTEGER PRIMARY KEY)",
        )
        backend.execute(
            backend.b,
            "CREATE TABLE customers (id INTEGER PRIMARY KEY, email BLOB NOT NULL, name VARCHAR(50) NOT NULL, referrer INT)",
            "CREATE TABLE widgets (id INTEGER PRIMARY KEY, qty INTEGER NOT NULL DEFAULT 0, label TEXT DEFAULT 'abc', " +
                "created TEXT DEFAULT (CURRENT_TIMESTAMP), ratio DOUBLE)",
            "CREATE TABLE extra (id INTEGER PRIMARY KEY)",
        )
    }

    override fun storageFixture(side: EngineSide, backend: EngineBackend) {
        backend.execute(
            side,
            "CREATE TABLE big (id INTEGER PRIMARY KEY AUTOINCREMENT, a INTEGER NOT NULL, b TEXT NOT NULL, payload TEXT NOT NULL)",
            "WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 200) " +
                "INSERT INTO big (a, b, payload) SELECT n, 'row ' || n, hex(zeroblob(50)) FROM s",
            "CREATE INDEX idx_a_b ON big (a, b)",
            "CREATE TABLE counter (id INTEGER PRIMARY KEY AUTOINCREMENT, v INT)",
            "INSERT INTO counter (v) VALUES (1)",
            "CREATE VIEW v_big AS SELECT id FROM big",
        )
    }

    @Test
    fun `the file has a size and a free list`() {
        storageFixture(backend.a, backend)
        val snapshot = runBlocking { StorageRepository(backend.a.manager).load("main") }
        assertTrue("sizes come from dbstat", snapshot.sizesAvailable)
        assertNotNull(snapshot.fileBytes)
        assertTrue(snapshot.fileBytes!! > 0)
        assertEquals(0L, snapshot.fileFreeBytes)
        // The table sizes add up to something no larger than the file.
        assertTrue(snapshot.tables.sumOf { it.totalBytes } <= snapshot.fileBytes!!)
    }

    @Test
    fun `a column with no declared type is searched`() {
        searchFixture(backend.a, backend)
        backend.execute(backend.a, "ALTER TABLE docs ADD COLUMN loose", "UPDATE docs SET loose = 'untyped treasure' WHERE id = 2")
        val repository = hu.laurel.sqlpulse.data.search.DatabaseSearchRepository(backend.a.manager)
        val columns = runBlocking { repository.columns("main") }.getValue("docs")
        val plan = hu.laurel.sqlpulse.data.search.DatabaseSearch.plan(
            "main", "docs", columns, "treasure", hu.laurel.sqlpulse.data.search.SearchMode.CONTAINS, 5, SqliteDialect,
        )!!
        val rows = runBlocking { repository.search(plan) }
        val hit = rows.single().cells.filter {
            hu.laurel.sqlpulse.data.search.DatabaseSearch.matchRange(it.second, "treasure", hu.laurel.sqlpulse.data.search.SearchMode.CONTAINS) != null
        }
        assertEquals(listOf("loose"), hit.map { it.first })
    }
}
