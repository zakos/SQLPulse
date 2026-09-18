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
)

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
)

data class TableStructure(
    val columns: List<SchemaColumn>,
    val indexes: List<SchemaIndex>,
    val foreignKeys: List<ForeignKey>,
) {
    /** §7.6 will refuse to edit rows of a table with no primary key; the browser says so too. */
    val primaryKey: List<String> get() = columns.filter { it.isPrimaryKey }.map { it.name }
}

/** Backtick-quotes an identifier. MySQL escapes a backtick by doubling it. */
fun quoteIdentifier(name: String): String = "`" + name.replace("`", "``") + "`"
