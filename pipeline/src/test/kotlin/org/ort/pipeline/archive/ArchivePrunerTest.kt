package org.ort.pipeline.archive

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.pipeline.PipelineTestFixtures
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * FR-STO-3d, AC-150, AC-151, D39. Closes on a real [OrtDatabase]/[org.ort.data.dao.SessionDao],
 * never a stand-in — see this package's report.
 */
@RunWith(RobolectricTestRunner::class)
class ArchivePrunerTest {

    private fun tempFilesDir(): File = Files.createTempDirectory("archive-pruner-test").toFile()

    private fun writeArchiveBytes(filesDir: File, sessionId: String, bytes: Int) {
        val dir = File(filesDir, "archive/$sessionId")
        dir.mkdirs()
        File(dir, "chunk-0.flac").writeBytes(ByteArray(bytes))
    }

    private fun writeOverAudioBytes(filesDir: File, sessionId: String, bytes: Int) {
        val dir = File(filesDir, "audio/$sessionId")
        dir.mkdirs()
        File(dir, "TX1.flac").writeBytes(ByteArray(bytes))
    }

    @Test
    @Requirement("AC-150", "FR-STO-3d")
    fun `AC_150 reaching the archive budget prunes the oldest archive interval first and leaves the newest`() =
        runBlocking {
            val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
            val filesDir = tempFilesDir()
            db.sessionDao().insert(PipelineTestFixtures.session(id = "OLD").copy(startedAt = 0L))
            db.sessionDao().insert(PipelineTestFixtures.session(id = "NEW").copy(startedAt = 1_000L))
            db.sessionDao().setArchiveKept("OLD")
            db.sessionDao().setArchiveKept("NEW")
            writeArchiveBytes(filesDir, "OLD", 100)
            writeArchiveBytes(filesDir, "NEW", 100)

            val pruned =
                pruneArchiveIfOverBudget(db, filesDir, budgetBytes = 150L, clock = TestClock(startWallMillis = 9_999L))

            assertEquals(listOf("OLD"), pruned)
            assertFalse(File(filesDir, "archive/OLD").exists())
            assertTrue(File(filesDir, "archive/NEW").exists())
            val states = archiveSessionStates(db).associateBy { it.sessionId }
            assertEquals(ArchiveState.REMOVED, states.getValue("OLD").state)
            assertEquals(9_999L, states.getValue("OLD").removedAtMillis)
            assertEquals(ArchiveState.KEPT, states.getValue("NEW").state)
        }

    @Test
    @Requirement("AC-150", "FR-STO-3d")
    fun `AC_150 over-audio directories are never touched while pruning the archive`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1").copy(startedAt = 0L))
        db.sessionDao().setArchiveKept("S1")
        writeArchiveBytes(filesDir, "S1", 500)
        writeOverAudioBytes(filesDir, "S1", 500)

        pruneArchiveIfOverBudget(db, filesDir, budgetBytes = 0L)

        assertFalse("the archive directory must be pruned", File(filesDir, "archive/S1").exists())
        assertTrue(
            "over audio must never be touched pruning the archive (FR-STO-3d)",
            File(filesDir, "audio/S1").exists(),
        )
    }

    @Test
    @Requirement("FR-STO-3d")
    fun `nothing is pruned while total archive usage is at or under the budget`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1").copy(startedAt = 0L))
        db.sessionDao().setArchiveKept("S1")
        writeArchiveBytes(filesDir, "S1", 100)

        val pruned = pruneArchiveIfOverBudget(db, filesDir, budgetBytes = 100L)

        assertEquals(emptyList<String>(), pruned)
        assertTrue(File(filesDir, "archive/S1").exists())
        assertEquals(ArchiveState.KEPT, archiveSessionStates(db).single().state)
    }

    @Test
    @Requirement("AC-151", "FR-STO-3d")
    fun `AC_151 a pruned session stays listed with its date rather than disappearing from the record`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1").copy(startedAt = 0L))
        db.sessionDao().setArchiveKept("S1")
        writeArchiveBytes(filesDir, "S1", 500)

        pruneArchiveIfOverBudget(db, filesDir, budgetBytes = 0L, clock = TestClock(startWallMillis = 42_000L))

        val session = db.sessionDao().getById("S1")
        assertTrue("the session row itself must still exist", session != null)
        assertEquals("REMOVED", session!!.archiveState)
        assertEquals(42_000L, session.archiveRemovedAtMillis)
    }

    @Test
    @Requirement("FR-SEG-9")
    fun `a session with no archive reads NONE, never fabricated as KEPT`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))

        val state = archiveSessionStates(db).single()
        assertEquals(ArchiveState.NONE, state.state)
        assertFalse(state.resegmentable)
    }

    @Test
    @Requirement("FR-SEG-9", "AC-151", "R-1038")
    fun `R_1038 a KEPT session with a recorded hole is not resegmentable, honestly`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.sessionDao().setArchiveKept("S1")
        db.archiveGapDao().insert(
            org.ort.data.entity.ArchiveGapEntity(
                id = "AG1",
                sessionId = "S1",
                startSample = 0L,
                sampleCount = 16_000L,
                reason = "archive_queue_overflow",
                recordedAtMillis = 0L,
            ),
        )

        val state = archiveSessionStates(db).single()
        assertEquals(ArchiveState.KEPT, state.state)
        assertTrue(state.hasGaps)
        assertFalse(
            "an archive with a recorded hole is present but not complete -- not honestly resegmentable",
            state.resegmentable,
        )
    }

    @Test
    @Requirement("FR-SEG-9")
    fun `a KEPT session with no recorded hole is resegmentable`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.sessionDao().setArchiveKept("S1")

        val state = archiveSessionStates(db).single()
        assertFalse(state.hasGaps)
        assertTrue(state.resegmentable)
    }
}
