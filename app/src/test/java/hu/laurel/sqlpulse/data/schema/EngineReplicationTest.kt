package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineReplicationTest {

    private fun pgRow(
        state: String = "streaming",
        replay: String? = "0.002",
        name: String = "standby1",
    ) = mapOf<String, String?>(
        "application_name" to name, "client_addr" to "10.0.0.5", "client_port" to "51234", "usename" to "repl",
        "state" to state, "sync_state" to "async", "write_lag" to "0.001", "flush_lag" to "0.002",
        "replay_lag" to replay, "replay_bytes" to "2048",
    )

    @Test
    fun `a streaming standby with a small lag is healthy and is drawn as a downstream peer`() {
        val channel = PostgresReplication.primary(listOf(pgRow())).single()
        assertEquals(ReplicationHealth.OK, channel.health)
        assertEquals("standby1", channel.name)
        assertEquals("10.0.0.5", channel.sourceHost)
        assertEquals(51234, channel.sourcePort)
        assertTrue(channel.peerIsDownstream)
        assertFalse(channel.showThreads)
        assertEquals(0L, channel.lagSeconds)
        val details = channel.details.associate { it.label to it.value }
        assertEquals("streaming", details["state"])
        assertEquals("async", details["sync_state"])
        assertEquals("2 ms", details["replay_lag"])
        assertEquals("1 ms", details["write_lag"])
    }

    @Test
    fun `lag thresholds are the same as for MySQL`() {
        assertEquals(ReplicationHealth.LAGGING, PostgresReplication.primary(listOf(pgRow(replay = "45"))).single().health)
        assertEquals(ReplicationHealth.FAR_BEHIND, PostgresReplication.primary(listOf(pgRow(replay = "600"))).single().health)
    }

    @Test
    fun `a streaming standby whose lag is NULL has simply nothing left to replay`() {
        val channel = PostgresReplication.primary(listOf(pgRow(replay = null))).single()
        assertEquals(ReplicationHealth.OK, channel.health)
        assertNull(channel.lagSeconds)
    }

    @Test
    fun `catchup is lagging and an unknown state is a stopped replica`() {
        assertEquals(ReplicationHealth.LAGGING, PostgresReplication.primary(listOf(pgRow(state = "catchup"))).single().health)
        assertEquals(ReplicationHealth.UNKNOWN, PostgresReplication.primary(listOf(pgRow(state = "startup"))).single().health)
        assertEquals(ReplicationHealth.STOPPED, PostgresReplication.primary(listOf(pgRow(state = "stopping"))).single().health)
    }

    @Test
    fun `the cards sort the worst standby first`() {
        val sorted = ReplicationStatus.sortedBySeverity(
            PostgresReplication.primary(listOf(pgRow(name = "ok"), pgRow(name = "bad", state = "stopping"))),
        )
        assertEquals(listOf("bad", "ok"), sorted.map { it.name })
    }

    @Test
    fun `a standby reads the receiver and replay lag`() {
        val channel = PostgresReplication.standby(
            mapOf(
                "status" to "streaming", "sender_host" to "primary.example", "sender_port" to "5432",
                "slot_name" to "slot1", "replay_lag" to "12.7", "receive_lsn" to "0/3000060", "replay_lsn" to "0/3000028",
            ),
        )
        assertEquals(ReplicationHealth.OK, channel.health)
        assertEquals(13L, channel.lagSeconds)
        assertEquals("primary.example", channel.sourceHost)
        assertEquals(5432, channel.sourcePort)
        assertFalse(channel.peerIsDownstream)
    }

    @Test
    fun `a standby with no receiver row status is unknown`() {
        val channel = PostgresReplication.standby(mapOf("status" to null, "replay_lag" to "0"))
        assertEquals(ReplicationHealth.UNKNOWN, channel.health)
    }

    private fun mssqlRow(health: String, state: String = "SYNCHRONIZED", lag: String? = "0", local: String = "0") =
        mapOf<String, String?>(
            "replica_server_name" to "SQL2", "database_name" to "Sales", "is_local" to local,
            "synchronization_state_desc" to state, "synchronization_health_desc" to health,
            "log_send_queue_size" to "10", "redo_queue_size" to "20", "secondary_lag_seconds" to lag,
            "availability_mode_desc" to "SYNCHRONOUS_COMMIT", "last_commit_time" to "2026-10-03 12:00:00",
        )

    @Test
    fun `an availability group replica takes the engine's own health word`() {
        assertEquals(ReplicationHealth.OK, SqlServerReplication.parse(listOf(mssqlRow("HEALTHY"))).single().health)
        assertEquals(ReplicationHealth.LAGGING, SqlServerReplication.parse(listOf(mssqlRow("PARTIALLY_HEALTHY"))).single().health)
        assertEquals(ReplicationHealth.STOPPED, SqlServerReplication.parse(listOf(mssqlRow("NOT_HEALTHY"))).single().health)
        assertEquals(ReplicationHealth.UNKNOWN, SqlServerReplication.parse(listOf(mssqlRow("whatever"))).single().health)
    }

    @Test
    fun `a healthy replica that is far behind is still flagged by its lag`() {
        assertEquals(ReplicationHealth.FAR_BEHIND, SqlServerReplication.parse(listOf(mssqlRow("HEALTHY", lag = "900"))).single().health)
    }

    @Test
    fun `an availability group channel is named replica slash database and shows its queues`() {
        val channel = SqlServerReplication.parse(listOf(mssqlRow("HEALTHY"))).single()
        assertEquals("SQL2 / Sales", channel.name)
        assertTrue(channel.peerIsDownstream)
        val details = channel.details.associate { it.label to it.value }
        assertEquals("SYNCHRONOUS_COMMIT", details["availability_mode"])
        assertTrue(details.containsKey("log_send_queue"))
        assertFalse(SqlServerReplication.parse(listOf(mssqlRow("HEALTHY", local = "1"))).single().peerIsDownstream)
    }

    @Test
    fun `lag text picks a readable unit`() {
        assertEquals("—", lagText(null))
        assertEquals("3 ms", lagText(0.003))
        assertEquals("1.5 s", lagText(1.5))
        assertEquals("2m 5s", lagText(125.0))
    }

    @Test
    fun `a role is written as the CREATE ROLE that would recreate it`() {
        assertEquals(
            "CREATE ROLE \"app\" WITH LOGIN CREATEDB NOINHERIT CONNECTION LIMIT 5 VALID UNTIL '2027-01-01 00:00:00+00';",
            PostgresRoles.createRole(
                "app", superuser = false, inherit = false, createRole = false, createDb = true, login = true,
                replication = false, bypassRls = false, connectionLimit = 5, validUntil = "2027-01-01 00:00:00+00",
            ),
        )
        assertEquals(
            "CREATE ROLE \"a\"\"b\" WITH NOLOGIN SUPERUSER REPLICATION BYPASSRLS;",
            PostgresRoles.createRole(
                "a\"b", superuser = true, inherit = true, createRole = false, createDb = false, login = false,
                replication = true, bypassRls = true, connectionLimit = -1, validUntil = null,
            ),
        )
    }
}
