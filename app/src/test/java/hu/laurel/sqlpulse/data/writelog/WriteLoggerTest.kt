package hu.laurel.sqlpulse.data.writelog

import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.db.WriteLogDao
import hu.laurel.sqlpulse.data.db.WriteLogEntity
import hu.laurel.sqlpulse.data.settings.Settings
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WriteLoggerTest {

    private val connection = ConnectionEntity(
        id = 2, name = "Éles", color = "Production", sshHost = "j", sshUser = "u", sshKeyId = null,
        dbHost = "h", database = "d", dbUser = "u", environment = "PRODUCTION",
    )
    private val sessions = mockk<SqlSessionManager> {
        every { currentConnection() } returns connection
        every { database } returns MutableStateFlow<String?>("billing")
    }
    private val settingsRepository = mockk<SettingsRepository> {
        every { settings } returns flowOf(Settings(writeLogDays = 30))
    }
    private val dao = mockk<WriteLogDao>(relaxed = true)
    private val logger = WriteLogger(sessions, dao, settingsRepository, UnconfinedTestDispatcher())

    private suspend fun record(failure: Throwable? = null) = logger.record(
        source = WriteSource.RESULT_EDIT, statement = "UPDATE t SET a = 1 WHERE id = 1", affectedRows = 1,
        failure = failure, startedAt = 10, durationMs = 5, inTransaction = false,
    )

    @Test
    fun `an entry is inserted with the live connection and database, then retention runs`() = runTest {
        val saved = mutableListOf<WriteLogEntity>()
        coEvery { dao.insert(capture(saved)) } returns 1

        record()

        assertEquals("billing", saved.single().database)
        assertEquals("PRODUCTION", saved.single().environment)
        assertEquals("RESULT_EDIT", saved.single().source)
        coVerify(exactly = 1) { dao.deleteOlderThan(any()) }
        coVerify(exactly = 1) { dao.trimToNewest(WriteLogRetention.MAX_ENTRIES) }
    }

    @Test
    fun `a database that cannot be written to never fails the write`() = runTest {
        coEvery { dao.insert(any()) } throws IllegalStateException("database is locked")
        record()
        record(failure = RuntimeException("the write itself failed"))
        // Reaching here without an exception is the assertion.
        coVerify(exactly = 2) { dao.insert(any()) }
    }

    @Test
    fun `a failing retention step is also swallowed`() = runTest {
        coEvery { dao.trimToNewest(any()) } throws IllegalStateException("disk full")
        record()
    }

    @Test
    fun `a broken session lookup is swallowed too`() = runTest {
        every { sessions.currentConnection() } throws IllegalStateException("closed")
        record()
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        coEvery { dao.insert(any()) } throws CancellationException("scope cancelled")
        try {
            record()
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertTrue(e.message!!.contains("cancelled"))
        }
    }
}
