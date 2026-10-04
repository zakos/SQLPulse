package hu.laurel.sqlpulse.integration.sqlite

import hu.laurel.sqlpulse.data.schema.TableKind
import hu.laurel.sqlpulse.data.sql.dialect.SqliteCatalog
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The schema browser's reads, against a real SQLite file (no server, so these always run).
 */
class SqliteCatalogIntegrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var file: File

    @Before
    fun makeFile() {
        file = SqliteFixture.create(folder.newFile("fixture.sqlite"))
    }

    private fun <T> read(block: (java.sql.Connection) -> T): T =
        SqliteFixture.session(file, readOnly = true).let { session ->
            try {
                session.use(block)
            } finally {
                session.close()
            }
        }

    @Test
    fun `the only namespace is main`() {
        assertEquals(listOf("main"), read { SqliteCatalog.namespaces(it) })
    }

    @Test
    fun `tables and views are listed with internal tables last`() {
        val tables = read { SqliteCatalog.tables(it, "main") }
        assertEquals(
            listOf("big_orders", "customer", "kv", "line", "orders", "tag", "sqlite_sequence"),
            tables.map { it.name },
        )
        assertEquals(TableKind.VIEW, tables.first { it.name == "big_orders" }.kind)
        assertEquals(TableKind.TABLE, tables.first { it.name == "customer" }.kind)
        // What SQLite cannot say without scanning is not made up.
        assertNull(tables.first { it.name == "customer" }.approximateRows)
    }

    @Test
    fun `columns carry type, nullability, defaults and the key`() {
        val columns = read { SqliteCatalog.columns(it, "main", "customer") }.associateBy { it.name }
        assertEquals(listOf("id", "name", "email", "born", "note", "score", "photo"), columns.keys.toList())
        assertEquals("INTEGER", columns.getValue("id").typeName)
        assertTrue(columns.getValue("id").isPrimaryKey)
        assertFalse(columns.getValue("name").nullable)
        assertTrue(columns.getValue("email").nullable)
        // A string default comes back unquoted, like the other engines' defaults.
        assertEquals("n/a", columns.getValue("note").defaultValue)
        assertNull(columns.getValue("born").defaultValue)
    }

    @Test
    fun `only a lone INTEGER key is the rowid alias that fills itself in`() {
        val orders = read { SqliteCatalog.columns(it, "main", "orders") }.associateBy { it.name }
        assertEquals("auto_increment", orders.getValue("id").extra)
        // INT is an ordinary column and the composite key fills nothing in.
        val line = read { SqliteCatalog.columns(it, "main", "line") }
        assertTrue(line.none { it.extra != null })
        // A WITHOUT ROWID table has no rowid to alias.
        assertNull(read { SqliteCatalog.columns(it, "main", "kv") }.first { it.name == "k" }.extra)
    }

    @Test
    fun `a table without a primary key says so by having no key column`() {
        assertTrue(read { SqliteCatalog.columns(it, "main", "tag") }.none { it.isPrimaryKey })
    }

    @Test
    fun `a view has columns too`() {
        assertEquals(listOf("id", "total"), read { SqliteCatalog.columns(it, "main", "big_orders") }.map { it.name })
    }

    @Test
    fun `indexes list the primary key first and skip SQLite's own key index`() {
        val customer = read { SqliteCatalog.indexes(it, "main", "customer") }
        assertEquals("PRIMARY", customer.first().name)
        assertEquals(listOf("id"), customer.first().columns)
        // The UNIQUE on email is an automatic index, still worth listing as unique.
        val email = customer.first { "email" in it.columns }
        assertTrue(email.unique)

        val line = read { SqliteCatalog.indexes(it, "main", "line") }
        assertEquals(listOf("order_id", "n"), line.single { it.name == "PRIMARY" }.columns)
        assertEquals(1, line.count { it.name == "PRIMARY" })

        val orders = read { SqliteCatalog.indexes(it, "main", "orders") }
        assertEquals(listOf("PRIMARY", "orders_placed"), orders.map { it.name })
        assertFalse(orders.last().unique)

        assertEquals(listOf("PRIMARY"), read { SqliteCatalog.indexes(it, "main", "kv") }.map { it.name })
        assertTrue(read { SqliteCatalog.indexes(it, "main", "tag") }.isEmpty())
    }

    @Test
    fun `foreign keys name their rules and resolve an omitted parent column`() {
        val orders = read { SqliteCatalog.foreignKeys(it, "main", "orders") }.single()
        assertEquals("customer", orders.referencedTable)
        assertEquals("id", orders.referencedColumn)
        assertEquals("CASCADE", orders.onDelete)
        assertEquals("customer_id", orders.column)

        // `REFERENCES Orders` — spelled with another case, naming no column.
        val line = read { SqliteCatalog.foreignKeys(it, "main", "line") }.single()
        assertEquals("orders", line.referencedTable)
        assertEquals("id", line.referencedColumn)
    }

    @Test
    fun `tables that point at a table are found through case differences`() {
        val usages = read { SqliteCatalog.referencingKeys(it, "main", "orders") }
        assertEquals(listOf("line"), usages.map { it.childTable })
        assertEquals("order_id", usages.single().childColumn)
        assertEquals("id", usages.single().parentColumn)
        assertEquals("orders", usages.single().parentTable)
    }

    @Test
    fun `links give one edge per foreign key`() {
        val edges = read { SqliteCatalog.links(it, "main") }.map { Triple(it.from, it.to, it.columns) }
        assertEquals(
            setOf(
                Triple("orders", "customer", listOf("customer_id")),
                Triple("line", "orders", listOf("order_id")),
            ),
            edges.toSet(),
        )
    }

    @Test
    fun `column names and primary keys for link guessing`() {
        val tables = read { SqliteCatalog.columnNames(it, "main") }.associateBy { it.table }
        assertEquals(listOf("order_id", "n"), tables.getValue("line").primaryKey)
        assertTrue(tables.getValue("tag").primaryKey.isEmpty())
        assertTrue("sqlite_sequence" !in tables)
    }

    @Test
    fun `the DDL tab is the stored statement followed by the table's indexes`() {
        val ddl = read { SqliteCatalog.ddl(it, "main", "orders") }
        assertTrue(ddl.startsWith("CREATE TABLE orders ("))
        assertTrue(ddl.contains("CREATE INDEX orders_placed ON orders (placed);"))
        // A view's own text, and no index.
        assertTrue(read { SqliteCatalog.ddl(it, "main", "big_orders") }.startsWith("CREATE VIEW big_orders"))
        assertEquals("", read { SqliteCatalog.ddl(it, "main", "nope") })
    }

    @Test
    fun `triggers are listed with their timing and event`() {
        val trigger = read { SqliteCatalog.triggers(it, "main") }.single()
        assertEquals("orders_after_insert", trigger.name)
        assertEquals("orders", trigger.table)
        assertEquals("AFTER", trigger.timing)
        assertEquals("INSERT", trigger.event)
    }

    @Test
    fun `the schema repository's reads work through the dialect`() = runBlocking {
        val session = SqliteFixture.session(file, readOnly = true)
        val manager = hu.laurel.sqlpulse.integration.IntegrationSessions.manager(
            session, "main", readOnly = true, dialect = hu.laurel.sqlpulse.data.sql.dialect.SqliteDialect,
        )
        val repository = hu.laurel.sqlpulse.data.schema.SchemaRepository(manager)
        assertEquals(listOf("main"), repository.databases())
        assertEquals(3L, repository.rowCount("main", "customer"))
        val page = repository.preview("main", "customer", limit = 2, offset = 1)
        assertEquals(2, page.rows.size)
        // The filter's LIKE treats the percent sign in the pattern as a character, not a wildcard.
        val filtered = repository.preview(
            "main", "customer",
            filter = hu.laurel.sqlpulse.data.sql.ColumnFilter(column = "name", contains = "50%"),
        )
        assertEquals(1, filtered.rows.size)
        val structure = repository.structure("main", "orders")
        assertEquals(listOf("id"), structure.primaryKey)
    }
}
