package hu.laurel.sqlpulse.data.schema

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageFormatTest {
    private val en = Locale.ENGLISH
    private val hu = Locale.forLanguageTag("hu")

    @Test
    fun smallSizesStayInBytes() {
        assertEquals("0 B", StorageFormat.bytes(0, en))
        assertEquals("512 B", StorageFormat.bytes(512, en))
    }

    @Test
    fun decimalSeparatorFollowsTheLocale() {
        val bytes = (1.2 * 1024 * 1024 * 1024).toLong()
        assertEquals("1.2 GB", StorageFormat.bytes(bytes, en))
        assertEquals("1,2 GB", StorageFormat.bytes(bytes, hu))
    }

    @Test
    fun wholeValuesHaveNoDecimal() {
        assertEquals("16 KB", StorageFormat.bytes(16 * 1024, en))
        assertEquals("16 KB", StorageFormat.bytes(16 * 1024, hu))
    }

    @Test
    fun largeValuesDropTheDecimal() {
        assertEquals("512 MB", StorageFormat.bytes(512L * 1024 * 1024 + 300_000, en))
    }

    @Test
    fun negativeIsClampedToZero() {
        assertEquals("0 B", StorageFormat.bytes(-5, en))
    }

    @Test
    fun uptimeSplitsIntoParts() {
        val parts = StorageFormat.uptime(3 * 86_400L + 4 * 3_600 + 5 * 60 + 9)
        assertEquals(UptimeParts(3, 4, 5), parts)
        assertEquals(UptimeParts(0, 0, 0), StorageFormat.uptime(-1))
    }

    @Test
    fun percentRounds() {
        assertEquals("92%", StorageFormat.percent(0.917, en))
    }
}
