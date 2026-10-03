package hu.laurel.sqlpulse.data.sql.dialect

import hu.laurel.sqlpulse.data.schema.SlowSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCatalogsTest {

    @Test
    fun `every engine hands out its own catalog and sqlite has none`() {
        assertSame(MySqlServerCatalog, MySqlDialect.server)
        assertSame(PostgresServerCatalog, PostgresDialect.server)
        assertSame(SqlServerServerCatalog, SqlServerDialect.server)
        assertSame(NoServerCatalog, SqliteDialect.server)
        assertThrows(EngineNotSupportedException::class.java) { SqliteDialect.server.pulse }
    }

    @Test
    fun `sqlite keeps the server screens hidden and the others show them`() {
        for (feature in listOf(
            EngineFeature.SERVER_ACTIVITY, EngineFeature.PULSE, EngineFeature.REPLICATION, EngineFeature.SLOW_QUERIES,
        )) {
            assertFalse(SqliteDialect.supports(feature))
            assertTrue(PostgresDialect.supports(feature))
            assertTrue(SqlServerDialect.supports(feature))
            assertTrue(MySqlDialect.supports(feature))
        }
    }

    @Test
    fun `what each engine can do with a session`() {
        assertEquals(setOf(KillAction.CANCEL), MySqlDialect.server.capabilities.killActions)
        assertFalse(MySqlDialect.server.capabilities.idleFilter)
        assertFalse(MySqlDialect.server.capabilities.transactionCards)
        assertEquals(setOf(KillAction.CANCEL, KillAction.TERMINATE), PostgresDialect.server.capabilities.killActions)
        assertEquals(KillAction.CANCEL, PostgresDialect.server.capabilities.primaryKill)
        assertEquals(setOf(KillAction.TERMINATE), SqlServerDialect.server.capabilities.killActions)
        assertEquals(KillAction.TERMINATE, SqlServerDialect.server.capabilities.primaryKill)
        assertTrue(PostgresDialect.server.capabilities.idleFilter)
        assertTrue(SqlServerDialect.server.capabilities.transactionCards)
    }

    // ------------------------------------------------------------------ PostgreSQL text

    @Test
    fun `the postgres process list excludes this backend and, by default, idle sessions`() {
        val busy = PostgresServerCatalog.SqlText.processList(includeIdle = false)
        assertTrue(busy.contains("pid <> pg_backend_pid()"))
        assertTrue(busy.contains("state IS DISTINCT FROM 'idle'"))
        assertTrue(busy.contains("backend_type = 'client backend'"))
        val all = PostgresServerCatalog.SqlText.processList(includeIdle = true)
        assertFalse(all.contains("'idle'"))
        // The columns the process cards read.
        for (column in listOf("\"Id\"", "\"User\"", "\"Host\"", "\"db\"", "\"Time\"", "\"State\"", "\"Info\"")) {
            assertTrue("$column is selected", busy.contains("AS $column"))
        }
    }

    @Test
    fun `postgres lock waits come from pg_blocking_pids and name both sessions`() {
        val sql = PostgresServerCatalog.SqlText.lockWaits
        assertTrue(sql.contains("pg_blocking_pids"))
        assertTrue(sql.contains("AS \"WaitingId\""))
        assertTrue(sql.contains("AS \"BlockingId\""))
    }

    @Test
    fun `postgres slow statements use the column names of the server's version and a closed sort list`() {
        val new = PostgresServerCatalog.SqlText.slow(SlowSort.TOTAL, version13 = true, limit = 25)
        assertTrue(new.contains("s.total_exec_time"))
        assertTrue(new.contains("ORDER BY s.total_exec_time DESC"))
        val old = PostgresServerCatalog.SqlText.slow(SlowSort.AVERAGE, version13 = false, limit = 25)
        assertTrue(old.contains("ORDER BY s.mean_time DESC"))
        assertTrue(PostgresServerCatalog.SqlText.slow(SlowSort.COUNT, true, 25).contains("ORDER BY s.calls DESC"))
        assertTrue(PostgresServerCatalog.SqlText.slow(SlowSort.COUNT, true, 5000).contains("LIMIT 100"))
    }

    @Test
    fun `a standby without sender_host columns (before PostgreSQL 11) still reads`() {
        assertTrue(PostgresServerCatalog.SqlText.standbyReplication(true).contains("r.sender_host"))
        assertFalse(PostgresServerCatalog.SqlText.standbyReplication(false).contains("r.sender_host"))
        // Idle primary: nothing received that is not replayed means no lag, not a growing one.
        assertTrue(
            PostgresServerCatalog.SqlText.standbyReplication(true)
                .contains("pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn()"),
        )
    }

    @Test
    fun `the postgres user list leaves out the predefined roles`() {
        assertTrue(PostgresServerCatalog.SqlText.users.contains("rolname !~ '^pg_'"))
    }

    // ------------------------------------------------------------------ SQL Server text

    @Test
    fun `the sql server process list excludes this session and, by default, sleeping ones`() {
        val busy = SqlServerServerCatalog.SqlText.processList(includeIdle = false)
        assertTrue(busy.contains("s.session_id <> @@SPID"))
        assertTrue(busy.contains("s.is_user_process = 1"))
        assertTrue(busy.contains("AND r.session_id IS NOT NULL"))
        assertFalse(SqlServerServerCatalog.SqlText.processList(includeIdle = true).contains("AND r.session_id IS NOT NULL"))
        for (column in listOf("AS Id", "AS [User]", "AS Host", "AS db", "AS [Time]", "AS State", "AS Info")) {
            assertTrue("$column is selected", busy.contains(column))
        }
    }

    @Test
    fun `sql server transactions name a sleeping session idle in transaction like postgres does`() {
        val sql = SqlServerServerCatalog.SqlText.transactions
        assertTrue(sql.contains("'idle in transaction'"))
        assertTrue(sql.contains("sys.dm_tran_session_transactions"))
        assertTrue(sql.contains("st.session_id <> @@SPID"))
    }

    @Test
    fun `sql server lock waits use blocking_session_id and ignore the negative markers`() {
        val sql = SqlServerServerCatalog.SqlText.lockWaits
        assertTrue(sql.contains("r.blocking_session_id > 0"))
        assertTrue(sql.contains("AS WaitingId"))
        assertTrue(sql.contains("AS BlockingId"))
    }

    @Test
    fun `sql server slow statements group by query hash and sort by a closed list`() {
        val total = SqlServerServerCatalog.SqlText.slow(SlowSort.TOTAL)
        assertTrue(total.contains("GROUP BY COALESCE(qs.query_hash, qs.sql_handle)"))
        assertTrue(total.contains("ORDER BY total_elapsed DESC"))
        assertTrue(SqlServerServerCatalog.SqlText.slow(SlowSort.AVERAGE).contains("ORDER BY avg_elapsed DESC"))
        assertTrue(SqlServerServerCatalog.SqlText.slow(SlowSort.COUNT).contains("ORDER BY executions DESC"))
    }

    @Test
    fun `the availability group query only names secondary_lag_seconds where the version has it`() {
        assertTrue(SqlServerServerCatalog.SqlText.replication(hasLag = true).contains("rs.secondary_lag_seconds"))
        assertFalse(SqlServerServerCatalog.SqlText.replication(hasLag = false).contains("rs.secondary_lag_seconds"))
    }

    @Test
    fun `the sql server sample asks for exactly the counters the pulse profile reads`() {
        val sql = SqlServerServerCatalog.SqlText.sample
        for (key in listOf(
            "batch_requests", "user_connections", "page_life_expectancy", "cache_hit", "cache_hit_base",
            "transactions", "lock_waits", "blocked", "uptime",
        )) {
            assertTrue("$key is selected", sql.contains("'$key'"))
        }
    }

    @Test
    fun `permissions are written as the statements that would grant them`() {
        assertEquals(
            "GRANT SELECT ON [dbo].[t] TO [app];",
            SqlServerServerCatalog.permissionLine("GRANT", "SELECT", " ON [dbo].[t]", "[app]"),
        )
        assertEquals(
            "GRANT VIEW SERVER STATE TO [mon] WITH GRANT OPTION;",
            SqlServerServerCatalog.permissionLine("GRANT_WITH_GRANT_OPTION", "VIEW SERVER STATE", "", "[mon]"),
        )
        assertEquals("DENY CONNECT SQL TO [x];", SqlServerServerCatalog.permissionLine("DENY", "CONNECT SQL", "", "[x]"))
    }
}
