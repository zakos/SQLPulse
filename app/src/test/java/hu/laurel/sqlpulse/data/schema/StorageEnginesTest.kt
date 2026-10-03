package hu.laurel.sqlpulse.data.schema

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces of the storage screen that are not MySQL's. */
class StorageEnginesTest {

    @Test
    fun `an integer serial counts against its type`() {
        val next = BigInteger.valueOf(30_001)
        assertEquals(30_001.0 / 32_767, AutoIncrementHeadroom.usage(next, "smallint")!!, 1e-4)
        assertEquals(1.0 / 2_147_483_647, AutoIncrementHeadroom.usage(BigInteger.ONE, "integer")!!, 1e-9)
        assertNull(AutoIncrementHeadroom.usage(next, "numeric"))
    }

    @Test
    fun `a SQL Server tinyint identity is unsigned`() {
        assertEquals(BigInteger.valueOf(255), AutoIncrementHeadroom.maxOf(SqlServerStorage.headroomType("tinyint")))
        assertEquals("bigint", SqlServerStorage.headroomType("bigint"))
    }

    @Test
    fun `a table's counter warns near the end whatever it is called`() {
        fun table(counter: CounterKind) = StorageTable(
            "t", null, null, 1, 1, 1, 0, BigInteger.valueOf(30_001), "smallint", null, null, null,
            counter = counter,
        )
        assertTrue(table(CounterKind.SEQUENCE).autoIncrementWarning)
        assertTrue(table(CounterKind.IDENTITY).autoIncrementWarning)
    }

    @Test
    fun `the engine statements read the catalogs they should`() {
        assertTrue(PostgresStorage.TABLES.contains("pg_total_relation_size"))
        assertTrue(PostgresStorage.TABLES.contains("pg_indexes_size"))
        assertTrue(PostgresStorage.TABLES.contains("n_live_tup"))
        assertTrue(PostgresStorage.UNUSED.contains("idx_scan = 0"))
        assertTrue(PostgresStorage.STATS_AGE.contains("stats_reset"))
        assertTrue(SqlServerStorage.TABLES.contains("sys.dm_db_partition_stats"))
        assertTrue(SqlServerStorage.UNUSED.contains("sys.dm_db_index_usage_stats"))
        assertTrue(SqlServerStorage.UPTIME.contains("sqlserver_start_time"))
    }

    @Test
    fun `an unused list counted since a reset says so`() {
        val unused = UnusedIndexes(emptyList(), fromUserstat = false, basis = UnusedBasis.STATS_RESET)
        assertEquals(UnusedBasis.STATS_RESET, unused.basis)
        assertEquals(UnusedBasis.USERSTAT, UnusedIndexes(emptyList(), fromUserstat = true).basis)
        assertEquals(UnusedBasis.SERVER_START, UnusedIndexes(emptyList(), fromUserstat = false).basis)
    }
}
