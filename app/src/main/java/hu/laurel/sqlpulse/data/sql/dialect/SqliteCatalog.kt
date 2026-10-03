package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.ForeignKey
import hu.laurel.sqlpulse.data.schema.GraphEdge
import hu.laurel.sqlpulse.data.schema.KeyColumnUsage
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.SchemaIndex
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.schema.SchemaTrigger
import hu.laurel.sqlpulse.data.schema.TableColumns
import hu.laurel.sqlpulse.data.schema.TableKind
import java.sql.Connection
import java.sql.ResultSet

/**
 * SQLite describes itself through `sqlite_schema` and the `pragma_*` table-valued functions.
 *
 * The functions are used rather than `PRAGMA …` statements because they can be joined and bound:
 * one statement over every table instead of one round trip each, and the table name is a
 * parameter instead of text pasted into SQL. Only the database `main` is listed (the picker has
 * one entry), but every statement takes the namespace, so an attached file would work the same.
 *
 * Differences from the information_schema engines, all deliberate:
 *  - Row counts, sizes, comments and collations are not known without scanning, so they are null.
 *  - Foreign keys are unnamed in SQLite; a foreign key's name is made up from its pragma id
 *    (`fk_0`) so its columns still group into one constraint. CHECK constraints are not listed:
 *    there is no pragma for them and parsing the DDL for them would be a guess.
 *  - A foreign key may omit the parent column (`REFERENCES parent`), meaning the parent's primary
 *    key; that is resolved here so the rest of the app always sees a column.
 *  - `INTEGER PRIMARY KEY` is the rowid alias and fills itself in, which is what the row editor
 *    and the CSV import mean by "auto_increment", so the column is marked that way.
 */
object SqliteCatalog : SchemaCatalog {

    const val MAIN = "main"

    override fun namespaces(connection: Connection): List<String> = listOf(MAIN)

    override fun tables(connection: Connection, namespace: String): List<SchemaTable> =
        connection.query(
            """
            SELECT name, type FROM ${schema(namespace)}
            WHERE type IN ('table', 'view')
            ORDER BY name LIKE 'sqlite\_%' ESCAPE '\', name COLLATE NOCASE
            """.trimIndent(),
        ) { rows ->
            SchemaTable(
                database = namespace,
                name = rows.getString("name"),
                kind = if (rows.getString("type") == "view") TableKind.VIEW else TableKind.TABLE,
                approximateRows = null,
                comment = null,
            )
        }

    override fun columns(connection: Connection, namespace: String, table: String): List<SchemaColumn> {
        val raw = connection.query(
            // table_xinfo, not table_info: it also reports generated columns, which table_info
            // leaves out although a SELECT * returns them. hidden = 1 is a virtual table's own
            // plumbing and is not a column the user can see.
            """SELECT name, type, "notnull", dflt_value, pk, hidden FROM pragma_table_xinfo(?, ?) WHERE hidden <> 1 ORDER BY cid""",
            table,
            namespace,
        ) { rows ->
            RawColumn(
                name = rows.getString("name"),
                type = rows.getString("type").orEmpty(),
                notNull = rows.getInt("notnull") != 0,
                default = rows.getString("dflt_value"),
                pk = rows.getInt("pk"),
                hidden = rows.getInt("hidden"),
            )
        }
        val rowidAlias = rowidAlias(connection, namespace, table, raw)
        return raw.map { column ->
            SchemaColumn(
                name = column.name,
                typeName = column.type,
                // A primary key column of a rowid table may hold NULL by an old SQLite quirk, but
                // the app treats keys as required values, so it is not offered as nullable.
                nullable = !column.notNull && column.pk == 0,
                defaultValue = unquoteDefault(column.default),
                isPrimaryKey = column.pk > 0,
                extra = when {
                    column.hidden == GENERATED_VIRTUAL -> "VIRTUAL GENERATED"
                    column.hidden == GENERATED_STORED -> "STORED GENERATED"
                    column.name == rowidAlias -> "auto_increment"
                    else -> null
                },
                comment = null,
            )
        }
    }

    override fun indexes(connection: Connection, namespace: String, table: String): List<SchemaIndex> {
        val primary = primaryKey(connection, namespace, table)
        val result = mutableListOf<SchemaIndex>()
        // SQLite has no index for the primary key of a rowid table, and calls the one it has for a
        // WITHOUT ROWID table `sqlite_autoindex_…`; either way the key is shown the way the other
        // engines show it, first and called PRIMARY.
        if (primary.isNotEmpty()) result += SchemaIndex("PRIMARY", unique = true, columns = primary)
        val listed = connection.query(
            """SELECT name, "unique", origin FROM pragma_index_list(?, ?) ORDER BY seq""",
            table,
            namespace,
        ) { rows -> Triple(rows.getString("name"), rows.getInt("unique") != 0, rows.getString("origin")) }
        for ((name, unique, origin) in listed) {
            if (origin == "pk") continue
            val columns = connection.query(
                "SELECT name FROM pragma_index_info(?, ?) ORDER BY seqno",
                name,
                namespace,
            ) { rows ->
                // An index on an expression has no column to name.
                rows.getString("name") ?: "<expression>"
            }
            result += SchemaIndex(name, unique, columns)
        }
        return result
    }

    override fun foreignKeys(connection: Connection, namespace: String, table: String): List<ForeignKey> {
        val tables = tableNames(connection, namespace)
        val parents = HashMap<String, List<String>>()
        return connection.query(
            """SELECT id, seq, "table", "from", "to", on_update, on_delete FROM pragma_foreign_key_list(?, ?) ORDER BY id, seq""",
            table,
            namespace,
        ) { rows ->
            val parent = tables[rows.getString("table").lowercase()] ?: rows.getString("table")
            ForeignKey(
                constraintName = "fk_${rows.getInt("id")}",
                column = rows.getString("from"),
                referencedDatabase = namespace,
                referencedTable = parent,
                referencedColumn = rows.getString("to")
                    ?: parents.getOrPut(parent) { primaryKey(connection, namespace, parent) }
                        .getOrNull(rows.getInt("seq")).orEmpty(),
                onDelete = rows.getString("on_delete"),
                onUpdate = rows.getString("on_update"),
            )
        }
    }

    override fun referencingKeys(connection: Connection, namespace: String, table: String): List<KeyColumnUsage> {
        val parentKey = primaryKey(connection, namespace, table)
        val tables = tableNames(connection, namespace)
        return connection.query(
            """
            SELECT m.name AS child, f.id, f.seq, f."from", f."to"
            FROM ${schema(namespace)} m, pragma_foreign_key_list(m.name, ?) f
            WHERE m.type = 'table' AND f."table" = ? COLLATE NOCASE
            ORDER BY m.name, f.id, f.seq
            """.trimIndent(),
            namespace,
            table,
        ) { rows ->
            KeyColumnUsage(
                constraintName = "fk_${rows.getInt("id")}",
                childDatabase = namespace,
                childTable = rows.getString("child"),
                childColumn = rows.getString("from"),
                parentDatabase = namespace,
                parentTable = tables[table.lowercase()] ?: table,
                parentColumn = rows.getString("to") ?: parentKey.getOrNull(rows.getInt("seq")).orEmpty(),
            )
        }
    }

    override fun links(connection: Connection, namespace: String): List<GraphEdge> {
        val tables = tableNames(connection, namespace)
        return connection.query(
            """
            SELECT m.name AS child, f.id, f."table" AS parent, f."from"
            FROM ${schema(namespace)} m, pragma_foreign_key_list(m.name, ?) f
            WHERE m.type = 'table'
            ORDER BY m.name, f.id, f.seq
            """.trimIndent(),
            namespace,
        ) { rows ->
            Triple(
                rows.getString("child") to rows.getInt("id"),
                tables[rows.getString("parent").lowercase()] ?: rows.getString("parent"),
                rows.getString("from"),
            )
        }.groupBy { it.first }
            .map { (key, columns) ->
                GraphEdge(from = key.first, to = columns.first().second, columns = columns.map { it.third })
            }
    }

    override fun columnNames(connection: Connection, namespace: String): List<TableColumns> =
        connection.query(
            """
            SELECT m.name AS tbl, c.name AS col, c.pk
            FROM ${schema(namespace)} m, pragma_table_info(m.name, ?) c
            WHERE m.type = 'table' AND m.name NOT LIKE 'sqlite\_%' ESCAPE '\'
            ORDER BY m.name, c.cid
            """.trimIndent(),
            namespace,
        ) { rows -> Triple(rows.getString("tbl"), rows.getString("col"), rows.getInt("pk")) }
            .groupBy { it.first }
            .map { (table, columns) ->
                TableColumns(
                    table = table,
                    columns = columns.map { it.second },
                    primaryKey = columns.filter { it.third > 0 }.sortedBy { it.third }.map { it.second },
                )
            }

    /**
     * The statement SQLite stored when the table was made, which is its own DDL tab, and the
     * statements of the table's explicitly created indexes after it. A table SQLite built itself
     * from a column constraint has no separate index text, and that is as it should be.
     */
    override fun ddl(connection: Connection, namespace: String, table: String): String {
        val statements = connection.query(
            """
            SELECT sql FROM ${schema(namespace)}
            WHERE sql IS NOT NULL AND (
                (type IN ('table', 'view') AND name = ?) OR (type = 'index' AND tbl_name = ?)
            )
            ORDER BY type = 'index', name
            """.trimIndent(),
            table,
            table,
        ) { rows -> rows.getString("sql") }
        return statements.joinToString("\n\n") { it.trimEnd().trimEnd(';') + ";" }
    }

    override fun triggers(connection: Connection, namespace: String): List<SchemaTrigger> =
        connection.query(
            "SELECT name, tbl_name, sql FROM ${schema(namespace)} WHERE type = 'trigger' ORDER BY tbl_name, name",
        ) { rows ->
            val (timing, event) = triggerShape(rows.getString("sql").orEmpty())
            SchemaTrigger(
                name = rows.getString("name"),
                table = rows.getString("tbl_name"),
                event = event,
                timing = timing,
            )
        }

    /**
     * The primary key columns of [table] in key order, which `pk` numbers (1 for the first
     * column of the key, 0 for a column that is not part of it).
     */
    internal fun primaryKey(connection: Connection, namespace: String, table: String): List<String> =
        connection.query(
            "SELECT name FROM pragma_table_info(?, ?) WHERE pk > 0 ORDER BY pk",
            table,
            namespace,
        ) { rows -> rows.getString("name") }

    /** Every table and view by lowercased name, to the name as it is spelled in the schema. */
    private fun tableNames(connection: Connection, namespace: String): Map<String, String> =
        connection.query("SELECT name FROM ${schema(namespace)} WHERE type IN ('table', 'view')") { rows ->
            rows.getString("name")
        }.associateBy { it.lowercase() }

    /**
     * The column that is the table's rowid under another name, or null.
     *
     * That is the single primary key column declared exactly `INTEGER`, in a table that has a
     * rowid at all (WITHOUT ROWID tables do not, and a view never does). `INT` and `BIGINT` keys
     * are ordinary columns, which is a famous SQLite detail worth getting right.
     */
    private fun rowidAlias(connection: Connection, namespace: String, table: String, columns: List<RawColumn>): String? {
        val key = columns.filter { it.pk > 0 }.singleOrNull() ?: return null
        if (!key.type.equals("INTEGER", ignoreCase = true)) return null
        val withoutRowid = connection.query(
            "SELECT wr FROM pragma_table_list WHERE schema = ? AND name = ?",
            namespace,
            table,
        ) { rows -> rows.getInt("wr") != 0 }.firstOrNull() ?: return null
        return key.name.takeUnless { withoutRowid }
    }

    /** `"main".sqlite_schema` — the namespace is a quoted identifier, never text from the user. */
    private fun schema(namespace: String) = "${SqliteDialect.quoteIdentifier(namespace)}.sqlite_schema"

    private class RawColumn(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val default: String?,
        val pk: Int,
        val hidden: Int,
    )

    private const val GENERATED_VIRTUAL = 2
    private const val GENERATED_STORED = 3

    /**
     * SQLite reports a default as the SQL text of the expression: `'abc'`, `0`, `CURRENT_TIMESTAMP`.
     * A plain string literal is unwrapped so it reads like the other engines' defaults; anything
     * else is an expression and is shown as written.
     */
    internal fun unquoteDefault(raw: String?): String? {
        if (raw == null) return null
        if (raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1, raw.length - 1).replace("''", "'")
        }
        return raw
    }

    private val TRIGGER_WHEN = Regex("(?i)\\b(BEFORE|AFTER|INSTEAD\\s+OF)\\s+(INSERT|UPDATE|DELETE)\\b")
    private val TRIGGER_EVENT = Regex("(?i)\\b(INSERT|UPDATE|DELETE)\\b")

    /**
     * Timing and event of a trigger, read off its CREATE text because SQLite keeps nothing else.
     * A trigger written without a timing is BEFORE, as SQLite documents.
     */
    internal fun triggerShape(sql: String): Pair<String, String> {
        TRIGGER_WHEN.find(sql)?.let { match ->
            return match.groupValues[1].uppercase().replace(Regex("\\s+"), " ") to match.groupValues[2].uppercase()
        }
        val event = TRIGGER_EVENT.find(sql)?.value?.uppercase().orEmpty()
        return "BEFORE" to event
    }

    private inline fun <T> Connection.query(
        sql: String,
        vararg parameters: String,
        mapper: (ResultSet) -> T,
    ): List<T> = prepareStatement(sql).use { statement ->
        parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(mapper(rows))
            }
        }
    }
}
