package hu.laurel.sqlpulse.data.schema

/** One table's columns, as much of them as guessing a link needs. */
data class TableColumns(
    val table: String,
    val columns: List<String>,
    /** Columns MySQL reports as `PRI`. A table with none cannot be the target of a guess. */
    val primaryKey: List<String>,
)

/**
 * Links read off the column names, for schemas that have no foreign keys.
 *
 * Plenty of real databases have none: MyISAM never enforced them, and anything built before InnoDB
 * became the default often never added them. A map of such a schema drawn from foreign keys alone
 * is a wall of unconnected boxes — true, and useless.
 *
 * So the names are used instead: `bolt_id` in one table and a table called `bolt` or `boltok` is
 * almost certainly a link. Almost is the important word. Everything found here is marked
 * [GraphEdge.guessed], drawn dashed, and said out loud in the interface; nothing acts on it, no
 * query is built from it, and where the evidence is ambiguous nothing is drawn at all.
 */
object LinkGuesser {

    /**
     * @param tables every table in the database with its columns.
     * @param known the foreign keys the server actually reported; a pair it already covers is
     *   never guessed at again.
     */
    fun infer(tables: List<TableColumns>, known: List<GraphEdge> = emptyList()): List<GraphEdge> {
        val byNormalised = tables.groupBy { normalise(it.table) }
        val alreadyLinked = known.map { it.from to it.to }.toSet()
        val found = mutableListOf<GraphEdge>()

        tables.forEach { table ->
            table.columns.forEach column@{ column ->
                val base = referencedName(column) ?: return@column
                val candidates = byNormalised[normalise(base)].orEmpty()
                    // A table with no primary key is not something a column can point at.
                    .filter { it.primaryKey.isNotEmpty() }
                // Ambiguous evidence — two tables whose names reduce to the same thing — is left
                // undrawn rather than resolved by a coin toss.
                val target = candidates.singleOrNull() ?: return@column
                if (target.table == table.table) return@column
                if (table.table to target.table in alreadyLinked) return@column
                if (found.any { it.from == table.table && it.to == target.table }) return@column
                found += GraphEdge(
                    from = table.table,
                    to = target.table,
                    columns = listOf(column),
                    guessed = true,
                )
            }
        }
        return found
    }

    /**
     * The table name a column seems to point at, or null.
     *
     * Only the `_id` ending counts. A bare `id` is the table's own key, and a column called
     * `bolt` — no suffix — is as likely to be a name as a reference; guessing from those would
     * fill the map with lines nobody can trust.
     */
    private fun referencedName(column: String): String? {
        val lower = column.lowercase()
        val base = when {
            lower.endsWith("_id") -> lower.removeSuffix("_id")
            lower.endsWith("_kod") -> lower.removeSuffix("_kod")
            lower.endsWith("_azon") -> lower.removeSuffix("_azon")
            else -> null
        }
        return base?.takeIf { it.isNotBlank() && it != "id" }
    }

    /**
     * Reduces a name to what it has in common with the other spelling of itself.
     *
     * `bolt_id` should find `boltok`, `felhasznalo_id` should find `felhasznalok`, `user_id`
     * should find `users`. Case, underscores and a plural ending are dropped; what remains is
     * compared. It is a heuristic and it is allowed to be wrong — which is why what it finds is
     * never presented as a fact.
     */
    private fun normalise(name: String): String {
        var value = name.lowercase().replace("_", "")
        PLURAL_ENDINGS.forEach { ending ->
            if (value.length > ending.length + 2 && value.endsWith(ending)) {
                value = value.dropLast(ending.length)
                return value
            }
        }
        return value
    }

    /** Hungarian and English plurals, longest first so "ok" is tried before "k". */
    private val PLURAL_ENDINGS = listOf("ok", "ek", "ak", "ök", "es", "k", "s", "i")
}
