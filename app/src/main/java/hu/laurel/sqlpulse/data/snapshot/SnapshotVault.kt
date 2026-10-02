package hu.laurel.sqlpulse.data.snapshot

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one snapshot that outlives the query screen, for comparing across connections.
 *
 * Snapshots live in the query view model, and that is gone as soon as the user leaves the screen
 * to open another connection — which is exactly the move "dev against production" needs. The app
 * only ever holds one live session (§5), so the two sides are necessarily taken one after the
 * other, and the first has to survive the switch in between.
 *
 * Memory only, never disk: a snapshot is rows of somebody's database (§9). It is dropped when the
 * app locks and with the process. Holding the most recent snapshot only keeps the cost at one
 * bounded copy ([SnapshotLimits]).
 */
@Singleton
class SnapshotVault @Inject constructor() {

    @Volatile
    private var held: ResultSnapshot? = null

    /** The most recently taken snapshot, from whichever connection it was taken on. */
    val snapshot: ResultSnapshot? get() = held

    fun keep(snapshot: ResultSnapshot) {
        held = snapshot
    }

    fun clear() {
        held = null
    }
}
