package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.CheckConstraint
import hu.laurel.sqlpulse.data.schema.ForeignKey
import hu.laurel.sqlpulse.data.schema.GraphEdge
import hu.laurel.sqlpulse.data.schema.KeyColumnUsage
import hu.laurel.sqlpulse.data.schema.RoutineKind
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.SchemaExtras
import hu.laurel.sqlpulse.data.schema.SchemaIndex
import hu.laurel.sqlpulse.data.schema.SchemaRoutine
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.schema.SchemaTrigger
import hu.laurel.sqlpulse.data.schema.TableColumns
import hu.laurel.sqlpulse.data.schema.TableKind
import hu.laurel.sqlpulse.data.schema.TablePartition
import java.sql.Connection
import java.sql.ResultSet

/**
 * PostgreSQL's schema, read from `pg_catalog`.
 *
 * `pg_catalog` rather than `information_schema` for nearly everything: the standard views list
 * only what the current role owns or has been granted a right on, hide materialized views and
 * partitions' parents, and cannot say what an index is made of or in which order a composite
 * foreign key's columns go. The catalog also gives `format_type` (the type as it is written in
 * DDL, `character varying(80)`, `integer[]`) and the server's own text for defaults, checks and
 * index definitions. What the user may see is still filtered the way `information_schema` does
 * it (owner, or any privilege).
 *
 * The namespace is a schema of the connected database (docs/tobb-motor-terv.md, 3.1). Requires
 * PostgreSQL 12 (generated columns, `prokind`); every statement is prepared with bound
 * parameters, and the one that interpolates names ([ddl]) quotes them.
 */
object PostgresCatalog : SchemaCatalog {

    /** A table the role can do something with, the way `information_schema.tables` decides it. */
    private const val VISIBLE =
        "(pg_has_role(c.relowner, 'USAGE') OR " +
            "has_table_privilege(c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))"

    override fun namespaces(connection: Connection): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT nspname FROM pg_namespace
                WHERE has_schema_privilege(oid, 'USAGE') AND nspname !~ '^pg_(toast_)?temp_'
                ORDER BY nspname
                """.trimIndent(),
            ).collect { it.getString(1) }
        }

    override fun tables(connection: Connection, namespace: String): List<SchemaTable> =
        connection.prepareStatement(
            """
            SELECT c.relname, c.relkind, c.reltuples, obj_description(c.oid, 'pg_class') AS comment,
                   CASE WHEN c.relkind IN ('r', 'p', 'm') THEN pg_table_size(c.oid) END AS data_bytes,
                   CASE WHEN c.relkind IN ('r', 'p', 'm') THEN pg_indexes_size(c.oid) END AS index_bytes
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'v', 'm', 'f') AND $VISIBLE
            ORDER BY c.relname
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.executeQuery().collect { rows ->
                val kind = rows.getString("relkind")
                // reltuples is -1 until the table has been vacuumed or analyzed once.
                val tuples = rows.getDouble("reltuples").takeIf { it >= 0 }
                SchemaTable(
                    database = namespace,
                    name = rows.getString("relname"),
                    kind = if (kind == "v" || kind == "m") TableKind.VIEW else TableKind.TABLE,
                    approximateRows = tuples?.toLong(),
                    comment = rows.getString("comment")?.takeIf { it.isNotBlank() },
                    dataBytes = rows.getLong("data_bytes").takeUnless { rows.wasNull() },
                    indexBytes = rows.getLong("index_bytes").takeUnless { rows.wasNull() },
                )
            }
        }

    private data class PgColumn(val column: SchemaColumn, val identity: Char)

    private fun pgColumns(connection: Connection, namespace: String, table: String): List<PgColumn> =
        connection.prepareStatement(
            """
            SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS type_name,
                   NOT a.attnotnull AS nullable, pg_get_expr(d.adbin, d.adrelid) AS default_expr,
                   a.attidentity, a.attgenerated,
                   EXISTS (SELECT 1 FROM pg_index i
                           WHERE i.indrelid = c.oid AND i.indisprimary AND a.attnum = ANY (i.indkey)) AS is_pk,
                   col_description(c.oid, a.attnum) AS comment,
                   CASE WHEN a.attcollation <> t.typcollation THEN coll.collname END AS collation
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_type t ON t.oid = a.atttypid
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            LEFT JOIN pg_collation coll ON coll.oid = a.attcollation
            WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().collect { rows ->
                val identity = rows.getString("attidentity").orEmpty().firstOrNull() ?: ' '
                val generated = rows.getString("attgenerated").orEmpty() == "s"
                val expression = rows.getString("default_expr")
                // For a generated column pg_attrdef holds the expression it is computed from, not
                // a default.
                val default = expression.takeUnless { generated }
                PgColumn(
                    SchemaColumn(
                        name = rows.getString("attname"),
                        typeName = rows.getString("type_name"),
                        nullable = rows.getBoolean("nullable"),
                        defaultValue = default,
                        isPrimaryKey = rows.getBoolean("is_pk"),
                        // The words the generic code looks for: "auto_increment" makes a CSV import
                        // leave the column to the server, "STORED GENERATED" marks a computed one.
                        extra = when {
                            generated -> "STORED GENERATED"
                            identity == 'a' -> "identity always, auto_increment"
                            identity == 'd' -> "identity, auto_increment"
                            default?.startsWith("nextval(") == true -> "auto_increment"
                            else -> null
                        },
                        comment = rows.getString("comment")?.takeIf { it.isNotBlank() },
                        collation = rows.getString("collation")?.takeIf { it.isNotBlank() },
                        generationExpression = if (generated) SchemaExtras.generationExpression(expression) else null,
                    ),
                    identity,
                )
            }
        }

    override fun columns(connection: Connection, namespace: String, table: String): List<SchemaColumn> =
        pgColumns(connection, namespace, table).map { it.column }

    /**
     * Every ordinary and partitioned table's columns in one statement. An enum column's type is
     * reported as `enum` (its own name would say nothing to the search); everything else is
     * `format_type`, as in [columns].
     */
    override fun searchColumns(connection: Connection, namespace: String): Map<String, List<SchemaColumn>> =
        connection.prepareStatement(
            """
            SELECT c.relname, a.attname,
                   CASE WHEN t.typtype = 'e' THEN 'enum' ELSE format_type(a.atttypid, a.atttypmod) END AS type_name,
                   EXISTS (SELECT 1 FROM pg_index i
                           WHERE i.indrelid = c.oid AND i.indisprimary AND a.attnum = ANY (i.indkey)) AS is_pk
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_type t ON t.oid = a.atttypid
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p') AND $VISIBLE
              AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY c.relname, a.attnum
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.executeQuery().use { rows ->
                val byTable = linkedMapOf<String, MutableList<SchemaColumn>>()
                while (rows.next()) {
                    byTable.getOrPut(rows.getString(1)) { mutableListOf() }.add(
                        SchemaColumn(
                            name = rows.getString(2),
                            typeName = rows.getString(3),
                            nullable = true,
                            defaultValue = null,
                            isPrimaryKey = rows.getBoolean(4),
                            extra = null,
                            comment = null,
                        ),
                    )
                }
                byTable
            }
        }

    /**
     * The key columns of every index of the table, in key order. An expression index has no column
     * to name, so the server's own text for that position stands in; INCLUDE columns are not part
     * of the key and are left out.
     */
    override fun indexes(connection: Connection, namespace: String, table: String): List<SchemaIndex> =
        connection.prepareStatement(
            """
            SELECT ic.relname AS index_name, i.indisunique,
                   CASE WHEN k.attnum = 0 THEN pg_get_indexdef(i.indexrelid, k.ord::int, true)
                        ELSE a.attname END AS column_name
            FROM pg_index i
            JOIN pg_class ic ON ic.oid = i.indexrelid
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
            LEFT JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
            WHERE n.nspname = ? AND t.relname = ? AND k.ord <= i.indnkeyatts
            ORDER BY ic.relname, k.ord
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().use { rows ->
                val grouped = LinkedHashMap<String, Pair<Boolean, MutableList<String>>>()
                while (rows.next()) {
                    grouped.getOrPut(rows.getString("index_name")) { rows.getBoolean("indisunique") to mutableListOf() }
                        .second += rows.getString("column_name")
                }
                grouped.map { (name, value) -> SchemaIndex(name, value.first, value.second) }
            }
        }

    /**
     * The columns of one constraint are paired by position (`unnest` of both arrays together), so
     * a composite key keeps its columns in step; the rules are decoded from their one-letter codes.
     */
    private const val FOREIGN_KEY_COLUMNS = """
        FROM pg_constraint con
        JOIN pg_class t ON t.oid = con.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        JOIN pg_class rt ON rt.oid = con.confrelid
        JOIN pg_namespace rn ON rn.oid = rt.relnamespace
        CROSS JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY AS k(attnum, refnum, ord)
        JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum
        JOIN pg_attribute ra ON ra.attrelid = con.confrelid AND ra.attnum = k.refnum
        WHERE con.contype = 'f'
    """

    override fun foreignKeys(connection: Connection, namespace: String, table: String): List<ForeignKey> =
        connection.prepareStatement(
            """
            SELECT con.conname, a.attname, rn.nspname AS ref_schema, rt.relname AS ref_table,
                   ra.attname AS ref_column,
                   CASE con.confdeltype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT'
                        WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' END AS on_delete,
                   CASE con.confupdtype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT'
                        WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' END AS on_update
            $FOREIGN_KEY_COLUMNS AND n.nspname = ? AND t.relname = ?
            ORDER BY con.conname, k.ord
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().collect { rows ->
                ForeignKey(
                    constraintName = rows.getString("conname"),
                    column = rows.getString("attname"),
                    referencedDatabase = rows.getString("ref_schema"),
                    referencedTable = rows.getString("ref_table"),
                    referencedColumn = rows.getString("ref_column"),
                    onDelete = rows.getString("on_delete"),
                    onUpdate = rows.getString("on_update"),
                )
            }
        }

    override fun referencingKeys(connection: Connection, namespace: String, table: String): List<KeyColumnUsage> =
        connection.prepareStatement(
            """
            SELECT con.conname, n.nspname AS child_schema, t.relname AS child_table,
                   a.attname AS child_column, rn.nspname AS parent_schema, rt.relname AS parent_table,
                   ra.attname AS parent_column
            $FOREIGN_KEY_COLUMNS AND rn.nspname = ? AND rt.relname = ?
            ORDER BY t.relname, con.conname, k.ord
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().collect { rows ->
                KeyColumnUsage(
                    constraintName = rows.getString("conname"),
                    childDatabase = rows.getString("child_schema"),
                    childTable = rows.getString("child_table"),
                    childColumn = rows.getString("child_column"),
                    parentDatabase = rows.getString("parent_schema"),
                    parentTable = rows.getString("parent_table"),
                    parentColumn = rows.getString("parent_column"),
                )
            }
        }

    /** One statement for the whole schema; the columns of a constraint become one edge. */
    override fun links(connection: Connection, namespace: String): List<GraphEdge> =
        connection.prepareStatement(
            """
            SELECT t.relname AS child_table, con.conname, rt.relname AS parent_table, a.attname AS child_column
            $FOREIGN_KEY_COLUMNS AND n.nspname = ? AND rn.nspname = n.nspname
            ORDER BY t.relname, con.conname, k.ord
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.executeQuery().collect { rows ->
                Triple(
                    rows.getString("child_table") to rows.getString("conname"),
                    rows.getString("parent_table"),
                    rows.getString("child_column"),
                )
            }
        }.groupBy { it.first }
            .map { (key, columns) ->
                GraphEdge(from = key.first, to = columns.first().second, columns = columns.map { it.third })
            }

    override fun columnNames(connection: Connection, namespace: String): List<TableColumns> =
        connection.prepareStatement(
            """
            SELECT c.relname, a.attname,
                   EXISTS (SELECT 1 FROM pg_index i
                           WHERE i.indrelid = c.oid AND i.indisprimary AND a.attnum = ANY (i.indkey)) AS is_pk
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'v', 'm', 'f') AND $VISIBLE
              AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY c.relname, a.attnum
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.executeQuery().collect { rows ->
                Triple(rows.getString("relname"), rows.getString("attname"), rows.getBoolean("is_pk"))
            }
        }.groupBy { it.first }
            .map { (table, columns) ->
                TableColumns(
                    table = table,
                    columns = columns.map { it.second },
                    primaryKey = columns.filter { it.third }.map { it.second },
                )
            }

    /**
     * PostgreSQL has no `SHOW CREATE TABLE`, so the statement is assembled: the columns (with
     * identity, generation, default and collation), then every constraint and index as the server
     * itself prints it (`pg_get_constraintdef`, `pg_get_indexdef`). A view is its query.
     */
    override fun ddl(connection: Connection, namespace: String, table: String): String {
        val kind = connection.prepareStatement(
            """
            SELECT c.relkind, CASE WHEN c.relkind IN ('v', 'm') THEN pg_get_viewdef(c.oid, true) END
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) to rows.getString(2) else null
            }
        } ?: return ""

        val name = PostgresDialect.qualify(namespace, table)
        if (kind.first == "v" || kind.first == "m") {
            val keyword = if (kind.first == "m") "MATERIALIZED VIEW" else "VIEW"
            return "CREATE $keyword $name AS\n${kind.second?.trim().orEmpty()}"
        }

        val lines = pgColumns(connection, namespace, table).map { (column, identity) ->
            buildString {
                append("    ").append(PostgresDialect.quoteIdentifier(column.name)).append(' ').append(column.typeName)
                column.collation?.let { append(" COLLATE ").append(PostgresDialect.quoteIdentifier(it)) }
                when {
                    column.generationExpression != null ->
                        append(" GENERATED ALWAYS AS (").append(column.generationExpression).append(") STORED")
                    identity == 'a' -> append(" GENERATED ALWAYS AS IDENTITY")
                    identity == 'd' -> append(" GENERATED BY DEFAULT AS IDENTITY")
                    column.defaultValue != null -> append(" DEFAULT ").append(column.defaultValue)
                }
                if (!column.nullable) append(" NOT NULL")
            }
        }.toMutableList()
        constraintDefinitions(connection, namespace, table).forEach { (constraint, definition) ->
            lines += "    CONSTRAINT ${PostgresDialect.quoteIdentifier(constraint)} $definition"
        }
        val statements = mutableListOf("CREATE TABLE $name (\n${lines.joinToString(",\n")}\n)")
        statements += standaloneIndexes(connection, namespace, table)
        return statements.joinToString(";\n\n", postfix = ";")
    }

    private const val TABLE_OID =
        "(SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
            "WHERE n.nspname = ? AND c.relname = ?)"

    private fun constraintDefinitions(connection: Connection, namespace: String, table: String): List<Pair<String, String>> =
        connection.prepareStatement(
            """
            SELECT conname, pg_get_constraintdef(oid, true)
            FROM pg_constraint
            WHERE conrelid = $TABLE_OID AND contype IN ('p', 'u', 'f', 'c', 'x')
            ORDER BY CASE contype WHEN 'p' THEN 0 WHEN 'u' THEN 1 WHEN 'f' THEN 3 ELSE 2 END, conname
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().collect { it.getString(1) to it.getString(2) }
        }

    /** The indexes no constraint already stands for. */
    private fun standaloneIndexes(connection: Connection, namespace: String, table: String): List<String> =
        connection.prepareStatement(
            """
            SELECT pg_get_indexdef(i.indexrelid)
            FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid
            WHERE i.indrelid = $TABLE_OID
              AND NOT EXISTS (SELECT 1 FROM pg_constraint k
                              WHERE k.conindid = i.indexrelid AND k.contype IN ('p', 'u', 'x')
                                AND k.conrelid = i.indrelid)
            ORDER BY ic.relname
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().collect { it.getString(1) }
        }

    override fun checkConstraints(connection: Connection, namespace: String, table: String): List<CheckConstraint> =
        connection.prepareStatement(
            """
            SELECT con.conname, pg_get_constraintdef(con.oid, true) AS definition
            FROM pg_constraint con
            JOIN pg_class t ON t.oid = con.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE con.contype = 'c' AND n.nspname = ? AND t.relname = ?
            ORDER BY con.conname
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().collect { rows ->
                val definition = rows.getString("definition").orEmpty()
                    .removeSuffix(" NOT VALID").removeSuffix(" NO INHERIT")
                CheckConstraint(
                    name = rows.getString("conname"),
                    expression = SchemaExtras.checkExpression(definition.removePrefix("CHECK ")),
                )
            }
        }

    /** The partitions of a partitioned table, each with the bounds it holds; nothing otherwise. */
    override fun tableExtras(connection: Connection, namespace: String, table: String): TableExtras =
        connection.prepareStatement(
            """
            SELECT c.relname, pg_get_expr(c.relpartbound, c.oid) AS bound, c.reltuples,
                   CASE pt.partstrat WHEN 'r' THEN 'RANGE' WHEN 'l' THEN 'LIST' WHEN 'h' THEN 'HASH' END AS method
            FROM pg_inherits i
            JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_partitioned_table pt ON pt.partrelid = i.inhparent
            WHERE i.inhparent = $TABLE_OID
            ORDER BY c.relname
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            TableExtras(
                collation = null,
                partitions = statement.executeQuery().collect { rows ->
                    TablePartition(
                        name = rows.getString("relname"),
                        method = rows.getString("method"),
                        expression = rows.getString("bound"),
                        approximateRows = rows.getDouble("reltuples").takeIf { it >= 0 }?.toLong(),
                    )
                },
            )
        }

    /** Functions and procedures, shown with their argument types because PostgreSQL overloads names. */
    override fun routines(connection: Connection, namespace: String): List<SchemaRoutine> =
        connection.prepareStatement(
            """
            SELECT p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')' AS signature,
                   p.prokind, pg_get_function_result(p.oid) AS returns, obj_description(p.oid, 'pg_proc') AS comment
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ? AND p.prokind IN ('f', 'p')
              AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = p.oid AND d.deptype = 'e')
            ORDER BY p.prokind DESC, p.proname, signature
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.executeQuery().collect { rows ->
                val function = rows.getString("prokind") == "f"
                SchemaRoutine(
                    name = rows.getString("signature"),
                    kind = if (function) RoutineKind.FUNCTION else RoutineKind.PROCEDURE,
                    returns = rows.getString("returns")?.takeIf { function },
                    comment = rows.getString("comment")?.takeIf { it.isNotBlank() },
                )
            }
        }

    override fun routineDdl(connection: Connection, namespace: String, routine: SchemaRoutine): String =
        connection.prepareStatement(
            """
            SELECT pg_get_functiondef(p.oid)
            FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ? AND p.prokind IN ('f', 'p')
              AND p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')' = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, routine.name)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1).orEmpty() else "" }
        }

    /** The trigger's event and timing are bits of `tgtype`: 2 BEFORE, 4 INSERT, 8 DELETE, 16 UPDATE, 32 TRUNCATE, 64 INSTEAD OF. */
    override fun triggers(connection: Connection, namespace: String): List<SchemaTrigger> =
        connection.prepareStatement(
            """
            SELECT tg.tgname, t.relname, tg.tgtype
            FROM pg_trigger tg
            JOIN pg_class t ON t.oid = tg.tgrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE NOT tg.tgisinternal AND n.nspname = ?
            ORDER BY t.relname, tg.tgname
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.executeQuery().collect { rows ->
                val type = rows.getInt("tgtype")
                SchemaTrigger(
                    name = rows.getString("tgname"),
                    table = rows.getString("relname"),
                    event = listOf(4 to "INSERT", 8 to "DELETE", 16 to "UPDATE", 32 to "TRUNCATE")
                        .filter { (bit, _) -> type and bit != 0 }
                        .joinToString(" OR ") { it.second },
                    timing = when {
                        type and 64 != 0 -> "INSTEAD OF"
                        type and 2 != 0 -> "BEFORE"
                        else -> "AFTER"
                    },
                )
            }
        }

    /** The server's own schemas: listed last in the picker rather than hidden. */
    val SYSTEM_SCHEMAS = setOf("pg_catalog", "information_schema", "pg_toast")

    private inline fun <T> ResultSet.collect(mapper: (ResultSet) -> T): List<T> = use { rows ->
        buildList {
            while (rows.next()) add(mapper(rows))
        }
    }
}
