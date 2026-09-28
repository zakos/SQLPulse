package hu.laurel.sqlpulse.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementLayoutTest {

    @Test
    fun anUpdateBreaksBeforeItsKeywordsAndLinesThemUp() {
        val lines = StatementLayout.lines("UPDATE invoices SET status = 'paid' WHERE id = 20416 AND status = 'overdue'")

        assertEquals(
            listOf(
                "UPDATE invoices",
                "   SET status = 'paid'",
                " WHERE id = 20416",
                "   AND status = 'overdue'",
            ),
            lines,
        )
    }

    @Test
    fun aKeywordInsideAStringLiteralIsNotABreak() {
        val lines = StatementLayout.lines("UPDATE notes SET body = 'x WHERE y' WHERE id = 1")

        assertEquals(listOf("UPDATE notes", "   SET body = 'x WHERE y'", " WHERE id = 1"), lines)
    }

    @Test
    fun aDoubledQuoteStaysInsideTheLiteral() {
        val lines = StatementLayout.lines("UPDATE t SET a = 'it''s WHERE' WHERE id = 1")

        assertEquals(listOf("UPDATE t", "   SET a = 'it''s WHERE'", " WHERE id = 1"), lines)
    }

    @Test
    fun aDeleteKeepsItsFromOnTheFirstLine() {
        assertEquals(
            listOf("DELETE FROM orders", " WHERE id = 88213", " LIMIT 1"),
            StatementLayout.lines("DELETE FROM orders WHERE id = 88213 LIMIT 1"),
        )
    }

    @Test
    fun aStatementWithoutKeywordsIsOneLine() {
        assertEquals(listOf("COMMIT"), StatementLayout.lines("COMMIT"))
    }

    @Test
    fun theWhereClauseAndItsConditionsAreTheOnesCalledOut() {
        assertTrue(StatementLayout.isCondition(" WHERE id = 1"))
        assertTrue(StatementLayout.isCondition("   AND status = 'x'"))
        assertFalse(StatementLayout.isCondition("   SET status = 'x'"))
        assertFalse(StatementLayout.isCondition("UPDATE invoices"))
    }
}
