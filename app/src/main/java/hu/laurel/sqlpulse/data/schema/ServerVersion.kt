package hu.laurel.sqlpulse.data.schema

/**
 * What `SELECT VERSION()` says, split into the parts a feature gate needs.
 *
 * Only what the server screen decides on: which family it is and whether it is new enough for a
 * table or a statement. Anything unparseable is [UNKNOWN], and gates treat it as "try it" — the
 * server's own error is a better answer than a guess made from a string.
 */
data class ServerVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val mariaDb: Boolean,
) {
    /** True when this is at least [major].[minor] of the MySQL family (not MariaDB). */
    fun mysqlAtLeast(major: Int, minor: Int): Boolean =
        !mariaDb && (this.major > major || (this.major == major && this.minor >= minor))

    fun mariaDbAtLeast(major: Int, minor: Int): Boolean =
        mariaDb && (this.major > major || (this.major == major && this.minor >= minor))

    val known: Boolean get() = major > 0

    companion object {
        val UNKNOWN = ServerVersion(0, 0, 0, mariaDb = false)

        private val NUMBERS = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?""")

        fun parse(version: String?): ServerVersion {
            if (version.isNullOrBlank()) return UNKNOWN
            // MariaDB prefixes "5.5.5-" to its version for clients that might choke on 10.x;
            // the real number follows it.
            val cleaned = version.trim().removePrefix("5.5.5-")
            val match = NUMBERS.find(cleaned) ?: return UNKNOWN
            return ServerVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toIntOrNull() ?: 0,
                mariaDb = version.contains("mariadb", ignoreCase = true),
            )
        }
    }
}
