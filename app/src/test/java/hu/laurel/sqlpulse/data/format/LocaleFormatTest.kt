package hu.laurel.sqlpulse.data.format

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleFormatTest {
    private val hu = Locale.forLanguageTag("hu-HU")
    private val en = Locale.US

    /** The grouping space is a no-break space in Hungarian; compare with it normalised. */
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ')

    @Test
    fun integersGroupByTheLocale() {
        assertEquals("1 204", LocaleFormat.integer(1204, hu).plain())
        assertEquals("1,204", LocaleFormat.integer(1204, en))
        assertEquals("999", LocaleFormat.integer(999, hu))
    }

    @Test
    fun decimalsUseTheLocalesSeparators() {
        assertEquals("1 204,50", LocaleFormat.decimal(1204.5, 2, hu).plain())
        assertEquals("1,204.50", LocaleFormat.decimal(1204.5, 2, en))
        assertEquals("43", LocaleFormat.decimal(42.6, 0, en))
    }

    @Test
    fun axisLabelsAreShortAndLocalised() {
        assertEquals("1,5M", LocaleFormat.axis(1_500_000.0, hu))
        assertEquals("12,3k", LocaleFormat.axis(12_340.0, hu))
        assertEquals("12.3k", LocaleFormat.axis(12_340.0, en))
        assertEquals("42", LocaleFormat.axis(42.0, hu))
        assertEquals("0,25", LocaleFormat.axis(0.25, hu))
    }

    @Test
    fun hungarianTimesAreTwentyFourHour() {
        val utc = TimeZone.getTimeZone("UTC")
        // 2026-10-03 21:14 UTC
        val millis = 1_791_062_040_000L
        val text = LocaleFormat.dateTime(millis, hu, utc).plain()
        assertTrue(text, text.contains("21:14"))
        assertTrue(text, text.contains("2026"))
        assertFalse(text, text.contains("PM"))
        assertTrue(LocaleFormat.dateTime(millis, en, utc).contains("PM"))
    }

    @Test
    fun smallCountsAreExactWithGrouping() {
        assertEquals("12 345", LocaleFormat.compactCount(12_345, hu).plain())
    }

    @Test
    fun byteSizesTakeTheDecimalSeparator() {
        assertEquals("1,5 KB", LocaleFormat.byteSize(1536, hu))
        assertEquals("1.5 KB", LocaleFormat.byteSize(1536, en))
        assertEquals("512 MB", LocaleFormat.byteSize(512L * 1024 * 1024, hu))
    }

    @Test
    fun percentIsRounded() {
        assertEquals("42%", LocaleFormat.percent(0.42, hu))
    }
}
