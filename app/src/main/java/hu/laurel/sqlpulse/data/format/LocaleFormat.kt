package hu.laurel.sqlpulse.data.format

import hu.laurel.sqlpulse.data.schema.formatByteSize
import android.icu.text.CompactDecimalFormat
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Numbers and dates in the language the interface is drawn in.
 *
 * Everything takes the locale explicitly: `String.format` and the no-argument `DateFormat` /
 * `NumberFormat` factories follow the JVM default, which is the device's region and can disagree
 * with the strings on screen ("1,204 rows" next to Hungarian words, or 2:05 PM on a 24-hour
 * screen). The UI passes `appLocale()`; tests pass a fixed one.
 */
object LocaleFormat {

    /** `1 204` in Hungarian, `1,204` in English: a whole number with the locale's grouping. */
    fun integer(value: Long, locale: Locale): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    /** [value] with exactly [digits] decimals and the locale's separators, e.g. `1 204,50`. */
    fun decimal(value: Double, digits: Int, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(value)

    /**
     * A date and a time such as `2026. okt. 3. 21:14` (Hungarian) or `Oct 3, 2026, 9:14 PM`.
     * The zone is a parameter so a test does not depend on the machine it runs on.
     */
    fun dateTime(millis: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
            .apply { timeZone = zone }
            .format(Date(millis))

    /** As [dateTime], with seconds: for a log line where two entries can share a minute. */
    fun dateTimeSeconds(millis: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale)
            .apply { timeZone = zone }
            .format(Date(millis))

    /**
     * A row count as a list shows it: exact with grouping below a hundred thousand, then shortened
     * the way the locale shortens ("1,2 M"). Not available off-device (ICU is Android's), so the
     * large branch falls back to grouping where the platform class is missing, as in unit tests.
     */
    fun compactCount(count: Long, locale: Locale): String {
        if (count < 100_000) return integer(count, locale)
        return try {
            CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT).format(count)
        } catch (_: Throwable) {
            integer(count, locale)
        }
    }

    /**
     * A number short enough for a chart axis: `12,3k`, `1,2M`, a whole number from ten up, two
     * decimals below that. The exact figure is in the grid one tap away.
     */
    fun axis(value: Double, locale: Locale): String {
        val magnitude = kotlin.math.abs(value)
        return when {
            magnitude >= 1_000_000 -> decimal(value / 1_000_000, 1, locale) + "M"
            magnitude >= 1_000 -> decimal(value / 1_000, 1, locale) + "k"
            magnitude >= 10 || value == value.toLong().toDouble() -> value.toLong().toString()
            else -> decimal(value, 2, locale)
        }
    }

    /** `1,5 KB` in Hungarian: [formatByteSize] with the locale's decimal separator. */
    fun byteSize(bytes: Long, locale: Locale): String =
        formatByteSize(bytes).replace('.', java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator)

    /** A share such as `42%` from 0.42, without the locale's own percent spacing rules. */
    fun percent(fraction: Double, locale: Locale): String = decimal(fraction * 100, 0, locale) + "%"
}
