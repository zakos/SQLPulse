package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.schema.ServerFlavor
import hu.laurel.sqlpulse.data.schema.StorageRepository
import hu.laurel.sqlpulse.data.schema.StorageSnapshot
import hu.laurel.sqlpulse.data.schema.StorageSource
import hu.laurel.sqlpulse.data.schema.UnavailableKind
import hu.laurel.sqlpulse.data.schema.quoteIdentifier
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SqlSession
import java.math.BigInteger
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The storage screen's queries against a real server (see [TestServer]).
 *
 * A database of its own is seeded with a table that has real rows and indexes, two tables whose
 * AUTO_INCREMENT counters sit near the end of a small integer type, and a view that must not show
 * up. What the server exposes differs a lot between MySQL, MariaDB and the grants of the user, so
 * the sections that depend on it are asserted in both of their legitimate forms: loaded with the
 * right content, or unavailable with a stated reason — never an exception.
 */
class StorageIntegrationTest {

    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession
    private lateinit var storage: StorageRepository
    private lateinit var version: String

    private val database = "sqlpulse_it_storage_${System.nanoTime()}"

    @Before
    fun connect() {
        val found = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlSession(config)
        storage = StorageRepository(IntegrationSessions.manager(session, config.database))
        session.use { connection ->
            version = connection.scalar("SELECT VERSION()")
            connection.execute("CREATE DATABASE ${q(database)} DEFAULT CHARACTER SET utf8mb4")
            // The one with substance. idx_a is a prefix of idx_a_b, which is what the redundancy
            // report exists to find; uq_b is unique and must never be called unused or redundant.
            connection.execute(
                """
                CREATE TABLE ${q(database)}.big (
                    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    a INT NOT NULL,
                    b VARCHAR(40) NOT NULL,
                    payload VARCHAR(200) NOT NULL,
                    KEY idx_a (a),
                    KEY idx_a_b (a, b),
                    UNIQUE KEY uq_b (b)
                ) ENGINE=InnoDB
                """.trimIndent(),
            )
            // Nothing fancy: a recursive CTE would not run on the 5.x servers the suite also meets.
            connection.execute("CREATE TABLE ${q(database)}.seq (n INT NOT NULL PRIMARY KEY) ENGINE=InnoDB")
            connection.execute(
                "INSERT INTO ${q(database)}.seq VALUES " + (1..20).joinToString(",") { "($it)" },
            )
            connection.execute(
                """
                INSERT INTO ${q(database)}.big (a, b, payload)
                SELECT x.n * 20 + y.n, CONCAT('b-', x.n, '-', y.n), REPEAT('p', 150)
                FROM ${q(database)}.seq x JOIN ${q(database)}.seq y
                """.trimIndent(),
            )
            // Counters pushed near the end of their type without inserting hundreds of rows.
            connection.execute(
                "CREATE TABLE ${q(database)}.tiny (id TINYINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, v INT) " +
                    "ENGINE=InnoDB AUTO_INCREMENT=240",
            )
            connection.execute(
                "CREATE TABLE ${q(database)}.small (id SMALLINT NOT NULL AUTO_INCREMENT PRIMARY KEY, v INT) " +
                    "ENGINE=InnoDB AUTO_INCREMENT=100",
            )
            connection.execute("INSERT INTO ${q(database)}.tiny (v) VALUES (1), (2)")
            connection.execute("CREATE VIEW ${q(database)}.big_view AS SELECT id, a FROM ${q(database)}.big")
            // Statistics are otherwise whatever the server last got round to computing.
            listOf("big", "seq", "tiny", "small").forEach { connection.execute("ANALYZE TABLE ${q(database)}.$it") }
        }
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) {
            runCatching { session.use { it.execute("DROP DATABASE IF EXISTS ${q(database)}") } }
        }
    }

    private fun load(): StorageSnapshot = runBlocking { storage.load(database) }

    @Test
    fun `lists base tables with sizes and row estimates and leaves views out`() {
        val snapshot = load()

        assertEquals(listOf("big", "seq", "small", "tiny"), snapshot.tables.map { it.name })
        assertFalse("a view is not storage", snapshot.tables.any { it.name == "big_view" })

        val big = snapshot.tables.first { it.name == "big" }
        assertEquals("InnoDB", big.engine)
        assertTrue("data length: ${big.dataBytes}", big.dataBytes > 0)
        assertTrue("index length: ${big.indexBytes}", big.indexBytes > 0)
        assertEquals(big.dataBytes + big.indexBytes, big.totalBytes)
        // 400 rows were inserted; InnoDB's estimate is allowed to be off, but not absurd.
        val rows = big.rowsEstimate
        assertNotNull(rows)
        assertTrue("row estimate: $rows", rows!! in 200..800)
        assertNotNull("create time", big.createTime)
        assertNotNull("collation", big.collation)
    }

    @Test
    fun `computes AUTO_INCREMENT headroom for a small integer key near its end`() {
        val snapshot = load()

        val tiny = snapshot.tables.first { it.name == "tiny" }
        // Started at 240 and two rows went in, so the next value is 242 of at most 255.
        assertEquals(BigInteger.valueOf(242), tiny.autoIncrement)
        assertTrue("column type: ${tiny.autoIncrementType}", tiny.autoIncrementType.orEmpty().startsWith("tinyint"))
        assertEquals(242.0 / 255.0, tiny.autoIncrementUsage!!, 0.0001)
        assertTrue(tiny.autoIncrementWarning)

        val small = snapshot.tables.first { it.name == "small" }
        assertEquals(BigInteger.valueOf(100), small.autoIncrement)
        assertEquals(100.0 / 32_767.0, small.autoIncrementUsage!!, 0.0001)
        assertFalse(small.autoIncrementWarning)

        // A table with no AUTO_INCREMENT column has no headroom to report.
        val seq = snapshot.tables.first { it.name == "seq" }
        assertNull(seq.autoIncrementUsage)
        assertFalse(seq.autoIncrementWarning)
    }

    @Test
    fun `reports index sizes when the statistics table is readable and says so when not`() {
        val snapshot = load()
        val big = snapshot.tables.first { it.name == "big" }

        if (snapshot.indexSizesAvailable) {
            val names = big.indexes.map { it.index }
            assertTrue("secondary indexes: $names", names.containsAll(listOf("idx_a", "idx_a_b", "uq_b")))
            assertFalse("PRIMARY is the data itself", "PRIMARY" in names)
            assertTrue(big.indexes.all { it.bytes > 0 })
            assertEquals("biggest first", big.indexes.sortedByDescending { it.bytes }, big.indexes)
        } else {
            // Not readable is an acceptable answer; a half-filled table would not be.
            assertTrue(snapshot.tables.all { it.indexes.isEmpty() })
        }
    }

    @Test
    fun `the unused and redundant sections are loaded or unavailable with a reason`() {
        val snapshot = load()
        val mariaDb = ServerFlavor.isMariaDb(version)

        when (val redundant = snapshot.redundant) {
            is StorageSource.Loaded -> {
                assertFalse("MariaDB has no sys views", mariaDb)
                val found = redundant.value.filter { it.table == "big" }
                // idx_a is the prefix of idx_a_b; the unique key is never offered for removal.
                assertTrue("redundant: $found", found.any { it.index == "idx_a" && it.coveredBy == "idx_a_b" })
                assertFalse(found.any { it.index == "uq_b" })
            }
            is StorageSource.Unavailable -> {
                if (mariaDb) assertEquals(UnavailableKind.NOT_ON_SERVER, redundant.kind)
                // Anything else is a server without the sys schema or a user who may not read it.
                else assertEquals(UnavailableKind.FAILED, redundant.kind)
            }
        }

        when (val unused = snapshot.unused) {
            is StorageSource.Loaded -> {
                assertEquals("userstat is MariaDB's counter", mariaDb, unused.value.fromUserstat)
                val ours = unused.value.indexes.filter { it.table == "big" }.map { it.index }
                assertFalse("PRIMARY is never unused", "PRIMARY" in ours)
                assertFalse("a unique index enforces a constraint whether or not it is read", "uq_b" in ours)
            }
            is StorageSource.Unavailable -> {
                val expected = if (mariaDb) {
                    setOf(UnavailableKind.USERSTAT_OFF, UnavailableKind.FAILED)
                } else {
                    setOf(UnavailableKind.PERFORMANCE_SCHEMA_OFF, UnavailableKind.FAILED)
                }
                assertTrue("unavailable because: ${unused.kind}", unused.kind in expected)
                if (unused.kind == UnavailableKind.FAILED) assertFalse(unused.detail.isNullOrBlank())
            }
        }
    }

    @Test
    fun `an index that was read is not reported unused`() {
        // The one case where "unused" has to be right rather than merely well-formed. It needs a
        // server that counts index usage at all, so it stands down on those that do not.
        val before = load().unused
        assumeTrue("index usage is not counted on this server: $before", before is StorageSource.Loaded)

        session.use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM ${q(database)}.big WHERE a = 25").close() }
        }
        val unused = (load().unused as StorageSource.Loaded).value.indexes.filter { it.table == "big" }.map { it.index }
        // The optimizer may answer from idx_a or from idx_a_b, but it answered from one of them.
        assertFalse("an index was read, yet both are called unused: $unused", "idx_a" in unused && "idx_a_b" in unused)
    }

    @Test
    fun `server flavour and uptime are read`() {
        val snapshot = load()
        assertEquals(version.contains("mariadb", ignoreCase = true), ServerFlavor.isMariaDb(version))
        val uptime = snapshot.uptimeSeconds
        assertNotNull(uptime)
        assertTrue(uptime!! >= 0)
        assertEquals(database, snapshot.database)
    }

    private fun q(name: String) = quoteIdentifier(name)

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.scalar(sql: String): String =
        createStatement().use { statement -> statement.executeQuery(sql).use { it.next(); it.getString(1) } }
}
