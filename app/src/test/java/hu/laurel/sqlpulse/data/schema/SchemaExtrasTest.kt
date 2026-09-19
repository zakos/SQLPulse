package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes in these tests are the ones the servers actually send back: MySQL's escaped and
 * parenthesised `CHECK_CLAUSE`, MariaDB's "PERSISTENT" for a stored column, and a `DELETE_RULE`
 * that is never empty. Every one of them was a reason for this file to exist.
 */
class SchemaExtrasTest {

    @Test
    fun `generated columns are recognised in every spelling the servers use`() {
        assertEquals(GeneratedKind.VIRTUAL, SchemaExtras.generatedKind("VIRTUAL GENERATED"))
        assertEquals(GeneratedKind.STORED, SchemaExtras.generatedKind("STORED GENERATED"))
        // MariaDB's own words.
        assertEquals(GeneratedKind.VIRTUAL, SchemaExtras.generatedKind("VIRTUAL"))
        assertEquals(GeneratedKind.STORED, SchemaExtras.generatedKind("PERSISTENT"))
    }

    @Test
    fun `an ordinary column is not generated, whatever else EXTRA says`() {
        assertNull(SchemaExtras.generatedKind(null))
        assertNull(SchemaExtras.generatedKind(""))
        assertNull(SchemaExtras.generatedKind("auto_increment"))
        // A default that is an expression is not a generated column, and the word must not match.
        assertNull(SchemaExtras.generatedKind("DEFAULT_GENERATED"))
        assertNull(SchemaExtras.generatedKind("DEFAULT_GENERATED on update CURRENT_TIMESTAMP"))
    }

    @Test
    fun `the generation expression loses the server's quoting`() {
        assertEquals("`price` * `qty`", SchemaExtras.generationExpression("(`price` * `qty`)"))
        assertEquals(
            "concat(`a`,' ',`b`)",
            SchemaExtras.generationExpression("concat(`a`,\\' \\',`b`)"),
        )
        assertNull(SchemaExtras.generationExpression(null))
        assertNull(SchemaExtras.generationExpression("   "))
    }

    @Test
    fun `only the referential rules that change something are shown`() {
        assertNull(SchemaExtras.foreignKeyRules("RESTRICT", "RESTRICT"))
        assertNull(SchemaExtras.foreignKeyRules("NO ACTION", "NO ACTION"))
        assertNull(SchemaExtras.foreignKeyRules(null, null))
        assertEquals("ON DELETE CASCADE", SchemaExtras.foreignKeyRules("CASCADE", "NO ACTION"))
        assertEquals("ON UPDATE SET NULL", SchemaExtras.foreignKeyRules("RESTRICT", "SET NULL"))
        assertEquals(
            "ON DELETE SET NULL · ON UPDATE CASCADE",
            SchemaExtras.foreignKeyRules("set null", "cascade"),
        )
    }

    @Test
    fun `a foreign key carries its own rule summary`() {
        val cascading = ForeignKey(
            constraintName = "fk_order_customer",
            column = "customer_id",
            referencedDatabase = "shop",
            referencedTable = "customers",
            referencedColumn = "id",
            onDelete = "CASCADE",
            onUpdate = "RESTRICT",
        )
        assertEquals("ON DELETE CASCADE", cascading.ruleSummary)
        // A key read from a server that would not say has nothing to add.
        assertNull(cascading.copy(onDelete = null, onUpdate = null).ruleSummary)
    }

    @Test
    fun `a check clause is unwrapped, but only where the brackets wrap all of it`() {
        assertEquals("`price` > 0", SchemaExtras.checkExpression("(`price` > 0)"))
        assertEquals(
            "(`a` > 0) and (`b` > 0)",
            SchemaExtras.checkExpression("((`a` > 0) and (`b` > 0))"),
        )
        // Two conditions side by side keep both of their pairs: stripping the outer characters
        // here would leave an expression that no longer parses.
        assertEquals(
            "(`a` > 0) and (`b` > 0)",
            SchemaExtras.checkExpression("(`a` > 0) and (`b` > 0)"),
        )
        // The character set introducer MySQL writes in front of a literal is left as it is: it is
        // part of a valid expression, and guessing at which prefixes are introducers would be a
        // way of changing the condition rather than showing it.
        assertEquals(
            "`code` <> _utf8mb4''",
            SchemaExtras.checkExpression("(`code` <> _utf8mb4\\'\\')"),
        )
        assertNull(SchemaExtras.checkExpression(null))
    }

    @Test
    fun `a check clause written over several lines becomes one`() {
        val clause = "(`status` in\n  ('new','paid',\n   'sent'))"
        assertEquals("`status` in ('new','paid', 'sent')", SchemaExtras.checkExpression(clause))
    }

    @Test
    fun `a column's collation is shown only where it differs from the table's`() {
        assertNull(SchemaExtras.columnCollation("utf8mb4_0900_ai_ci", "utf8mb4_0900_ai_ci"))
        assertNull(SchemaExtras.columnCollation("utf8mb4_0900_ai_ci", "UTF8MB4_0900_AI_CI"))
        // A number or a date has none at all.
        assertNull(SchemaExtras.columnCollation("utf8mb4_0900_ai_ci", null))
        assertEquals(
            "utf8mb4_bin",
            SchemaExtras.columnCollation("utf8mb4_0900_ai_ci", "utf8mb4_bin"),
        )
        // A table whose collation is unknown cannot hide a column's.
        assertEquals("latin1_swedish_ci", SchemaExtras.columnCollation(null, "latin1_swedish_ci"))
    }

    @Test
    fun `the character set is the part of the collation before the first underscore`() {
        assertEquals("utf8mb4", SchemaExtras.charsetOf("utf8mb4_0900_ai_ci"))
        assertEquals("latin1", SchemaExtras.charsetOf("latin1_swedish_ci"))
        assertNull(SchemaExtras.charsetOf(null))
        assertNull(SchemaExtras.charsetOf("  "))
    }

    @Test
    fun `partitioning is summarised by its method and expression`() {
        val partitions = listOf(
            TablePartition("p2023", method = "RANGE", expression = "YEAR(sold_at)", approximateRows = 10),
            TablePartition("p2024", method = "RANGE", expression = "YEAR(sold_at)", approximateRows = 32),
        )
        assertEquals("RANGE (YEAR(sold_at))", SchemaExtras.partitionSummary(partitions))
        assertEquals(42L, SchemaExtras.partitionRowTotal(partitions))
    }

    @Test
    fun `a method with no expression is shown without empty brackets`() {
        val partitions = listOf(TablePartition("p0", method = "KEY", expression = null))
        assertEquals("KEY", SchemaExtras.partitionSummary(partitions))
        assertNull(SchemaExtras.partitionRowTotal(partitions))
        assertNull(SchemaExtras.partitionSummary(emptyList()))
    }

    @Test
    fun `a partition that reports no estimate is left out of the total, not counted as empty`() {
        val partitions = listOf(
            TablePartition("p0", method = "HASH", approximateRows = 5),
            TablePartition("p1", method = "HASH", approximateRows = null),
        )
        assertEquals(5L, SchemaExtras.partitionRowTotal(partitions))
    }

    @Test
    fun `a table knows whether it is partitioned`() {
        val plain = TableStructure(columns = emptyList(), indexes = emptyList(), foreignKeys = emptyList())
        assertFalse(plain.partitioned)
        assertTrue(plain.copy(partitions = listOf(TablePartition("p0"))).partitioned)
        // A server with no CHECK constraints looks exactly like a table that declares none.
        assertTrue(plain.checks.isEmpty())
    }

    @Test
    fun `a long expression is cut short rather than pushing the row off the screen`() {
        val long = "a".repeat(200)
        val shortened = SchemaExtras.shorten(long, max = 20)
        assertEquals(20, shortened.length)
        assertTrue(shortened.endsWith("…"))
        assertEquals("short", SchemaExtras.shorten("short", max = 20))
    }

    @Test
    fun `a generated column reports its kind through the model`() {
        val column = SchemaColumn(
            name = "total",
            typeName = "decimal(10,2)",
            nullable = true,
            defaultValue = null,
            isPrimaryKey = false,
            extra = "STORED GENERATED",
            comment = null,
            generationExpression = "`price` * `qty`",
        )
        assertEquals(GeneratedKind.STORED, column.generatedKind)
        assertNull(column.copy(extra = null).generatedKind)
    }
}
