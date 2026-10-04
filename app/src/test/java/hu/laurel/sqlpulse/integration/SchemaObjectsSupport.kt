package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.schema.CachedCheck
import hu.laurel.sqlpulse.data.schema.CachedColumn
import hu.laurel.sqlpulse.data.schema.CachedForeignKey
import hu.laurel.sqlpulse.data.schema.CachedIndex
import hu.laurel.sqlpulse.data.schema.CachedStructure
import hu.laurel.sqlpulse.data.schema.CachedTable
import hu.laurel.sqlpulse.data.schema.CachedTrigger
import hu.laurel.sqlpulse.data.schema.DiffField
import hu.laurel.sqlpulse.data.schema.DiffStatus
import hu.laurel.sqlpulse.data.schema.SchemaDiffResult
import hu.laurel.sqlpulse.data.schema.SchemaDiffSide
import hu.laurel.sqlpulse.data.schema.SchemaRepository
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * What every engine's "objects" comparison test shares: how a namespace is turned into the
 * cache's vocabulary (the way the live refresh does it, now with foreign key rules, CHECK
 * constraints, view text and trigger bodies), and what the fixture's differences must come out as.
 *
 * The fixture contract each engine implements: tables `parent` and `child`; `child.parent_id`
 * references `parent` ON DELETE CASCADE on side A and SET NULL on side B (ON UPDATE the same);
 * `child` has the CHECK constraints `qty_ok` (`qty > 0` on A, `qty >= 0` on B) and `price_ok`
 * (`price >= 0` on both, written differently); views `v_child` (WHERE qty > 0 / qty > 1) and
 * `v_same` (one query, formatted differently); triggers `trg_child` (a different body) and
 * `trg_same` (one body, formatted differently) on `child`.
 */
object SchemaObjectsSupport {

    fun side(manager: SqlSessionManager, namespace: String, engine: DatabaseEngine): SchemaDiffSide = runBlocking {
        val schema = SchemaRepository(manager)
        val tables = schema.tables(namespace, refresh = true)
        SchemaDiffSide(
            database = namespace,
            engine = engine,
            tables = tables.map {
                CachedTable(
                    database = it.database, name = it.name, kind = it.kind.name,
                    approximateRows = it.approximateRows, comment = it.comment, engine = it.engine,
                    collation = it.collation, dataBytes = it.dataBytes, indexBytes = it.indexBytes,
                )
            },
            structures = tables.associate { table ->
                val structure = schema.structure(namespace, table.name, refresh = true)
                table.name to CachedStructure(
                    columns = structure.columns.mapIndexed { position, column ->
                        CachedColumn(
                            column.name, column.typeName, column.nullable, column.defaultValue,
                            column.isPrimaryKey, column.extra, column.comment, position,
                        )
                    },
                    indexes = structure.indexes.mapIndexed { position, index ->
                        CachedIndex(index.name, index.unique, index.columns, position)
                    },
                    foreignKeys = structure.foreignKeys.map {
                        CachedForeignKey(
                            it.constraintName, it.column, it.referencedDatabase, it.referencedTable, it.referencedColumn,
                            onDelete = it.onDelete, onUpdate = it.onUpdate,
                        )
                    },
                    checks = structure.checks.map { CachedCheck(it.name, it.expression, it.enforced) },
                )
            },
            viewDefinitions = schema.viewDefinitions(namespace),
            triggers = schema.triggerDefinitions(namespace).map {
                CachedTrigger(it.name, it.table, it.timing, it.event, it.body.orEmpty())
            },
        )
    }

    /** The differences the fixture contract produces, and nothing else. */
    fun assertFixtureDifferences(result: SchemaDiffResult) {
        assertEquals(listOf("child", "v_child"), result.tables.map { it.name })

        val child = result.tables.single { it.name == "child" }
        val key = child.foreignKeys.single()
        assertEquals(DiffStatus.CHANGED, key.status)
        val rule = key.changes.single()
        assertEquals(DiffField.ON_DELETE, rule.field)
        assertTrue(rule.a, rule.a.orEmpty().contains("CASCADE", ignoreCase = true))
        assertTrue(rule.b, rule.b.orEmpty().contains("SET NULL", ignoreCase = true))

        val check = child.checks.single()
        assertEquals("qty_ok", check.name)
        assertEquals(DiffField.EXPRESSION, check.changes.single().field)
        assertTrue(child.columns.isEmpty() && child.indexes.isEmpty())

        val view = result.tables.single { it.name == "v_child" }
        assertEquals(DiffField.DEFINITION, view.changes.single().field)

        val trigger = result.triggers.single()
        assertEquals("child.trg_child", trigger.name)
        assertEquals(DiffField.DEFINITION, trigger.changes.single().field)

        // parent, v_same, price_ok and trg_same differ in spelling only.
        assertEquals(2, result.identicalCount)
    }
}
