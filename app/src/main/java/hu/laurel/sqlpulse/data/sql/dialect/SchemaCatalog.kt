package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.CheckConstraint
import hu.laurel.sqlpulse.data.schema.ForeignKey
import hu.laurel.sqlpulse.data.schema.GraphEdge
import hu.laurel.sqlpulse.data.schema.KeyColumnUsage
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.SchemaEvent
import hu.laurel.sqlpulse.data.schema.SchemaIndex
import hu.laurel.sqlpulse.data.schema.SchemaRoutine
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.schema.SchemaTrigger
import hu.laurel.sqlpulse.data.schema.TableColumns
import hu.laurel.sqlpulse.data.schema.TablePartition
import java.sql.Connection

/**
 * How one engine describes its own schema: the statements behind the schema browser, a table's
 * Structure tab, the map and the foreign-key walk.
 *
 * Every function runs on a connection it is handed (SchemaRepository borrows it from the pool and
 * keeps the caching), blocks, and throws the driver's SQLException as it comes. Results use the
 * app's engine-neutral models, so the screens do not change per engine.
 *
 * "Namespace" is what the app's database picker lists: a MySQL database, a PostgreSQL or SQL
 * Server schema inside the connection's database, SQLite's `main` (and attached files). See
 * docs/tobb-motor-terv.md for why the picker is not two-level.
 *
 * The optional parts have empty defaults, so an engine that has no events, no partitions or no
 * CHECK constraints simply does not override them — and the screen shows an empty section rather
 * than an error.
 */
interface SchemaCatalog {

    /** Every namespace the user can see, in any order; the repository sorts system ones last. */
    fun namespaces(connection: Connection): List<String>

    fun tables(connection: Connection, namespace: String): List<SchemaTable>

    fun columns(connection: Connection, namespace: String, table: String): List<SchemaColumn>

    fun indexes(connection: Connection, namespace: String, table: String): List<SchemaIndex>

    /** The table's own foreign keys, one row per column, with their ON DELETE/UPDATE rules. */
    fun foreignKeys(connection: Connection, namespace: String, table: String): List<ForeignKey>

    /** Other tables' foreign keys pointing at [table], in constraint column order. */
    fun referencingKeys(connection: Connection, namespace: String, table: String): List<KeyColumnUsage>

    /** Every foreign key inside [namespace], one edge per constraint, for the map. */
    fun links(connection: Connection, namespace: String): List<GraphEdge>

    /** Every table's column names and primary key, for guessing links where none are declared. */
    fun columnNames(connection: Connection, namespace: String): List<TableColumns>

    /** The table's CREATE statement for the DDL tab, or "" when the engine cannot produce one. */
    fun ddl(connection: Connection, namespace: String, table: String): String

    fun checkConstraints(connection: Connection, namespace: String, table: String): List<CheckConstraint> =
        emptyList()

    /** The table's collation and partitions, where the engine has such things. */
    fun tableExtras(connection: Connection, namespace: String, table: String): TableExtras =
        TableExtras(collation = null, partitions = emptyList())

    fun routines(connection: Connection, namespace: String): List<SchemaRoutine> = emptyList()

    fun routineDdl(connection: Connection, namespace: String, routine: SchemaRoutine): String = ""

    fun triggers(connection: Connection, namespace: String): List<SchemaTrigger> = emptyList()

    fun events(connection: Connection, namespace: String): List<SchemaEvent> = emptyList()
}

/** What one statement brings back about the table itself: its collation and its partitions. */
data class TableExtras(
    val collation: String?,
    val partitions: List<TablePartition>,
)
