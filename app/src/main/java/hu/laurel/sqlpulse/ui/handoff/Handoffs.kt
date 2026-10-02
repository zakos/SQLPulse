package hu.laurel.sqlpulse.ui.handoff

import hu.laurel.sqlpulse.data.sql.ColumnFilter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SQL text on its way to the query editor from another screen.
 *
 * SQL is passed through memory rather than a navigation argument: a statement can be long and
 * full of characters a route would have to escape, and a route survives being restored after the
 * process died, which would open the same text again for no reason. The text is taken once; it is
 * only ever put in a tab, never run.
 */
@Singleton
class EditorHandoff @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)

    /** Observed by the editor, which may already be alive when the offer arrives. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(sql: String) {
        if (sql.isNotBlank()) _pending.value = sql
    }

    /** Returns the waiting text and clears it, so a second reader gets nothing. */
    fun take(): String? = _pending.value.also { _pending.value = null }
}

/**
 * A row filter on its way to a table screen: the search hit that was tapped, so the table opens
 * on that row instead of on its first page.
 */
@Singleton
class TableFilterHandoff @Inject constructor() {
    private data class Offer(val database: String, val table: String, val filter: ColumnFilter)

    private var offer: Offer? = null

    @Synchronized
    fun offer(database: String, table: String, filter: ColumnFilter) {
        offer = Offer(database, table, filter)
    }

    /** The filter meant for exactly this table, once. Another table's offer is left alone. */
    @Synchronized
    fun take(database: String, table: String): ColumnFilter? {
        val waiting = offer ?: return null
        if (waiting.database != database || waiting.table != table) return null
        offer = null
        return waiting.filter
    }
}
