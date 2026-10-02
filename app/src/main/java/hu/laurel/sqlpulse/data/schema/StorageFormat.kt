package hu.laurel.sqlpulse.data.schema

import java.text.NumberFormat
import java.util.Locale

/** Days, hours and minutes of an uptime; the screen words them in the user's language. */
data class UptimeParts(val days: Long, val hours: Long, val minutes: Long)

/**
 * Number and size formatting for the storage screen.
 *
 * Takes the [Locale] explicitly rather than reading the default: Hungarian writes "1,2 GB" and
 * English "1.2 GB", and a pure function with a parameter is the only way a unit test can check
 * both in one run.
 */
object StorageFormat {
    private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

    fun bytes(bytes: Long, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= 1024 && unit < UNITS.lastIndex) {
            value /= 1024
            unit++
        }
        // Past three digits a decimal is noise next to the server's own estimate.
        if (value >= 100) format.maximumFractionDigits = 0
        return "${format.format(value)} ${UNITS[unit]}"
    }

    fun count(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

    fun percent(fraction: Double, locale: Locale): String =
        NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }.format(fraction)

    fun uptime(seconds: Long): UptimeParts {
        val total = seconds.coerceAtLeast(0)
        return UptimeParts(total / 86_400, total % 86_400 / 3_600, total % 3_600 / 60)
    }
}
