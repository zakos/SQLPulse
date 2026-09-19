package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowLinksTest {

    private fun fk(constraint: String, column: String, refTable: String, refColumn: String) =
        ForeignKey(constraint, column, "shop", refTable, refColumn)

    @Test
    fun `a single column key becomes one link`() {
        val links = RowLinks.parentLinks("shop", "orders", listOf(fk("fk_customer", "customer_id", "customers", "id")))

        assertEquals(1, links.size)
        assertEquals("customers", links[0].parentTable)
        assertEquals(listOf(LinkColumn("customer_id", "id")), links[0].columns)
    }

    @Test
    fun `several columns of one constraint are one link in order`() {
        val links = RowLinks.parentLinks(
            "shop",
            "order_lines",
            listOf(
                fk("fk_order", "order_year", "orders", "year"),
                fk("fk_order", "order_no", "orders", "no"),
            ),
        )

        assertEquals(1, links.size)
        assertEquals(listOf("order_year", "order_no"), links[0].childColumns)
        assertEquals(listOf("year", "no"), links[0].parentColumns)
    }

    @Test
    fun `a single column parent lookup binds the value`() {
        val link = RowLinks.parentLinks("shop", "orders", listOf(fk("fk_c", "customer_id", "customers", "id"))).first()
        val filter = RowLinks.parentFilter(link, mapOf("id" to "7", "customer_id" to "42"))

        assertNotNull(filter)
        val sql = RowLinks.selectRows("shop", "customers", filter!!, limit = 50)
        assertEquals("SELECT * FROM `shop`.`customers` WHERE `id` = ? LIMIT 50", sql.sql)
        assertEquals(listOf("42"), sql.parameters)
    }

    @Test
    fun `a composite key becomes one WHERE with every column bound`() {
        val link = RowLinks.parentLinks(
            "shop",
            "order_lines",
            listOf(
                fk("fk_order", "order_year", "orders", "year"),
                fk("fk_order", "order_no", "orders", "no"),
            ),
        ).first()
        val filter = RowLinks.parentFilter(link, mapOf("order_year" to "2024", "order_no" to "118"))!!

        val sql = RowLinks.selectRows("shop", "orders", filter, limit = 50)
        assertEquals("SELECT * FROM `shop`.`orders` WHERE `year` = ? AND `no` = ? LIMIT 50", sql.sql)
        assertEquals(listOf("2024", "118"), sql.parameters)
    }

    @Test
    fun `a NULL foreign key offers nothing rather than looking up NULL`() {
        val link = RowLinks.parentLinks("shop", "orders", listOf(fk("fk_c", "customer_id", "customers", "id"))).first()

        assertNull(RowLinks.parentFilter(link, mapOf("customer_id" to null)))
    }

    @Test
    fun `one NULL column of a composite key is enough to offer nothing`() {
        val link = RowLinks.parentLinks(
            "shop",
            "order_lines",
            listOf(
                fk("fk_order", "order_year", "orders", "year"),
                fk("fk_order", "order_no", "orders", "no"),
            ),
        ).first()

        assertNull(RowLinks.parentFilter(link, mapOf("order_year" to "2024", "order_no" to null)))
    }

    @Test
    fun `a column the row does not carry offers nothing`() {
        val link = RowLinks.parentLinks("shop", "orders", listOf(fk("fk_c", "customer_id", "customers", "id"))).first()

        assertNull(RowLinks.parentFilter(link, mapOf("id" to "7")))
    }

    @Test
    fun `identifiers needing quoting are quoted and values stay bound`() {
        val link = RowLinks.parentLinks(
            "we`ird",
            "order`s",
            listOf(ForeignKey("fk", "cust`omer", "we`ird", "cust`omers", "i`d")),
        ).first()
        val filter = RowLinks.parentFilter(link, mapOf("cust`omer" to "O'Brien; DROP TABLE x--"))!!

        val sql = RowLinks.selectRows("we`ird", "cust`omers", filter, limit = 50)
        assertEquals(
            "SELECT * FROM `we``ird`.`cust``omers` WHERE `i``d` = ? LIMIT 50",
            sql.sql,
        )
        // The value never reaches the statement text.
        assertEquals(listOf("O'Brien; DROP TABLE x--"), sql.parameters)
        assertTrue(!sql.sql.contains("O'Brien"))
    }

    @Test
    fun `a missing parent is reported as missing`() {
        assertEquals(LookupOutcome.MISSING, RowLinks.outcome(0))
    }

    @Test
    fun `several parents matching is neither one nor missing`() {
        assertEquals(LookupOutcome.ONE, RowLinks.outcome(1))
        assertEquals(LookupOutcome.SEVERAL, RowLinks.outcome(2))
        assertEquals(LookupOutcome.SEVERAL, RowLinks.outcome(9))
    }

    @Test
    fun `the link of a cell is found by its column, whatever the case`() {
        val links = RowLinks.parentLinks(
            "shop",
            "orders",
            listOf(fk("fk_c", "customer_id", "customers", "id"), fk("fk_s", "shop_id", "shops", "id")),
        )

        assertEquals("shops", RowLinks.linkForColumn(links, "SHOP_ID")?.parentTable)
        assertNull(RowLinks.linkForColumn(links, "total"))
    }

    @Test
    fun `children are filtered by the parent row's own values`() {
        val inbound = RowLinks.group(
            listOf(
                KeyColumnUsage("fk_order", "shop", "order_lines", "order_year", "shop", "orders", "year"),
                KeyColumnUsage("fk_order", "shop", "order_lines", "order_no", "shop", "orders", "no"),
            ),
        ).first()
        val filter = RowLinks.childFilter(inbound, mapOf("year" to "2024", "no" to "118"))!!

        val count = RowLinks.countRows("shop", "order_lines", filter)
        assertEquals(
            "SELECT COUNT(*) FROM `shop`.`order_lines` WHERE `order_year` = ? AND `order_no` = ?",
            count.sql,
        )
        assertEquals(listOf("2024", "118"), count.parameters)
    }

    @Test
    fun `a guessed link is marked and only followed onto a single primary key`() {
        val guesses = listOf(
            GraphEdge(from = "orders", to = "customers", columns = listOf("customer_id"), guessed = true),
            GraphEdge(from = "orders", to = "shops", columns = listOf("shop_id"), guessed = true),
            GraphEdge(from = "other", to = "customers", columns = listOf("customer_id"), guessed = true),
        )
        val links = RowLinks.guessedLinks(
            database = "shop",
            table = "orders",
            guesses = guesses,
            // `shops` has a composite primary key, so there is no telling which column to match.
            primaryKeys = mapOf("customers" to listOf("id"), "shops" to listOf("region", "code")),
        )

        assertEquals(1, links.size)
        assertEquals("customers", links[0].parentTable)
        assertTrue(links[0].guessed)
    }

    @Test
    fun `a declared link is not marked as a guess`() {
        val link = RowLinks.parentLinks("shop", "orders", listOf(fk("fk_c", "customer_id", "customers", "id"))).first()

        assertTrue(!link.guessed)
    }
}
