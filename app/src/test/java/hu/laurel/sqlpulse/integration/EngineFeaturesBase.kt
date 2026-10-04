package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.schema.CachedColumn
import hu.laurel.sqlpulse.data.schema.CachedForeignKey
import hu.laurel.sqlpulse.data.schema.CachedIndex
import hu.laurel.sqlpulse.data.schema.CachedStructure
import hu.laurel.sqlpulse.data.schema.CachedTable
import hu.laurel.sqlpulse.data.schema.DiffField
import hu.laurel.sqlpulse.data.schema.DiffStatus
import hu.laurel.sqlpulse.data.schema.SchemaDiff
import hu.laurel.sqlpulse.data.schema.SchemaDiffResult
import hu.laurel.sqlpulse.data.schema.SchemaDiffSide
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.StorageRepository
import hu.laurel.sqlpulse.data.schema.StorageSnapshot
import hu.laurel.sqlpulse.data.schema.StorageSource
import hu.laurel.sqlpulse.data.schema.UnavailableKind
import hu.laurel.sqlpulse.data.search.DatabaseSearch
import hu.laurel.sqlpulse.data.search.DatabaseSearchRepository
import hu.laurel.sqlpulse.data.search.SearchMode
import hu.laurel.sqlpulse.data.search.SearchRow
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.dialect.EngineFeature
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialect
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One database or schema of a backend, as the repositories address it. */
class EngineSide(val manager: SqlSessionManager, val namespace: String)

/** A live engine for [EngineFeaturesBase]: a dialect, two comparable namespaces, and a way to run DDL. */
interface EngineBackend : AutoCloseable {
    val dialect: SqlDialect
    val a: EngineSide
    val b: EngineSide
    fun execute(side: EngineSide, vararg statements: String)
}

/**
 * Database search, schema comparison and the storage screen against one real engine.
 *
 * The three features were written against MySQL's catalog; what each engine's subclass supplies is
 * only its own spelling of the fixtures (DDL, inserts), so every assertion here is the same for
 * PostgreSQL, SQL Server and SQLite: a literal `%`, `_`, `!` or `[` in a term, a number term
 * reaching number columns, a cell cut on the server, a row limit, types spelled differently on the
 * two sides, and sizes and counters read through the engine's own catalog.
 */
abstract class EngineFeaturesBase {

    /** Connects (skipping the test through `Assume` when there is nothing to connect to). */
    protected abstract fun connect(): EngineBackend

    /** `docs(id PK, title text, body text, qty integer, payload binary)` plus the rows [DOCS_ROWS] describes. */
    protected abstract fun searchFixture(side: EngineSide, backend: EngineBackend)

    /** Both sides of the comparison; see [diffExpectations]. */
    protected abstract fun diffFixture(backend: EngineBackend)

    /** Both sides of the objects comparison; the contract is in [SchemaObjectsSupport]. */
    protected abstract fun objectsFixture(backend: EngineBackend)

    /** `big` (indexed, with rows), `counter` (an identity near the end of its type) and a view. */
    protected abstract fun storageFixture(side: EngineSide, backend: EngineBackend)

    /** Substrings of the two spellings the `customers.email` type differs by, as the catalog reports them. */
    protected open val emailTypeA: String = "100"
    protected open val emailTypeB: String = "255"

    /** Whether the engine counts a next value for identity or serial columns. */
    protected open val hasCounterHeadroom: Boolean = true

    /** False where the engine keeps no index usage at all (SQLite). */
    protected open val hasUnusedIndexStats: Boolean = true

    protected lateinit var backend: EngineBackend

    private val dialect get() = backend.dialect

    @Before
    fun open() {
        backend = connect()
    }

    @After
    fun close() {
        if (this::backend.isInitialized) backend.close()
    }

    // ------------------------------------------------------------------ the engine advertises them

    @Test
    fun `the three features are advertised`() {
        assertTrue(dialect.supports(EngineFeature.DATABASE_SEARCH))
        assertTrue(dialect.supports(EngineFeature.SCHEMA_DIFF))
        assertTrue(dialect.supports(EngineFeature.STORAGE))
    }

    // ------------------------------------------------------------------ search

    private fun searchRepository() = DatabaseSearchRepository(backend.a.manager)

    private var searchSeeded = false

    private fun runSearch(term: String, mode: SearchMode, rowLimit: Int): Map<String, List<SearchRow>> = runBlocking {
        val repository = searchRepository()
        val columns = repository.columns(backend.a.namespace)
        columns.mapNotNull { (table, cols) ->
            val plan = DatabaseSearch.plan(backend.a.namespace, table, cols, term, mode, rowLimit, dialect) ?: return@mapNotNull null
            table to repository.search(plan)
        }.toMap().filterValues { it.isNotEmpty() }
    }

    private fun seedSearch() {
        if (!searchSeeded) {
            searchFixture(backend.a, backend)
            searchSeeded = true
        }
    }

    private fun hits(term: String, mode: SearchMode = SearchMode.CONTAINS, rowLimit: Int = 50): List<SearchRow> {
        seedSearch()
        return runSearch(term, mode, rowLimit)["docs"].orEmpty()
    }

    private fun ids(rows: List<SearchRow>): Set<String?> = rows.map { it.key.single().second }.toSet()

    @Test
    fun `the column listing carries types and the primary key`() {
        seedSearch()
        val columns = runBlocking { searchRepository().columns(backend.a.namespace) }.getValue("docs")
        assertEquals(listOf("id"), columns.filter { it.isPrimaryKey }.map { it.name })
        assertTrue(columns.map { it.name }.containsAll(listOf("id", "title", "body", "qty", "payload")))
        assertTrue(columns.all { it.typeName.isNotBlank() })
    }

    @Test
    fun `a binary column is never searched`() {
        seedSearch()
        val columns = runBlocking { searchRepository().columns(backend.a.namespace) }.getValue("docs")
        val plan = DatabaseSearch.plan(backend.a.namespace, "docs", columns, "x", SearchMode.CONTAINS, 5, dialect)!!
        assertFalse("payload" in plan.searchColumns)
        assertTrue("title" in plan.searchColumns && "body" in plan.searchColumns)
        // A word does not reach the number column either.
        assertFalse("qty" in plan.searchColumns)
    }

    @Test
    fun `a search ignores case`() {
        assertEquals(setOf("1"), ids(hits("HELLO world")))
        assertEquals(setOf("1"), ids(hits("hello", SearchMode.CONTAINS)))
    }

    @Test
    fun `exact mode matches the whole value`() {
        assertEquals(setOf("2"), ids(hits("CAFE", SearchMode.EXACT)))
        assertEquals(emptySet<String?>(), ids(hits("caf", SearchMode.EXACT)))
    }

    @Test
    fun `a percent sign is searched for literally`() {
        assertEquals(setOf("3"), ids(hits("100%")))
    }

    @Test
    fun `an underscore is searched for literally`() {
        // Row 8 is underXscore: an unescaped underscore would match it as well.
        assertEquals(setOf("4"), ids(hits("under_score")))
    }

    @Test
    fun `the escape character is searched for literally`() {
        assertEquals(setOf("5"), ids(hits("!")))
    }

    @Test
    fun `a square bracket is searched for literally`() {
        assertEquals(setOf("6"), ids(hits("[x]")))
    }

    @Test
    fun `a number term also looks in number columns`() {
        // 100 is row 2's quantity and the start of row 3's title.
        assertEquals(setOf("2", "3"), ids(hits("100")))
    }

    @Test
    fun `a long cell comes back cut`() {
        val row = hits("needle").single()
        assertEquals("7", row.key.single().second)
        val cell = row.cells.single { it.first == "body" }.second
        assertEquals(DatabaseSearch.CELL_CHARS, cell.length)
        assertTrue(cell.startsWith("needle"))
    }

    @Test
    fun `the row limit holds`() {
        seedSearch()
        backend.execute(backend.a, *manyRows())
        assertEquals(3, runSearch("filler", SearchMode.CONTAINS, rowLimit = 3)["docs"]!!.size)
        assertEquals(12, runSearch("filler", SearchMode.CONTAINS, rowLimit = 50)["docs"]!!.size)
    }

    /** Twelve rows with "filler" in the title; the engine's subclass says how a row is inserted. */
    protected abstract fun manyRows(): Array<String>

    // ------------------------------------------------------------------ schema comparison

    private fun cachedSide(side: EngineSide): SchemaDiffSide = runBlocking {
        val schema = SchemaRepository(side.manager)
        val tables = schema.tables(side.namespace, refresh = true)
        SchemaDiffSide(
            database = side.namespace,
            engine = dialect.engine,
            tables = tables.map {
                CachedTable(
                    database = it.database, name = it.name, kind = it.kind.name,
                    approximateRows = it.approximateRows, comment = it.comment, engine = it.engine,
                    collation = it.collation, dataBytes = it.dataBytes, indexBytes = it.indexBytes,
                )
            },
            structures = tables.associate { table ->
                val structure = schema.structure(side.namespace, table.name, refresh = true)
                table.name to CachedStructure(
                    columns = structure.columns.mapIndexed { position, column ->
                        CachedColumn(
                            column.name, column.typeName, column.nullable, column.defaultValue,
                            column.isPrimaryKey, column.extra, column.comment, position,
                        )
                    },
                    indexes = structure.indexes.mapIndexed { position, index ->
                        CachedIndex(index.name, index.unique, index.columns, position)
                    },
                    foreignKeys = structure.foreignKeys.map {
                        CachedForeignKey(it.constraintName, it.column, it.referencedDatabase, it.referencedTable, it.referencedColumn)
                    },
                )
            },
        )
    }

    private fun compare(): SchemaDiffResult {
        diffFixture(backend)
        return SchemaDiff.compare(cachedSide(backend.a), cachedSide(backend.b))
    }

    @Test
    fun `the deliberate differences are found and the spelling ones are not`() {
        val result = compare()
        // widgets is the same table however each side spells its types, defaults and key names.
        assertEquals(listOf("customers", "extra", "legacy"), result.tables.map { it.name })
        assertEquals(DiffStatus.CHANGED, result.tables.single { it.name == "customers" }.status)
        assertEquals(DiffStatus.ONLY_B, result.tables.single { it.name == "extra" }.status)
        assertEquals(DiffStatus.ONLY_A, result.tables.single { it.name == "legacy" }.status)
        assertEquals(1, result.identicalCount)
        assertTrue(result.unchecked.isEmpty())
    }

    @Test
    fun `a changed column says what changed in it`() {
        val customers = compare().tables.single { it.name == "customers" }
        val byName = customers.columns.associateBy { it.name }
        assertEquals(setOf("email", "name"), byName.keys)

        val email = byName.getValue("email").changes.single()
        assertEquals(DiffField.TYPE, email.field)
        assertTrue(email.a, email.a!!.contains(emailTypeA))
        assertTrue(email.b, email.b!!.contains(emailTypeB))

        val name = byName.getValue("name").changes.single()
        assertEquals(DiffField.NULLABLE, name.field)
        assertEquals("NULL", name.a)
        assertEquals("NOT NULL", name.b)
    }

    @Test
    fun `views, triggers, checks and foreign key rules are compared`() {
        objectsFixture(backend)
        val result = SchemaDiff.compare(
            SchemaObjectsSupport.side(backend.a.manager, backend.a.namespace, dialect.engine),
            SchemaObjectsSupport.side(backend.b.manager, backend.b.namespace, dialect.engine),
        )
        SchemaObjectsSupport.assertFixtureDifferences(result)
    }

    // ------------------------------------------------------------------ storage

    private var storageSeeded = false

    private fun storage(): StorageSnapshot {
        if (!storageSeeded) {
            storageFixture(backend.a, backend)
            storageSeeded = true
        }
        return runBlocking { StorageRepository(backend.a.manager).load(backend.a.namespace) }
    }

    @Test
    fun `storage lists the tables with sizes and leaves the view out`() {
        val snapshot = storage()
        assertEquals(dialect.engine, snapshot.engine)
        assertEquals(setOf("big", "counter"), snapshot.tables.map { it.name }.toSet())
        val big = snapshot.tables.single { it.name == "big" }
        assertTrue("sizes are readable", snapshot.sizesAvailable)
        assertTrue("big has data: ${big.dataBytes}", big.dataBytes > 0)
        assertTrue("big has an index: ${big.indexBytes}", big.indexBytes > 0)
        // PostgreSQL's count is the statistics collector's, which lags a moment behind the inserts.
        assertTrue("rows ${big.rowsEstimate}", big.rowsEstimate in 1L..200L)
        assertNull(big.freeBytes.takeIf { it < 0 })
    }

    @Test
    fun `storage names the index sizes of a table`() {
        val big = storage().tables.single { it.name == "big" }
        val index = big.indexes.single { it.index == "idx_a_b" }
        assertTrue(index.bytes > 0)
    }

    @Test
    fun `an identity near the end of its type is a warning`() {
        if (!hasCounterHeadroom) return
        val counter = storage().tables.single { it.name == "counter" }
        assertNotNull(counter.autoIncrement)
        assertTrue("usage ${counter.autoIncrementUsage}", counter.autoIncrementWarning)
        // The other table's counter is nowhere near.
        assertFalse(storage().tables.single { it.name == "big" }.autoIncrementWarning)
    }

    @Test
    fun `unused indexes are listed or the section says why not`() {
        val unused = storage().unused
        if (!hasUnusedIndexStats) {
            assertEquals(StorageSource.Unavailable(UnavailableKind.NOT_FOR_ENGINE), unused)
            return
        }
        when (unused) {
            is StorageSource.Loaded -> assertTrue(
                unused.value.indexes.any { it.table == "big" && it.index == "idx_a_b" },
            )
            is StorageSource.Unavailable -> assertNotNull(unused.kind)
        }
    }

    protected fun docsRows(insert: (id: Int, title: String?, body: String?, qty: Int?) -> String): List<String> = listOf(
        insert(1, "Árvíztűrő tükörfúrógép", "Hello World", 10),
        insert(2, "cafe", "Café au lait", 100),
        insert(3, "100% pure", "a_b", 5),
        insert(4, "under_score", "plain", 7),
        insert(8, "underXscore", "plain", null),
        insert(5, "bang!", "wow!!", null),
        insert(6, "brackets", "[x] list", null),
        // The term sits at the start, so what comes back must be the cut-off first thousand.
        insert(7, "long", "needle" + "x".repeat(3000), null),
    )

    protected fun sqlText(value: String?): String = value?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
}
