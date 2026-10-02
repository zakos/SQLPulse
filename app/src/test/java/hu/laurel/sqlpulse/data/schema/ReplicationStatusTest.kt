package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplicationStatusTest {

    private fun replica(vararg pairs: Pair<String, String?>): Map<String, String?> = mapOf(
        "Replica_IO_Running" to "Yes",
        "Replica_SQL_Running" to "Yes",
        "Seconds_Behind_Source" to "0",
    ) + pairs

    private fun parseOne(row: Map<String, String?>) = ReplicationStatus.parse(listOf(row)).single()

    @Test
    fun `modern names are mapped`() {
        val channel = parseOne(
            replica(
                "Source_Host" to "db1.example", "Source_Port" to "3306", "Source_User" to "repl",
                "Retrieved_Gtid_Set" to "uuid:1-10", "Executed_Gtid_Set" to "uuid:1-9",
                "Source_Log_File" to "bin.000012", "Read_Source_Log_Pos" to "154",
                "Relay_Log_File" to "relay.000003", "Relay_Log_Pos" to "99", "Exec_Source_Log_Pos" to "150",
                "Replica_SQL_Running_State" to "Replica has read all relay log",
            ),
        )
        assertEquals("db1.example", channel.sourceHost)
        assertEquals(3306, channel.sourcePort)
        assertEquals("uuid:1-10", channel.retrievedGtidSet)
        assertEquals(154L, channel.readSourceLogPos)
        assertEquals(150L, channel.execSourceLogPos)
        assertEquals("Replica has read all relay log", channel.sqlState)
        assertEquals(ReplicationHealth.OK, channel.health)
    }

    @Test
    fun `legacy names are mapped`() {
        val channel = parseOne(
            mapOf(
                "Slave_IO_Running" to "Yes", "Slave_SQL_Running" to "Yes",
                "Seconds_Behind_Master" to "42", "Master_Host" to "old", "Master_Port" to "3307",
                "Master_Log_File" to "mysql-bin.000001", "Read_Master_Log_Pos" to "4",
                "Exec_Master_Log_Pos" to "4", "Slave_SQL_Running_State" to "Reading event",
            ),
        )
        assertEquals("old", channel.sourceHost)
        assertEquals(3307, channel.sourcePort)
        assertEquals(42L, channel.lagSeconds)
        assertEquals("mysql-bin.000001", channel.sourceLogFile)
        assertEquals(ReplicationHealth.LAGGING, channel.health)
    }

    @Test
    fun `mariadb all slaves status keeps connection name and gtid position`() {
        val channel = parseOne(
            mapOf(
                "Connection_name" to "branch-a", "Slave_IO_Running" to "Yes", "Slave_SQL_Running" to "Yes",
                "Seconds_Behind_Master" to "1", "Gtid_IO_Pos" to "0-1-77",
                "Slave_SQL_State" to "Slave has read all relay log",
            ),
        )
        assertEquals("branch-a", channel.name)
        assertEquals("0-1-77", channel.executedGtidSet)
        assertEquals("Slave has read all relay log", channel.sqlState)
    }

    @Test
    fun `column names match case-insensitively`() {
        val channel = parseOne(
            mapOf("replica_io_running" to "yes", "REPLICA_SQL_RUNNING" to "YES", "seconds_behind_source" to "3"),
        )
        assertEquals(ReplicationHealth.OK, channel.health)
    }

    @Test
    fun `lag thresholds`() {
        fun health(lag: String?) = parseOne(replica("Seconds_Behind_Source" to lag)).health
        assertEquals(ReplicationHealth.OK, health("30"))
        assertEquals(ReplicationHealth.LAGGING, health("31"))
        assertEquals(ReplicationHealth.LAGGING, health("300"))
        assertEquals(ReplicationHealth.FAR_BEHIND, health("301"))
    }

    @Test
    fun `null lag with running threads is unknown`() {
        val channel = parseOne(replica("Seconds_Behind_Source" to null))
        assertNull(channel.lagSeconds)
        assertEquals(ReplicationHealth.UNKNOWN, channel.health)
    }

    @Test
    fun `stopped thread without error is stopped`() {
        val sqlStopped = parseOne(replica("Replica_SQL_Running" to "No", "Seconds_Behind_Source" to null))
        assertEquals(ReplicationHealth.STOPPED, sqlStopped.health)
        assertEquals(ReplicationHealth.STOPPED, parseOne(replica("Replica_IO_Running" to "No")).health)
    }

    @Test
    fun `an error wins over everything`() {
        val channel = parseOne(
            replica("Replica_SQL_Running" to "No", "Last_SQL_Error" to "Duplicate entry '1'", "Last_SQL_Errno" to "1062"),
        )
        assertEquals(ReplicationHealth.ERROR, channel.health)
        assertEquals("Duplicate entry '1'", channel.lastError)
    }

    @Test
    fun `io error comes before sql error and errno alone counts`() {
        val both = parseOne(replica("Last_IO_Error" to "Cannot connect", "Last_SQL_Error" to "x"))
        assertEquals("Cannot connect", both.lastError)
        val numberOnly = parseOne(replica("Last_IO_Errno" to "2003"))
        assertEquals("error 2003", numberOnly.lastIoError)
        val zero = parseOne(replica("Last_IO_Errno" to "0", "Last_IO_Error" to ""))
        assertNull(zero.lastIoError)
    }

    @Test
    fun `connecting is unknown not stopped`() {
        assertEquals(ReplicationHealth.UNKNOWN, parseOne(replica("Replica_IO_Running" to "Connecting")).health)
    }

    @Test
    fun `configured delay is not counted as lag`() {
        val delayed = parseOne(replica("Seconds_Behind_Source" to "3610", "SQL_Delay" to "3600"))
        assertEquals(10L, delayed.effectiveLag)
        assertEquals(ReplicationHealth.OK, delayed.health)
    }

    @Test
    fun `multi-source channels are kept and the worst comes first`() {
        val channels = ReplicationStatus.sortedBySeverity(
            ReplicationStatus.parse(
                listOf(
                    replica("Channel_Name" to "a"),
                    replica("Channel_Name" to "b", "Replica_SQL_Running" to "No", "Last_SQL_Error" to "boom"),
                    replica("Channel_Name" to "c", "Seconds_Behind_Source" to "100"),
                ),
            ),
        )
        assertEquals(listOf("b", "c", "a"), channels.map { it.name })
    }

    @Test
    fun `statements per flavour`() {
        assertEquals(
            "SHOW ALL SLAVES STATUS",
            ReplicationStatus.statements(ServerVersion.parse("10.11.6-MariaDB")).first(),
        )
        assertEquals(
            listOf("SHOW REPLICA STATUS", "SHOW SLAVE STATUS"),
            ReplicationStatus.statements(ServerVersion.parse("8.0.39")),
        )
    }

    @Test
    fun `access denied codes`() {
        assertTrue(ReplicationStatus.isAccessDenied(1227))
        assertTrue(!ReplicationStatus.isAccessDenied(1064))
    }

    @Test
    fun `lag formatting`() {
        assertEquals("45 s", ReplicationStatus.formatSeconds(45))
        assertEquals("5 m 3 s", ReplicationStatus.formatSeconds(303))
        assertEquals("2 h 4 m", ReplicationStatus.formatSeconds(7440))
    }

    @Test
    fun `lag formatting in hungarian`() {
        val units = DurationUnits.HUNGARIAN
        val hu = java.util.Locale.forLanguageTag("hu-HU")
        assertEquals("45 mp", ReplicationStatus.formatSeconds(45, units, hu))
        assertEquals("5 p 3 mp", ReplicationStatus.formatSeconds(303, units, hu))
        assertEquals("2 ó 4 p", ReplicationStatus.formatSeconds(7440, units, hu))
        // No digit grouping inside a duration: "1 200 ó" would read as two numbers.
        assertEquals("1200 ó 0 p", ReplicationStatus.formatSeconds(1200L * 3600, units, hu))
    }
}
