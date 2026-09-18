package hu.laurel.sqlpulse.data.connection

/**
 * Which server a connection points at (research summary, §2.0).
 *
 * The app cannot tell a test database from the one customers are using, and the two look identical
 * once a result grid is open. Saying it out loud is what makes the difference visible: the list
 * groups by it, the card carries it, and a production connection that is allowed to write asks
 * once before it opens. That question is the whole protection — nothing here blocks a statement
 * the user has decided to run.
 */
enum class ConnectionEnvironment {
    DEVELOPMENT,
    TEST,
    PRODUCTION,

    /** Never classified. What every connection saved before this existed becomes. */
    UNSET,
    ;

    val isProduction: Boolean get() = this == PRODUCTION

    companion object {
        /**
         * Least dangerous first, unclassified last: production sits at the bottom of the list,
         * where it takes one deliberate scroll to reach, and the untagged rest below it reads as
         * the invitation to classify them.
         */
        val ORDER: List<ConnectionEnvironment> = listOf(DEVELOPMENT, TEST, PRODUCTION, UNSET)

        /** Unknown or missing names fall back to [UNSET] rather than guessing at production. */
        fun fromName(name: String?): ConnectionEnvironment =
            entries.firstOrNull { it.name == name } ?: UNSET

        /**
         * Splits [items] into groups in [ORDER], keeping each group's own order and dropping the
         * groups nothing falls into.
         */
        fun <T> group(
            items: List<T>,
            environmentOf: (T) -> ConnectionEnvironment,
        ): List<Pair<ConnectionEnvironment, List<T>>> =
            ORDER.mapNotNull { environment ->
                items.filter { environmentOf(it) == environment }
                    .takeIf { it.isNotEmpty() }
                    ?.let { environment to it }
            }
    }
}
