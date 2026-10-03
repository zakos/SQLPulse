package hu.laurel.sqlpulse.data.search

import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.PostgresDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlServerDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqliteDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The statement and column rules of the database search, per engine, with the SQL pinned word for word. */
class DatabaseSearchEnginesTest {

    private fun column(name: String, type: String, pk: Boolean = false) = SchemaColumn(
        name = name, typeName = type, nullable = true, defaultValue = null,
        isPrimaryKey = pk, extra = null, comment = null,
    )

    private fun docs(idType: String, textType: String, numberType: String) =
        listOf(column("id", idType, pk = true), column("title", textType), column("qty", numberType))

    // ------------------------------------------------------------------ statements

    @Test
    fun `MySQL keeps its old statement exactly`() {
        val plan = DatabaseSearch.plan("shop", "docs", docs("int(11)", "varchar(10)", "int"), "ab", SearchMode.CONTAINS, 20)!!
        assertEquals(
            "SELECT `id`, LEFT(`title`, 1000) FROM `shop`.`docs` " +
                "WHERE LOWER(CAST(`title` AS CHAR)) LIKE LOWER(?) ESCAPE '!' LIMIT 20",
            plan.sql,
        )
        assertEquals(plan.sql, DatabaseSearch.plan("shop", "docs", docs("int(11)", "varchar(10)", "int"), "ab", SearchMode.CONTAINS, 20, MySqlDialect)!!.sql)
    }

    @Test
    fun `PostgreSQL casts to text, lower-cases and limits`() {
        val plan = DatabaseSearch.plan(
            "public", "docs", docs("integer", "character varying(80)", "integer"), "ab", SearchMode.CONTAINS, 20, PostgresDialect,
        )!!
        assertEquals(
            "SELECT \"id\", LEFT(CAST(\"title\" AS text), 1000) FROM \"public\".\"docs\" " +
                "WHERE LOWER(CAST(\"title\" AS text)) LIKE LOWER(?) ESCAPE '!' LIMIT 20",
            plan.sql,
        )
        assertEquals(listOf("%ab%"), plan.parameters)
    }

    @Test
    fun `SQL Server casts to NVARCHAR(MAX) and pages with OFFSET FETCH`() {
        val plan = DatabaseSearch.plan(
            "dbo", "docs", docs("int", "nvarchar", "int"), "ab", SearchMode.CONTAINS, 20, SqlServerDialect,
        )!!
        assertEquals(
            "SELECT [id], LEFT(CAST([title] AS NVARCHAR(MAX)), 1000) FROM [dbo].[docs] " +
                "WHERE LOWER(CAST([title] AS NVARCHAR(MAX))) LIKE LOWER(?) ESCAPE '!' " +
                "ORDER BY (SELECT NULL) OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY",
            plan.sql,
        )
    }

    @Test
    fun `SQLite cuts with SUBSTR`() {
        val plan = DatabaseSearch.plan(
            "main", "docs", docs("INTEGER", "TEXT", "INTEGER"), "ab", SearchMode.EXACT, 20, SqliteDialect,
        )!!
        assertEquals(
            "SELECT \"id\", SUBSTR(CAST(\"title\" AS TEXT), 1, 1000) FROM \"main\".\"docs\" " +
                "WHERE LOWER(CAST(\"title\" AS TEXT)) LIKE LOWER(?) ESCAPE '!' LIMIT 20",
            plan.sql,
        )
        assertEquals(listOf("ab"), plan.parameters)
    }

    @Test
    fun `several columns are ORed and each gets its own pattern`() {
        val columns = listOf(column("a", "text"), column("b", "text"))
        val plan = DatabaseSearch.plan("public", "t", columns, "x", SearchMode.CONTAINS, 5, PostgresDialect)!!
        assertEquals(2, plan.parameters.size)
        assertTrue(plan.sql, plan.sql.contains(" OR "))
        assertTrue(plan.keyColumns.isEmpty())
    }

    // ------------------------------------------------------------------ escaping

    @Test
    fun `a square bracket is escaped for SQL Server only`() {
        assertEquals("![x!]".replace("!]", "]"), DatabaseSearch.escapeLike("[x]", DatabaseEngine.SQLSERVER))
        assertEquals("[x]", DatabaseSearch.escapeLike("[x]", DatabaseEngine.POSTGRESQL))
        assertEquals("[x]", DatabaseSearch.escapeLike("[x]"))
        assertEquals("100!%!_!!", DatabaseSearch.escapeLike("100%_!", DatabaseEngine.SQLSERVER))
        assertEquals("%![a]%", DatabaseSearch.pattern("[a]", SearchMode.CONTAINS, DatabaseEngine.SQLSERVER))
    }

    // ------------------------------------------------------------------ which columns

    private fun searchable(engine: DatabaseEngine, type: String, numbers: Boolean = false) =
        DatabaseSearch.isSearchable(type, numbers, engine)

    @Test
    fun `PostgreSQL types`() {
        val pg = DatabaseEngine.POSTGRESQL
        for (text in listOf("text", "character varying(255)", "character(3)", "citext", "uuid", "json", "jsonb", "enum", "name")) {
            assertTrue(text, searchable(pg, text))
        }
        for (other in listOf("bytea", "text[]", "timestamp with time zone", "integer", "boolean", "point", "tsvector")) {
            assertFalse(other, searchable(pg, other))
        }
        assertTrue(searchable(pg, "numeric(10,2)", numbers = true))
        assertTrue(searchable(pg, "double precision", numbers = true))
        assertFalse(searchable(pg, "integer[]", numbers = true))
    }

    @Test
    fun `SQL Server types`() {
        val ms = DatabaseEngine.SQLSERVER
        for (text in listOf("nvarchar", "varchar", "char", "nchar", "text", "ntext", "uniqueidentifier")) {
            assertTrue(text, searchable(ms, text))
        }
        for (other in listOf("varbinary", "image", "xml", "datetime2", "bit", "int", "money", "geography")) {
            assertFalse(other, searchable(ms, other))
        }
        assertTrue(searchable(ms, "money", numbers = true))
        assertTrue(searchable(ms, "decimal", numbers = true))
        assertFalse(searchable(ms, "bit", numbers = true))
    }

    @Test
    fun `SQLite types follow the affinity rules`() {
        val lite = DatabaseEngine.SQLITE
        for (text in listOf("TEXT", "VARCHAR(20)", "NVARCHAR(100)", "CLOB", "character varying", "")) {
            assertTrue("[$text]", searchable(lite, text))
        }
        for (other in listOf("BLOB", "DATE", "DATETIME", "BOOLEAN", "INTEGER", "REAL", "NUMERIC")) {
            assertFalse(other, searchable(lite, other))
        }
        for (number in listOf("INTEGER", "INT", "BIGINT", "REAL", "DOUBLE", "FLOAT", "NUMERIC(10,2)", "DECIMAL")) {
            assertTrue(number, searchable(lite, number, numbers = true))
        }
        assertFalse(searchable(lite, "DATE", numbers = true))
    }

    @Test
    fun `MySQL types are unchanged`() {
        assertTrue(searchable(DatabaseEngine.MYSQL, "varchar(20)"))
        assertFalse(searchable(DatabaseEngine.MYSQL, "json"))
        assertNotNull(DatabaseSearch.plan("d", "t", listOf(column("a", "text")), "x", SearchMode.CONTAINS, 1))
    }
}
