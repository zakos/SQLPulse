package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline schema cache's rules, tested without a database.
 *
 * [SchemaCache] is the whole reason the cache can be checked at all: it has no Android, Room or
 * JDBC import, so the clock can be moved a month forward here in a line. What is asserted below
 * is deliberately the behaviour a user would notice — that a live answer is never replaced by a
 * stored one, that a stored one cannot reach the screen without its capture time, and that a
 * refresh removes what the server no longer has — rather than the shape of the helpers.
 */
class SchemaCacheTest {

    // ------------------------------------------------------------ staleness

    @Test
    fun `a capture taken now is neither stale nor expired`() {
        assertFalse(SchemaCache.isStale(NOW, NOW))
        assertFalse(SchemaCache.isExpired(NOW, NOW))
        assertEquals(0L, SchemaCache.ageMillis(NOW, NOW))
    }

    /** Exactly at the boundary is the last fresh moment, not the first stale one. */
    @Test
    fun `staleness begins after the boundary and not at it`() {
        val policy = SchemaCachePolicy()
        assertFalse(SchemaCache.isStale(NOW - policy.staleAfterMillis, NOW, policy))
        assertTrue(SchemaCache.isStale(NOW - policy.staleAfterMillis - 1, NOW, policy))
    }

    /** A clock that moved backwards must not make a capture look like it came from the future. */
    @Test
    fun `a capture from the future reads as brand new`() {
        assertEquals(0L, SchemaCache.ageMillis(NOW + DAY, NOW))
        assertFalse(SchemaCache.isStale(NOW + DAY, NOW))
        assertEquals(CacheAgeUnit.JUST_NOW, SchemaCache.age(NOW + DAY, NOW).unit)
    }

    @Test
    fun `a policy that expires before it goes stale is refused`() {
        val failure = runCatching { SchemaCachePolicy(staleAfterMillis = DAY, expireAfterMillis = 1) }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    // ------------------------------------------------------------------ age

    @Test
    fun `the age is given in the coarsest unit that is still true`() {
        assertEquals(CacheAge(CacheAgeUnit.JUST_NOW, 0), SchemaCache.age(NOW - 40_000, NOW))
        assertEquals(CacheAge(CacheAgeUnit.MINUTES, 1), SchemaCache.age(NOW - 60_000, NOW))
        assertEquals(CacheAge(CacheAgeUnit.MINUTES, 59), SchemaCache.age(NOW - 59 * 60_000, NOW))
        assertEquals(CacheAge(CacheAgeUnit.HOURS, 1), SchemaCache.age(NOW - 60 * 60_000, NOW))
        assertEquals(CacheAge(CacheAgeUnit.HOURS, 23), SchemaCache.age(NOW - 23 * HOUR, NOW))
        assertEquals(CacheAge(CacheAgeUnit.DAYS, 1), SchemaCache.age(NOW - DAY, NOW))
        assertEquals(CacheAge(CacheAgeUnit.DAYS, 9), SchemaCache.age(NOW - 9 * DAY, NOW))
    }

    // ------------------------------------------------------- what is shown

    @Test
    fun `a live answer wins over anything stored`() {
        val view = SchemaCache.view(
            live = listOf("orders"),
            cached = SchemaSnapshot(listOf("orders", "customers"), NOW - HOUR),
            now = NOW,
        )
        assertTrue(view.isLive)
        assertEquals(listOf("orders"), view.valueOrNull())
    }

    /**
     * The failure this whole feature exists to avoid, stated as a test: a database the server
     * says is empty must render empty, not as last week's tables.
     */
    @Test
    fun `an empty live answer is still a live answer`() {
        val view = SchemaCache.view(
            live = emptyList<String>(),
            cached = SchemaSnapshot(listOf("orders"), NOW - HOUR),
            now = NOW,
        )
        assertTrue(view.isLive)
        assertEquals(emptyList<String>(), view.valueOrNull())
    }

    @Test
    fun `with no session the capture is shown, marked, with the moment it was taken`() {
        val view = SchemaCache.view(
            live = null,
            cached = SchemaSnapshot(listOf("orders"), NOW - 3 * HOUR),
            now = NOW,
        )
        val cached = view as SchemaView.Cached
        assertFalse(view.isLive)
        assertEquals(SchemaOrigin.CACHED, cached.origin)
        assertEquals(NOW - 3 * HOUR, cached.capturedAt)
        assertEquals(CacheAge(CacheAgeUnit.HOURS, 3), cached.age)
        assertFalse("three hours is not yet stale", cached.stale)
        assertEquals(listOf("orders"), cached.value)
    }

    @Test
    fun `an old capture is still shown, but marked stale`() {
        val cached = SchemaCache.view(
            live = null,
            cached = SchemaSnapshot(listOf("orders"), NOW - 5 * DAY),
            now = NOW,
        ) as SchemaView.Cached
        assertTrue(cached.stale)
        assertEquals(CacheAge(CacheAgeUnit.DAYS, 5), cached.age)
        assertEquals(listOf("orders"), cached.value)
    }

    @Test
    fun `a capture past its expiry is not shown at all`() {
        val view = SchemaCache.view(
            live = null,
            cached = SchemaSnapshot(listOf("orders"), NOW - 40 * DAY),
            now = NOW,
        )
        assertEquals(SchemaView.Absent, view)
        assertNull(view.valueOrNull())
    }

    @Test
    fun `nothing live and nothing stored is absent rather than empty`() {
        val view = SchemaCache.view<List<String>>(live = null, cached = null, now = NOW)
        assertEquals(SchemaView.Absent, view)
        assertFalse(view.isLive)
    }

    // ------------------------------------------------------ when to capture

    @Test
    fun `capturing happens when there is nothing stored, when it is stale, or when asked`() {
        assertTrue("never captured", SchemaCache.shouldCapture(null, NOW))
        assertFalse("captured an hour ago", SchemaCache.shouldCapture(NOW - HOUR, NOW))
        assertTrue("captured five days ago", SchemaCache.shouldCapture(NOW - 5 * DAY, NOW))
        assertTrue(
            "the refresh button always writes",
            SchemaCache.shouldCapture(NOW - HOUR, NOW, userAsked = true),
        )
    }

    // -------------------------------------------------------------- merging

    @Test
    fun `a refresh adds what is new, replaces what changed and removes what is gone`() {
        val cached = listOf(
            table("orders", rows = 10),
            table("customers", rows = 3),
            table("legacy_audit", rows = 1),
        )
        val fresh = listOf(
            table("orders", rows = 12),
            table("customers", rows = 3),
            table("shipments", rows = 0),
        )

        val merge = SchemaCache.mergeTables(cached, fresh)

        assertEquals(listOf("shipments"), merge.added.map { it.name })
        assertEquals(listOf("orders"), merge.changed.map { it.name })
        assertEquals(listOf("customers"), merge.unchanged.map { it.name })
        assertEquals(listOf("shop.legacy_audit"), merge.removedKeys)
        assertEquals(listOf("orders", "shipments"), merge.upserts.map { it.name }.sorted())
        assertFalse(merge.identical)
    }

    @Test
    fun `a refresh that finds no change says so, so the capture time is not moved for nothing`() {
        val tables = listOf(table("orders", rows = 10))
        val merge = SchemaCache.mergeTables(tables, tables)
        assertTrue(merge.identical)
        assertTrue(merge.upserts.isEmpty())
        assertEquals(listOf("orders"), merge.unchanged.map { it.name })
    }

    /** Two databases on the same server may both have an `orders`; they must not collide. */
    @Test
    fun `tables are keyed by database as well as name`() {
        val merge = SchemaCache.mergeTables(
            cached = listOf(table("orders", database = "shop")),
            fresh = listOf(table("orders", database = "shop"), table("orders", database = "warehouse")),
        )
        assertEquals(listOf("warehouse.orders"), merge.added.map { "${it.database}.${it.name}" })
        assertTrue(merge.removedKeys.isEmpty())
    }

    @Test
    fun `an emptied server removes every cached table`() {
        val merge = SchemaCache.mergeTables(listOf(table("orders"), table("customers")), emptyList())
        assertEquals(listOf("shop.orders", "shop.customers"), merge.removedKeys)
        assertTrue(merge.upserts.isEmpty())
        assertFalse(merge.identical)
    }

    // ------------------------------------------------------ index columns

    @Test
    fun `an index's columns survive the round trip, commas and all`() {
        val columns = listOf("customer_id", "created, at", "`odd`")
        assertEquals(columns, SchemaCache.splitColumns(SchemaCache.joinColumns(columns)))
    }

    @Test
    fun `an index with no columns comes back with none, not with one empty name`() {
        assertEquals(emptyList<String>(), SchemaCache.splitColumns(SchemaCache.joinColumns(emptyList())))
    }

    @Test
    fun `a single column needs no separator to come back`() {
        assertEquals(listOf("id"), SchemaCache.splitColumns(SchemaCache.joinColumns(listOf("id"))))
    }

    private fun table(name: String, database: String = "shop", rows: Long = 0): CachedTable =
        CachedTable(
            database = database,
            name = name,
            kind = "TABLE",
            approximateRows = rows,
            comment = null,
            engine = "InnoDB",
            collation = "utf8mb4_general_ci",
            dataBytes = 1024,
            indexBytes = 256,
        )

    private companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR

        /** An arbitrary fixed "now". Nothing here reads a clock. */
        const val NOW = 1_800_000_000_000L
    }
}
