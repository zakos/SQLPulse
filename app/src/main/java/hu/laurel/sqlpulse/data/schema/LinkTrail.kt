package hu.laurel.sqlpulse.data.schema

/**
 * One place the walk has been: a table, and the rows of it that were on screen.
 *
 * [filter] null means the table as it was opened, with no link filter on it — the step the walk
 * started from. [label] is what the interface calls this step, and [guessed] marks a step reached
 * along a link nobody declared.
 */
data class TrailStep(
    val database: String,
    val table: String,
    val filter: RowFilter? = null,
    val label: String = table,
    val guessed: Boolean = false,
)

/**
 * The walk from row to row, so parent → child → parent can be stepped back along rather than lost.
 *
 * Memory only, for the life of the screen: nothing here is written to disk, because it is made of
 * values out of the user's own data (§9).
 *
 * Returning to a table and filter the walk has already been at rewinds to that step instead of
 * stacking another copy of it. A ring of two tables referencing each other would otherwise grow a
 * trail without end, and "back" would walk a circle rather than out.
 */
data class LinkTrail(val steps: List<TrailStep> = emptyList()) {

    val isEmpty: Boolean get() = steps.isEmpty()

    val depth: Int get() = steps.size

    /** The step on screen now. */
    val current: TrailStep? get() = steps.lastOrNull()

    /** True when there is somewhere to step back to. */
    val canGoBack: Boolean get() = steps.size > 1

    /** True once any step was reached along a guessed link. */
    val hasGuessedStep: Boolean get() = steps.any { it.guessed }

    fun push(step: TrailStep): LinkTrail {
        val seen = steps.indexOfFirst { it.database == step.database && it.table == step.table && it.filter == step.filter }
        return when {
            seen < 0 -> LinkTrail(steps + step)
            else -> LinkTrail(steps.take(seen + 1))
        }
    }

    /**
     * Steps back one place, or returns this trail unchanged when there is nowhere to go — the
     * screen's own back button handles leaving, and a trail cannot pop its first step away.
     */
    fun pop(): LinkTrail = if (canGoBack) LinkTrail(steps.dropLast(1)) else this
}
