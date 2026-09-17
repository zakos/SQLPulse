package hu.laurel.sqlpulse.data.schema

enum class TableKind { TABLE, VIEW }

data class SchemaTable(
    val database: String,
    val name: String,
    val kind: TableKind,
    /** InnoDB's estimate; exact counts are expensive on large tables. */
    val approximateRows: Long?,
    val comment: String?,
)

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
