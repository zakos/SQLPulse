package hu.laurel.sqlpulse.data.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryDraftsTest {

    private fun roundTrip(book: DraftBook): DraftBook = QueryDrafts.decode(QueryDrafts.encode(book))

    @Test
    fun `an empty book survives`() {
        val decoded = roundTrip(DraftBook())
        assertTrue(decoded.drafts.isEmpty())
        assertNull(decoded.activeId)
    }

    @Test
    fun `drafts and the tab in front come back as they went in`() {
        val book = DraftBook(
            drafts = listOf(
                QueryDraft(id = 1, title = "havi", sql = "SELECT 1", database = "luna"),
                QueryDraft(id = 7, title = null, sql = "SELECT 2", database = null),
            ),
            activeId = 7,
        )
        assertEquals(book, roundTrip(book))
    }

    @Test
    fun `a draft holding a quote and a newline is returned character for character`() {
        val sql = "SELECT *\nFROM hivasok\nWHERE nev = 'O''Brien; -- nem komment'\n  AND x = \"a\\nb\"\n"
        val book = DraftBook(listOf(QueryDraft(id = 3, title = null, sql = sql, database = null)), 3)
        assertEquals(sql, roundTrip(book).drafts.single().sql)
    }

    @Test
    fun `nothing is trimmed and nothing is capped`() {
        val sql = "   \n\n  SELECT 1   \n\n   "
        val long = "SELECT " + "x".repeat(50_000)
        val book = DraftBook(
            listOf(
                QueryDraft(id = 1, title = "  szokozok  ", sql = sql, database = null),
                QueryDraft(id = 2, title = null, sql = long, database = null),
            ),
            1,
        )
        val decoded = roundTrip(book)
        assertEquals(sql, decoded.drafts[0].sql)
        assertEquals("  szokozok  ", decoded.drafts[0].title)
        assertEquals(long, decoded.drafts[1].sql)
    }

    @Test
    fun `an empty title is not the same as no title`() {
        val book = DraftBook(
            listOf(
                QueryDraft(id = 1, title = "", sql = "a", database = ""),
                QueryDraft(id = 2, title = null, sql = "b", database = null),
            ),
            1,
        )
        val decoded = roundTrip(book)
        assertEquals("", decoded.drafts[0].title)
        assertEquals("", decoded.drafts[0].database)
        assertNull(decoded.drafts[1].title)
        assertNull(decoded.drafts[1].database)
    }

    @Test
    fun `a title holding a newline does not become two records`() {
        val book = DraftBook(listOf(QueryDraft(id = 1, title = "ket\nsor", sql = "SELECT 1", database = null)), 1)
        val decoded = roundTrip(book)
        assertEquals(1, decoded.drafts.size)
        assertEquals("ket\nsor", decoded.drafts.single().title)
    }

    @Test
    fun `an active id pointing at a tab that is gone falls back to the first`() {
        val book = DraftBook(listOf(QueryDraft(id = 4, title = null, sql = "a", database = null)), activeId = 99)
        assertEquals(4L, roundTrip(book).activeId)
    }

    @Test
    fun `nothing stored means no drafts`() {
        assertEquals(DraftBook(), QueryDrafts.decode(null))
        assertEquals(DraftBook(), QueryDrafts.decode(""))
    }

    @Test
    fun `a file that is not ours is ignored rather than guessed at`() {
        assertTrue(QueryDrafts.decode("SELECT 1").drafts.isEmpty())
        assertTrue(QueryDrafts.decode("{\"sql\":\"SELECT 1\"}").drafts.isEmpty())
    }

    @Test
    fun `a truncated file keeps the drafts that did survive`() {
        val book = DraftBook(
            listOf(
                QueryDraft(id = 1, title = null, sql = "SELECT 1", database = null),
                QueryDraft(id = 2, title = null, sql = "SELECT 2", database = null),
            ),
            1,
        )
        val encoded = QueryDrafts.encode(book)
        val decoded = QueryDrafts.decode(encoded.dropLast(4))
        assertEquals(listOf(1L), decoded.drafts.map { it.id })
        assertEquals("SELECT 1", decoded.drafts.single().sql)
    }

    @Test
    fun `a corrupt record header stops the read instead of inventing a draft`() {
        val encoded = QueryDrafts.encode(
            DraftBook(listOf(QueryDraft(id = 1, title = null, sql = "SELECT 1", database = null)), 1),
        ).replace("tab 1 -1 -1 8", "tab 1 -1 -1 nyolc")
        assertTrue(QueryDrafts.decode(encoded).drafts.isEmpty())
    }
}
