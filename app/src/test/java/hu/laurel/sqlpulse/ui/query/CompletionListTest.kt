package hu.laurel.sqlpulse.ui.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompletionListTest {
    private fun col(name: String) = CompletionItem(name, CompletionKind.COLUMN, "int · t")
    private fun kw(name: String) = CompletionItem(name, CompletionKind.KEYWORD)

    @Test
    fun `columns come before keywords and shorter names first when a prefix is typed`() {
        val ranked = CompletionRanking.rank(
            listOf(kw("CASE"), col("customer_id"), col("cur"), CompletionItem("COUNT(", CompletionKind.FUNCTION)),
            "c",
        )
        // Typed case first (the lower-case columns), then the upper-case words by kind.
        assertEquals(listOf("cur", "customer_id", "COUNT(", "CASE"), ranked.map { it.text })
    }

    @Test
    fun `typed case wins over a case-insensitive match`() {
        val ranked = CompletionRanking.rank(listOf(col("customer_id"), CompletionItem("Customers", CompletionKind.TABLE)), "Cu")
        assertEquals("Customers", ranked.first().text)
    }

    @Test
    fun `without a prefix the callers order is kept within a kind and tables and columns lead`() {
        val ranked = CompletionRanking.rank(listOf(kw("FROM"), col("b"), col("a")), "")
        assertEquals(listOf("b", "a", "FROM"), ranked.map { it.text })
    }

    @Test
    fun `an exact match and duplicates are dropped and the list is capped`() {
        val many = (1..20).map { col("c$it") }
        assertEquals(CompletionRanking.MAX_ROWS, CompletionRanking.rank(many, "c").size)
        assertEquals(listOf("cc"), CompletionRanking.rank(listOf(col("c"), col("cc"), col("cc")), "c").map { it.text })
    }

    @Test
    fun `a snippet that equals the typed word is still offered`() {
        val snippet = CompletionItem("sel", CompletionKind.SNIPPET, snippetBody = "SELECT ")
        assertEquals(listOf(snippet), CompletionRanking.rank(listOf(snippet), "sel"))
    }

    @Test
    fun `keywords with a bracket are functions`() {
        assertEquals(CompletionKind.FUNCTION, CompletionRanking.kindOfKeyword("SUM("))
        assertEquals(CompletionKind.KEYWORD, CompletionRanking.kindOfKeyword("ORDER BY"))
    }

    @Test
    fun `split bolds the typed part in the suggestions own spelling`() {
        assertEquals("cu" to "stomers", CompletionRanking.split("customers", "cu"))
        assertEquals("CU" to "STOMERS", CompletionRanking.split("CUSTOMERS", "cu"))
        assertEquals("" to "orders", CompletionRanking.split("orders", "cu"))
        assertEquals("" to "orders", CompletionRanking.split("orders", ""))
    }

    @Test
    fun `selection wraps in both directions`() {
        assertEquals(0, CompletionRanking.move(2, 1, 3))
        assertEquals(2, CompletionRanking.move(0, -1, 3))
        assertEquals(0, CompletionRanking.move(5, 1, 0))
    }

    @Test
    fun `the list goes under the cursor when it fits`() {
        val spot = CompletionPlacement.place(100, 200, 223, 300, 160, 600, 400, 8, 80)
        assertEquals(PopupSpot(92, 223, 160), spot)
    }

    @Test
    fun `the list flips above the cursor rather than covering the key bar`() {
        val spot = CompletionPlacement.place(50, 480, 503, 300, 160, 560, 400, 8, 80)
        assertEquals(PopupSpot(50, 320, 160), spot)
    }

    @Test
    fun `x is clamped to the screen`() {
        assertEquals(112, CompletionPlacement.place(390, 100, 123, 280, 100, 600, 400, 8, 80)!!.x)
        assertEquals(8, CompletionPlacement.place(-50, 100, 123, 280, 100, 600, 400, 8, 80)!!.x)
    }

    @Test
    fun `the list is shortened when neither side has room and hidden when there is none`() {
        // 100 px below the cursor line, 60 above: the roomier side, shortened.
        assertEquals(PopupSpot(8, 83, 100), CompletionPlacement.place(0, 60, 83, 280, 240, 183, 400, 8, 80))
        assertNull(CompletionPlacement.place(0, 30, 53, 280, 240, 80, 400, 8, 80))
    }
}
