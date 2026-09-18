package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RowSqlBuilderTest {

    private val key = linkedMapOf<String, String?>("id" to "42")

    @Test
    fun `update binds the new value first and the key after it`() {
        val prepared = RowSqlBuilder.update("shop", "orders", key, "status", "paid")

        assertEquals("UPDATE `shop`.`orders` SET `status` = ? WHERE `id` = ?", prepared.sql)
        assertEquals(listOf("paid", "42"), prepared.parameters)
    }

    @Test
    fun `a composite key produces one condition per column`() {
        val composite = linkedMapOf<String, String?>("tenant" to "hu", "id" to "7")

        val prepared = RowSqlBuilder.update("shop", "orders", composite, "note", null)

        assertEquals(
            "UPDATE `shop`.`orders` SET `note` = ? WHERE `tenant` = ? AND `id` = ?",
            prepared.sql,
        )
        assertEquals(listOf(null, "hu", "7"), prepared.parameters)
    }

    @Test
    fun `identifiers are quoted and embedded backticks are doubled`() {
        val prepared = RowSqlBuilder.delete("shop", "we`ird", key)

        assertEquals("DELETE FROM `shop`.`we``ird` WHERE `id` = ?", prepared.sql)
    }

    @Test
    fun `a missing key is refused rather than matching every row`() {
        assertThrows(NoPrimaryKeyException::class.java) {
            RowSqlBuilder.delete("shop", "orders", emptyMap())
        }
    }

    @Test
    fun `a null key value is refused because the WHERE would match nothing`() {
        assertThrows(NoPrimaryKeyException::class.java) {
            RowSqlBuilder.update("shop", "orders", mapOf("id" to null), "status", "paid")
        }
    }

    @Test
    fun `insert lists columns and placeholders in the same order`() {
        val prepared = RowSqlBuilder.insert(
            "shop",
            "orders",
            linkedMapOf("id" to "1", "status" to "new"),
        )

        assertEquals("INSERT INTO `shop`.`orders` (`id`, `status`) VALUES (?, ?)", prepared.sql)
        assertEquals(listOf("1", "new"), prepared.parameters)
    }

    @Test
    fun `render writes the values in for the confirmation dialog`() {
        val prepared = RowSqlBuilder.update("shop", "orders", key, "status", "paid")

        assertEquals(
            "UPDATE `shop`.`orders` SET `status` = 'paid' WHERE `id` = '42'",
            RowSqlBuilder.render(prepared),
        )
    }

    @Test
    fun `render shows NULL unquoted and escapes quotes`() {
        val prepared = RowSqlBuilder.update("shop", "orders", key, "note", null)
        assertEquals(
            "UPDATE `shop`.`orders` SET `note` = NULL WHERE `id` = '42'",
            RowSqlBuilder.render(prepared),
        )

        val quoted = RowSqlBuilder.update("shop", "orders", key, "note", "it's fine")
        assertEquals(
            "UPDATE `shop`.`orders` SET `note` = 'it''s fine' WHERE `id` = '42'",
            RowSqlBuilder.render(quoted),
        )
    }

    @Test
    fun `render does not consume a question mark that is part of a value`() {
        val prepared = RowSqlBuilder.update("shop", "orders", key, "note", "why?")

        assertEquals(
            "UPDATE `shop`.`orders` SET `note` = 'why?' WHERE `id` = '42'",
            RowSqlBuilder.render(prepared),
        )
    }

    @Test
    fun `an update can insist that the value is still the one that was read`() {
        val sql = RowSqlBuilder.update(
            database = "shop",
            table = "orders",
            key = mapOf("id" to "7"),
            column = "status",
            newValue = "paid",
            expectedValue = Expected.of("pending"),
        )
        assertEquals(
            "UPDATE `shop`.`orders` SET `status` = ? WHERE `id` = ? AND `status` <=> ?",
            sql.sql,
        )
        assertEquals(listOf("paid", "7", "pending"), sql.parameters)
    }

    @Test
    fun `the guard uses NULL-safe equality, so a column that was NULL can be guarded too`() {
        val sql = RowSqlBuilder.update(
            database = "shop",
            table = "orders",
            key = mapOf("id" to "7"),
            column = "note",
            newValue = "x",
            expectedValue = Expected.of(null),
        )
        // "= NULL" would match nothing; "<=> NULL" matches a NULL.
        assertTrue(sql.sql, sql.sql.endsWith("`note` <=> ?"))
        assertEquals(listOf("x", "7", null), sql.parameters)
    }

    @Test
    fun `without a guard the statement is the plain one`() {
        val sql = RowSqlBuilder.update("shop", "orders", mapOf("id" to "7"), "status", "paid")
        assertEquals("UPDATE `shop`.`orders` SET `status` = ? WHERE `id` = ?", sql.sql)
        assertEquals(listOf("paid", "7"), sql.parameters)
    }

    @Test
    fun `the conflict probe reads back the one column of the one row`() {
        val sql = RowSqlBuilder.selectValue("shop", "orders", mapOf("id" to "7"), "status")
        assertEquals("SELECT `status` FROM `shop`.`orders` WHERE `id` = ?", sql.sql)
        assertEquals(listOf("7"), sql.parameters)
    }
}
