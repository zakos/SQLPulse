package hu.laurel.sqlpulse.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridWidthsTest {

    @Test
    fun `a column is as wide as its longest value, plus what the header spends`() {
        // Eight characters of value, and the room the sort icon and the drag handle take inside
        // the same column — the cells below get this same total, which is what keeps a column
        // under its own title.
        assertEquals(
            8 * GridWidths.CHAR_WIDTH_DP + GridWidths.HEADER_CONTROLS_DP,
            GridWidths.columnWidthDp(labelLength = 3, widestValueLength = 8),
        )
    }

    @Test
    fun `a long title widens the column even where the values are short`() {
        assertTrue(
            GridWidths.columnWidthDp(labelLength = 20, widestValueLength = 1) >
                GridWidths.columnWidthDp(labelLength = 4, widestValueLength = 1),
        )
    }

    @Test
    fun `a tiny column still leaves the label somewhere to be`() {
        val narrowest = GridWidths.columnWidthDp(labelLength = 1, widestValueLength = 1)
        assertTrue(narrowest - GridWidths.HEADER_CONTROLS_DP >= GridWidths.MIN_CHARS * GridWidths.CHAR_WIDTH_DP)
    }

    @Test
    fun `one enormous value cannot take the whole screen`() {
        assertEquals(
            GridWidths.MAX_CHARS * GridWidths.CHAR_WIDTH_DP + GridWidths.HEADER_CONTROLS_DP,
            GridWidths.columnWidthDp(labelLength = 4, widestValueLength = 4_000),
        )
    }

    @Test
    fun `a column dragged to its narrowest still fits the header's controls`() {
        // Below this the sort icon and the handle would be all there is, and the title would be
        // an ellipsis with nothing before it.
        assertTrue(GridWidths.MIN_WIDTH_DP > GridWidths.HEADER_CONTROLS_DP)
    }
}
