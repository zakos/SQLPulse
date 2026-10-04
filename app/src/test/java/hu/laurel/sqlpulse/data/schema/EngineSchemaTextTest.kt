package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Type, default and name normalisation per engine, and the "same engine only" rule of the comparison. */
class EngineSchemaTextTest {

    private val pg = DatabaseEngine.POSTGRESQL
    private val ms = DatabaseEngine.SQLSERVER
    private val lite = DatabaseEngine.SQLITE

    // ------------------------------------------------------------------ PostgreSQL

    @Test
    fun `PostgreSQL long and short type names are one type`() {
        fun same(a: String, b: String) = assertEquals("$a vs $b", SchemaDiff.normalizeType(a, pg), SchemaDiff.normalizeType(b, pg))
        same("character varying(255)", "varchar(255)")
        same("int4", "integer")
        same("INT", "integer")
        same("int8", "bigint")
        same("int2", "smallint")
        same("float8", "double precision")
        same("bool", "boolean")
        same("decimal(10, 2)", "numeric(10,2)")
        same("timestamp without time zone", "timestamp")
        same("timestamp(3) with time zone", "timestamptz(3)")
        same("character(3)", "bpchar(3)")
        same("int4[]", "integer[]")
        same("serial", "integer")
        same("bigserial", "bigint")
    }

    @Test
    fun `PostgreSQL types that differ stay different`() {
        assertFalse(SchemaDiff.normalizeType("varchar(255)", pg) == SchemaDiff.normalizeType("varchar(100)", pg))
        assertFalse(SchemaDiff.normalizeType("integer", pg) == SchemaDiff.normalizeType("bigint", pg))
        assertFalse(SchemaDiff.normalizeType("timestamp", pg) == SchemaDiff.normalizeType("timestamptz", pg))
        assertFalse(SchemaDiff.normalizeType("integer", pg) == SchemaDiff.normalizeType("integer[]", pg))
        assertFalse(SchemaDiff.normalizeType("numeric(10,2)", pg) == SchemaDiff.normalizeType("numeric(12,2)", pg))
    }

    @Test
    fun `a serial default names its sequence and the schema does not matter`() {
        val dev = SchemaDiff.normalizeDefault("nextval('shop_dev.orders_id_seq'::regclass)", pg)
        val prod = SchemaDiff.normalizeDefault("nextval('orders_id_seq'::regclass)", pg)
        assertEquals(dev, prod)
        assertEquals("nextval(sequence)", dev)
    }

    @Test
    fun `PostgreSQL casts on literals are not part of the default`() {
        assertEquals("abc", SchemaDiff.normalizeDefault("'abc'::character varying", pg))
        assertEquals("abc", SchemaDiff.normalizeDefault("'abc'::text", pg))
        assertEquals("0", SchemaDiff.normalizeDefault("0", pg))
        assertEquals("0", SchemaDiff.normalizeDefault("(0)::numeric", pg))
        assertEquals(
            SchemaDiff.normalizeDefault("now()", pg),
            SchemaDiff.normalizeDefault("CURRENT_TIMESTAMP", pg),
        )
        assertNull(SchemaDiff.normalizeDefault(null, pg))
    }

    // ------------------------------------------------------------------ SQL Server

    @Test
    fun `SQL Server nvarchar max has one spelling`() {
        assertEquals("nvarchar(max)", SchemaDiff.normalizeType("nvarchar(-1)", ms))
        assertEquals("nvarchar(max)", SchemaDiff.normalizeType("NVARCHAR( MAX )", ms))
        assertEquals("decimal(10,2)", SchemaDiff.normalizeType("numeric(10, 2)", ms))
        assertEquals("rowversion", SchemaDiff.normalizeType("timestamp", ms))
        assertFalse(SchemaDiff.normalizeType("nvarchar(50)", ms) == SchemaDiff.normalizeType("varchar(50)", ms))
    }

    @Test
    fun `SQL Server default brackets and the N prefix are spelling`() {
        assertEquals("0", SchemaDiff.normalizeDefault("((0))", ms))
        assertEquals("0", SchemaDiff.normalizeDefault("(0)", ms))
        assertEquals("getdate()", SchemaDiff.normalizeDefault("(getdate())", ms))
        assertEquals("getdate()", SchemaDiff.normalizeDefault("(GETDATE())", ms))
        assertEquals("abc", SchemaDiff.normalizeDefault("(N'abc')", ms))
        assertEquals("abc", SchemaDiff.normalizeDefault("('abc')", ms))
        // Brackets that belong to the expression stay.
        assertEquals("(1)+(2)", SchemaDiff.normalizeDefault("(1)+(2)", ms)?.replace(" ", ""))
    }

    @Test
    fun `SQL Server generated constraint names are recognised`() {
        assertTrue(EngineSchemaText.isGeneratedName(ms, "PK__widgets__3213E83F5C8A2E1B"))
        assertTrue(EngineSchemaText.isGeneratedName(ms, "DF__widgets__qty__4AB81AF0"))
        assertFalse(EngineSchemaText.isGeneratedName(ms, "PK_widgets"))
        assertFalse(EngineSchemaText.isGeneratedName(ms, "ix_orders_total"))
        assertFalse(EngineSchemaText.isGeneratedName(DatabaseEngine.MYSQL, "PK__widgets__3213E83F5C8A2E1B"))
    }

    // ------------------------------------------------------------------ SQLite

    @Test
    fun `SQLite types collapse to their affinity`() {
        fun same(a: String, b: String) = assertEquals("$a vs $b", SchemaDiff.normalizeType(a, lite), SchemaDiff.normalizeType(b, lite))
        same("VARCHAR(255)", "TEXT")
        same("NVARCHAR(20)", "clob")
        same("INT", "INTEGER")
        same("BIGINT", "integer")
        same("DOUBLE", "REAL")
        same("", "BLOB")
        assertFalse(SchemaDiff.normalizeType("TEXT", lite) == SchemaDiff.normalizeType("BLOB", lite))
        assertFalse(SchemaDiff.normalizeType("INTEGER", lite) == SchemaDiff.normalizeType("REAL", lite))
        // The NUMERIC family keeps its name: DATE and BOOLEAN say what the column is for.
        assertFalse(SchemaDiff.normalizeType("DATE", lite) == SchemaDiff.normalizeType("BOOLEAN", lite))
    }

    @Test
    fun `SQLite default parentheses are spelling`() {
        assertEquals(
            SchemaDiff.normalizeDefault("CURRENT_TIMESTAMP", lite),
            SchemaDiff.normalizeDefault("(CURRENT_TIMESTAMP)", lite),
        )
    }

    // ------------------------------------------------------------------ MySQL stays as it was

    @Test
    fun `MySQL defaults and types go through the old rules`() {
        assertEquals("int", SchemaDiff.normalizeType("int(11)"))
        assertEquals("tinyint(1)", SchemaDiff.normalizeType("tinyint(1)", DatabaseEngine.MYSQL))
        assertEquals("abc", SchemaDiff.normalizeDefault("'abc'"))
    }

    // ------------------------------------------------------------------ the comparison itself

    private fun side(engine: DatabaseEngine, vararg columns: CachedColumn, index: List<CachedIndex> = emptyList()) =
        SchemaDiffSide(
            database = "db",
            tables = listOf(CachedTable("db", "t", TableKind.TABLE.name, null, null, null, null, null, null)),
            structures = mapOf("t" to CachedStructure(columns.toList(), index, emptyList())),
            engine = engine,
        )

    private fun col(name: String, type: String, default: String? = null, extra: String? = null) =
        CachedColumn(name, type, nullable = false, defaultValue = default, isPrimaryKey = false, extra = extra, comment = null, position = 0)

    @Test
    fun `two engines are refused`() {
        val error = assertThrows(EngineMismatchException::class.java) {
            SchemaDiff.compare(side(DatabaseEngine.MYSQL), side(pg))
        }
        assertEquals(DatabaseEngine.MYSQL, error.a)
        assertEquals(pg, error.b)
    }

    @Test
    fun `PostgreSQL spelling differences are not reported and real ones are`() {
        val a = side(pg, col("id", "integer", "nextval('dev.t_id_seq'::regclass)", "auto_increment"), col("name", "character varying(20)", "'x'::character varying"))
        val b = side(pg, col("id", "int4", "nextval('t_id_seq'::regclass)", "auto_increment"), col("name", "varchar(30)", "'x'::character varying"))
        val columns = SchemaDiff.compare(a, b).tables.single().columns
        assertEquals(listOf("name"), columns.map { it.name })
        assertEquals(DiffField.TYPE, columns.single().changes.single().field)
    }

    @Test
    fun `SQL Server generated primary key names do not make a difference`() {
        val key = { name: String -> listOf(CachedIndex(name, true, listOf("id"), 0)) }
        val a = side(ms, col("id", "int"), index = key("PK__t__3213E83F5C8A2E1B"))
        val b = side(ms, col("id", "int"), index = key("PK__t__3213E83FAAAAAAAA"))
        assertTrue(SchemaDiff.compare(a, b).identical)
        // A person-chosen name that differs is still a difference.
        val c = side(ms, col("id", "int"), index = key("PK_t"))
        val d = side(ms, col("id", "int"), index = key("PK_t_id"))
        assertEquals(1, SchemaDiff.compare(c, d).changedCount)
    }

    @Test
    fun `MySQL comparison is unchanged by the engine field`() {
        val a = side(DatabaseEngine.MYSQL, col("n", "int(11)"))
        val b = side(DatabaseEngine.MYSQL, col("n", "int"))
        assertTrue(SchemaDiff.compare(a, b).identical)
    }
}
