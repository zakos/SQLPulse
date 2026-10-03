package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.CheckConstraint
import hu.laurel.sqlpulse.data.schema.ForeignKey
import hu.laurel.sqlpulse.data.schema.GraphEdge
import hu.laurel.sqlpulse.data.schema.KeyColumnUsage
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.SchemaIndex
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.schema.TableColumns
import hu.laurel.sqlpulse.data.sql.JdbcConfig
import hu.laurel.sqlpulse.data.sql.NotEditableReason
import hu.laurel.sqlpulse.data.sql.ResultEditability
import hu.laurel.sqlpulse.data.sql.SqlFailure
import hu.laurel.sqlpulse.data.sql.SqlFailures
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.data.sql.StatementKind
import hu.laurel.sqlpulse.data.sql.WritePreviewQuery
import java.sql.Connection
import java.sql.SQLException

/**
 * The starting point of an engine that is known but not implemented: not [connectable], no
 * [features], and every engine-specific member refuses with [EngineNotSupportedException].
 *
 * The lexical members default to standard SQL ([SqlGrammar.ANSI], `"quoted"` names, `''`
 * literals), because those are right more often than not and are what the shared scanners need to
 * be safe at all. Everything that talks to a server or decides what a statement does throws, so
 * an unfinished engine fails loudly instead of quietly running MySQL's answer.
 *
 * An engine is finished when its dialect overrides every throwing member, sets [connectable] and
 * lists its [features]; docs/tobb-motor-terv.md has the checklist.
 */
abstract class UnsupportedDialect(final override val engine: DatabaseEngine) : SqlDialect {

    override val connectable: Boolean = false
    override val features: Set<EngineFeature> = emptySet()
    override val grammar: SqlGrammar = SqlGrammar.ANSI

    override fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    override fun stringLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    override fun nullSafeEquals(quotedColumn: String): String = "$quotedColumn IS NOT DISTINCT FROM ?"

    override val likeEscape: String = " ESCAPE '\\'"

    override fun limit(select: String, limit: Int, offset: Int?, ordered: Boolean): String =
        unsupported("paging")

    override fun blobLengthAndHead(quotedColumn: String, maxBytes: Int): String =
        unsupported("BLOB preview")

    override fun classify(sql: String): StatementKind = SqlGuards.classify(sql, grammar)

    override fun applyDefaultLimit(sql: String, limit: Int): SqlGuards.LimitResult =
        unsupported("the default row limit")

    override fun isUnguardedWrite(sql: String): Boolean = SqlGuards.isUnguardedWrite(sql, grammar)

    override fun namespaceSwitch(sql: String): String? = null

    override fun writeCountQuery(sql: String): String? = null

    override fun writePreviewQuery(sql: String, limit: Int): WritePreviewQuery? = null

    override fun resultEditability(sql: String): ResultEditability =
        ResultEditability.NotEditable(NotEditableReason.UNSUPPORTED)

    override fun connector(config: JdbcConfig): EngineConnector = unsupported("connecting")

    override fun useNamespace(connection: Connection, namespace: String): Unit =
        unsupported("switching the namespace")

    /** Every engine but MySQL connects to a database and lands in one of its schemas. */
    override fun initialNamespace(connection: Connection, configured: String): String? =
        currentNamespace(connection)

    override val catalog: SchemaCatalog = UnsupportedCatalog(engine)

    override val systemNamespaces: Set<String> = emptySet()

    /** SQLSTATE and message text still say something; only MySQL's error numbers are MySQL's. */
    override fun failureOf(error: SQLException): SqlFailure = SqlFailures.of(error)

    protected fun unsupported(what: String): Nothing = throw EngineNotSupportedException(engine, what)
}

/** A catalog that refuses every read, for an engine whose catalog has not been written. */
class UnsupportedCatalog(private val engine: DatabaseEngine) : SchemaCatalog {
    private fun no(): Nothing = throw EngineNotSupportedException(engine, "reading the schema")

    override fun namespaces(connection: Connection): List<String> = no()
    override fun tables(connection: Connection, namespace: String): List<SchemaTable> = no()
    override fun columns(connection: Connection, namespace: String, table: String): List<SchemaColumn> = no()
    override fun indexes(connection: Connection, namespace: String, table: String): List<SchemaIndex> = no()
    override fun foreignKeys(connection: Connection, namespace: String, table: String): List<ForeignKey> = no()
    override fun referencingKeys(connection: Connection, namespace: String, table: String): List<KeyColumnUsage> = no()
    override fun links(connection: Connection, namespace: String): List<GraphEdge> = no()
    override fun columnNames(connection: Connection, namespace: String): List<TableColumns> = no()
    override fun ddl(connection: Connection, namespace: String, table: String): String = no()
    override fun checkConstraints(connection: Connection, namespace: String, table: String): List<CheckConstraint> = no()
}
