package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkGuesserTest {

    private fun table(name: String, vararg columns: String, pk: String? = "id") =
        TableColumns(name, columns.toList(), listOfNotNull(pk))

    @Test
    fun `a column ending in _id finds the table it is named after`() {
        val links = LinkGuesser.infer(
            listOf(
                table("hivasok", "id", "bolt_id"),
                table("bolt", "id", "nev"),
            ),
        )
        assertEquals(1, links.size)
        assertEquals("hivasok", links.single().from)
        assertEquals("bolt", links.single().to)
        // Never stated as fact: the map draws these dashed and says they are guesses.
        assertTrue(links.single().guessed)
    }

    @Test
    fun `a plural table name is still found`() {
        val links = LinkGuesser.infer(
            listOf(
                table("hivasok", "id", "bolt_id", "user_id"),
                table("boltok", "id"),
                table("users", "id"),
            ),
        )
        assertEquals(setOf("boltok", "users"), links.map { it.to }.toSet())
    }

    @Test
    fun `a bare id is the table's own key, not a reference`() {
        val links = LinkGuesser.infer(listOf(table("bolt", "id"), table("id", "id")))
        assertTrue(links.isEmpty())
    }

    @Test
    fun `a column with no recognised ending is left alone`() {
        // "bolt" as a column is as likely to be a name as a reference.
        val links = LinkGuesser.infer(listOf(table("hivasok", "id", "bolt"), table("bolt", "id")))
        assertTrue(links.isEmpty())
    }

    @Test
    fun `a table without a primary key is not something a column can point at`() {
        val links = LinkGuesser.infer(
            listOf(table("hivasok", "id", "log_id"), table("log", "message", pk = null)),
        )
        assertTrue(links.isEmpty())
    }

    @Test
    fun `ambiguous evidence draws nothing rather than picking one`() {
        // "bolt" and "boltok" both reduce to the same name; which one bolt_id means is unknowable
        // from the names alone.
        val links = LinkGuesser.infer(
            listOf(table("hivasok", "id", "bolt_id"), table("bolt", "id"), table("boltok", "id")),
        )
        assertTrue(links.isEmpty())
    }

    @Test
    fun `a foreign key the server reported is not guessed at again`() {
        val links = LinkGuesser.infer(
            tables = listOf(table("hivasok", "id", "bolt_id"), table("bolt", "id")),
            known = listOf(GraphEdge("hivasok", "bolt", listOf("bolt_id"))),
        )
        assertTrue(links.isEmpty())
    }

    @Test
    fun `a table pointing at itself by name is not drawn as a guess`() {
        // parent_id in "parent" would be a self-link on every tree table; too weak to draw.
        val links = LinkGuesser.infer(listOf(table("parent", "id", "parent_id")))
        assertTrue(links.isEmpty())
    }

    @Test
    fun `two columns pointing at the same table are one link`() {
        val links = LinkGuesser.infer(
            listOf(
                table("hivasok", "id", "user_id", "felvevo_user_id"),
                table("user", "id"),
            ),
        )
        assertEquals(1, links.size)
    }

    @Test
    fun `the other endings a Hungarian schema uses are recognised`() {
        val links = LinkGuesser.infer(
            listOf(
                table("munkalap", "id", "szerzodes_kod", "kutak_azon"),
                table("szerzodes", "id"),
                table("kutak", "id"),
            ),
        )
        assertEquals(setOf("szerzodes", "kutak"), links.map { it.to }.toSet())
    }

    @Test
    fun `a schema with nothing to match produces nothing`() {
        assertTrue(LinkGuesser.infer(emptyList()).isEmpty())
        assertTrue(LinkGuesser.infer(listOf(table("alone", "id", "value_id"))).isEmpty())
    }
}
