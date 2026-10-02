package hu.laurel.sqlpulse.data.search

import hu.laurel.sqlpulse.data.sql.ColumnFilter
import hu.laurel.sqlpulse.data.sql.TableQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHitFilterTest {

    @Test
    fun `a single column key filters on that value exactly`() {
        val row = SearchRow(listOf("id" to "1042"), listOf("code" to "KB-20417"))
        val filter = SearchHitFilter.forRow(row, "KB-2", SearchMode.CONTAINS)!!
        assertEquals(ColumnFilter("id", "1042", exact = true), filter)
        assertEquals(" WHERE `id` = ?", TableQuery.where(filter))
        assertEquals(listOf("1042"), TableQuery.whereParameters(filter))
    }

    @Test
    fun `a composite key adds one equality per column`() {
        val row = SearchRow(listOf("order_id" to "7", "line" to "2"), listOf("sku" to "AB"))
        val filter = SearchHitFilter.forRow(row, "AB", SearchMode.CONTAINS)!!
        assertEquals(" WHERE `order_id` = ? AND `line` = ?", TableQuery.where(filter))
        assertEquals(listOf("7", "2"), TableQuery.whereParameters(filter))
    }

    @Test
    fun `without a key the matching cell is used`() {
        val row = SearchRow(emptyList(), listOf("name" to "Alice", "city" to "Budapest"))
        val filter = SearchHitFilter.forRow(row, "budap", SearchMode.CONTAINS)!!
        assertEquals("city", filter.column)
        assertEquals("Budapest", filter.contains)
        assertTrue(filter.exact)
    }

    @Test
    fun `a key with a null column falls back to the matching cell`() {
        val row = SearchRow(listOf("id" to null), listOf("note" to "hello"))
        val filter = SearchHitFilter.forRow(row, "hell", SearchMode.CONTAINS)!!
        assertEquals("note", filter.column)
    }

    @Test
    fun `a cell cut short on the server is matched as contains`() {
        val long = "x".repeat(DatabaseSearch.CELL_CHARS)
        val filter = SearchHitFilter.forRow(SearchRow(emptyList(), listOf("body" to long)), "xx", SearchMode.CONTAINS)!!
        assertFalse(filter.exact)
    }

    @Test
    fun `a row with nothing to go on has no filter`() {
        assertNull(SearchHitFilter.forRow(SearchRow(emptyList(), emptyList()), "a", SearchMode.CONTAINS))
    }

    @Test
    fun `an exact filter keeps the value as typed while contains escapes wildcards`() {
        assertEquals(listOf("50%_"), TableQuery.whereParameters(ColumnFilter("c", "50%_", exact = true)))
        assertEquals(listOf("%50\\%\\_%"), TableQuery.whereParameters(ColumnFilter("c", "50%_")))
    }

    @Test
    fun `an exact filter on the empty string still filters`() {
        assertEquals(" WHERE `c` = ?", TableQuery.where(ColumnFilter("c", "", exact = true)))
        assertEquals("", TableQuery.where(ColumnFilter("c", "")))
    }
}
