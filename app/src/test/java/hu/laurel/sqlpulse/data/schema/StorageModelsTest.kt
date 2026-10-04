package hu.laurel.sqlpulse.data.schema

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageModelsTest {

    private fun table(
        name: String,
        data: Long = 0,
        index: Long = 0,
        free: Long = 0,
        rows: Long? = null,
        next: BigInteger? = null,
        type: String? = null,
    ) = StorageTable(
        name = name, engine = "InnoDB", rowFormat = "Dynamic", rowsEstimate = rows,
        dataBytes = data, indexBytes = index, freeBytes = free, autoIncrement = next,
        autoIncrementType = type, createTime = null, updateTime = null, collation = null,
    )

    @Test
    fun maxOfKnowsSignedAndUnsignedRanges() {
        assertEquals(BigInteger.valueOf(4_294_967_295), AutoIncrementHeadroom.maxOf("int(10) unsigned"))
        assertEquals(BigInteger.valueOf(2_147_483_647), AutoIncrementHeadroom.maxOf("int(11)"))
        assertEquals(BigInteger.valueOf(255), AutoIncrementHeadroom.maxOf("tinyint unsigned"))
        assertEquals(BigInteger.valueOf(16_777_215), AutoIncrementHeadroom.maxOf("MEDIUMINT(8) UNSIGNED"))
        assertEquals(BigInteger("18446744073709551615"), AutoIncrementHeadroom.maxOf("bigint(20) unsigned"))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), AutoIncrementHeadroom.maxOf("bigint"))
        assertNull(AutoIncrementHeadroom.maxOf("decimal(10,0)"))
        assertNull(AutoIncrementHeadroom.maxOf(null))
    }

    @Test
    fun warnsFromEightyPercent() {
        val nearly = table("t", next = BigInteger.valueOf(3_500_000_000), type = "int(10) unsigned")
        assertTrue(nearly.autoIncrementWarning)
        val fine = table("t", next = BigInteger.valueOf(3_000_000_000), type = "int(10) unsigned")
        assertFalse(fine.autoIncrementWarning)
        // The same counter that is fine unsigned is past the end of a signed int.
        assertTrue(table("t", next = BigInteger.valueOf(3_000_000_000), type = "int(11)").autoIncrementWarning)
    }

    @Test
    fun noWarningWithoutCounterOrKnownType() {
        assertNull(table("t").autoIncrementUsage)
        assertFalse(table("t", next = BigInteger.TEN, type = null).autoIncrementWarning)
    }

    @Test
    fun hugeBigintCounterDoesNotOverflow() {
        val usage = AutoIncrementHeadroom.usage(BigInteger("18446744073709551615"), "bigint unsigned")
        assertEquals(1.0, usage!!, 0.0)
    }

    @Test
    fun totalsAddUp() {
        val totals = StorageTotals.of(listOf(table("a", 10, 5, 1), table("b", 20, 1, 2)))
        assertEquals(30, totals.dataBytes)
        assertEquals(6, totals.indexBytes)
        assertEquals(3, totals.freeBytes)
        assertEquals(36, totals.totalBytes)
        assertEquals(2, totals.tableCount)
    }

    @Test
    fun sortsByEachKey() {
        val tables = listOf(
            table("b", data = 10, index = 1, free = 5, rows = 100),
            table("a", data = 1, index = 1, free = 50, rows = null),
            table("c", data = 20, index = 20, free = 0, rows = 7),
        )
        assertEquals(listOf("c", "b", "a"), StorageSort.TOTAL.apply(tables).map { it.name })
        assertEquals(listOf("b", "c", "a"), StorageSort.ROWS.apply(tables).map { it.name })
        assertEquals(listOf("a", "b", "c"), StorageSort.FREE.apply(tables).map { it.name })
        assertEquals(listOf("a", "b", "c"), StorageSort.NAME.apply(tables).map { it.name })
    }

    @Test
    fun mergesIndexSizesBiggestFirstWithoutPrimary() {
        val merged = mergeIndexSizes(
            listOf(table("orders"), table("lonely")),
            mapOf(
                "orders" to listOf(IndexSize("PRIMARY", 900), IndexSize("idx_a", 10), IndexSize("idx_b", 30)),
            ),
        )
        assertEquals(listOf("idx_b", "idx_a"), merged[0].indexes.map { it.index })
        assertTrue(merged[1].indexes.isEmpty())
    }

    @Test
    fun recognisesMariaDb() {
        assertTrue(ServerFlavor.isMariaDb("11.4.2-MariaDB-ubu2404"))
        assertFalse(ServerFlavor.isMariaDb("8.0.36"))
        assertFalse(ServerFlavor.isMariaDb(null))
    }
}
