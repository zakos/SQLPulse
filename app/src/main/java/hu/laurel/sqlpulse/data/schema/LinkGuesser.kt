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
 *
 * Applications that name every table the same way (`mantis_bug_table`, `wp_posts`) are read with
 * that prefix/suffix taken off, and a few role words (`reporter_id`, `handler_id`) are taken to
 * mean the user table when the schema has one.
 */
object LinkGuesser {

    /**
     * @param tables every table in the database with its columns.
     * @param known the foreign keys the server actually reported; a pair it already covers is
     *   never guessed at again.
     */
    fun infer(tables: List<TableColumns>, known: List<GraphEdge> = emptyList()): List<GraphEdge> {
        val affixes = Affixes.detect(tables.map { it.table })
        // Both the bare name and the name without the shared prefix/suffix are looked up, so a
        // schema that mostly uses a prefix still finds its odd table without one.
        val index = mutableMapOf<String, MutableSet<TableColumns>>()
        tables.forEach { table ->
            listOf(table.table, affixes.strip(table.table)).forEach { name ->
                index.getOrPut(normalise(name)) { mutableSetOf() } += table
            }
        }
        val userTable = lookup(index, "user")
        val alreadyLinked = known.map { it.from to it.to }.toSet()
        val found = mutableListOf<GraphEdge>()

        tables.forEach { table ->
            table.columns.forEach column@{ column ->
                val base = referencedName(column) ?: return@column
                val target = resolve(table, base, index, userTable) ?: return@column
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
     * The table [base] (a column name without its `_id`) points at, or null.
     *
     * In order: the name itself; for `parent_id`, the column's own table (a tree); a role word
     * (`reporter`, `handler`, …) when the schema has a user table; and finally the last word of a
     * longer name (`source_bug` → `bug`), which is how a role is usually spelled out.
     */
    private fun resolve(
        owner: TableColumns,
        base: String,
        index: Map<String, Set<TableColumns>>,
        userTable: TableColumns?,
    ): TableColumns? {
        val matches = candidates(index, base)
        // Ambiguous evidence — two tables whose names reduce to the same thing — is left
        // undrawn rather than resolved by a coin toss.
        if (matches.size > 1) return null
        val direct = matches.singleOrNull()
        // A column never points at its own table by name alone: `bug_id` inside the bug table is
        // its own key, not a link. `parent_id` is the one spelling that says "my own table".
        if (direct != null && direct.table != owner.table) return direct
        if (base == "parent" && owner.primaryKey.size == 1) return owner
        if (direct != null) return null
        if (base in USER_ROLES && userTable != null && userTable.table != owner.table) return userTable
        val words = base.split('_').filter { it.isNotEmpty() }
        if (words.size > 1 && words.last().length >= MIN_ROLE_TARGET) {
            val tail = lookup(index, words.last())
            if (tail != null && tail.table != owner.table) return tail
        }
        return null
    }

    /** The tables [name] reduces to that a column can point at. */
    private fun candidates(index: Map<String, Set<TableColumns>>, name: String): List<TableColumns> =
        index[normalise(name)].orEmpty()
            // A table with no primary key is not something a column can point at.
            .filter { it.primaryKey.isNotEmpty() }

    private fun lookup(index: Map<String, Set<TableColumns>>, name: String): TableColumns? =
        candidates(index, name).singleOrNull()

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

    /**
     * The prefix and suffix most of a schema's tables share: `mantis_` and `_table` in
     * `mantis_bug_table`. Without taking them off, `bug_id` has no table called `bug` to find.
     */
    private class Affixes(val prefix: String, val suffix: String) {
        fun strip(name: String): String {
            var value = name
            if (prefix.isNotEmpty() && value.startsWith(prefix, ignoreCase = true)) value = value.substring(prefix.length)
            if (suffix.isNotEmpty() && value.endsWith(suffix, ignoreCase = true)) {
                value = value.substring(0, value.length - suffix.length)
            }
            return value.ifBlank { name }
        }

        companion object {
            fun detect(names: List<String>): Affixes {
                if (names.size < MIN_TABLES_FOR_AFFIX) return Affixes("", "")
                val prefix = common(
                    names.mapNotNull { name ->
                        name.indexOf('_').takeIf { it > 0 && it < name.length - 1 }?.let { name.substring(0, it + 1) }
                    },
                    names.size,
                )
                val suffix = common(
                    names.mapNotNull { name ->
                        name.lastIndexOf('_').takeIf { it > 0 && it < name.length - 1 }?.let { name.substring(it) }
                    },
                    names.size,
                )
                return Affixes(prefix, suffix)
            }

            private fun common(candidates: List<String>, total: Int): String {
                val best = candidates.groupingBy { it.lowercase() }.eachCount().maxByOrNull { it.value } ?: return ""
                return if (best.value >= total * MIN_AFFIX_SHARE) best.key else ""
            }
        }
    }

    /** Share of the tables that must carry the same prefix or suffix for it to count as one. */
    private const val MIN_AFFIX_SHARE = 0.6
    private const val MIN_TABLES_FOR_AFFIX = 3

    /** A role's target must be a real word, so `x_id` in `a_x_id` does not link to a table `x`. */
    private const val MIN_ROLE_TARGET = 3

    /** Words that name a part played by a user rather than a table of their own. */
    private val USER_ROLES = setOf(
        "reporter", "handler", "author", "owner", "creator", "modifier", "assignee",
    )

    /** Hungarian and English plurals, longest first so "ok" is tried before "k". */
    private val PLURAL_ENDINGS = listOf("ok", "ek", "ak", "ök", "es", "k", "s", "i")
}
