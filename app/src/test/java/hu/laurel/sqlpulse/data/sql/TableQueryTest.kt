package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TableQueryTest {

    @Test
    fun `order by quotes the column and names the direction`() {
        assertEquals(" ORDER BY `created_at` ASC", TableQuery.orderBy(ColumnSort("created_at", false)))
        assertEquals(" ORDER BY `created_at` DESC", TableQuery.orderBy(ColumnSort("created_at", true)))
    }

    @Test
    fun `no sort means no order by`() {
        assertEquals("", TableQuery.orderBy(null))
    }

    @Test
    fun `a backtick in a column name is escaped rather than closing the quote`() {
        assertEquals(" ORDER BY `we``ird` ASC", TableQuery.orderBy(ColumnSort("we`ird", false)))
    }

    @Test
    fun `sorting cycles ascending descending and back to the table order`() {
        val first = TableQuery.nextSort(null, "name")
        assertEquals(ColumnSort("name", false), first)

        val second = TableQuery.nextSort(first, "name")
        assertEquals(ColumnSort("name", true), second)

        assertNull(TableQuery.nextSort(second, "name"))
    }

    @Test
    fun `sorting a different column starts that column ascending`() {
        val onName = ColumnSort("name", true)

        assertEquals(ColumnSort("id", false), TableQuery.nextSort(onName, "id"))
    }

    @Test
    fun `a filter becomes a bound LIKE`() {
        val filter = ColumnFilter("email", "example")

        assertEquals(" WHERE `email` LIKE ?", TableQuery.where(filter))
        assertEquals("%example%", TableQuery.whereParameter(filter))
    }

    @Test
    fun `a blank filter matches everything`() {
        assertEquals("", TableQuery.where(ColumnFilter("email", "   ")))
        assertNull(TableQuery.whereParameter(ColumnFilter("email", "")))
        assertEquals("", TableQuery.where(null))
        assertNull(TableQuery.whereParameter(null))
    }

    @Test
    fun `wildcards typed by the user are escaped so they match literally`() {
        assertEquals("%50\\%%", TableQuery.whereParameter(ColumnFilter("discount", "50%")))
        assertEquals("%a\\_b%", TableQuery.whereParameter(ColumnFilter("code", "a_b")))
    }
}
