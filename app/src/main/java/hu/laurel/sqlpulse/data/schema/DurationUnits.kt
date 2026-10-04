package hu.laurel.sqlpulse.data.schema

/**
 * The words for hours, minutes and seconds in a duration such as `2 h 20 m`.
 *
 * The formatters are pure and take these as a parameter instead of reading resources, so a unit
 * test can check English and Hungarian (`2 ó 20 p`) in one run; the screens fill them from
 * `strings_polish.xml`, which is where a translation lives.
 */
data class DurationUnits(val hour: String, val minute: String, val second: String) {
    companion object {
        val ENGLISH = DurationUnits("h", "m", "s")
        val HUNGARIAN = DurationUnits("ó", "p", "mp")
    }
}
