package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.schema.CachedColumn
import hu.laurel.sqlpulse.data.schema.CachedForeignKey
import hu.laurel.sqlpulse.data.schema.CachedIndex
import hu.laurel.sqlpulse.data.schema.CachedStructure
import hu.laurel.sqlpulse.data.schema.CachedTable
import hu.laurel.sqlpulse.data.schema.DiffField
import hu.laurel.sqlpulse.data.schema.DiffStatus
import hu.laurel.sqlpulse.data.schema.ItemDiff
import hu.laurel.sqlpulse.data.schema.SchemaDiff
import hu.laurel.sqlpulse.data.schema.SchemaDiffOptions
import hu.laurel.sqlpulse.data.schema.SchemaDiffResult
import hu.laurel.sqlpulse.data.schema.SchemaDiffSide
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.schema.quoteIdentifier
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.SqlSession
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The schema comparison on two real databases of one server (see [TestServer]).
 *
 * Both sides are read the way the app's live refresh reads them: [SchemaRepository.tables] and
 * [SchemaRepository.structure] against information_schema, and then turned into the cache's own
 * vocabulary, which is what [SchemaDiff] compares. The two databases differ in a handful of
 * deliberate ways, and in a handful of ways that are only spelling — an integer display width, a
 * spelling of the current time, a self-referencing key that names its own database — which must
 * not be reported, because the same comparison is made between two servers that spell them
 * differently.
 */
class SchemaDiffIntegrationTest {

    private lateinit var config: JdbcConfig
    private lateinit var session: SqlSession
    private lateinit var schema: SchemaRepository

    private val stamp = System.nanoTime()
    private val a = "sqlpulse_it_diff_a_$stamp"
    private val b = "sqlpulse_it_diff_b_$stamp"

    @Before
    fun connect() {
        val found = TestServer.configOrNull()
        assumeTrue("no ${TestServer.URL_VARIABLE}, so there is no server to talk to", found != null)
        config = found!!
        session = SqlSession(config)
        schema = SchemaRepository(IntegrationSessions.manager(session, config.database))
        session.use { connection ->
            listOf(a, b).forEach { connection.execute("CREATE DATABASE ${q(it)} DEFAULT CHARACTER SET utf8mb4") }

            // --- customers: the real differences ---------------------------------------------
            connection.execute(
                """
                CREATE TABLE ${q(a)}.customers (
                    id INT NOT NULL PRIMARY KEY,
                    email VARCHAR(100) NOT NULL,
                    name VARCHAR(50) NULL,
                    status VARCHAR(10) NOT NULL DEFAULT 'new',
                    referrer_id INT NULL,
                    KEY idx_name (name),
                    KEY ix_referrer (referrer_id),
                    CONSTRAINT fk_customers_referrer FOREIGN KEY (referrer_id) REFERENCES customers (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent(),
            )
            // B: a wider email, a name that may not be NULL, another default, a unique index that
            // A does not have, no idx_name, and no foreign key on referrer_id.
            connection.execute(
                """
                CREATE TABLE ${q(b)}.customers (
                    id INT NOT NULL PRIMARY KEY,
                    email VARCHAR(255) NOT NULL,
                    name VARCHAR(50) NOT NULL,
                    status VARCHAR(10) NOT NULL DEFAULT 'active',
                    referrer_id INT NULL,
                    UNIQUE KEY uq_email (email),
                    KEY ix_referrer (referrer_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent(),
            )

            // --- widgets: only spelling differs ----------------------------------------------
            // Display width of an integer, the three spellings of the current time, a self
            // reference that has to name its own database on one side and the other.
            connection.execute(
                """
                CREATE TABLE ${q(a)}.widgets (
                    id INT(11) NOT NULL PRIMARY KEY,
                    qty INT(5) NOT NULL DEFAULT 0,
                    big BIGINT(20) NULL,
                    created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    touched TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    label VARCHAR(20) NULL DEFAULT 'abc',
                    parent_id INT(11) NULL,
                    KEY ix_parent (parent_id),
                    CONSTRAINT fk_widgets_parent FOREIGN KEY (parent_id) REFERENCES widgets (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent(),
            )
            connection.execute(
                """
                CREATE TABLE ${q(b)}.widgets (
                    id INT NOT NULL PRIMARY KEY,
                    qty INT NOT NULL DEFAULT 0,
                    big BIGINT NULL,
                    created TIMESTAMP NOT NULL DEFAULT NOW(),
                    touched TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP() ON UPDATE NOW(),
                    label VARCHAR(20) NULL DEFAULT 'abc',
                    parent_id INT NULL,
                    KEY ix_parent (parent_id),
                    CONSTRAINT fk_widgets_parent FOREIGN KEY (parent_id) REFERENCES widgets (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent(),
            )

            // --- orders: identical, with a key to another table of its own database -----------
            listOf(a, b).forEach { database ->
                connection.execute(
                    """
                    CREATE TABLE ${q(database)}.orders (
                        id INT NOT NULL PRIMARY KEY,
                        customer_id INT NOT NULL,
                        KEY ix_customer (customer_id),
                        CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent(),
                )
            }

            // --- a table on one side only each --------------------------------------------------
            connection.execute("CREATE TABLE ${q(a)}.legacy (id INT PRIMARY KEY) ENGINE=InnoDB")
            connection.execute("CREATE TABLE ${q(b)}.extra (id INT PRIMARY KEY) ENGINE=InnoDB")
        }
    }

    @After
    fun disconnect() {
        if (this::session.isInitialized) {
            runCatching {
                session.use {
                    it.execute("DROP DATABASE IF EXISTS ${q(a)}")
                    it.execute("DROP DATABASE IF EXISTS ${q(b)}")
                }
            }
            session.close()
        }
    }

    @Test
    fun `the deliberate differences are found and nothing else is`() {
        val result = compare()

        assertEquals(listOf("customers", "extra", "legacy"), result.tables.map { it.name })
        assertEquals(DiffStatus.CHANGED, result.tables.single { it.name == "customers" }.status)
        assertEquals(DiffStatus.ONLY_B, result.tables.single { it.name == "extra" }.status)
        assertEquals(DiffStatus.ONLY_A, result.tables.single { it.name == "legacy" }.status)
        // widgets and orders are the same table, however each server spells it.
        assertEquals(2, result.identicalCount)
        assertTrue(result.unchecked.isEmpty())
        assertEquals(1, result.changedCount)
    }

    @Test
    fun `a changed column says what changed in it`() {
        val customers = compare().tables.single { it.name == "customers" }

        val byName = customers.columns.associateBy { it.name }
        assertEquals(setOf("email", "name", "status"), byName.keys)
        assertEquals(DiffStatus.CHANGED, byName.getValue("email").status)

        val email = byName.getValue("email").changes.single()
        assertEquals(DiffField.TYPE, email.field)
        assertEquals("varchar(100)", email.a)
        assertEquals("varchar(255)", email.b)

        val name = byName.getValue("name").changes.single()
        assertEquals(DiffField.NULLABLE, name.field)
        assertEquals("NULL", name.a)
        assertEquals("NOT NULL", name.b)

        val status = byName.getValue("status").changes.single()
        assertEquals(DiffField.DEFAULT, status.field)
        // MariaDB quotes a literal default and MySQL does not; either way it is the default.
        assertEquals("new", status.a?.trim('\''))
        assertEquals("active", status.b?.trim('\''))
    }

    @Test
    fun `an index on one side and a unique index on the other are told apart`() {
        val customers = compare().tables.single { it.name == "customers" }

        val byName = customers.indexes.associateBy { it.name }
        assertEquals(setOf("idx_name", "uq_email"), byName.keys)
        assertEquals(DiffStatus.ONLY_A, byName.getValue("idx_name").status)
        assertEquals(DiffStatus.ONLY_B, byName.getValue("uq_email").status)
        assertTrue(byName.getValue("uq_email").summary.orEmpty().contains("email"))
    }

    @Test
    fun `a self-referencing key that only one side has is a foreign key difference`() {
        val customers = compare().tables.single { it.name == "customers" }

        val key: ItemDiff = customers.foreignKeys.single()
        assertEquals("fk_customers_referrer", key.name)
        assertEquals(DiffStatus.ONLY_A, key.status)
        assertTrue(key.summary.orEmpty().contains("customers"))
    }

    @Test
    fun `integer widths and spellings of the current time are not differences`() {
        val sideA = side(a)
        val sideB = side(b)
        val widgetsA = sideA.structures.getValue("widgets")
        val widgetsB = sideB.structures.getValue("widgets")

        // The server really does hand these over in whatever spelling it likes — MariaDB writes
        // current_timestamp(), MySQL 8 DEFAULT_GENERATED — so the raw rows are not what is
        // compared. What is: no column, index or key of the two widgets tables differs.
        assertEquals(emptyList<ItemDiff>(), SchemaDiff.diffColumns(widgetsA.columns, widgetsB.columns))
        assertEquals(emptyList<ItemDiff>(), SchemaDiff.diffIndexes(widgetsA.indexes, widgetsB.indexes))
        assertEquals(
            emptyList<ItemDiff>(),
            SchemaDiff.diffForeignKeys(
                SchemaDiff.groupForeignKeys(widgetsA.foreignKeys, a),
                SchemaDiff.groupForeignKeys(widgetsB.foreignKeys, b),
            ),
        )
        // And the self reference does name each side's own database — which is what the grouping
        // has to take away for the two to be one key.
        assertEquals(setOf(a), widgetsA.foreignKeys.map { it.referencedDatabase }.toSet())
        assertEquals(setOf(b), widgetsB.foreignKeys.map { it.referencedDatabase }.toSet())

        // The widths that matter are kept: a column that really is wider differs.
        val wider = widgetsB.columns.map { if (it.name == "qty") it.copy(typeName = "bigint") else it }
        assertEquals(listOf("qty"), SchemaDiff.diffColumns(widgetsA.columns, wider).map { it.name })
    }

    @Test
    fun `a database compared with itself is identical`() {
        val same = SchemaDiff.compare(side(a), side(a))
        assertTrue(same.identical)
        assertEquals(4, same.identicalCount)
    }

    @Test
    fun `column order and identifier case are options, not accidents`() {
        // Same tables, one column moved: only the order option sees it.
        session.use { connection ->
            connection.execute("ALTER TABLE ${q(b)}.orders MODIFY customer_id INT NOT NULL FIRST")
        }
        val sideA = side(a)
        val sideB = side(b)

        val ordered = SchemaDiff.compare(sideA, sideB)
        assertTrue(ordered.tables.single { it.name == "orders" }.columns.any { c -> c.changes.any { it.field == DiffField.POSITION } })
        val unordered = SchemaDiff.compare(sideA, sideB, SchemaDiffOptions(compareColumnOrder = false))
        assertTrue(unordered.tables.none { it.name == "orders" })
    }

    // ------------------------------------------------------------------------------------------

    private fun compare(): SchemaDiffResult = SchemaDiff.compare(side(a), side(b))

    /**
     * One database as the cache would hold it after a live refresh: the table list, then every
     * structure, each read through [SchemaRepository] and translated the way
     * `SchemaCacheRepository` translates it.
     */
    private fun side(database: String): SchemaDiffSide = runBlocking {
        val tables = schema.tables(database, refresh = true)
        SchemaDiffSide(
            database = database,
            tables = tables.map {
                CachedTable(
                    database = it.database,
                    name = it.name,
                    kind = it.kind.name,
                    approximateRows = it.approximateRows,
                    comment = it.comment,
                    engine = it.engine,
                    collation = it.collation,
                    dataBytes = it.dataBytes,
                    indexBytes = it.indexBytes,
                )
            },
            structures = tables.associate { table ->
                val structure = schema.structure(database, table.name, refresh = true)
                table.name to CachedStructure(
                    columns = structure.columns.mapIndexed { position, column ->
                        CachedColumn(
                            name = column.name,
                            typeName = column.typeName,
                            nullable = column.nullable,
                            defaultValue = column.defaultValue,
                            isPrimaryKey = column.isPrimaryKey,
                            extra = column.extra,
                            comment = column.comment,
                            position = position,
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

    private fun q(name: String) = quoteIdentifier(name)

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}
