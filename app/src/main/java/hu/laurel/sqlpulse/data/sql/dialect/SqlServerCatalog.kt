package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.CheckConstraint
import hu.laurel.sqlpulse.data.schema.ForeignKey
import hu.laurel.sqlpulse.data.schema.GraphEdge
import hu.laurel.sqlpulse.data.schema.KeyColumnUsage
import hu.laurel.sqlpulse.data.schema.RoutineKind
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.SchemaIndex
import hu.laurel.sqlpulse.data.schema.SchemaRoutine
import hu.laurel.sqlpulse.data.schema.SchemaTable
import hu.laurel.sqlpulse.data.schema.SchemaTrigger
import hu.laurel.sqlpulse.data.schema.TableColumns
import hu.laurel.sqlpulse.data.schema.TableKind
import java.sql.Connection
import java.sql.ResultSet

/**
 * SQL Server reads its own schema through the `sys.*` catalog views of the current database.
 *
 * `sys.*` rather than INFORMATION_SCHEMA because the latter has no identity or computed columns,
 * no included-column or index information, and no extended properties (the table comments). The
 * namespace is a *schema*; the database is the one the connection was opened on. Every statement
 * is prepared with bound parameters, and `sys.*` shows a user only what they have rights on, so
 * an empty list can mean "not visible" as well as "none" — as it does for MySQL's routines.
 */
object SqlServerCatalog : SchemaCatalog {

    /** The server's own schemas: listed last in the picker rather than hidden. */
    val SYSTEM_SCHEMAS = setOf("sys", "INFORMATION_SCHEMA", "guest")

    /**
     * Schemas of the current database, without the fixed database-role schemas (`db_owner` and
     * friends, ids 16384–16399), which are never a place tables live and would only be noise.
     */
    override fun namespaces(connection: Connection): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name FROM sys.schemas WHERE schema_id NOT BETWEEN 16384 AND 16399 ORDER BY name",
            ).collect { it.getString(1) }
        }

    /**
     * Tables and views of one schema, with the row estimate and sizes from the allocation
     * metadata (what `sp_spaceused` reads): cheap, and an estimate for the same reason InnoDB's
     * is. A table comment is the `MS_Description` extended property, which is what SSMS writes.
     */
    override fun tables(connection: Connection, namespace: String): List<SchemaTable> =
        connection.prepareStatement(
            """
            SELECT o.name AS name, o.type AS kind,
                   CASE WHEN o.type = 'U' THEN
                       (SELECT SUM(p.rows) FROM sys.partitions p
                         WHERE p.object_id = o.object_id AND p.index_id IN (0, 1)) END AS row_count,
                   CASE WHEN o.type = 'U' THEN
                       (SELECT CAST(SUM(au.used_pages) AS bigint) * 8192
                          FROM sys.partitions p JOIN sys.allocation_units au ON au.container_id = p.partition_id
                         WHERE p.object_id = o.object_id AND p.index_id IN (0, 1)) END AS data_bytes,
                   CASE WHEN o.type = 'U' THEN
                       (SELECT CAST(SUM(au.used_pages) AS bigint) * 8192
                          FROM sys.partitions p JOIN sys.allocation_units au ON au.container_id = p.partition_id
                         WHERE p.object_id = o.object_id AND p.index_id > 1) END AS index_bytes,
                   CAST(ep.value AS nvarchar(4000)) AS comment
            FROM sys.objects o
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            LEFT JOIN sys.extended_properties ep
                   ON ep.class = 1 AND ep.major_id = o.object_id AND ep.minor_id = 0 AND ep.name = N'MS_Description'
            WHERE s.name = ? AND o.type IN ('U', 'V') AND o.is_ms_shipped = 0
            ORDER BY o.name
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.executeQuery().collect { rows ->
                SchemaTable(
                    database = namespace,
                    name = rows.getString("name"),
                    kind = if (rows.getString("kind").trim() == "V") TableKind.VIEW else TableKind.TABLE,
                    approximateRows = rows.getLong("row_count").takeUnless { rows.wasNull() },
                    comment = rows.getString("comment")?.takeIf { it.isNotBlank() },
                    dataBytes = rows.getLong("data_bytes").takeUnless { rows.wasNull() },
                    indexBytes = rows.getLong("index_bytes").takeUnless { rows.wasNull() },
                )
            }
        }

    /**
     * A view's or routine's own text from `sys.sql_modules`; "" for a table, which has no
     * `SHOW CREATE TABLE` equivalent (the DDL tab is not offered for SQL Server tables).
     */
    override fun ddl(connection: Connection, namespace: String, table: String): String =
        definition(connection, namespace, table)

    private fun definition(connection: Connection, namespace: String, name: String): String =
        connection.prepareStatement(
            """
            SELECT m.definition
            FROM sys.sql_modules m
            JOIN sys.objects o ON o.object_id = m.object_id
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            WHERE s.name = ? AND o.name = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, name)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1).orEmpty() else "" }
        }

    /**
     * Columns with their type spelled the way T-SQL declares it (`nvarchar(50)`, `decimal(10,2)`),
     * identity and computed columns marked in `extra` in the words the shared code already reads:
     * an identity column says `auto_increment` (CSV import leaves such columns out), a computed or
     * `rowversion` column says `VIRTUAL GENERATED` / `STORED GENERATED` (it can never be written).
     */
    override fun columns(connection: Connection, namespace: String, table: String): List<SchemaColumn> =
        connection.prepareStatement(
            """
            SELECT c.name AS name, t.name AS type_name, c.max_length, c.precision, c.scale,
                   c.is_nullable, c.is_identity, dc.definition AS default_definition,
                   cc.definition AS computed_definition, cc.is_persisted,
                   c.collation_name,
                   CAST(ep.value AS nvarchar(4000)) AS comment,
                   CASE WHEN EXISTS (
                        SELECT 1 FROM sys.indexes i
                        JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                        WHERE i.object_id = c.object_id AND i.is_primary_key = 1 AND ic.column_id = c.column_id
                   ) THEN 1 ELSE 0 END AS is_pk
            FROM sys.columns c
            JOIN sys.objects o ON o.object_id = c.object_id
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            JOIN sys.types t ON t.user_type_id = c.user_type_id
            LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id
            LEFT JOIN sys.computed_columns cc ON cc.object_id = c.object_id AND cc.column_id = c.column_id
            LEFT JOIN sys.extended_properties ep
                   ON ep.class = 1 AND ep.major_id = c.object_id AND ep.minor_id = c.column_id AND ep.name = N'MS_Description'
            WHERE s.name = ? AND o.name = ? AND o.type IN ('U', 'V')
            ORDER BY c.column_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().collect { rows ->
                val typeName = rows.getString("type_name")
                val computed = rows.getString("computed_definition")
                val extra = when {
                    computed != null ->
                        if (rows.getBoolean("is_persisted")) "STORED GENERATED" else "VIRTUAL GENERATED"
                    typeName.equals("timestamp", ignoreCase = true) -> "STORED GENERATED"
                    rows.getBoolean("is_identity") -> "IDENTITY auto_increment"
                    else -> null
                }
                SchemaColumn(
                    name = rows.getString("name"),
                    typeName = SqlServerTypes.declared(
                        typeName,
                        rows.getInt("max_length"),
                        rows.getInt("precision"),
                        rows.getInt("scale"),
                    ),
                    nullable = rows.getBoolean("is_nullable"),
                    defaultValue = rows.getString("default_definition")?.let(SqlServerTypes::unwrap),
                    isPrimaryKey = rows.getInt("is_pk") == 1,
                    extra = extra,
                    comment = rows.getString("comment")?.takeIf { it.isNotBlank() },
                    collation = rows.getString("collation_name")?.takeIf { it.isNotBlank() },
                    generationExpression = computed?.let(SqlServerTypes::unwrap),
                )
            }
        }

    /**
     * Key columns of every index, in key order. Included columns are not part of the key and are
     * left out; a heap (no clustered index) and a columnstore index have no key columns and so
     * no row here.
     */
    override fun indexes(connection: Connection, namespace: String, table: String): List<SchemaIndex> =
        connection.prepareStatement(
            """
            SELECT i.name AS index_name, i.is_unique, c.name AS column_name
            FROM sys.indexes i
            JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
            JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
            JOIN sys.objects o ON o.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            WHERE s.name = ? AND o.name = ? AND i.name IS NOT NULL
              AND ic.is_included_column = 0 AND ic.key_ordinal > 0
            ORDER BY i.index_id, ic.key_ordinal
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().use { rows ->
                val grouped = LinkedHashMap<String, Pair<Boolean, MutableList<String>>>()
                while (rows.next()) {
                    grouped.getOrPut(rows.getString("index_name")) { rows.getBoolean("is_unique") to mutableListOf() }
                        .second += rows.getString("column_name")
                }
                grouped.map { (name, value) -> SchemaIndex(name, value.first, value.second) }
            }
        }

    private const val FOREIGN_KEY_COLUMNS = """
        FROM sys.foreign_keys fk
        JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
        JOIN sys.tables pt ON pt.object_id = fk.parent_object_id
        JOIN sys.schemas ps ON ps.schema_id = pt.schema_id
        JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id
        JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id
        JOIN sys.schemas rs ON rs.schema_id = rt.schema_id
        JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
    """

    override fun foreignKeys(connection: Connection, namespace: String, table: String): List<ForeignKey> =
        connection.prepareStatement(
            """
            SELECT fk.name AS constraint_name, pc.name AS column_name, rs.name AS ref_schema,
                   rt.name AS ref_table, rc.name AS ref_column,
                   fk.delete_referential_action_desc AS delete_rule,
                   fk.update_referential_action_desc AS update_rule
            $FOREIGN_KEY_COLUMNS
            WHERE ps.name = ? AND pt.name = ?
            ORDER BY fk.name, fkc.constraint_column_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().collect { rows ->
                ForeignKey(
                    constraintName = rows.getString("constraint_name"),
                    column = rows.getString("column_name"),
                    referencedDatabase = rows.getString("ref_schema"),
                    referencedTable = rows.getString("ref_table"),
                    referencedColumn = rows.getString("ref_column"),
                    // NO_ACTION, CASCADE, SET_NULL, SET_DEFAULT → the words the shared code reads.
                    onDelete = rows.getString("delete_rule")?.replace('_', ' '),
                    onUpdate = rows.getString("update_rule")?.replace('_', ' '),
                )
            }
        }

    override fun referencingKeys(connection: Connection, namespace: String, table: String): List<KeyColumnUsage> =
        connection.prepareStatement(
            """
            SELECT fk.name AS constraint_name, ps.name AS child_schema, pt.name AS child_table,
                   pc.name AS child_column, rs.name AS parent_schema, rt.name AS parent_table,
                   rc.name AS parent_column
            $FOREIGN_KEY_COLUMNS
            WHERE rs.name = ? AND rt.name = ?
            ORDER BY pt.name, fk.name, fkc.constraint_column_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().collect { rows ->
                KeyColumnUsage(
                    constraintName = rows.getString("constraint_name"),
                    childDatabase = rows.getString("child_schema"),
                    childTable = rows.getString("child_table"),
                    childColumn = rows.getString("child_column"),
                    parentDatabase = rows.getString("parent_schema"),
                    parentTable = rows.getString("parent_table"),
                    parentColumn = rows.getString("parent_column"),
                )
            }
        }

    /** One edge per constraint inside the schema, the columns of a composite key grouped. */
    override fun links(connection: Connection, namespace: String): List<GraphEdge> =
        connection.prepareStatement(
            """
            SELECT pt.name AS child_table, fk.name AS constraint_name, pc.name AS column_name,
                   rt.name AS parent_table
            $FOREIGN_KEY_COLUMNS
            WHERE ps.name = ? AND rs.name = ps.name
            ORDER BY pt.name, fk.name, fkc.constraint_column_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.executeQuery().collect { rows ->
                Triple(
                    rows.getString("child_table") to rows.getString("constraint_name"),
                    rows.getString("parent_table"),
                    rows.getString("column_name"),
                )
            }
        }.groupBy { it.first }
            .map { (key, columns) ->
                GraphEdge(from = key.first, to = columns.first().second, columns = columns.map { it.third })
            }

    override fun columnNames(connection: Connection, namespace: String): List<TableColumns> =
        connection.prepareStatement(
            """
            SELECT o.name AS table_name, c.name AS column_name,
                   CASE WHEN EXISTS (
                        SELECT 1 FROM sys.indexes i
                        JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                        WHERE i.object_id = c.object_id AND i.is_primary_key = 1 AND ic.column_id = c.column_id
                   ) THEN 1 ELSE 0 END AS is_pk
            FROM sys.columns c
            JOIN sys.objects o ON o.object_id = c.object_id
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            WHERE s.name = ? AND o.type IN ('U', 'V') AND o.is_ms_shipped = 0
            ORDER BY o.name, c.column_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.executeQuery().collect { rows ->
                Triple(rows.getString("table_name"), rows.getString("column_name"), rows.getInt("is_pk") == 1)
            }
        }.groupBy { it.first }
            .map { (table, columns) ->
                TableColumns(
                    table = table,
                    columns = columns.map { it.second },
                    primaryKey = columns.filter { it.third }.map { it.second },
                )
            }

    override fun checkConstraints(connection: Connection, namespace: String, table: String): List<CheckConstraint> =
        connection.prepareStatement(
            """
            SELECT cc.name, cc.definition, cc.is_disabled
            FROM sys.check_constraints cc
            JOIN sys.tables t ON t.object_id = cc.parent_object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ? AND t.name = ?
            ORDER BY cc.name
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.setString(2, table)
            statement.executeQuery().collect { rows ->
                CheckConstraint(
                    name = rows.getString("name"),
                    expression = rows.getString("definition")?.let(SqlServerTypes::unwrap),
                    // A disabled constraint stays in the schema and is never applied.
                    enforced = !rows.getBoolean("is_disabled"),
                )
            }
        }

    /** The database's default collation, which is what a column's own collation is compared to. */
    override fun tableExtras(connection: Connection, namespace: String, table: String): TableExtras =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT CAST(DATABASEPROPERTYEX(DB_NAME(), 'Collation') AS nvarchar(128))").use { rows ->
                TableExtras(collation = if (rows.next()) rows.getString(1) else null, partitions = emptyList())
            }
        }

    /** User procedures and functions (scalar, inline and multi-statement table-valued). */
    override fun routines(connection: Connection, namespace: String): List<SchemaRoutine> =
        connection.prepareStatement(
            """
            SELECT o.name AS name, o.type AS kind,
                   (SELECT TOP (1) t.name FROM sys.parameters p JOIN sys.types t ON t.user_type_id = p.user_type_id
                     WHERE p.object_id = o.object_id AND p.parameter_id = 0) AS returns_type,
                   CAST(ep.value AS nvarchar(4000)) AS comment
            FROM sys.objects o
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            LEFT JOIN sys.extended_properties ep
                   ON ep.class = 1 AND ep.major_id = o.object_id AND ep.minor_id = 0 AND ep.name = N'MS_Description'
            WHERE s.name = ? AND o.type IN ('P', 'FN', 'IF', 'TF') AND o.is_ms_shipped = 0
            ORDER BY o.type, o.name
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.executeQuery().collect { rows ->
                val kind = if (rows.getString("kind").trim() == "P") RoutineKind.PROCEDURE else RoutineKind.FUNCTION
                SchemaRoutine(
                    name = rows.getString("name"),
                    kind = kind,
                    returns = rows.getString("returns_type")?.takeIf { kind == RoutineKind.FUNCTION },
                    comment = rows.getString("comment")?.takeIf { it.isNotBlank() },
                )
            }
        }

    override fun routineDdl(connection: Connection, namespace: String, routine: SchemaRoutine): String =
        definition(connection, namespace, routine.name)

    override fun triggers(connection: Connection, namespace: String): List<SchemaTrigger> =
        connection.prepareStatement(
            """
            SELECT tr.name AS name, t.name AS table_name, tr.is_instead_of_trigger,
                   STUFF((SELECT ', ' + te.type_desc FROM sys.trigger_events te
                           WHERE te.object_id = tr.object_id ORDER BY te.type FOR XML PATH('')), 1, 2, '') AS events
            FROM sys.triggers tr
            JOIN sys.tables t ON t.object_id = tr.parent_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ? AND tr.is_ms_shipped = 0
            ORDER BY t.name, tr.name
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, namespace)
            statement.executeQuery().collect { rows ->
                SchemaTrigger(
                    name = rows.getString("name"),
                    table = rows.getString("table_name"),
                    event = rows.getString("events").orEmpty(),
                    timing = if (rows.getBoolean("is_instead_of_trigger")) "INSTEAD OF" else "AFTER",
                )
            }
        }

    private inline fun <T> ResultSet.collect(mapper: (ResultSet) -> T): List<T> = use { rows ->
        buildList {
            while (rows.next()) add(mapper(rows))
        }
    }
}

/** How `sys.columns` facts are spelled as a column type, and how stored expressions are unwrapped. */
internal object SqlServerTypes {

    /**
     * The type as T-SQL declares it. `max_length` is in bytes, so the two-byte national types
     * are halved, and -1 means `max`. Types without a length or scale are returned as they are.
     */
    fun declared(name: String, maxLength: Int, precision: Int, scale: Int): String = when (name.lowercase()) {
        "char", "varchar", "binary", "varbinary" -> "$name(${if (maxLength == -1) "max" else maxLength})"
        "nchar", "nvarchar" -> "$name(${if (maxLength == -1) "max" else maxLength / 2})"
        "decimal", "numeric" -> "$name($precision,$scale)"
        "datetime2", "datetimeoffset", "time" -> "$name($scale)"
        // float(53) is the plain `float`; only the narrower mantissa is worth a number.
        "float" -> if (precision == 53) name else "$name($precision)"
        else -> name
    }

    /**
     * `((0))` → `0`, `(getdate())` → `getdate()`: SQL Server stores a default or a check wrapped
     * in one pair of parentheses per level it was written with, which says nothing to a reader.
     * Only a pair that encloses the whole text is removed, so `(a) + (b)` is left alone.
     */
    fun unwrap(expression: String): String {
        var text = expression.trim()
        while (text.length >= 2 && text.first() == '(' && text.last() == ')' && encloses(text)) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    /** True when the first `(` of [text] is closed by its last `)`, not earlier. */
    private fun encloses(text: String): Boolean {
        var depth = 0
        var inString = false
        for ((i, c) in text.withIndex()) {
            when {
                c == '\'' -> inString = !inString
                inString -> Unit
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0 && i != text.lastIndex) return false
                }
            }
        }
        return depth == 0
    }
}
