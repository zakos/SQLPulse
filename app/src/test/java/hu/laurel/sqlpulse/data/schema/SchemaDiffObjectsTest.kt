package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The v12 parts of the comparison: FK rules, CHECK constraints, view definitions, triggers. */
class SchemaDiffObjectsTest {

    private fun table(name: String, kind: TableKind = TableKind.TABLE) =
        CachedTable("db", name, kind.name, null, null, null, null, null, null)

    private fun fk(name: String, column: String = "customer_id", onDelete: String? = null, onUpdate: String? = null) =
        CachedForeignKey(name, column, "db", "customers", "id", onDelete = onDelete, onUpdate = onUpdate)

    private fun structure(
        keys: List<CachedForeignKey> = emptyList(),
        checks: List<CachedCheck>? = emptyList(),
    ) = CachedStructure(emptyList(), emptyList(), keys, checks)

    private fun side(
        database: String,
        structure: CachedStructure,
        engine: DatabaseEngine = DatabaseEngine.MYSQL,
        views: Map<String, String> = emptyMap(),
        triggers: List<CachedTrigger>? = null,
        vararg extra: CachedTable,
    ) = SchemaDiffSide(
        database = database,
        tables = listOf(table("orders")) + extra,
        structures = mapOf("orders" to structure),
        viewDefinitions = views,
        triggers = triggers,
        engine = engine,
    )

    private fun trigger(name: String, body: String, timing: String = "BEFORE", event: String = "INSERT", table: String = "orders") =
        CachedTrigger(name, table, timing, event, body)

    // ------------------------------------------------------------------ foreign key rules

    @Test
    fun `a changed on delete rule is reported`() {
        val a = side("dev", structure(listOf(fk("fk_c", onDelete = "CASCADE", onUpdate = "RESTRICT"))))
        val b = side("prod", structure(listOf(fk("fk_c", onDelete = "SET NULL", onUpdate = "RESTRICT"))))
        val change = SchemaDiff.compare(a, b).tables.single().foreignKeys.single().changes.single()
        assertEquals(DiffField.ON_DELETE, change.field)
        assertEquals("CASCADE", change.a)
        assertEquals("SET NULL", change.b)
    }

    @Test
    fun `an unknown rule from an older capture is not a difference`() {
        val a = side("dev", structure(listOf(fk("fk_c", onDelete = "CASCADE", onUpdate = "CASCADE"))))
        val b = side("prod", structure(listOf(fk("fk_c"))))
        assertTrue(SchemaDiff.compare(a, b).identical)
    }

    @Test
    fun `no action and restrict are one rule on mysql only`() {
        val a = side("dev", structure(listOf(fk("fk_c", onDelete = "NO ACTION", onUpdate = "NO ACTION"))))
        val b = side("prod", structure(listOf(fk("fk_c", onDelete = "RESTRICT", onUpdate = "RESTRICT"))))
        assertTrue(SchemaDiff.compare(a, b).identical)

        val pa = a.copy(engine = DatabaseEngine.POSTGRESQL)
        val pb = b.copy(engine = DatabaseEngine.POSTGRESQL)
        val keys = SchemaDiff.compare(pa, pb).tables.single().foreignKeys.single()
        assertEquals(listOf(DiffField.ON_DELETE, DiffField.ON_UPDATE), keys.changes.map { it.field })
    }

    @Test
    fun `sql server rule words with underscores read like the others`() {
        assertTrue(SchemaDiff.sameRule("SET_NULL", "set null", DatabaseEngine.SQLSERVER))
        assertFalse(SchemaDiff.sameRule("CASCADE", "SET NULL", DatabaseEngine.SQLSERVER))
    }

    @Test
    fun `a key only on one side shows its rules in the summary`() {
        val a = side("dev", structure(listOf(fk("fk_c", onDelete = "CASCADE", onUpdate = "NO ACTION"))))
        val b = side("prod", structure())
        val summary = SchemaDiff.compare(a, b).tables.single().foreignKeys.single().summary!!
        assertTrue(summary, "CASCADE" in summary)
    }

    // ------------------------------------------------------------------ checks

    @Test
    fun `a check only one side has is reported with its condition`() {
        val a = side("dev", structure(checks = listOf(CachedCheck("age_ok", "(`age` >= 0)"))))
        val b = side("prod", structure())
        val item = SchemaDiff.compare(a, b).tables.single().checks.single()
        assertEquals(DiffStatus.ONLY_A, item.status)
        assertEquals("age_ok", item.name)
    }

    @Test
    fun `the same condition spelled by two servers is one check`() {
        val a = side("dev", structure(checks = listOf(CachedCheck("age_ok", "(`age` >= 0)"))))
        val b = side("prod", structure(checks = listOf(CachedCheck("age_ok", "age >= 0"))))
        assertTrue(SchemaDiff.compare(a, b).identical)
    }

    @Test
    fun `a changed condition and a changed enforcement are reported`() {
        val a = side("dev", structure(checks = listOf(CachedCheck("age_ok", "age >= 0", enforced = true))))
        val b = side("prod", structure(checks = listOf(CachedCheck("age_ok", "age > 0", enforced = false))))
        val fields = SchemaDiff.compare(a, b).tables.single().checks.single().changes.map { it.field }
        assertEquals(listOf(DiffField.EXPRESSION, DiffField.ENFORCED), fields)
    }

    @Test
    fun `generated check names do not matter when the condition is the same`() {
        val a = side("dev", structure(checks = listOf(CachedCheck("orders_chk_1", "age >= 0"))))
        val b = side("prod", structure(checks = listOf(CachedCheck("orders_chk_7", "age >= 0"))))
        assertTrue(SchemaDiff.compare(a, b).identical)
        // A real name that differs is a rename.
        val c = side("prod", structure(checks = listOf(CachedCheck("non_negative", "age >= 0"))))
        val check = SchemaDiff.compare(a, c).tables.single().checks.single()
        assertEquals(DiffField.NAME, check.changes.single().field)
    }

    @Test
    fun `checks of a structure captured before they were kept are not compared`() {
        val a = side("dev", structure(checks = listOf(CachedCheck("age_ok", "age >= 0"))))
        val b = side("prod", structure(checks = null))
        assertTrue(SchemaDiff.compare(a, b).identical)
    }

    // ------------------------------------------------------------------ views

    private fun views(a: String?, b: String?, engine: DatabaseEngine = DatabaseEngine.MYSQL): SchemaDiffResult {
        fun viewSide(db: String, text: String?) = SchemaDiffSide(
            database = db,
            tables = listOf(table("v", TableKind.VIEW)),
            structures = mapOf("v" to structure()),
            viewDefinitions = text?.let { mapOf("v" to it) }.orEmpty(),
            engine = engine,
        )
        return SchemaDiff.compare(viewSide("shop_dev", a), viewSide("shop", b))
    }

    @Test
    fun `a changed view body reports the stretch where it differs`() {
        val result = views(
            "CREATE ALGORITHM=UNDEFINED DEFINER=`a`@`%` SQL SECURITY DEFINER VIEW `shop_dev`.`v` AS select `t`.`id` AS `id` from `shop_dev`.`t` where `t`.`active` = 1",
            "CREATE ALGORITHM=UNDEFINED DEFINER=`b`@`%` SQL SECURITY DEFINER VIEW `shop`.`v` AS select `t`.`id` AS `id` from `shop`.`t` where `t`.`active` = 0",
        )
        val change = result.tables.single().changes.single()
        assertEquals(DiffField.DEFINITION, change.field)
        assertTrue(change.a!!.endsWith("1") && change.b!!.endsWith("0"))
    }

    @Test
    fun `postgres and sql server views compare by their normalised text`() {
        assertTrue(
            views(
                "SELECT t.id,\n    t.name\n   FROM shop_dev.t;",
                "select t.id, t.name from t;",
                DatabaseEngine.POSTGRESQL,
            ).let { it.tables.isEmpty() },
        )
        assertEquals(
            1,
            views(
                "CREATE VIEW [dbo].[v] AS SELECT [id] FROM [dbo].[t] WHERE [x] = 1",
                "CREATE VIEW [dbo].[v] AS SELECT [id] FROM [dbo].[t] WHERE [x] = 2",
                DatabaseEngine.SQLSERVER,
            ).tables.size,
        )
    }

    @Test
    fun `a view whose text one side could not read is not reported`() {
        assertTrue(views("CREATE VIEW v AS select 1", null).identical)
    }

    // ------------------------------------------------------------------ triggers

    @Test
    fun `triggers differing in body are reported with the excerpt`() {
        val a = side("dev", structure(), triggers = listOf(trigger("bi", "begin set new.x = 1; end")))
        val b = side("prod", structure(), triggers = listOf(trigger("bi", "BEGIN SET NEW.x = 2; END")))
        val item = SchemaDiff.compare(a, b).triggers.single()
        assertEquals("orders.bi", item.name)
        assertEquals(DiffField.DEFINITION, item.changes.single().field)
    }

    @Test
    fun `reformatting a trigger body is not a change`() {
        val a = side("dev", structure(), triggers = listOf(trigger("bi", "BEGIN\n  SET NEW.x = 1;\nEND")))
        val b = side("prod", structure(), triggers = listOf(trigger("bi", "begin set `new`.`x`=1; end")))
        val result = SchemaDiff.compare(a, b)
        assertTrue(result.triggers.isEmpty())
        assertTrue(result.identical)
    }

    @Test
    fun `timing and event changes and one sided triggers are reported`() {
        val a = side(
            "dev", structure(),
            triggers = listOf(
                trigger("bi", "b", timing = "BEFORE", event = "INSERT"),
                trigger("only_dev", "b"),
            ),
        )
        val b = side("prod", structure(), triggers = listOf(trigger("bi", "b", timing = "AFTER", event = "UPDATE")))
        val triggers = SchemaDiff.compare(a, b).triggers
        val changed = triggers.single { it.status == DiffStatus.CHANGED }
        assertEquals(listOf(DiffField.TIMING, DiffField.EVENT), changed.changes.map { it.field })
        assertEquals("orders.only_dev", triggers.single { it.status == DiffStatus.ONLY_A }.name)
    }

    @Test
    fun `the same trigger under a different name is a rename`() {
        val a = side("dev", structure(), triggers = listOf(trigger("trg_1", "body")))
        val b = side("prod", structure(), triggers = listOf(trigger("trg_2", "body")))
        val item = SchemaDiff.compare(a, b).triggers.single()
        assertEquals(DiffStatus.CHANGED, item.status)
        assertEquals(DiffField.NAME, item.changes.single().field)
    }

    @Test
    fun `postgres event lists compare as sets`() {
        val a = side("dev", structure(), DatabaseEngine.POSTGRESQL, triggers = listOf(trigger("t", "b", event = "INSERT OR UPDATE")))
        val b = side("prod", structure(), DatabaseEngine.POSTGRESQL, triggers = listOf(trigger("t", "b", event = "UPDATE OR INSERT")))
        assertTrue(SchemaDiff.compare(a, b).triggers.isEmpty())
    }

    @Test
    fun `triggers never captured are a gap and not a match or a difference`() {
        val a = side("dev", structure(), triggers = listOf(trigger("bi", "b")))
        val b = side("prod", structure(), triggers = null)
        val result = SchemaDiff.compare(a, b)
        assertEquals(StructureGap.MISSING_B, result.triggerGap)
        assertTrue(result.triggers.isEmpty())
        assertEquals(StructureGap.MISSING_BOTH, SchemaDiff.compare(b, b).triggerGap)
        assertEquals(StructureGap.NONE, SchemaDiff.compare(a, a).triggerGap)
        assertNull(SchemaDiff.compare(a, a).triggers.firstOrNull())
    }
}
