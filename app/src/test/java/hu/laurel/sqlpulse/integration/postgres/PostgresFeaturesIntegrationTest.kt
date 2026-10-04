package hu.laurel.sqlpulse.integration.postgres

import hu.laurel.sqlpulse.data.schema.StorageSource
import hu.laurel.sqlpulse.data.sql.dialect.PostgresDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialect
import hu.laurel.sqlpulse.integration.EngineBackend
import hu.laurel.sqlpulse.integration.EngineFeaturesBase
import hu.laurel.sqlpulse.integration.EngineSide
import org.junit.Assert.assertTrue
import org.junit.Test

/** Database search, schema comparison and the storage screen on a real PostgreSQL. */
class PostgresFeaturesIntegrationTest : EngineFeaturesBase() {

    override fun connect(): EngineBackend {
        val fixture = PostgresFixture()
        val other = fixture.schema + "_b"
        fixture.execute("CREATE SCHEMA ${fixture.q(other)}")
        return object : EngineBackend {
            override val dialect: SqlDialect = PostgresDialect
            override val a = EngineSide(fixture.manager, fixture.schema)
            override val b = EngineSide(fixture.manager, other)
            override fun execute(side: EngineSide, vararg statements: String) = fixture.execute(*statements)
            override fun close() {
                runCatching { fixture.execute("DROP SCHEMA IF EXISTS ${fixture.q(other)} CASCADE") }
                fixture.close()
            }
        }
    }

    private fun t(side: EngineSide, table: String) = PostgresDialect.qualify(side.namespace, table)

    override fun searchFixture(side: EngineSide, backend: EngineBackend) {
        val docs = t(side, "docs")
        backend.execute(
            side,
            "CREATE TABLE $docs (id integer PRIMARY KEY, title text, body text, qty integer, payload bytea, tags text[], meta jsonb)",
            *docsRows { id, title, body, qty ->
                "INSERT INTO $docs (id, title, body, qty) VALUES ($id, ${sqlText(title)}, ${sqlText(body)}, ${qty ?: "NULL"})"
            }.toTypedArray(),
        )
    }

    override fun manyRows(): Array<String> = Array(12) {
        "INSERT INTO ${t(backend.a, "docs")} (id, title) VALUES (${100 + it}, 'filler $it')"
    }

    override fun diffFixture(backend: EngineBackend) {
        val a = backend.a
        val b = backend.b
        backend.execute(
            a,
            "CREATE TABLE ${t(a, "customers")} (id integer PRIMARY KEY, email varchar(100) NOT NULL, name varchar(50) NULL, referrer integer)",
            // int4, character varying and now() spelled the long way; serial's default names its own schema.
            "CREATE TABLE ${t(a, "widgets")} (id serial PRIMARY KEY, qty int4 NOT NULL DEFAULT 0, " +
                "label character varying(20) DEFAULT 'abc', created timestamp without time zone DEFAULT now(), amount decimal(10,2))",
            "CREATE TABLE ${t(a, "legacy")} (id integer PRIMARY KEY)",
        )
        backend.execute(
            b,
            "CREATE TABLE ${t(b, "customers")} (id integer PRIMARY KEY, email varchar(255) NOT NULL, name varchar(50) NOT NULL, referrer integer)",
            "CREATE TABLE ${t(b, "widgets")} (id serial PRIMARY KEY, qty integer NOT NULL DEFAULT 0, " +
                "label varchar(20) DEFAULT 'abc', created timestamp DEFAULT CURRENT_TIMESTAMP, amount numeric(10,2))",
            "CREATE TABLE ${t(b, "extra")} (id integer PRIMARY KEY)",
        )
    }

    override fun objectsFixture(backend: EngineBackend) {
        for ((side, variant) in listOf(backend.a to "a", backend.b to "b")) {
            val ns = PostgresDialect.quoteIdentifier(side.namespace)
            val a = variant == "a"
            backend.execute(
                side,
                "CREATE TABLE ${t(side, "parent")} (id integer PRIMARY KEY)",
                "CREATE TABLE ${t(side, "child")} (id integer PRIMARY KEY, parent_id integer, qty integer, price integer, note text, " +
                    "CONSTRAINT child_parent_fk FOREIGN KEY (parent_id) REFERENCES ${t(side, "parent")} (id) " +
                    "ON DELETE ${if (a) "CASCADE" else "SET NULL"}, " +
                    "CONSTRAINT qty_ok CHECK (qty ${if (a) ">" else ">="} 0), " +
                    "CONSTRAINT price_ok CHECK (${if (a) "price >= 0" else "(price)>=0"}))",
                "CREATE VIEW ${t(side, "v_child")} AS SELECT id, qty FROM ${t(side, "child")} WHERE qty > ${if (a) 0 else 1}",
                if (a) "CREATE VIEW ${t(side, "v_same")} AS SELECT id FROM ${t(side, "child")} WHERE price > 5"
                else "CREATE VIEW ${t(side, "v_same")} AS\n  SELECT   id\n FROM ${t(side, "child")}\n  WHERE price>5",
                "CREATE FUNCTION $ns.touch() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN NEW.note := '${if (a) "a" else "b"}'; RETURN NEW; END \$\$",
                "CREATE FUNCTION $ns.same_fn() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN NEW.price := " +
                    (if (a) "1; RETURN NEW; END" else "1;\n  RETURN NEW;\n END") + " \$\$",
                "CREATE TRIGGER trg_child BEFORE INSERT ON ${t(side, "child")} FOR EACH ROW EXECUTE FUNCTION $ns.touch()",
                "CREATE TRIGGER trg_same BEFORE UPDATE ON ${t(side, "child")} FOR EACH ROW EXECUTE FUNCTION $ns.same_fn()",
            )
        }
    }

    override fun storageFixture(side: EngineSide, backend: EngineBackend) {
        val big = t(side, "big")
        val counter = t(side, "counter")
        backend.execute(
            side,
            "CREATE TABLE $big (id integer GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, a integer NOT NULL, b varchar(40) NOT NULL, payload varchar(200) NOT NULL)",
            "INSERT INTO $big (a, b, payload) SELECT g, 'row ' || g, repeat('x', 100) FROM generate_series(1, 200) g",
            "CREATE INDEX idx_a_b ON $big (a, b)",
            "CREATE TABLE $counter (id smallint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, v integer)",
            "ALTER TABLE $counter ALTER COLUMN id RESTART WITH 30000",
            "INSERT INTO $counter (v) VALUES (1)",
            "CREATE VIEW ${t(side, "v_big")} AS SELECT id FROM $big",
            "ANALYZE $big",
        )
    }

    @Test
    fun `json and uuid columns are searched as text and arrays are not`() {
        val side = backend.a
        val things = t(side, "things")
        backend.execute(
            side,
            "CREATE TABLE $things (id integer PRIMARY KEY, token uuid, doc jsonb, tags text[], note text)",
            "INSERT INTO $things VALUES (1, '123e4567-e89b-12d3-a456-426614174000', '{\"city\": \"Győr\"}', '{needle}', 'n')",
        )
        val plan = { term: String ->
            val columns = kotlinx.coroutines.runBlocking {
                hu.laurel.sqlpulse.data.search.DatabaseSearchRepository(side.manager).columns(side.namespace)
            }.getValue("things")
            hu.laurel.sqlpulse.data.search.DatabaseSearch.plan(
                side.namespace, "things", columns, term, hu.laurel.sqlpulse.data.search.SearchMode.CONTAINS, 5, PostgresDialect,
            )!!
        }
        assertTrue("tags" !in plan("needle").searchColumns)
        val repository = hu.laurel.sqlpulse.data.search.DatabaseSearchRepository(side.manager)
        val uuidHit = kotlinx.coroutines.runBlocking { repository.search(plan("e89b")) }
        assertTrue(uuidHit.single().cells.any { it.first == "token" })
        val jsonHit = kotlinx.coroutines.runBlocking { repository.search(plan("győr")) }
        assertTrue(jsonHit.single().cells.any { it.first == "doc" })
    }

    @Test
    fun `unused index statistics say how long they have been counting`() {
        val snapshot = kotlinx.coroutines.runBlocking {
            storageFixture(backend.a, backend)
            hu.laurel.sqlpulse.data.schema.StorageRepository(backend.a.manager).load(backend.a.namespace)
        }
        assertTrue(snapshot.unused is StorageSource.Loaded)
        assertTrue("age ${snapshot.uptimeSeconds}", (snapshot.uptimeSeconds ?: -1) >= 0)
    }
}
