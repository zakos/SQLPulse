package hu.laurel.sqlpulse.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenWidthTest {

    @Test
    fun `a phone stays one column, sideways as well`() {
        assertFalse(ScreenWidth.isWide(392)) // a phone upright
        assertFalse(ScreenWidth.isWide(640)) // the same phone turned sideways
    }

    @Test
    fun `a tablet gets two`() {
        assertTrue(ScreenWidth.isWide(800)) // a small tablet sideways
        assertTrue(ScreenWidth.isWide(1280))
        assertTrue(ScreenWidth.isWide(ScreenWidth.TWO_PANE_DP))
    }
}
