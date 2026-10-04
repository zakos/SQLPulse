package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaDiffTest {

    // ------------------------------------------------------------------ builders

    private fun table(name: String, kind: TableKind = TableKind.TABLE, engine: String? = "InnoDB", collation: String? = "utf8mb4_general_ci", comment: String? = null, database: String = "db") =
        CachedTable(database, name, kind.name, null, comment, engine, collation, null, null)

    private fun column(name: String, type: String = "int", nullable: Boolean = false, default: String? = null, extra: String? = null, position: Int = 0, comment: String? = null, pk: Boolean = false) =
        CachedColumn(name, type, nullable, default, pk, extra, comment, position)

    private fun columns(vararg columns: CachedColumn) = columns.mapIndexed { index, it -> it.copy(position = index) }

    private fun structure(columns: List<CachedColumn> = emptyList(), indexes: List<CachedIndex> = emptyList(), keys: List<CachedForeignKey> = emptyList()) =
        CachedStructure(columns, indexes, keys)

    private fun side(database: String, vararg tables: Pair<CachedTable, CachedStructure?>, views: Map<String, String> = emptyMap()) =
        SchemaDiffSide(
            database = database,
            tables = tables.map { it.first },
            structures = tables.mapNotNull { (t, s) -> s?.let { t.name to it } }.toMap(),
            viewDefinitions = views,
        )

    private val id = column("id", "int(11)", pk = true)

    // ------------------------------------------------------------------ tables

    @Test
    fun `identical schemas report nothing`() {
        val s = structure(columns(id, column("name", "varchar(64)")))
        val result = SchemaDiff.compare(side("dev", table("users") to s), side("prod", table("users") to s))
        assertTrue(result.identical)
        assertEquals(1, result.identicalCount)
        assertEquals(0, result.tables.size)
    }

    @Test
    fun `tables only on one side are reported per side and sorted by name`() {
        val result = SchemaDiff.compare(
            side("dev", table("orders") to null, table("Audit") to null, table("users") to structure()),
            side("prod", table("users") to structure(), table("legacy") to null),
        )
        assertEquals(listOf("Audit", "legacy", "orders"), result.tables.map { it.name })
        assertEquals(listOf(DiffStatus.ONLY_A, DiffStatus.ONLY_B, DiffStatus.ONLY_A), result.tables.map { it.status })
        assertEquals(2, result.onlyACount)
        assertEquals(1, result.onlyBCount)
        assertEquals(0, result.changedCount)
    }

    @Test
    fun `case-insensitive matching only when asked`() {
        val a = side("dev", table("Users") to structure())
        val b = side("prod", table("users") to structure())
        assertEquals(2, SchemaDiff.compare(a, b).tables.size)
        val ignoring = SchemaDiff.compare(a, b, SchemaDiffOptions(ignoreIdentifierCase = true))
        assertTrue(ignoring.identical)
    }

    @Test
    fun `exact name wins over a case-insensitive candidate`() {
        val pairs = SchemaDiff.matchByName(listOf("Orders", "orders"), listOf("orders", "ORDERS"), ignoreCase = true) { it }
        assertEquals(listOf("Orders" to "ORDERS", "orders" to "orders"), pairs.sortedBy { it.first })
    }

    @Test
    fun `an ambiguous case-insensitive match is left unpaired`() {
        val pairs = SchemaDiff.matchByName(listOf("Orders"), listOf("orders", "ORDERS"), ignoreCase = true) { it }
        assertEquals(listOf("Orders" to null, null to "orders", null to "ORDERS"), pairs)
    }

    @Test
    fun `engine and collation differences are reported on the table`() {
        val result = SchemaDiff.compare(
            side("dev", table("t", engine = "InnoDB", collation = "utf8mb4_0900_ai_ci") to structure()),
            side("prod", table("t", engine = "MyISAM", collation = "utf8mb4_general_ci") to structure()),
        )
        val changes = result.tables.single().changes
        assertEquals(
            listOf(
                FieldChange(DiffField.ENGINE, "InnoDB", "MyISAM"),
                FieldChange(DiffField.COLLATION, "utf8mb4_0900_ai_ci", "utf8mb4_general_ci"),
            ),
            changes,
        )
    }

    @Test
    fun `ignoring charset drops collation differences`() {
        val result = SchemaDiff.compare(
            side("dev", table("t", collation = "utf8mb4_0900_ai_ci") to structure()),
            side("prod", table("t", collation = "latin1_swedish_ci") to structure()),
            SchemaDiffOptions(ignoreCharset = true),
        )
        assertTrue(result.identical)
    }

    @Test
    fun `utf8 and utf8mb3 are the same collation`() {
        val result = SchemaDiff.compare(
            side("dev", table("t", collation = "utf8mb3_general_ci") to structure()),
            side("prod", table("t", collation = "utf8_general_ci") to structure()),
        )
        assertTrue(result.identical)
    }

    @Test
    fun `an unknown engine is not a difference`() {
        val result = SchemaDiff.compare(
            side("dev", table("t", engine = null) to structure()),
            side("prod", table("t", engine = "innodb") to structure()),
        )
        assertTrue(result.identical)
    }

    @Test
    fun `comments are ignored by default and compared when asked`() {
        val a = side("dev", table("t", comment = "new words") to structure(columns(column("c", comment = "x"))))
        val b = side("prod", table("t", comment = null) to structure(columns(column("c", comment = "y"))))
        assertTrue(SchemaDiff.compare(a, b).identical)
        val diff = SchemaDiff.compare(a, b, SchemaDiffOptions(ignoreComments = false)).tables.single()
        assertEquals(listOf(FieldChange(DiffField.COMMENT, "new words", null)), diff.changes)
        assertEquals(DiffField.COMMENT, diff.columns.single().changes.single().field)
    }

    @Test
    fun `a table turned into a view is a kind change`() {
        val result = SchemaDiff.compare(
            side("dev", table("t", kind = TableKind.VIEW, engine = null) to null),
            side("prod", table("t") to null),
        )
        val diff = result.tables.single()
        assertEquals(FieldChange(DiffField.KIND, "VIEW", "TABLE"), diff.changes.single())
        assertEquals(StructureGap.MISSING_BOTH, diff.gap)
    }

    @Test
    fun `a missing structure makes a matching table unchecked rather than identical`() {
        val result = SchemaDiff.compare(
            side("dev", table("a") to structure(), table("b") to null),
            side("prod", table("a") to null, table("b") to structure()),
        )
        assertFalse(result.identical)
        assertEquals(0, result.identicalCount)
        assertEquals(
            listOf(UncheckedTable("a", StructureGap.MISSING_B), UncheckedTable("b", StructureGap.MISSING_A)),
            result.unchecked,
        )
    }

    // ------------------------------------------------------------------ columns

    @Test
    fun `columns added, removed and changed`() {
        val a = columns(id, column("email", "varchar(255)", nullable = true), column("age", "int"))
        val b = columns(id, column("age", "bigint"), column("legacy", "text", default = "'x'"))
        val diffs = SchemaDiff.diffColumns(a, b)
        assertEquals(
            listOf(
                ItemDiff("email", DiffStatus.ONLY_A, summary = "varchar(255) NULL"),
                ItemDiff("age", DiffStatus.CHANGED, listOf(FieldChange(DiffField.TYPE, "int", "bigint"))),
                ItemDiff("legacy", DiffStatus.ONLY_B, summary = "text NOT NULL DEFAULT 'x'"),
            ),
            diffs,
        )
    }

    @Test
    fun `nullability, default and extra are compared`() {
        val diffs = SchemaDiff.diffColumns(
            columns(column("c", "int", nullable = true, default = "1", extra = "auto_increment")),
            columns(column("c", "int", nullable = false, default = "2", extra = null)),
        )
        assertEquals(
            listOf(
                FieldChange(DiffField.NULLABLE, "NULL", "NOT NULL"),
                FieldChange(DiffField.DEFAULT, "1", "2"),
                FieldChange(DiffField.EXTRA, "auto_increment", null),
            ),
            diffs.single().changes,
        )
    }

    @Test
    fun `order is compared only when both sides have the same columns`() {
        val moved = SchemaDiff.diffColumns(
            columns(column("a"), column("b")),
            columns(column("b"), column("a")),
        )
        assertEquals(
            listOf(FieldChange(DiffField.POSITION, "1", "2")),
            moved.first { it.name == "a" }.changes,
        )
        // With one column more, every later column has "moved"; only the added one is reported.
        val added = SchemaDiff.diffColumns(
            columns(column("x"), column("a"), column("b")),
            columns(column("a"), column("b")),
        )
        assertEquals(listOf("x"), added.map { it.name })
        assertTrue(SchemaDiff.diffColumns(columns(column("a"), column("b")), columns(column("b"), column("a")), SchemaDiffOptions(compareColumnOrder = false)).isEmpty())
    }

    @Test
    fun `columns are ordered by their stored position, not list order`() {
        val diffs = SchemaDiff.diffColumns(
            listOf(column("b", position = 1), column("a", position = 0)),
            listOf(column("a", position = 0), column("b", position = 1)),
        )
        assertTrue(diffs.isEmpty())
    }

    @Test
    fun `column names match case-insensitively when asked`() {
        val a = columns(column("Email"))
        val b = columns(column("email"))
        assertEquals(2, SchemaDiff.diffColumns(a, b).size)
        assertTrue(SchemaDiff.diffColumns(a, b, SchemaDiffOptions(ignoreIdentifierCase = true)).isEmpty())
    }

    @Test
    fun `integer display widths are not a difference`() {
        assertEquals("int", SchemaDiff.normalizeType("int(11)"))
        assertEquals("bigint unsigned", SchemaDiff.normalizeType("BIGINT(20) UNSIGNED"))
        assertEquals("tinyint(1)", SchemaDiff.normalizeType("tinyint(1)"))
        assertEquals("tinyint", SchemaDiff.normalizeType("tinyint(4)"))
        assertEquals("int(5) unsigned zerofill", SchemaDiff.normalizeType("int(5) unsigned zerofill"))
        assertEquals("varchar(11)", SchemaDiff.normalizeType("varchar(11)"))
        assertEquals("decimal(10,2)", SchemaDiff.normalizeType("decimal(10,2)"))
        assertTrue(SchemaDiff.diffColumns(columns(column("c", "int(11)")), columns(column("c", "int"))).isEmpty())
    }

    @Test
    fun `MariaDB and MySQL spellings of defaults agree`() {
        assertNull(SchemaDiff.normalizeDefault("NULL"))
        assertNull(SchemaDiff.normalizeDefault(null))
        assertEquals("abc", SchemaDiff.normalizeDefault("'abc'"))
        assertEquals("it's", SchemaDiff.normalizeDefault("'it''s'"))
        assertEquals("current_timestamp", SchemaDiff.normalizeDefault("current_timestamp()"))
        assertEquals("current_timestamp", SchemaDiff.normalizeDefault("CURRENT_TIMESTAMP"))
        assertEquals("current_timestamp", SchemaDiff.normalizeDefault("now()"))
        assertEquals("current_timestamp(3)", SchemaDiff.normalizeDefault("CURRENT_TIMESTAMP(3)"))
        // A bare word is text, not the function.
        assertEquals("now", SchemaDiff.normalizeDefault("now"))
        // The quoted string 'NULL' is a text default, not the absence of one.
        assertEquals("NULL", SchemaDiff.normalizeDefault("'NULL'"))
    }

    @Test
    fun `MySQL 8 extra words do not count as differences`() {
        assertNull(SchemaDiff.normalizeExtra("DEFAULT_GENERATED"))
        assertEquals(
            SchemaDiff.normalizeExtra("on update current_timestamp()"),
            SchemaDiff.normalizeExtra("DEFAULT_GENERATED on update CURRENT_TIMESTAMP"),
        )
        assertNull(SchemaDiff.normalizeExtra("  "))
    }

    // ------------------------------------------------------------------ indexes

    @Test
    fun `indexes added, removed and changed`() {
        val diffs = SchemaDiff.diffIndexes(
            listOf(
                CachedIndex("PRIMARY", true, listOf("id"), 0),
                CachedIndex("ix_email", true, listOf("email"), 1),
                CachedIndex("ix_new", false, listOf("a", "b"), 2),
            ),
            listOf(
                CachedIndex("PRIMARY", true, listOf("id", "tenant"), 0),
                CachedIndex("ix_email", false, listOf("email"), 1),
                CachedIndex("ix_old", false, listOf("c"), 2),
            ),
        )
        assertEquals(
            listOf(
                ItemDiff("PRIMARY", DiffStatus.CHANGED, listOf(FieldChange(DiffField.COLUMNS, "id", "id, tenant"))),
                ItemDiff("ix_email", DiffStatus.CHANGED, listOf(FieldChange(DiffField.UNIQUE, "UNIQUE", "INDEX"))),
                ItemDiff("ix_new", DiffStatus.ONLY_A, summary = "INDEX (a, b)"),
                ItemDiff("ix_old", DiffStatus.ONLY_B, summary = "INDEX (c)"),
            ),
            diffs,
        )
    }

    @Test
    fun `the same index under another name is a rename, not two differences`() {
        val diffs = SchemaDiff.diffIndexes(
            listOf(CachedIndex("idx_customer", false, listOf("customer_id"), 0)),
            listOf(CachedIndex("customer_id", false, listOf("customer_id"), 0)),
        )
        assertEquals(
            listOf(ItemDiff("idx_customer", DiffStatus.CHANGED, listOf(FieldChange(DiffField.NAME, "idx_customer", "customer_id")))),
            diffs,
        )
    }

    @Test
    fun `primary key on one side only reads as a primary key`() {
        val diffs = SchemaDiff.diffIndexes(listOf(CachedIndex("PRIMARY", true, listOf("id"), 0)), emptyList())
        assertEquals("PRIMARY KEY (id)", diffs.single().summary)
    }

    // ------------------------------------------------------------------ foreign keys

    private fun fk(name: String, column: String, refDb: String, refTable: String, refColumn: String) =
        CachedForeignKey(name, column, refDb, refTable, refColumn)

    @Test
    fun `a key into its own schema is compared without the schema name`() {
        val result = SchemaDiff.compare(
            side("shop_dev", table("orders", database = "shop_dev") to structure(keys = listOf(fk("fk_c", "customer_id", "shop_dev", "customers", "id")))),
            side("shop", table("orders", database = "shop") to structure(keys = listOf(fk("fk_c", "customer_id", "shop", "customers", "id")))),
        )
        assertTrue(result.identical)
    }

    @Test
    fun `a key into another schema keeps the schema name`() {
        val result = SchemaDiff.compare(
            side("shop_dev", table("orders") to structure(keys = listOf(fk("fk_c", "customer_id", "crm_dev", "customers", "id")))),
            side("shop", table("orders") to structure(keys = listOf(fk("fk_c", "customer_id", "crm", "customers", "id")))),
        )
        val change = result.tables.single().foreignKeys.single().changes.single()
        assertEquals(FieldChange(DiffField.REFERENCES, "crm_dev.customers(id)", "crm.customers(id)"), change)
    }

    @Test
    fun `multi-column keys are grouped into one`() {
        val grouped = SchemaDiff.groupForeignKeys(
            listOf(
                fk("fk_line", "order_id", "db", "order_lines", "order_id"),
                fk("fk_line", "line_no", "db", "order_lines", "line_no"),
            ),
            ownDatabase = "db",
        )
        val key = grouped.single()
        assertEquals(listOf("line_no", "order_id"), key.columns)
        assertEquals(listOf("line_no", "order_id"), key.referencedColumns)
        assertNull(key.referencedDatabase)
        assertEquals("order_lines(line_no, order_id)", key.references)
    }

    @Test
    fun `generated constraint names do not hide an identical key`() {
        val a = SchemaDiff.groupForeignKeys(listOf(fk("fk_orders_customer", "customer_id", "dev", "customers", "id")), "dev")
        val b = SchemaDiff.groupForeignKeys(listOf(fk("orders_ibfk_1", "customer_id", "prod", "customers", "id")), "prod")
        val diff = SchemaDiff.diffForeignKeys(a, b).single()
        assertEquals(DiffStatus.CHANGED, diff.status)
        assertEquals(listOf(FieldChange(DiffField.NAME, "fk_orders_customer", "orders_ibfk_1")), diff.changes)
    }

    @Test
    fun `keys with different targets stay separate`() {
        val a = SchemaDiff.groupForeignKeys(listOf(fk("fk_a", "customer_id", "db", "customers", "id")), "db")
        val b = SchemaDiff.groupForeignKeys(listOf(fk("fk_b", "customer_id", "db", "clients", "id")), "db")
        val diffs = SchemaDiff.diffForeignKeys(a, b)
        assertEquals(listOf(DiffStatus.ONLY_A, DiffStatus.ONLY_B), diffs.map { it.status })
        assertEquals("(customer_id) → customers(id)", diffs.first().summary)
    }

    @Test
    fun `a key with the same name but another target is changed`() {
        val a = SchemaDiff.groupForeignKeys(listOf(fk("fk", "customer_id", "db", "customers", "id")), "db")
        val b = SchemaDiff.groupForeignKeys(listOf(fk("fk", "client_id", "db", "customers", "id")), "db")
        assertEquals(
            listOf(FieldChange(DiffField.COLUMNS, "customer_id", "client_id")),
            SchemaDiff.diffForeignKeys(a, b).single().changes,
        )
    }

    // ------------------------------------------------------------------ views

    @Test
    fun `the definer is stripped from a view header`() {
        val ddl = "CREATE ALGORITHM=UNDEFINED DEFINER=`app`@`%` SQL SECURITY DEFINER VIEW `v` AS select 1"
        assertEquals("CREATE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `v` AS select 1", SchemaDiff.stripDefiner(ddl))
        assertEquals(
            "CREATE VIEW v AS select 1",
            SchemaDiff.stripDefiner("CREATE DEFINER=CURRENT_USER() VIEW v AS select 1"),
        )
        assertEquals(
            "CREATE VIEW v AS select 1",
            SchemaDiff.stripDefiner("CREATE DEFINER = 'root'@'localhost' VIEW v AS select 1"),
        )
        // A DEFINER after VIEW is part of the body, not the header.
        val body = "CREATE VIEW v AS select 'DEFINER=`x`@`y`' AS t"
        assertEquals(body, SchemaDiff.stripDefiner(body))
    }

    @Test
    fun `view definitions differing only in definer, schema and spacing are equal`() {
        val dev = "CREATE ALGORITHM=UNDEFINED DEFINER=`dev`@`%` SQL SECURITY DEFINER VIEW `shop_dev`.`v` AS select `shop_dev`.`t`.`id` AS `id` from `shop_dev`.`t`"
        val prod = "CREATE ALGORITHM=UNDEFINED DEFINER=`deploy`@`10.0.0.%` SQL SECURITY DEFINER VIEW `shop`.`v` AS  select `shop`.`t`.`id` AS `id`\n from `shop`.`t`"
        assertEquals(
            ObjectText.normalize(dev, "shop_dev", DatabaseEngine.MYSQL),
            ObjectText.normalize(prod, "shop", DatabaseEngine.MYSQL),
        )
        val result = SchemaDiff.compare(
            side("shop_dev", table("v", kind = TableKind.VIEW, engine = null) to structure(), views = mapOf("v" to dev)),
            side("shop", table("v", kind = TableKind.VIEW, engine = null) to structure(), views = mapOf("v" to prod)),
        )
        assertTrue(result.identical)
    }

    @Test
    fun `a changed view body is reported`() {
        val dev = "CREATE VIEW `v` AS select `t`.`id` AS `id` from `t` where `t`.`active` = 1"
        val prod = "CREATE VIEW `v` AS select `t`.`id` AS `id` from `t`"
        val result = SchemaDiff.compare(
            side("a", table("v", kind = TableKind.VIEW, engine = null) to structure(), views = mapOf("v" to dev)),
            side("b", table("v", kind = TableKind.VIEW, engine = null) to structure(), views = mapOf("v" to prod)),
        )
        assertEquals(DiffField.DEFINITION, result.tables.single().changes.single().field)
    }

    @Test
    fun `spaces inside string literals are kept`() {
        assertFalse(
            ObjectText.normalize("CREATE VIEW v AS select 'a  b'", "x", DatabaseEngine.MYSQL) ==
                ObjectText.normalize("CREATE VIEW v AS select 'a b'", "x", DatabaseEngine.MYSQL),
        )
        assertEquals(
            "create view v as select a+b",
            ObjectText.normalize("CREATE   VIEW v AS select a + b", "x", DatabaseEngine.MYSQL),
        )
    }

    @Test
    fun `a view without a known definition on one side is not reported`() {
        val result = SchemaDiff.compare(
            side("a", table("v", kind = TableKind.VIEW, engine = null) to structure(), views = mapOf("v" to "CREATE VIEW v AS select 1")),
            side("b", table("v", kind = TableKind.VIEW, engine = null) to structure()),
        )
        assertTrue(result.identical)
    }
}
