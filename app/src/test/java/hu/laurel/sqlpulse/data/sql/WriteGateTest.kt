package hu.laurel.sqlpulse.data.sql

import hu.laurel.sqlpulse.data.connection.WriteUnlockStore
import hu.laurel.sqlpulse.data.db.ConnectionEntity
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class WriteGateTest {

    private val sessions = mockk<SqlSessionManager>()
    private val unlock = WriteUnlockStore()
    private val gate = WriteGate(sessions, unlock)

    private fun connect(environment: String, readOnly: Boolean = false) {
        every { sessions.currentConnection() } returns ConnectionEntity(
            id = 7, name = "c", color = "Amber", sshHost = "s", sshUser = "u", sshKeyId = null, dbHost = "h", database = "d", dbUser = "u",
            readOnly = readOnly, environment = environment,
        )
    }

    @Test
    fun `development connection may be written to`() {
        connect("DEVELOPMENT")
        gate.check()
    }

    @Test(expected = ReadOnlyConnectionException::class)
    fun `read-only connection is refused`() {
        connect("DEVELOPMENT", readOnly = true)
        gate.check()
    }

    @Test(expected = WritesLockedException::class)
    fun `locked production connection is refused`() {
        connect("PRODUCTION")
        gate.check()
    }

    @Test
    fun `unlocked production connection may be written to`() {
        connect("PRODUCTION")
        unlock.unlock(7)
        gate.check()
    }

    @Test
    fun `no session leaves the decision to the driver`() {
        every { sessions.currentConnection() } returns null
        gate.check()
    }
}
