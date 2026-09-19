package hu.laurel.sqlpulse.data.schema

import hu.laurel.sqlpulse.data.sql.PreparedSql

/** One column of a link: the child column and the parent column it references. */
data class LinkColumn(val child: String, val parent: String)

/**
 * One relationship between two tables, in the direction the foreign key points: [childTable] holds
 * the columns, [parentTable] is what they reference.
 *
 * A constraint made of several columns is one link with several [columns]; a composite key is the
 * normal case in older schemas and walking it has to work exactly like a single column does.
 */
data class RowLink(
    val constraintName: String,
    val childDatabase: String,
    val childTable: String,
    val parentDatabase: String,
    val parentTable: String,
    val columns: List<LinkColumn>,
    /**
     * True when no foreign key says this link exists and [LinkGuesser] read it off the column
     * names. Everything built from one is marked in the interface, exactly as the map marks it;
     * it is never followed silently.
     */
    val guessed: Boolean = false,
) {
    val childColumns: List<String> get() = columns.map { it.child }
    val parentColumns: List<String> get() = columns.map { it.parent }
}

/** One column of a keyed read: a value that is bound, never written into the statement. */
data class ColumnMatch(val column: String, val value: String)

/** The WHERE of a keyed read. Empty means "the whole table", which is not a link lookup. */
data class RowFilter(val matches: List<ColumnMatch>) {
    val isEmpty: Boolean get() = matches.isEmpty()
}

/** How many rows a lookup that expected one row actually found. */
enum class LookupOutcome { MISSING, ONE, SEVERAL }

/**
 * One row of `information_schema.KEY_COLUMN_USAGE` as either direction needs it: which table holds
 * the column, and which table and column it references.
 */
data class KeyColumnUsage(
    val constraintName: String,
    val childDatabase: String,
    val childTable: String,
    val childColumn: String,
    val parentDatabase: String,
    val parentTable: String,
    val parentColumn: String,
)

/**
 * Walking the data along its relationships (§7.3): from a cell to the row it points at, and from a
 * row to what points at it.
 *
 * Plain Kotlin with no Android in it, because every decision here — which columns of a row make up
 * the lookup, what a composite key becomes, what to do when a value is NULL — is a decision worth
 * a test.
 *
 * Every value taken from a cell is a bound parameter. Identifiers cannot be bound, so they go
 * through [quoteIdentifier]; nothing else is ever written into the statement text.
 */
object RowLinks {

    /** A parent lookup expects one row; more than a handful means the link is not what it looked. */
    const val PARENT_LIMIT = 50

    /** A child listing is a page like any other. */
    const val CHILD_LIMIT = 100

    /** The declared foreign keys of one table, grouped into one link per constraint. */
    fun parentLinks(database: String, table: String, foreignKeys: List<ForeignKey>): List<RowLink> =
        group(
            foreignKeys.map { fk ->
                KeyColumnUsage(
                    constraintName = fk.constraintName,
                    childDatabase = database,
                    childTable = table,
                    childColumn = fk.column,
                    parentDatabase = fk.referencedDatabase,
                    parentTable = fk.referencedTable,
                    parentColumn = fk.referencedColumn,
                )
            },
        )

    /**
     * Groups key columns into links, keeping the order the columns were read in — which is the
     * constraint's own column order, and the order a composite key has to be matched in.
     */
    fun group(usages: List<KeyColumnUsage>, guessed: Boolean = false): List<RowLink> =
        usages.groupBy { Triple(it.constraintName, it.childTable, it.parentTable) }
            .map { (_, columns) ->
                val first = columns.first()
                RowLink(
                    constraintName = first.constraintName,
                    childDatabase = first.childDatabase,
                    childTable = first.childTable,
                    parentDatabase = first.parentDatabase,
                    parentTable = first.parentTable,
                    columns = columns.map { LinkColumn(it.childColumn, it.parentColumn) },
                    guessed = guessed,
                )
            }

    /**
     * Links read off the column names, for a table whose schema declares none.
     *
     * A guess only becomes something the app can follow when the table it points at has exactly
     * one primary key column: with nothing declared, there is no other way to know which column of
     * the parent the value belongs in, and inventing one would be worse than offering nothing.
     */
    fun guessedLinks(
        database: String,
        table: String,
        guesses: List<GraphEdge>,
        primaryKeys: Map<String, List<String>>,
    ): List<RowLink> = guesses
        .filter { it.guessed && it.from == table }
        .mapNotNull { edge ->
            val childColumn = edge.columns.singleOrNull() ?: return@mapNotNull null
            val parentColumn = primaryKeys[edge.to]?.singleOrNull() ?: return@mapNotNull null
            RowLink(
                constraintName = "${edge.from}.$childColumn",
                childDatabase = database,
                childTable = edge.from,
                parentDatabase = database,
                parentTable = edge.to,
                columns = listOf(LinkColumn(childColumn, parentColumn)),
                guessed = true,
            )
        }

    /** The link a cell in [column] belongs to, or null where that column references nothing. */
    fun linkForColumn(links: List<RowLink>, column: String): RowLink? =
        links.firstOrNull { link -> link.childColumns.any { it.equals(column, ignoreCase = true) } }

    /**
     * The WHERE that finds the row this one points at, or null when it points at nothing.
     *
     * A NULL in any column of the key is exactly that: no parent. Looking one up would mean
     * `WHERE id = NULL`, which matches nothing and would be reported as a missing row — a
     * different and misleading thing. So the offer is not made at all.
     */
    fun parentFilter(link: RowLink, row: Map<String, String?>): RowFilter? =
        matches(link.columns.map { it.parent to valueOf(row, it.child) })

    /** The WHERE that finds the rows pointing at this one, from the parent row's own values. */
    fun childFilter(link: RowLink, parentRow: Map<String, String?>): RowFilter? =
        matches(link.columns.map { it.child to valueOf(parentRow, it.parent) })

    /**
     * Reads the rows a filter matches.
     *
     * [limit] is an integer this file controls and never user input, so it is interpolated the way
     * the rest of the app's paged reads do it; every value is bound.
     */
    fun selectRows(
        database: String,
        table: String,
        filter: RowFilter,
        limit: Int = CHILD_LIMIT,
    ): PreparedSql {
        require(!filter.isEmpty) { "a link lookup needs at least one column" }
        return PreparedSql(
            sql = "SELECT * FROM ${qualified(database, table)}${where(filter)} LIMIT $limit",
            parameters = filter.matches.map { it.value },
        )
    }

    /** Counts what a filter matches, for the "what points at this" list. */
    fun countRows(database: String, table: String, filter: RowFilter): PreparedSql {
        require(!filter.isEmpty) { "a link lookup needs at least one column" }
        return PreparedSql(
            sql = "SELECT COUNT(*) FROM ${qualified(database, table)}${where(filter)}",
            parameters = filter.matches.map { it.value },
        )
    }

    /**
     * What a parent lookup found.
     *
     * More than one row is not an error — a guessed link, or a foreign key onto a non-unique
     * column, can genuinely match several — but it is not the single row the user was promised,
     * so it is said out loud rather than silently showing the first.
     */
    fun outcome(rowsFound: Int): LookupOutcome = when {
        rowsFound <= 0 -> LookupOutcome.MISSING
        rowsFound == 1 -> LookupOutcome.ONE
        else -> LookupOutcome.SEVERAL
    }

    private fun matches(pairs: List<Pair<String, String?>>): RowFilter? {
        if (pairs.isEmpty()) return null
        val matched = pairs.map { (column, value) -> ColumnMatch(column, value ?: return null) }
        return RowFilter(matched)
    }

    /** Column names come back from the server in whatever case the schema uses; MySQL ignores it. */
    private fun valueOf(row: Map<String, String?>, column: String): String? =
        row.entries.firstOrNull { it.key.equals(column, ignoreCase = true) }?.value

    private fun qualified(database: String, table: String) =
        "${quoteIdentifier(database)}.${quoteIdentifier(table)}"

    private fun where(filter: RowFilter) = filter.matches.joinToString(
        prefix = " WHERE ",
        separator = " AND ",
    ) { "${quoteIdentifier(it.column)} = ?" }
}
