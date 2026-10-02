package hu.laurel.sqlpulse.data.schema

/**
 * Schema comparison between two databases — typically the development copy and production — as
 * pure logic.
 *
 * The algorithm follows dbx's `crates/dbx-sql-schema/src/schema_diff.rs` (Apache-2.0): tables are
 * paired by name, then columns, indexes and foreign keys inside each pair; exact name matches win
 * over case-insensitive ones (`resolve_column_matches`), column order is only compared when every
 * column found its partner (`diff_columns_with_identifier_options`), a foreign key pointing into
 * its own schema has the schema name neutralised (`normalize_self_referencing_fk`), and a MySQL
 * view's DDL loses its `DEFINER` and its own schema qualifier before it is compared
 * (`normalize_mysql_view_ddl`, `strip_mysql_view_definer`). What dbx does with the result — sync
 * SQL, rollback scripts — is deliberately left behind: §2 of the specification rules out DDL, so
 * this comparison only ever reports.
 *
 * Both sides are what the offline schema cache holds ([CachedTable], [CachedStructure]), not the
 * live models. The app keeps one session at a time, so at most one side can ever be read live;
 * comparing two captures is the only way both sides can be put next to each other at all, and it
 * also means the comparison works on the underground. The cache keeps less than a live read does
 * — no column collation, no foreign key rules, no CHECK constraints, no triggers — so those are
 * simply not compared, rather than reported as differences that are really only gaps.
 *
 * Nothing here knows about Android, Room or JDBC, so every rule below is unit-tested.
 */

/** What the comparison should overlook. */
data class SchemaDiffOptions(
    /**
     * Pair `Orders` with `orders`. Off by default: on Linux, MySQL table names are case-sensitive
     * and the two really are different tables there.
     */
    val ignoreIdentifierCase: Boolean = false,
    /** Leave collations out. Two server versions with different defaults would otherwise differ everywhere. */
    val ignoreCharset: Boolean = false,
    /** Leave comments out. On by default: a reworded comment is rarely what anybody is hunting for. */
    val ignoreComments: Boolean = true,
    /** Report a column that sits in a different place. */
    val compareColumnOrder: Boolean = true,
)

/** One side as it was captured: the database name, its tables, and whatever structures are known. */
data class SchemaDiffSide(
    val database: String,
    val tables: List<CachedTable>,
    /**
     * Structure per table name. A table missing from here has only ever been listed, never opened,
     * so there is nothing to compare its columns against — which is different from having none.
     */
    val structures: Map<String, CachedStructure>,
    /** `SHOW CREATE VIEW` text per view name, where known. The cache does not keep it today. */
    val viewDefinitions: Map<String, String> = emptyMap(),
)

/** Where something was found. A and B rather than added and removed: neither side is "right". */
enum class DiffStatus { ONLY_A, ONLY_B, CHANGED }

/** What differs about a thing present on both sides. The screen turns these into words. */
enum class DiffField {
    NAME, KIND, ENGINE, COLLATION, COMMENT,
    TYPE, NULLABLE, DEFAULT, EXTRA, POSITION,
    UNIQUE, COLUMNS, REFERENCES, DEFINITION,
}

/** One property that differs, as each side has it. Null means the side has no value there. */
data class FieldChange(val field: DiffField, val a: String?, val b: String?)

/**
 * A column, index or foreign key that differs.
 *
 * [summary] describes an item that is on one side only — its type, or its columns — in the SQL
 * words a reader already knows, because "only in A: email" says little without "varchar(255)".
 */
data class ItemDiff(
    val name: String,
    val status: DiffStatus,
    val changes: List<FieldChange> = emptyList(),
    val summary: String? = null,
)

/** Whose structure was never captured. Without it only the table's own row can be compared. */
enum class StructureGap { NONE, MISSING_A, MISSING_B, MISSING_BOTH }

data class TableDiff(
    /** The name on side A, or on side B for a table only B has. */
    val name: String,
    val status: DiffStatus,
    val view: Boolean,
    val changes: List<FieldChange> = emptyList(),
    val columns: List<ItemDiff> = emptyList(),
    val indexes: List<ItemDiff> = emptyList(),
    val foreignKeys: List<ItemDiff> = emptyList(),
    val gap: StructureGap = StructureGap.NONE,
) {
    /** Everything inside the table that differs, for the count next to its name. */
    val itemCount: Int get() = changes.size + columns.size + indexes.size + foreignKeys.size
}

/** A table on both sides whose own row matches but whose structure one side never captured. */
data class UncheckedTable(val name: String, val gap: StructureGap)

data class SchemaDiffResult(
    val tables: List<TableDiff>,
    /** Tables on both sides, fully compared, with nothing to report. */
    val identicalCount: Int,
    val unchecked: List<UncheckedTable>,
) {
    val onlyACount: Int get() = tables.count { it.status == DiffStatus.ONLY_A }
    val onlyBCount: Int get() = tables.count { it.status == DiffStatus.ONLY_B }
    val changedCount: Int get() = tables.count { it.status == DiffStatus.CHANGED }

    /** True only when everything was compared and nothing differs. A gap is not a match. */
    val identical: Boolean get() = tables.isEmpty() && unchecked.isEmpty()
}

object SchemaDiff {

    fun compare(a: SchemaDiffSide, b: SchemaDiffSide, options: SchemaDiffOptions = SchemaDiffOptions()): SchemaDiffResult {
        val ignoreCase = options.ignoreIdentifierCase
        val pairs = matchByName(a.tables, b.tables, ignoreCase) { it.name }
        val tables = mutableListOf<TableDiff>()
        val unchecked = mutableListOf<UncheckedTable>()
        var identical = 0

        for ((left, right) in pairs) {
            when {
                right == null -> tables += TableDiff(left!!.name, DiffStatus.ONLY_A, left.isView)
                left == null -> tables += TableDiff(right.name, DiffStatus.ONLY_B, right.isView)
                else -> {
                    val diff = compareTable(left, right, a, b, options)
                    when {
                        diff.itemCount > 0 -> tables += diff
                        diff.gap != StructureGap.NONE -> unchecked += UncheckedTable(left.name, diff.gap)
                        else -> identical++
                    }
                }
            }
        }

        return SchemaDiffResult(
            tables = tables.sortedWith(compareBy({ it.name.lowercase() }, { it.name })),
            identicalCount = identical,
            unchecked = unchecked.sortedWith(compareBy({ it.name.lowercase() }, { it.name })),
        )
    }

    private fun compareTable(
        left: CachedTable,
        right: CachedTable,
        a: SchemaDiffSide,
        b: SchemaDiffSide,
        options: SchemaDiffOptions,
    ): TableDiff {
        val changes = mutableListOf<FieldChange>()
        if (left.isView != right.isView) changes += FieldChange(DiffField.KIND, left.kind, right.kind)
        // A null engine or collation is a side that did not say (a view, an old capture), not a
        // side that has none; only two stated values can disagree.
        if (left.engine != null && right.engine != null && !left.engine.equals(right.engine, ignoreCase = true)) {
            changes += FieldChange(DiffField.ENGINE, left.engine, right.engine)
        }
        if (!options.ignoreCharset && left.collation != null && right.collation != null &&
            normalizeCollation(left.collation) != normalizeCollation(right.collation)
        ) {
            changes += FieldChange(DiffField.COLLATION, left.collation, right.collation)
        }
        if (!options.ignoreComments && left.comment.orEmpty() != right.comment.orEmpty()) {
            changes += FieldChange(DiffField.COMMENT, left.comment, right.comment)
        }
        if (left.isView && right.isView) {
            val leftDdl = a.viewDefinitions[left.name]
            val rightDdl = b.viewDefinitions[right.name]
            if (leftDdl != null && rightDdl != null &&
                normalizeViewDefinition(leftDdl, a.database, options.ignoreIdentifierCase) !=
                normalizeViewDefinition(rightDdl, b.database, options.ignoreIdentifierCase)
            ) {
                changes += FieldChange(DiffField.DEFINITION, null, null)
            }
        }

        val leftStructure = a.structures[left.name]
        val rightStructure = b.structures[right.name]
        val gap = when {
            leftStructure == null && rightStructure == null -> StructureGap.MISSING_BOTH
            leftStructure == null -> StructureGap.MISSING_A
            rightStructure == null -> StructureGap.MISSING_B
            else -> StructureGap.NONE
        }
        if (leftStructure == null || rightStructure == null) {
            return TableDiff(left.name, DiffStatus.CHANGED, left.isView, changes, gap = gap)
        }

        return TableDiff(
            name = left.name,
            status = DiffStatus.CHANGED,
            view = left.isView,
            changes = changes,
            columns = diffColumns(leftStructure.columns, rightStructure.columns, options),
            indexes = diffIndexes(leftStructure.indexes, rightStructure.indexes, options),
            foreignKeys = diffForeignKeys(
                groupForeignKeys(leftStructure.foreignKeys, a.database, options.ignoreIdentifierCase),
                groupForeignKeys(rightStructure.foreignKeys, b.database, options.ignoreIdentifierCase),
                options,
            ),
        )
    }

    // ------------------------------------------------------------------ columns

    fun diffColumns(a: List<CachedColumn>, b: List<CachedColumn>, options: SchemaDiffOptions = SchemaDiffOptions()): List<ItemDiff> {
        val left = a.sortedBy { it.position }
        val right = b.sortedBy { it.position }
        val pairs = matchByName(left, right, options.ignoreIdentifierCase) { it.name }
        // Order only means something when the two lists are the same columns; with one added, every
        // column after it has "moved", and reporting all of them would bury the one real change.
        val compareOrder = options.compareColumnOrder && left.size == right.size && pairs.all { it.first != null && it.second != null }
        val diffs = mutableListOf<ItemDiff>()
        for ((l, r) in pairs) {
            when {
                r == null -> diffs += ItemDiff(l!!.name, DiffStatus.ONLY_A, summary = describeColumn(l))
                l == null -> diffs += ItemDiff(r.name, DiffStatus.ONLY_B, summary = describeColumn(r))
                else -> {
                    val changes = mutableListOf<FieldChange>()
                    if (normalizeType(l.typeName) != normalizeType(r.typeName)) {
                        changes += FieldChange(DiffField.TYPE, l.typeName, r.typeName)
                    }
                    if (l.nullable != r.nullable) {
                        changes += FieldChange(DiffField.NULLABLE, nullability(l.nullable), nullability(r.nullable))
                    }
                    if (normalizeDefault(l.defaultValue) != normalizeDefault(r.defaultValue)) {
                        changes += FieldChange(DiffField.DEFAULT, l.defaultValue, r.defaultValue)
                    }
                    if (normalizeExtra(l.extra) != normalizeExtra(r.extra)) {
                        changes += FieldChange(DiffField.EXTRA, l.extra, r.extra)
                    }
                    if (!options.ignoreComments && l.comment.orEmpty() != r.comment.orEmpty()) {
                        changes += FieldChange(DiffField.COMMENT, l.comment, r.comment)
                    }
                    if (compareOrder) {
                        val leftIndex = left.indexOf(l)
                        val rightIndex = right.indexOf(r)
                        if (leftIndex != rightIndex) {
                            changes += FieldChange(DiffField.POSITION, "${leftIndex + 1}", "${rightIndex + 1}")
                        }
                    }
                    if (changes.isNotEmpty()) diffs += ItemDiff(l.name, DiffStatus.CHANGED, changes)
                }
            }
        }
        return diffs
    }

    private fun nullability(nullable: Boolean) = if (nullable) "NULL" else "NOT NULL"

    private fun describeColumn(column: CachedColumn): String = buildString {
        append(column.typeName)
        append(if (column.nullable) " NULL" else " NOT NULL")
        column.defaultValue?.let { append(" DEFAULT ").append(it) }
    }

    /**
     * A column type as two servers can agree on it.
     *
     * MySQL 8.0.19 stopped reporting integer display widths — `int(11)` became `int` — while 5.7
     * and MariaDB still report them, and a dev/production pair on different versions would
     * otherwise differ on every integer column of every table. The width never changed what the
     * column stores, so it is dropped; except for `tinyint(1)`, which 8.0 keeps because it is how
     * a boolean is spelled, and `zerofill`, where the width does change what is shown.
     */
    fun normalizeType(type: String): String {
        val lower = type.trim().lowercase().replace(WHITESPACE, " ")
        if ("zerofill" in lower) return lower
        return INTEGER_WIDTH.replace(lower) { match ->
            val base = match.groupValues[1]
            if (base == "tinyint" && match.groupValues[2] == "1") match.value else base
        }
    }

    /**
     * A column default as two servers can agree on it.
     *
     * MariaDB 10.2.7 started quoting literal defaults (`'abc'`) and spelling "no default" as the
     * word `NULL`, where MySQL says `abc` and SQL NULL; and the two disagree on whether
     * `current_timestamp` takes brackets. None of that is a difference in the schema.
     */
    fun normalizeDefault(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.equals("NULL", ignoreCase = true)) return null
        if (trimmed.length >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return trimmed.substring(1, trimmed.length - 1).replace("''", "'")
        }
        return normalizeTimestampWords(trimmed)
    }

    /**
     * `EXTRA` as two servers can agree on it: MySQL 8 adds `DEFAULT_GENERATED` to every column
     * whose default is an expression, which 5.7 and MariaDB never say — the expression itself is
     * already compared as the default.
     */
    fun normalizeExtra(value: String?): String? {
        val words = value?.trim()?.lowercase() ?: return null
        val cleaned = normalizeTimestampWords(words.replace("default_generated", ""))
            .replace(WHITESPACE, " ").trim()
        return cleaned.ifEmpty { null }
    }

    /**
     * `CURRENT_TIMESTAMP`, `current_timestamp()` and `now()` are one function: MySQL reports the
     * first, MariaDB the second, and a hand-written schema may well use the third.
     */
    private fun normalizeTimestampWords(value: String): String =
        TIMESTAMP_CALL.replace(value) { match ->
            val precision = match.groupValues[1].ifEmpty { match.groupValues[2] }
            "current_timestamp" + if (precision.isEmpty() || precision == "0") "" else "($precision)"
        }

    /**
     * A collation as two servers can agree on it: MySQL 8.0.30 renamed `utf8` to `utf8mb3`
     * without changing what it stores, so `utf8_general_ci` and `utf8mb3_general_ci` are one.
     */
    fun normalizeCollation(value: String): String =
        value.trim().lowercase().replace(Regex("^utf8mb3_"), "utf8_")

    // ------------------------------------------------------------------ indexes

    fun diffIndexes(a: List<CachedIndex>, b: List<CachedIndex>, options: SchemaDiffOptions = SchemaDiffOptions()): List<ItemDiff> {
        val ignoreCase = options.ignoreIdentifierCase
        val pairs = pairLeftovers(
            matchByName(a, b, ignoreCase) { it.name },
        ) { l, r -> l.unique == r.unique && listsEqual(l.columns, r.columns, ignoreCase) }
        return pairs.mapNotNull { (l, r) ->
            when {
                r == null -> ItemDiff(l!!.name, DiffStatus.ONLY_A, summary = describeIndex(l))
                l == null -> ItemDiff(r.name, DiffStatus.ONLY_B, summary = describeIndex(r))
                else -> {
                    val changes = mutableListOf<FieldChange>()
                    if (!identifiersEqual(l.name, r.name, ignoreCase)) changes += FieldChange(DiffField.NAME, l.name, r.name)
                    if (l.unique != r.unique) changes += FieldChange(DiffField.UNIQUE, uniqueness(l.unique), uniqueness(r.unique))
                    if (!listsEqual(l.columns, r.columns, ignoreCase)) {
                        changes += FieldChange(DiffField.COLUMNS, l.columns.joinToString(", "), r.columns.joinToString(", "))
                    }
                    changes.takeIf { it.isNotEmpty() }?.let { ItemDiff(l.name, DiffStatus.CHANGED, it) }
                }
            }
        }
    }

    private fun uniqueness(unique: Boolean) = if (unique) "UNIQUE" else "INDEX"

    private fun describeIndex(index: CachedIndex): String {
        val kind = when {
            index.name == "PRIMARY" -> "PRIMARY KEY"
            index.unique -> "UNIQUE"
            else -> "INDEX"
        }
        return "$kind (${index.columns.joinToString(", ")})"
    }

    // ------------------------------------------------------------------ foreign keys

    /**
     * A foreign key as one unit: the cache keeps a row per column, a reader thinks of one arrow.
     *
     * [referencedDatabase] is null when the key points into its own database. Dev and production
     * almost never share a database name (`shop_dev` against `shop`), so a key that stays inside
     * its schema has to be compared without that name, or every one of them would differ.
     */
    data class GroupedForeignKey(
        val name: String,
        val columns: List<String>,
        val referencedDatabase: String?,
        val referencedTable: String,
        val referencedColumns: List<String>,
    ) {
        val references: String
            get() = buildString {
                referencedDatabase?.let { append(it).append('.') }
                append(referencedTable).append('(').append(referencedColumns.joinToString(", ")).append(')')
            }
    }

    fun groupForeignKeys(keys: List<CachedForeignKey>, ownDatabase: String, ignoreCase: Boolean = false): List<GroupedForeignKey> =
        keys.groupBy { it.constraintName }.map { (name, rows) ->
            // The cache returns a key's columns sorted by name, not in declaration order; pairs are
            // kept together and sorted so two captures of the same key always read the same way.
            val ordered = rows.sortedBy { it.column }
            val first = ordered.first()
            GroupedForeignKey(
                name = name,
                columns = ordered.map { it.column },
                referencedDatabase = first.referencedDatabase.takeUnless {
                    identifiersEqual(it, ownDatabase, ignoreCase)
                },
                referencedTable = first.referencedTable,
                referencedColumns = ordered.map { it.referencedColumn },
            )
        }

    fun diffForeignKeys(
        a: List<GroupedForeignKey>,
        b: List<GroupedForeignKey>,
        options: SchemaDiffOptions = SchemaDiffOptions(),
    ): List<ItemDiff> {
        val ignoreCase = options.ignoreIdentifierCase
        fun sameTarget(l: GroupedForeignKey, r: GroupedForeignKey) =
            sameNullable(l.referencedDatabase, r.referencedDatabase, ignoreCase) &&
                identifiersEqual(l.referencedTable, r.referencedTable, ignoreCase) &&
                listsEqual(l.referencedColumns, r.referencedColumns, ignoreCase)
        // Constraint names are often generated (`orders_ibfk_1`), so the same key can carry a
        // different name on each side; a leftover pair with the same columns and target is one key.
        val pairs = pairLeftovers(matchByName(a, b, ignoreCase) { it.name }) { l, r ->
            listsEqual(l.columns, r.columns, ignoreCase) && sameTarget(l, r)
        }
        return pairs.mapNotNull { (l, r) ->
            when {
                r == null -> ItemDiff(l!!.name, DiffStatus.ONLY_A, summary = describeForeignKey(l))
                l == null -> ItemDiff(r.name, DiffStatus.ONLY_B, summary = describeForeignKey(r))
                else -> {
                    val changes = mutableListOf<FieldChange>()
                    if (!identifiersEqual(l.name, r.name, ignoreCase)) changes += FieldChange(DiffField.NAME, l.name, r.name)
                    if (!listsEqual(l.columns, r.columns, ignoreCase)) {
                        changes += FieldChange(DiffField.COLUMNS, l.columns.joinToString(", "), r.columns.joinToString(", "))
                    }
                    if (!sameTarget(l, r)) changes += FieldChange(DiffField.REFERENCES, l.references, r.references)
                    changes.takeIf { it.isNotEmpty() }?.let { ItemDiff(l.name, DiffStatus.CHANGED, it) }
                }
            }
        }
    }

    private fun describeForeignKey(key: GroupedForeignKey): String =
        "(${key.columns.joinToString(", ")}) → ${key.references}"

    // ------------------------------------------------------------------ views

    /**
     * A MySQL view's `SHOW CREATE VIEW` text, reduced to what the view does.
     *
     * The `DEFINER` is the account that happened to create the view, which is a different account
     * on every server; the view's own schema qualifier (`` `shop_dev`.`orders` ``) differs between
     * dev and production for the same reason a foreign key's does; and whitespace is formatting.
     * Quoted strings are copied untouched, since a space inside a literal is part of the view.
     */
    fun normalizeViewDefinition(ddl: String, ownDatabase: String, ignoreCase: Boolean = false): String {
        val text = stripDefiner(ddl)
        val out = StringBuilder(text.length)
        var previousAtom: Boolean? = null
        var pendingSpace = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace()) {
                pendingSpace = true
                i++
                continue
            }
            val end: Int
            val atom: Boolean
            var replacement: String? = null
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    end = quotedEnd(text, i)
                    atom = true
                    if (c == '`' && nextNonSpace(text, end) == '.') {
                        val identifier = text.substring(i + 1, end - 1).replace("``", "`")
                        if (identifiersEqual(identifier, ownDatabase, ignoreCase)) replacement = SCHEMA_PLACEHOLDER
                    }
                }
                c in VIEW_SYMBOLS -> {
                    end = i + 1
                    atom = false
                }
                else -> {
                    var j = i + 1
                    while (j < text.length && !text[j].isWhitespace() && text[j] !in VIEW_SYMBOLS &&
                        text[j] != '\'' && text[j] != '"' && text[j] != '`'
                    ) j++
                    end = j
                    atom = true
                    if (nextNonSpace(text, end) == '.' && identifiersEqual(text.substring(i, end), ownDatabase, ignoreCase)) {
                        replacement = SCHEMA_PLACEHOLDER
                    }
                }
            }
            // A space only survives between two words, where removing it would merge them.
            if (pendingSpace && previousAtom == true && atom) out.append(' ')
            out.append(replacement ?: text.substring(i, end))
            previousAtom = atom
            pendingSpace = false
            i = end
        }
        return out.toString()
    }

    /** `DEFINER=`user`@`host`` (or `=CURRENT_USER`) out of the header, i.e. before the word VIEW. */
    fun stripDefiner(ddl: String): String {
        val view = VIEW_KEYWORD.find(ddl) ?: return ddl
        val header = ddl.substring(0, view.range.first)
        val stripped = DEFINER.replace(header, "")
        return stripped + ddl.substring(view.range.first)
    }

    private fun quotedEnd(text: String, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            if (text[i] == '\\' && quote != '`') {
                i += 2
                continue
            }
            if (text[i] == quote) {
                // A doubled quote is an escaped quote, not the end.
                if (i + 1 < text.length && text[i + 1] == quote) {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return text.length
    }

    private fun nextNonSpace(text: String, from: Int): Char? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return text.getOrNull(i)
    }

    // ------------------------------------------------------------------ matching

    /**
     * Pairs two lists by name, keeping A's order and then B's leftovers.
     *
     * Exact matches are made first, so `Orders` still pairs with `Orders` when `orders` is also
     * there; only then, if asked, does a case-insensitive name find a partner — and only when the
     * partner is unambiguous, because guessing between `Orders` and `ORDERS` would be inventing a
     * pairing the schema does not have.
     */
    fun <T> matchByName(a: List<T>, b: List<T>, ignoreCase: Boolean, name: (T) -> String): List<Pair<T?, T?>> {
        val partner = arrayOfNulls<Int>(a.size)
        val used = BooleanArray(b.size)
        val exact = HashMap<String, MutableList<Int>>()
        b.forEachIndexed { index, item -> exact.getOrPut(name(item)) { mutableListOf() } += index }
        a.forEachIndexed { index, item ->
            val candidate = exact[name(item)]?.firstOrNull { !used[it] }
            if (candidate != null) {
                partner[index] = candidate
                used[candidate] = true
            }
        }
        if (ignoreCase) {
            a.forEachIndexed { index, item ->
                if (partner[index] != null) return@forEachIndexed
                val candidates = b.indices.filter { !used[it] && name(b[it]).equals(name(item), ignoreCase = true) }
                if (candidates.size == 1) {
                    partner[index] = candidates.single()
                    used[candidates.single()] = true
                }
            }
        }
        return buildList {
            a.forEachIndexed { index, item -> add(item to partner[index]?.let { b[it] }) }
            b.forEachIndexed { index, item -> if (!used[index]) add(null to item) }
        }
    }

    /**
     * Joins a one-sided item from A with a one-sided item from B when [same] says they are the same
     * thing under different names. Each item joins at most once, in A's order.
     */
    private fun <T> pairLeftovers(pairs: List<Pair<T?, T?>>, same: (T, T) -> Boolean): List<Pair<T?, T?>> {
        // Positions rather than values: two identical items on one side are still two items.
        val onlyB = pairs.indices.filter { pairs[it].first == null }.toMutableList()
        val joined = mutableSetOf<Int>()
        val result = mutableListOf<Pair<T?, T?>>()
        pairs.forEachIndexed { index, (l, r) ->
            when {
                l != null && r == null -> {
                    val match = onlyB.firstOrNull { same(l, pairs[it].second!!) }
                    if (match != null) {
                        onlyB.remove(match)
                        joined += match
                        result += l to pairs[match].second
                    } else {
                        result += l to null
                    }
                }
                l == null && index in joined -> Unit
                else -> result += l to r
            }
        }
        return result
    }

    fun identifiersEqual(a: String, b: String, ignoreCase: Boolean): Boolean = a.equals(b, ignoreCase)

    private fun sameNullable(a: String?, b: String?, ignoreCase: Boolean): Boolean = when {
        a == null || b == null -> a == b
        else -> identifiersEqual(a, b, ignoreCase)
    }

    private fun listsEqual(a: List<String>, b: List<String>, ignoreCase: Boolean): Boolean =
        a.size == b.size && a.indices.all { identifiersEqual(a[it], b[it], ignoreCase) }

    private val CachedTable.isView: Boolean get() = kind == TableKind.VIEW.name

    private val WHITESPACE = Regex("\\s+")
    private val INTEGER_WIDTH = Regex("\\b(tinyint|smallint|mediumint|int|integer|bigint)\\s*\\(\\s*(\\d+)\\s*\\)")
    private val TIMESTAMP_CALL = Regex(
        // `now` only with its brackets: bare, it is as likely to be a text default as a function.
        "\\b(?:(?:current_timestamp|localtimestamp|localtime)\\b(?:\\s*\\(\\s*(\\d*)\\s*\\))?|now\\s*\\(\\s*(\\d*)\\s*\\))",
        RegexOption.IGNORE_CASE,
    )
    private val VIEW_KEYWORD = Regex("\\bVIEW\\b", RegexOption.IGNORE_CASE)
    private val PRINCIPAL = "(?:`(?:[^`]|``)*`|'(?:[^'\\\\]|\\\\.|'')*'|\"(?:[^\"\\\\]|\\\\.)*\"|[^\\s@()]+)"
    private val DEFINER = Regex(
        "\\bDEFINER\\s*=\\s*(?:CURRENT_USER(?:\\s*\\(\\s*\\))?|$PRINCIPAL\\s*@\\s*$PRINCIPAL)\\s*",
        RegexOption.IGNORE_CASE,
    )
    private const val SCHEMA_PLACEHOLDER = "`__schema__`"
    private val VIEW_SYMBOLS = setOf(
        '(', ')', '[', ']', '{', '}', ',', '.', ';', '+', '-', '*', '/', '%',
        '<', '>', '=', '!', '|', '&', '^', '~', '?', ':', '@',
    )
}
