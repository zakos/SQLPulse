package hu.laurel.sqlpulse.data.backup

/**
 * What to do with a connection whose name is already taken on this device.
 *
 * Nothing is ever overwritten silently: an import that finds a name it recognises asks, and the
 * user answers per connection or for all of them at once.
 */
enum class MergeResolution {
    /** Import it under a free name; the existing one is left exactly as it is. */
    KEEP_BOTH,

    /** Delete the existing connection (with its credentials and saved queries) and put this one in its place. */
    REPLACE,

    /** Leave the existing one alone and do not import this one at all. */
    SKIP,
}

/** One decided connection. [finalName] is what it will be called once the import has run. */
data class MergeDecision(
    val sourceName: String,
    val conflicting: Boolean,
    val resolution: MergeResolution,
    val finalName: String,
) {
    /** True when the import will write this connection at all. */
    val imports: Boolean get() = resolution != MergeResolution.SKIP

    /** True when an existing connection of the same name has to go first. */
    val replacesExisting: Boolean get() = conflicting && resolution == MergeResolution.REPLACE
}

/**
 * Turns "here is what is in the file, here is what is already on the phone, here is what the user
 * chose" into the list of things the import will do.
 *
 * Pure, so the screen can show the plan before anything happens and the tests can check every
 * branch of it without a database.
 */
object BackupMerge {

    /** Names in [payload] that already exist on this device, in the order the file lists them. */
    fun conflicts(payload: BackupPayload, existingNames: Collection<String>): List<String> {
        val existing = existingNames.toSet()
        return payload.connections.map { it.name }.filter { it in existing }
    }

    /**
     * @param defaultResolution what a conflict does unless [perConnection] says otherwise — the
     *   "apply to all" answer.
     * @param perConnection the answer for one connection, by its name in the file.
     */
    fun plan(
        payload: BackupPayload,
        existingNames: Collection<String>,
        defaultResolution: MergeResolution = MergeResolution.KEEP_BOTH,
        perConnection: Map<String, MergeResolution> = emptyMap(),
    ): List<MergeDecision> {
        val existing = existingNames.toMutableSet()
        return payload.connections.map { connection ->
            val conflicting = connection.name in existing
            val resolution = if (conflicting) {
                perConnection[connection.name] ?: defaultResolution
            } else {
                // A name nobody else has is simply imported; the choice does not apply to it.
                MergeResolution.KEEP_BOTH
            }
            val finalName = when {
                !conflicting -> connection.name.also { existing += it }
                resolution == MergeResolution.KEEP_BOTH -> freeName(connection.name, existing)
                    .also { existing += it }
                // REPLACE frees the name it takes over; SKIP changes nothing either way.
                else -> connection.name
            }
            MergeDecision(
                sourceName = connection.name,
                conflicting = conflicting,
                resolution = resolution,
                finalName = finalName,
            )
        }
    }

    /**
     * "Server" -> "Server (imported)" -> "Server (imported 2)" ...
     *
     * [taken] grows as the plan is built, so importing a file twice in a row, or a file that
     * itself contains two connections of the same name, still ends with distinct names.
     */
    fun freeName(base: String, taken: Set<String>): String {
        val first = "$base $SUFFIX"
        if (first !in taken) return first
        var counter = 2
        while ("$first $counter" in taken) counter++
        return "$first $counter"
    }

    private const val SUFFIX = "(imported)"
}
