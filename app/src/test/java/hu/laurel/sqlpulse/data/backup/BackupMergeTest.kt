package hu.laurel.sqlpulse.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergeTest {

    private val payload = BackupPayload(
        connections = listOf(
            BackupSamples.connection("reports"),
            BackupSamples.connection("staging"),
        ),
    )

    @Test
    fun `nothing conflicts on an empty device`() {
        assertTrue(BackupMerge.conflicts(payload, emptyList()).isEmpty())
        val plan = BackupMerge.plan(payload, emptyList())
        assertEquals(listOf("reports", "staging"), plan.map { it.finalName })
        assertTrue(plan.none { it.conflicting })
        assertTrue(plan.all { it.imports })
    }

    @Test
    fun `a name already in use is reported as a conflict`() {
        assertEquals(listOf("staging"), BackupMerge.conflicts(payload, listOf("staging", "other")))
    }

    @Test
    fun `keep both renames the imported connection and leaves the existing one`() {
        val plan = BackupMerge.plan(payload, listOf("staging"), MergeResolution.KEEP_BOTH)
        val staging = plan.single { it.sourceName == "staging" }
        assertEquals("staging (imported)", staging.finalName)
        assertTrue(staging.imports)
        assertTrue(!staging.replacesExisting)
        // The one that did not clash keeps its own name.
        assertEquals("reports", plan.single { it.sourceName == "reports" }.finalName)
    }

    @Test
    fun `keep both counts up when the renamed name is taken too`() {
        val plan = BackupMerge.plan(
            payload,
            listOf("staging", "staging (imported)", "staging (imported) 2"),
            MergeResolution.KEEP_BOTH,
        )
        assertEquals("staging (imported) 3", plan.single { it.sourceName == "staging" }.finalName)
    }

    @Test
    fun `two connections of the same name in one file do not collide`() {
        val twice = BackupPayload(
            connections = listOf(
                BackupSamples.connection("reports"),
                BackupSamples.connection("reports"),
                BackupSamples.connection("reports"),
            ),
        )
        val plan = BackupMerge.plan(twice, emptyList())
        assertEquals(
            listOf("reports", "reports (imported)", "reports (imported) 2"),
            plan.map { it.finalName },
        )
    }

    @Test
    fun `replace keeps the name and marks the existing one for removal`() {
        val plan = BackupMerge.plan(payload, listOf("staging"), MergeResolution.REPLACE)
        val staging = plan.single { it.sourceName == "staging" }
        assertEquals("staging", staging.finalName)
        assertTrue(staging.imports)
        assertTrue(staging.replacesExisting)
    }

    @Test
    fun `skip imports nothing for that connection`() {
        val plan = BackupMerge.plan(payload, listOf("staging"), MergeResolution.SKIP)
        val staging = plan.single { it.sourceName == "staging" }
        assertTrue(!staging.imports)
        assertTrue(!staging.replacesExisting)
        assertEquals("staging", staging.finalName)
    }

    @Test
    fun `a per-connection answer overrides the answer given for all`() {
        val plan = BackupMerge.plan(
            payload,
            listOf("reports", "staging"),
            defaultResolution = MergeResolution.SKIP,
            perConnection = mapOf("reports" to MergeResolution.REPLACE),
        )
        assertTrue(plan.single { it.sourceName == "reports" }.replacesExisting)
        assertTrue(!plan.single { it.sourceName == "staging" }.imports)
    }

    @Test
    fun `the answer given for all does not touch connections that do not clash`() {
        val plan = BackupMerge.plan(payload, listOf("staging"), MergeResolution.SKIP)
        assertTrue(plan.single { it.sourceName == "reports" }.imports)
    }

    @Test
    fun `a skipped conflict leaves its name free for a later keep-both`() {
        val three = BackupPayload(
            connections = listOf(
                BackupSamples.connection("staging"),
                BackupSamples.connection("staging"),
            ),
        )
        val plan = BackupMerge.plan(
            three,
            listOf("staging"),
            defaultResolution = MergeResolution.KEEP_BOTH,
        )
        assertEquals(
            listOf("staging (imported)", "staging (imported) 2"),
            plan.map { it.finalName },
        )
    }

    @Test
    fun `an empty backup plans nothing`() {
        assertTrue(BackupMerge.plan(BackupPayload(), listOf("staging")).isEmpty())
    }
}
