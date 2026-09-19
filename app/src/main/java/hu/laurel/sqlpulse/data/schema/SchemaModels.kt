package hu.laurel.sqlpulse.data.schema

enum class TableKind { TABLE, VIEW }

/** What the browser is listing. MySQL keeps each of these in its own place. */
enum class ObjectKind { TABLES, VIEWS, ROUTINES, TRIGGERS, EVENTS }

data class SchemaTable(
    val database: String,
    val name: String,
    val kind: TableKind,
    /** InnoDB's estimate; exact counts are expensive on large tables. */
    val approximateRows: Long?,
    val comment: String?,
    /** InnoDB, MyISAM, MEMORY... Null for a view, which has no storage. */
    val engine: String? = null,
    val collation: String? = null,
    /** Rows and indexes on disk, as the server estimates them. */
    val dataBytes: Long? = null,
    val indexBytes: Long? = null,
) {
    val totalBytes: Long? get() = when {
        dataBytes == null && indexBytes == null -> null
        else -> (dataBytes ?: 0) + (indexBytes ?: 0)
    }
}

enum class RoutineKind { PROCEDURE, FUNCTION }

data class SchemaRoutine(
    val name: String,
    val kind: RoutineKind,
    /** The declared return type of a function; null for a procedure. */
    val returns: String?,
    val comment: String?,
)

data class SchemaTrigger(
    val name: String,
    val table: String,
    /** INSERT, UPDATE or DELETE. */
    val event: String,
    /** BEFORE or AFTER. */
    val timing: String,
)

data class SchemaEvent(
    val name: String,
    /** ENABLED, DISABLED or SLAVESIDE_DISABLED. */
    val status: String,
    /** "every 1 DAY" for a recurring event, or the one-off time. */
    val schedule: String?,
)

/**
 * A byte count as a person reads it.
 *
 * Powers of 1024 with the short unit names, which is what every database tool shows and what the
 * numbers in `information_schema` are measured in. One decimal is enough to compare two tables;
 * more would suggest a precision the server's own estimate does not have.
 */
fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = kotlin.math.round(value * 10) / 10
    val text = if (rounded >= 100 || rounded == kotlin.math.floor(rounded)) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
    return "$text ${units[unit]}"
}

data class SchemaColumn(
    val name: String,
    val typeName: String,
    val nullable: Boolean,
    val defaultValue: String?,
    val isPrimaryKey: Boolean,
    val extra: String?,
    val comment: String?,
    /**
     * The column's own collation, which is only interesting where it differs from the table's.
     * Null on a column that holds no text, and on a server too old to have been asked.
     */
    val collation: String? = null,
    /**
     * The expression a generated column is computed from, already unquoted; null for an ordinary
     * column and on any server that does not know about generated columns.
     */
    val generationExpression: String? = null,
) {
    /**
     * VIRTUAL, STORED, or null for an ordinary column.
     *
     * Read off [extra] rather than off [generationExpression], because a server old enough not to
     * have been asked for the expression still announces the column as generated, and a column
     * marked generated with no expression to show is still worth marking.
     */
    val generatedKind: GeneratedKind? get() = SchemaExtras.generatedKind(extra)
}

data class SchemaIndex(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

data class ForeignKey(
    val constraintName: String,
    val column: String,
    val referencedDatabase: String,
    val referencedTable: String,
    val referencedColumn: String,
    /** CASCADE, SET NULL, SET DEFAULT, RESTRICT or NO ACTION, as the server reports it. */
    val onDelete: String? = null,
    val onUpdate: String? = null,
) {
    /**
     * The rules as one line, or null where they are the defaults.
     *
     * Deleting a row under a `CASCADE` key deletes rows in other tables too, which is exactly the
     * kind of thing nobody wants to discover afterwards — §7.6 makes writes visible before they
     * happen, and this is the same idea applied to the schema.
     */
    val ruleSummary: String? get() = SchemaExtras.foreignKeyRules(onDelete, onUpdate)
}

/**
 * A CHECK constraint (MySQL 8.0.16+, MariaDB 10.2+).
 *
 * Older servers parse `CHECK` and then ignore it, so there is nothing to list there and nothing
 * to warn about either: the schema genuinely has none.
 */
data class CheckConstraint(
    val name: String,
    /** The condition, already unwrapped from the server's own quoting. */
    val expression: String?,
    /**
     * False for a constraint declared `NOT ENFORCED`, which MySQL keeps in the schema but never
     * applies. MariaDB has no such state and always reports true.
     */
    val enforced: Boolean = true,
)

/**
 * One partition of a partitioned table.
 *
 * [approximateRows] is per partition, and is what makes the list worth reading: a `RANGE`
 * partitioning whose rows all sit in one partition is doing nothing for the queries it was added
 * for, and that only shows up when the partitions are listed side by side.
 */
data class TablePartition(
    val name: String,
    /** Set only where the partition is itself subdivided. */
    val subName: String? = null,
    /** RANGE, LIST, HASH, KEY, and their COLUMNS and LINEAR variants. */
    val method: String? = null,
    /** The expression or column list the rows are distributed on. */
    val expression: String? = null,
    val approximateRows: Long? = null,
)

data class TableStructure(
    val columns: List<SchemaColumn>,
    val indexes: List<SchemaIndex>,
    val foreignKeys: List<ForeignKey>,
    /** Empty on a server without CHECK constraints, and on a table that declares none. */
    val checks: List<CheckConstraint> = emptyList(),
    /** Empty for an unpartitioned table, which is nearly all of them. */
    val partitions: List<TablePartition> = emptyList(),
    /** The table's default collation, against which a column's own collation is compared. */
    val collation: String? = null,
) {
    /** §7.6 will refuse to edit rows of a table with no primary key; the browser says so too. */
    val primaryKey: List<String> get() = columns.filter { it.isPrimaryKey }.map { it.name }

    val partitioned: Boolean get() = partitions.isNotEmpty()
}

/**
 * Single-quotes a value for a statement that cannot take a parameter — `SHOW GRANTS FOR 'u'@'h'`
 * being the one that matters. A quote and a backslash are escaped; anything else is copied.
 */
fun quoteStringLiteral(value: String): String =
    "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

/** Backtick-quotes an identifier. MySQL escapes a backtick by doubling it. */
fun quoteIdentifier(name: String): String = "`" + name.replace("`", "``") + "`"
