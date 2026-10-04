package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectTextTest {

    private fun mysql(text: String, schema: String = "shop") = ObjectText.normalize(text, schema, DatabaseEngine.MYSQL)

    @Test
    fun `mysql view header words are stripped but the same words in the body are not`() {
        val header = "CREATE ALGORITHM=MERGE DEFINER=`app`@`%` SQL SECURITY INVOKER VIEW `v` AS select 1 AS `one`"
        assertEquals(mysql("CREATE VIEW v AS select 1 AS one"), mysql(header))
        // After the word VIEW these are text of the query.
        assertNotEquals(
            mysql("CREATE VIEW v AS select 'ALGORITHM=MERGE'"),
            mysql("CREATE VIEW v AS select 'ALGORITHM=TEMPTABLE'"),
        )
    }

    @Test
    fun `the own schema qualifier goes and another schema stays`() {
        assertEquals(
            mysql("CREATE VIEW v AS select t.id from t join other.u on 1 = 1"),
            mysql("CREATE VIEW `shop`.`v` AS select `shop`.`t`.`id` from `shop`.`t` join `other`.`u` on 1=1"),
        )
        assertNotEquals(
            mysql("CREATE VIEW v AS select 1 from other.u"),
            mysql("CREATE VIEW v AS select 1 from third.u"),
        )
    }

    @Test
    fun `case quoting comments and whitespace are formatting`() {
        val a = "SELECT  Id,\n   Name -- the name\n FROM /* base */ Users WHERE Active = 1"
        val b = "select `id`, `name` from `users` where `active`=1"
        assertEquals(mysql(a), mysql(b))
    }

    @Test
    fun `a name that needs its quotes keeps its spelling and string literals are untouched`() {
        assertNotEquals(mysql("select `Order Id` from t"), mysql("select `order id` from t"))
        assertNotEquals(mysql("select 'A b' from t"), mysql("select 'a b' from t"))
        assertEquals(mysql("select \"x y\" from t"), mysql("select \"x y\" from t"))
    }

    @Test
    fun `mysql double quotes are strings but postgres double quotes are names`() {
        assertNotEquals(mysql("select \"Abc\""), mysql("select \"abc\""))
        assertEquals(
            ObjectText.normalize("select \"Abc\" from \"public\".t", "public", DatabaseEngine.POSTGRESQL),
            ObjectText.normalize("SELECT abc FROM t", "public", DatabaseEngine.POSTGRESQL),
        )
    }

    @Test
    fun `sql server brackets and the dbo qualifier are formatting`() {
        val dev = "CREATE VIEW [dbo].[v] AS SELECT [t].[id] FROM [dbo].[t]"
        val prod = "create view dbo.v as\n  select t.id\n  from t"
        assertEquals(
            ObjectText.normalize(prod, "dbo", DatabaseEngine.SQLSERVER),
            ObjectText.normalize(dev, "dbo", DatabaseEngine.SQLSERVER),
        )
    }

    @Test
    fun `a character set introducer is the servers spelling of a literal`() {
        assertEquals(mysql("select 'x'"), mysql("select _utf8mb4'x'"))
    }

    @Test
    fun `check expressions lose the brackets that wrap the whole condition`() {
        val postgres = ObjectText.normalizeExpression("((age >= 0))", "public", DatabaseEngine.POSTGRESQL)
        assertEquals(postgres, ObjectText.normalizeExpression("age>=0", "public", DatabaseEngine.POSTGRESQL))
        assertEquals(
            ObjectText.normalizeExpression("(`age` >= 0)", "shop", DatabaseEngine.MYSQL),
            ObjectText.normalizeExpression("age >= 0", "shop", DatabaseEngine.MYSQL),
        )
        // Brackets that do not wrap the whole condition are part of it.
        assertEquals("(a)and(b)", ObjectText.normalizeExpression("(a) and (b)", "x", DatabaseEngine.MYSQL))
        assertNotEquals(
            ObjectText.normalizeExpression("age > 0", "x", DatabaseEngine.MYSQL),
            ObjectText.normalizeExpression("age >= 0", "x", DatabaseEngine.MYSQL),
        )
    }

    @Test
    fun `excerpts show the stretch around the first difference`() {
        val a = "select id from orders where active = 1 and total > 100"
        val b = "select id from orders where active = 1 and total > 200"
        val (x, y) = ObjectText.excerpts(a, b, before = 10, after = 10)
        assertTrue(x.endsWith("100") && y.endsWith("200"))
        assertTrue(x.startsWith("…") && y.startsWith("…"))
        val (same, _) = ObjectText.excerpts("abc", "abc")
        assertEquals("abc", same)
    }
}
