package hu.laurel.sqlpulse.ui.query

import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor used to keep its own row limit (500) and never read Settings -> default row limit,
 * so the hint and the auto-LIMIT ignored a 5000 the user had chosen. The ViewModel now feeds the
 * setting through [EditorRowLimit.clamp] into the same state field both read.
 */
class QueryEditorRowLimitTest {

    @Test
    fun `the setting value is what the editor uses`() {
        assertEquals(5000, EditorRowLimit.clamp(5000))
        assertEquals(50, EditorRowLimit.clamp(50))
    }

    @Test
    fun `an out of range setting is clamped rather than refused`() {
        assertEquals(EditorRowLimit.MAX, EditorRowLimit.clamp(1_000_000))
        assertEquals(1, EditorRowLimit.clamp(0))
    }

    @Test
    fun `the auto limit applied to a select follows the limit it is given`() {
        val limited = MySqlDialect.applyDefaultLimit("SELECT * FROM t", EditorRowLimit.clamp(5000))
        assertTrue(limited.sql, limited.sql.contains("LIMIT 5000"))
        assertEquals(500, SqlGuards.DEFAULT_ROW_LIMIT)
    }
}
