package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Test

class ColumnEditorsTest {

    @Test
    fun `an enum offers exactly its values`() {
        assertEquals(
            CellEditor.Choice(listOf("new", "paid", "sent")),
            ColumnEditors.of("enum('new','paid','sent')"),
        )
    }

    @Test
    fun `a value containing a comma or a quote survives`() {
        assertEquals(
            CellEditor.Choice(listOf("a,b", "it's")),
            ColumnEditors.of("enum('a,b','it''s')"),
        )
    }

    @Test
    fun `a set offers several of its values`() {
        assertEquals(CellEditor.Choices(listOf("read", "write")), ColumnEditors.of("set('read','write')"))
    }

    @Test
    fun `tinyint(1) is a boolean, other integers are numbers`() {
        assertEquals(CellEditor.Bool, ColumnEditors.of("tinyint(1)"))
        assertEquals(CellEditor.Number, ColumnEditors.of("tinyint(4)"))
        assertEquals(CellEditor.Number, ColumnEditors.of("int(11) unsigned"))
        assertEquals(CellEditor.Number, ColumnEditors.of("decimal(10,2)"))
    }

    @Test
    fun `the date types are told apart`() {
        assertEquals(CellEditor.Date, ColumnEditors.of("date"))
        assertEquals(CellEditor.DateTime, ColumnEditors.of("datetime"))
        assertEquals(CellEditor.DateTime, ColumnEditors.of("timestamp"))
        assertEquals(CellEditor.Time, ColumnEditors.of("time"))
        // A year is a number, not a date anyone would pick from a calendar.
        assertEquals(CellEditor.Number, ColumnEditors.of("year(4)"))
    }

    @Test
    fun `text and anything unrecognised gets the plain box`() {
        assertEquals(CellEditor.Text, ColumnEditors.of("varchar(255)"))
        assertEquals(CellEditor.Text, ColumnEditors.of("longtext"))
        assertEquals(CellEditor.Text, ColumnEditors.of("geometry"))
        assertEquals(CellEditor.Text, ColumnEditors.of(""))
    }

    @Test
    fun `the case the server reports in does not matter`() {
        assertEquals(CellEditor.Date, ColumnEditors.of("DATE"))
        assertEquals(CellEditor.Choice(listOf("a")), ColumnEditors.of("ENUM('a')"))
    }
}
