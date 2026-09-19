package hu.laurel.sqlpulse.ui.query

import hu.laurel.sqlpulse.data.sql.ColumnSort
import hu.laurel.sqlpulse.data.sql.ParameterValue
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.data.sql.SqlScript

/** Which of the three lists under the editor is showing. Per tab, like everything else here. */
enum class QueryPanel { RESULT, HISTORY, FAVOURITES }

/**
 * One statement of a script that has been run.
 *
 * The table is kept per statement rather than only for the last one: a script whose first query
 * answered the question and whose third failed should still show the first answer.
 */
data class StatementRun(
    val sql: String,
    val table: ResultTable? = null,
    val updateCount: Int? = null,
    val switchedTo: String? = null,
    val error: String? = null,
)

/**
 * One editor tab: everything about a piece of work in progress.
 *
 * A tab is the unit of work, not the screen — the SQL, the database it runs against, the parameter
 * values that were typed for it, its result and the statement list of its last run all belong to
 * the tab. Switching tabs therefore restores the whole situation, which is the point: the reason
 * for a second tab is almost always "I need to look at something else without losing this".
 *
 * Only what the session owns is shared: the connection, whether a transaction is open, and the one
 * query that may be running at a time.
 */
data class QueryTab(
    val id: Long,
    /** What the user named it, or null to let the label be derived from the SQL. */
    val title: String? = null,
    val sql: String = "",
    val database: String? = null,
    /** The values last given for this tab's `:parameters`, so re-running does not ask again. */
    val parameters: Map<String, ParameterValue> = emptyMap(),
    val pendingParameters: List<String> = emptyList(),
    val result: ResultTable? = null,
    val updateCount: Int? = null,
    val statements: List<StatementRun> = emptyList(),
    val selectedStatement: Int = 0,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val error: String? = null,
    val errorDetail: String? = null,
    val switchedTo: String? = null,
    val resultSort: ColumnSort? = null,
    val panel: QueryPanel = QueryPanel.RESULT,
    val editorCollapsed: Boolean = false,
    val suggestions: List<String> = emptyList(),
    /**
     * The text as it stood when it was last saved as, or loaded from, a favourite.
     *
     * This is what "unsaved" is measured against. Null means the text has never been a favourite.
     */
    val savedSql: String? = null,
) {
    /** True when the tab holds more than one statement, so "run" means "run all of them". */
    val isScript: Boolean get() = SqlScript.split(sql).size > 1

    /** True when part of the editor is selected, so "run" means "run only that". */
    val hasSelection: Boolean get() = selectionEnd > selectionStart

    /**
     * True when closing this tab would throw text away.
     *
     * The comparison is trimmed because saving a favourite trims — leading blank lines are not a
     * change the user made since saving. The tab's own text is never touched by this.
     */
    val unsaved: Boolean get() = sql.isNotBlank() && sql.trim() != savedSql?.trim()
}

/**
 * Opening, closing, renaming and duplicating tabs.
 *
 * Plain data in, plain data out: no ids are minted here and no clock is read, so every rule below
 * can be stated as an equality in a test. The caller supplies the id for a new tab.
 */
object QueryTabs {

    /**
     * How many tabs may be open at once.
     *
     * Eight, because every tab holds a result set in memory and because a tab nobody can point at
     * is not a tab anybody uses. The number is small enough that the phone presentation can name
     * the one you are in and put the rest one tap away.
     */
    const val MAX_TABS = 8

    /** How long a derived label may get before it is elided. Display only; the SQL is untouched. */
    const val LABEL_LENGTH = 24

    /** Adds a tab after the last one, or null when [MAX_TABS] is already reached. */
    fun open(
        tabs: List<QueryTab>,
        id: Long,
        sql: String = "",
        database: String? = null,
    ): List<QueryTab>? {
        if (tabs.size >= MAX_TABS) return null
        return tabs + QueryTab(
            id = id,
            sql = sql,
            database = database,
            selectionStart = sql.length,
            selectionEnd = sql.length,
        )
    }

    /**
     * Copies a tab's text, database and parameters into a new tab next to it.
     *
     * The result and the statement list are deliberately not copied: a duplicate is a place to try
     * a variant of the query, and showing it the answer to a query it has not run yet would be a
     * lie about which text produced those rows.
     */
    fun duplicate(tabs: List<QueryTab>, id: Long, newId: Long): List<QueryTab>? {
        if (tabs.size >= MAX_TABS) return null
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return null
        val source = tabs[index]
        val copy = QueryTab(
            id = newId,
            title = source.title,
            sql = source.sql,
            database = source.database,
            parameters = source.parameters,
            selectionStart = source.sql.length,
            selectionEnd = source.sql.length,
            savedSql = source.savedSql,
        )
        return tabs.toMutableList().apply { add(index + 1, copy) }
    }

    /**
     * Removes a tab.
     *
     * Closing the only tab empties it instead of leaving no editor at all: "close" means "I am done
     * with this", and being left with nowhere to type is not what that means. The id survives, so
     * whatever was pointing at the active tab still is.
     */
    fun close(tabs: List<QueryTab>, id: Long): List<QueryTab> {
        if (tabs.none { it.id == id }) return tabs
        if (tabs.size == 1) return listOf(QueryTab(id = id))
        return tabs.filterNot { it.id == id }
    }

    /** Which tab to show once [closed] is gone: the one to its right, else the one to its left. */
    fun activeAfterClose(tabs: List<QueryTab>, closed: Long, active: Long): Long {
        if (tabs.size <= 1) return active
        if (active != closed) return active
        val index = tabs.indexOfFirst { it.id == closed }
        if (index < 0) return active
        return tabs.getOrNull(index + 1)?.id ?: tabs[index - 1].id
    }

    /** A blank name clears the name rather than setting an empty one, so the label goes back to the SQL. */
    fun rename(tabs: List<QueryTab>, id: Long, title: String): List<QueryTab> =
        tabs.map { if (it.id == id) it.copy(title = title.trim().ifBlank { null }) else it }

    /** Replaces one tab, leaving the rest alone. */
    fun replace(tabs: List<QueryTab>, id: Long, block: (QueryTab) -> QueryTab): List<QueryTab> =
        tabs.map { if (it.id == id) block(it) else it }

    /**
     * What the tab is called: its name, else the first line of its SQL, else nothing — and the
     * screen then falls back to a numbered placeholder it can translate.
     */
    fun label(tab: QueryTab): String? {
        tab.title?.takeIf { it.isNotBlank() }?.let { return it }
        val line = tab.sql.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val collapsed = line.trim().replace(WHITESPACE, " ")
        return if (collapsed.length <= LABEL_LENGTH) {
            collapsed
        } else {
            collapsed.take(LABEL_LENGTH - 1) + "…"
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
