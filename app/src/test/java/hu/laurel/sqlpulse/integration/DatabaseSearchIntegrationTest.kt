package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.quoteIdentifier
import hu.laurel.sqlpulse.data.search.DatabaseSearch
import hu.laurel.sqlpulse.data.search.DatabaseSearchRepository
import hu.laurel.sqlpulse.data.search.SearchMode
import hu.laurel.sqlpulse.data.search.SearchRow
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SqlSession
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
 * "Search the whole database" against a seeded schema on a real server (see [TestServer]): the
 * column listing [DatabaseSearchRepository] reads from information_schema, the per-table
 * statement [DatabaseSearch] builds from it, and what comes back.
 *
 * The terms are the ones that break a naive LIKE — `%`, `_`, the escape character itself, a
 * backslash, accents, emoji, a number — because what the unit tests cannot say is how this server
 * reads the pattern it is handed.
 *
 * The data lives in a database of its own: the search walks every table of a schema, and tables
 * left by other tests would be hits.
 */
class DatabaseSearchIntegrationTest {

    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession
    private lateinit var repository: DatabaseSearchRepository

    private val database = "sqlpulse_it_search_${System.nanoTime()}"

    @Before
    fun connect() {
        val found = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlSession(config)
        repository = DatabaseSearchRepository(IntegrationSessions.manager(session, database))
        session.use { connection ->
            connection.execute("CREATE DATABASE ${q(database)} DEFAULT CHARACTER SET utf8mb4")
            connection.execute(
                """
                CREATE TABLE ${q(database)}.docs (
                    id INT PRIMARY KEY,
                    title VARCHAR(100) NULL,
                    body LONGTEXT NULL,
                    qty INT NULL,
                    price DECIMAL(8,2) NULL,
                    kind ENUM('red', 'green') NULL,
                    tags SET('alpha', 'beta') NULL,
                    created DATETIME NULL,
                    payload BLOB NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent(),
            )
            connection.execute(
                """
                INSERT INTO ${q(database)}.docs (id, title, body, qty, price, kind, tags, created, payload) VALUES
                    (1, 'Árvíztűrő tükörfúrógép', 'Hello World', 10, 9.99, NULL, NULL, '2024-05-06 07:08:09', NULL),
                    (2, 'cafe', 'Café au lait', 100, NULL, NULL, 'alpha,beta', NULL, NULL),
                    (3, '100% pure', 'a_b', 5, NULL, NULL, NULL, NULL, NULL),
                    (4, 'under_score', 'underXscore', 7, NULL, NULL, NULL, NULL, NULL),
                    (5, 'bang!', 'wow!!', NULL, NULL, NULL, NULL, NULL, NULL),
                    (6, NULL, NULL, NULL, 3.14, 'green', NULL, NULL, 0x48656C6C6F),
                    (7, 'path', 'C:\\temp\\new', NULL, NULL, NULL, NULL, NULL, NULL),
                    (8, 'emoji', 'smile 😀 here', NULL, NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
            // A long cell with the term past the point where the app cuts what it brings back.
            connection.execute(
                "INSERT INTO ${q(database)}.docs (id, title, body) VALUES " +
                    "(9, 'long', CONCAT(REPEAT('x', 1500), 'needle'))",
            )
            // No primary key: a hit cannot be keyed, and must still be found.
            connection.execute("CREATE TABLE ${q(database)}.plain (label VARCHAR(50)) DEFAULT CHARSET=utf8mb4")
            connection.execute("INSERT INTO ${q(database)}.plain VALUES ('hello plain'), (NULL)")
            // Nothing in here is worth looking in.
            connection.execute(
                "CREATE TABLE ${q(database)}.blobs (id INT PRIMARY KEY, data BLOB, at DATETIME) DEFAULT CHARSET=utf8mb4",
            )
            connection.execute("INSERT INTO ${q(database)}.blobs VALUES (1, 0x68656C6C6F, '2020-01-01 00:00:00')")
        }
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) {
            runCatching { session.use { it.execute("DROP DATABASE IF EXISTS ${q(database)}") } }
            session.close()
        }
    }

    @Test
    fun `the column listing carries the types and keys the search decides on`() {
        val columns = runBlocking { repository.columns(database) }

        assertEquals(setOf("docs", "plain", "blobs"), columns.keys)
        val docs = columns.getValue("docs").associateBy { it.name }
        assertEquals(listOf("id", "title", "body", "qty", "price", "kind", "tags", "created", "payload"), columns.getValue("docs").map { it.name })
        assertTrue(docs.getValue("id").isPrimaryKey)
        assertFalse(docs.getValue("title").isPrimaryKey)
        assertEquals("varchar", DatabaseSearch.baseType(docs.getValue("title").typeName))
        assertEquals("enum", DatabaseSearch.baseType(docs.getValue("kind").typeName))
        assertEquals("decimal", DatabaseSearch.baseType(docs.getValue("price").typeName))
        // Whatever name the server gives a BLOB, the search leaves it out.
        assertFalse(DatabaseSearch.isSearchable(docs.getValue("payload").typeName, numbersToo = true))
        assertFalse(DatabaseSearch.isSearchable(docs.getValue("created").typeName, numbersToo = true))
    }

    @Test
    fun `a word is found whatever its case, in text, with the key and the cell`() {
        val hits = search("hello")

        // Not the BLOB that holds the same five bytes, and not the BLOB-only table.
        assertEquals(setOf("docs", "plain"), hits.keys)
        val doc = hits.getValue("docs").single()
        assertEquals(listOf("id" to "1"), doc.key)
        // The row comes back whole (title as well), with the hit among its cells.
        assertEquals(listOf("title" to "Árvíztűrő tükörfúrógép", "body" to "Hello World"), doc.cells)
        assertEquals(listOf("Hello World"), cells(hits, "docs", "body"))
        assertEquals(listOf("hello plain"), hits.getValue("plain").single().cells.map { it.second })
        // No primary key, no key columns — and still a hit.
        assertEquals(emptyList<Pair<String, String?>>(), hits.getValue("plain").single().key)
    }

    @Test
    fun `accented and non latin text is matched and the cell comes back intact`() {
        assertEquals(listOf("Árvíztűrő tükörfúrógép"), cells(search("TÜKÖR"), "docs", "title"))
        assertEquals(listOf("Café au lait"), cells(search("café"), "docs", "body"))
        assertEquals(listOf("smile 😀 here"), cells(search("😀"), "docs", "body"))
    }

    @Test
    fun `a percent sign in the term is a percent sign`() {
        // Unescaped, "%" is a pattern that matches every text cell there is.
        val hits = search("%")
        assertEquals(listOf("100% pure"), cells(hits, "docs", "title"))
        assertEquals(1, hits.getValue("docs").size)
        assertEquals(setOf("docs"), hits.keys)
    }

    @Test
    fun `an underscore in the term is an underscore`() {
        val hits = search("_")
        // Unescaped it would also hit "underXscore" and everything else with a character in it.
        assertEquals(setOf("3", "4"), hits.getValue("docs").map { it.key.single().second }.toSet())
        assertEquals(setOf("docs"), hits.keys)
    }

    @Test
    fun `the escape character itself in the term is found literally`() {
        assertEquals(setOf("5"), search("!").getValue("docs").map { it.key.single().second }.toSet())
        assertEquals(listOf("wow!!"), cells(search("!!"), "docs", "body"))
        // A term that is all pattern: "!%" means an exclamation mark followed by a percent sign.
        assertTrue(search("!%").isEmpty())
        assertTrue(search("%!").isEmpty())
    }

    @Test
    fun `a backslash is matched as one, whatever the server does with backslashes in strings`() {
        // The term is bound, never written into the statement, and the escape is "!" — so a
        // server with NO_BACKSLASH_ESCAPES and one without must agree on this.
        assertEquals(listOf("C:\\temp\\new"), cells(search("C:\\temp"), "docs", "body"))
        assertEquals(listOf("C:\\temp\\new"), cells(search("\\new"), "docs", "body"))
    }

    @Test
    fun `an exact search matches the whole cell and takes the wildcards literally`() {
        assertEquals(listOf("cafe"), cells(search("CAFE", SearchMode.EXACT), "docs", "title"))
        assertTrue(search("caf", SearchMode.EXACT).isEmpty())
        // "under_score" as an exact term is not a pattern for "underXscore".
        val hits = search("under_score", SearchMode.EXACT)
        assertEquals(listOf("under_score"), cells(hits, "docs", "title"))
        assertNull(cells(hits, "docs", "body").firstOrNull())
    }

    @Test
    fun `a numeric term also looks in the number columns`() {
        val hits = search("100")
        // The quantity 100, and the text "100% pure" it also appears in.
        assertEquals(setOf("2", "3"), hits.getValue("docs").map { it.key.single().second }.toSet())
        assertEquals(listOf("100"), cells(hits, "docs", "qty"))

        assertEquals(listOf("9.99"), cells(search("9.99"), "docs", "price"))
        assertEquals(listOf("3.14"), cells(search("3.14", SearchMode.EXACT), "docs", "price"))
        // An exact number is the whole number: 10 is not 100.
        val exact = search("10", SearchMode.EXACT).getValue("docs")
        assertEquals(listOf("1"), exact.map { it.key.single().second })
    }

    @Test
    fun `a word does not look in the number columns`() {
        val columns = runBlocking { repository.columns(database) }.getValue("docs")
        val plan = DatabaseSearch.plan(database, "docs", columns, "alpha", SearchMode.CONTAINS, 50)!!
        assertEquals(listOf("title", "body", "kind", "tags"), plan.searchColumns)
        // Text, enum and set are looked in; ids, numbers, dates and BLOBs are not.
        assertEquals(listOf("id"), plan.keyColumns)
        assertEquals(setOf("2"), search("alpha").getValue("docs").map { it.key.single().second }.toSet())
        assertEquals(setOf("6"), search("green").getValue("docs").map { it.key.single().second }.toSet())
    }

    @Test
    fun `NULL cells are not hits and are left out of the row`() {
        val hits = search("e")
        val byKey = hits.getValue("docs").associateBy { it.key.single().second }
        // Row 6 is mostly NULL: its title and body cannot contain anything, its enum does.
        assertEquals(listOf("kind" to "green"), byKey.getValue("6").cells)
        // And no cell of any row is ever a null that the screen would have to print.
        hits.values.flatten().forEach { row -> row.cells.forEach { assertNotNull(it.second) } }
    }

    @Test
    fun `a long cell is cut on the server while the row is still found by the whole value`() {
        val row = search("needle").getValue("docs").single()
        val body = row.cells.single { it.first == "body" }.second
        assertEquals(DatabaseSearch.CELL_CHARS, body.length)
        // The term is past the cut, so the app has nothing to highlight; the row is shown anyway.
        assertNull(DatabaseSearch.matchRange(body, "needle", SearchMode.CONTAINS))
    }

    @Test
    fun `each table is limited on its own`() {
        val columns = runBlocking { repository.columns(database) }.getValue("docs")
        val plan = DatabaseSearch.plan(database, "docs", columns, "e", SearchMode.CONTAINS, rowLimit = 2)!!
        assertEquals(2, runBlocking { repository.search(plan) }.size)
    }

    @Test
    fun `a table with nothing worth looking in gets no statement`() {
        val columns = runBlocking { repository.columns(database) }
        assertNull(DatabaseSearch.plan(database, "blobs", columns.getValue("blobs"), "hello", SearchMode.CONTAINS, 50))
        // With a number the id is worth a look, and the BLOB and the date still are not.
        val numeric = DatabaseSearch.plan(database, "blobs", columns.getValue("blobs"), "1", SearchMode.CONTAINS, 50)!!
        assertEquals(listOf("id"), numeric.searchColumns)
    }

    @Test
    fun `a term that is not there finds nothing and a blank one builds no statement`() {
        assertTrue(search("zzz-not-in-the-data").isEmpty())
        val columns = runBlocking { repository.columns(database) }.getValue("docs")
        assertNull(DatabaseSearch.plan(database, "docs", columns, "   ", SearchMode.CONTAINS, 50))
    }

    // ------------------------------------------------------------------------------------------

    /** The whole search, as the screen runs it: every table of the database in turn. */
    private fun search(term: String, mode: SearchMode = SearchMode.CONTAINS): Map<String, List<SearchRow>> =
        runBlocking {
            lastTerm = term
            lastMode = mode
            val result = linkedMapOf<String, List<SearchRow>>()
            repository.columns(database).forEach { (table, columns: List<SchemaColumn>) ->
                val plan = DatabaseSearch.plan(database, table, columns, term, mode, rowLimit = 100) ?: return@forEach
                val rows = repository.search(plan)
                if (rows.isNotEmpty()) result[table] = rows
            }
            result
        }

    private var lastTerm = ""
    private var lastMode = SearchMode.CONTAINS

    /**
     * The cells of one column that hold the term of the last [search], in the order they came.
     *
     * A row is returned whole — every non-NULL searched cell, hit or not, so the screen can show
     * the row — and it is the app's own [DatabaseSearch.matchRange] that says which cell is the hit.
     */
    private fun cells(hits: Map<String, List<SearchRow>>, table: String, column: String): List<String> =
        hits[table].orEmpty().flatMap { row ->
            row.cells
                .filter { it.first == column && DatabaseSearch.matchRange(it.second, lastTerm, lastMode) != null }
                .map { it.second }
        }

    private fun q(name: String) = quoteIdentifier(name)

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}
