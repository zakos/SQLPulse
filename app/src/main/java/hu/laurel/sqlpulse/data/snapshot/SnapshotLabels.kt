package hu.laurel.sqlpulse.data.snapshot

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The small texts around a snapshot that are decisions rather than layout: what time it was taken
 * at, and where it came from. Pure, so both can be checked without a screen.
 */
object SnapshotLabels {

    /**
     * A time of day in the language of the app, not of the JVM.
     *
     * The JVM default follows the device's region, which is not what the interface is written in;
     * a Hungarian screen showing "2:05 PM" next to Hungarian words reads as a mistake. Hungarian
     * gets 24 hours from its own locale data.
     */
    fun clock(
        millis: Long,
        locale: Locale,
        withSeconds: Boolean = false,
        zone: TimeZone = TimeZone.getDefault(),
    ): String =
        DateFormat.getTimeInstance(if (withSeconds) DateFormat.MEDIUM else DateFormat.SHORT, locale)
            .apply { timeZone = zone }
            .format(Date(millis))

    /**
     * Where a snapshot came from, when that is not where the user is now; null when it is the same
     * connection and database (nothing to say), or when the snapshot never recorded its origin.
     *
     * Taking a snapshot on one connection and switching to another is the point of the feature, and
     * an unmarked snapshot of production next to a result from dev is exactly the confusion it
     * must not allow. A different database on the same connection matters just as much.
     */
    fun originNote(origin: SnapshotOrigin?, currentConnectionId: Long, currentDatabase: String?): String? {
        origin ?: return null
        val sameConnection = origin.connectionId == currentConnectionId
        val connection = origin.connectionName?.takeIf { !sameConnection && it.isNotBlank() }
        val database = origin.database?.takeIf { it.isNotBlank() && (!sameConnection || it != currentDatabase) }
        return listOfNotNull(connection, database).joinToString(" / ").ifEmpty { null }
    }
}
