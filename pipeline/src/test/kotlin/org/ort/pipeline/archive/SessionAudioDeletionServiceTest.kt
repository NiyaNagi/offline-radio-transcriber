package org.ort.pipeline.archive

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.PassId
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.pipeline.PipelineTestFixtures
import org.ort.pipeline.capture.CaptureState
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * FR-STO-3, FR-STO-3b, FR-STO-3e, D40, P9. Closes on a real [OrtDatabase], never a stand-in — see
 * this package's report.
 */
@RunWith(RobolectricTestRunner::class)
class SessionAudioDeletionServiceTest {

    private fun tempFilesDir(): File = Files.createTempDirectory("session-audio-deletion-test").toFile()

    private fun writeOverAudioBytes(filesDir: File, sessionId: String, bytes: Int) {
        val dir = File(filesDir, "audio/$sessionId")
        dir.mkdirs()
        File(dir, "TX1.flac").writeBytes(ByteArray(bytes))
    }

    private fun writeArchiveBytes(filesDir: File, sessionId: String, bytes: Int) {
        val dir = File(filesDir, "archive/$sessionId")
        dir.mkdirs()
        File(dir, "chunk-0.flac").writeBytes(ByteArray(bytes))
    }

    @Before
    fun resetCaptureState() {
        CaptureState.idle(clearSession = true)
    }

    @After
    fun tearDownCaptureState() {
        CaptureState.idle(clearSession = true)
    }

    // -- preview ------------------------------------------------------------------------------

    @Test
    @Requirement("FR-STO-3")
    fun `preview reports real measured bytes for both over audio and archive`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.sessionDao().setArchiveKept("S1")
        writeOverAudioBytes(filesDir, "S1", 300)
        writeArchiveBytes(filesDir, "S1", 700)

        val preview = SessionAudioDeletionService.preview(db, filesDir, "S1")!!

        assertEquals(300L, preview.overAudioBytes)
        assertEquals(700L, preview.archiveBytes)
        assertFalse(preview.overAudioAlreadyRemoved)
        assertEquals(ArchiveState.KEPT, preview.archiveState)
        assertEquals(1000L, preview.bytesFor(SessionAudioTarget.BOTH))
    }

    @Test
    @Requirement("FR-STO-3")
    fun `preview of an unknown session is null, never fabricated`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        assertNull(SessionAudioDeletionService.preview(db, tempFilesDir(), "no-such-session"))
    }

    // -- refusals -------------------------------------------------------------------------------

    @Test
    @Requirement("FR-STO-3")
    fun `canDelete refuses a session that is currently capturing`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        CaptureState.capturing("S1")

        val refusal = SessionAudioDeletionService.canDelete(db, "S1")

        assertEquals(SessionAudioDeletionRefusal.SessionCapturing, refusal)
    }

    @Test
    @Requirement("FR-STO-3")
    fun `canDelete does not refuse when a different session is the one capturing`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        CaptureState.capturing("SOME-OTHER-SESSION")

        assertNull(SessionAudioDeletionService.canDelete(db, "S1"))
    }

    @Test
    @Requirement("FR-STO-3")
    fun `canDelete refuses a session with an active work-queue item`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1", sessionId = "S1"))
        WorkQueue(db, TestClock()).enqueue("TX1", PassId.B_OFFLINE)

        val refusal = SessionAudioDeletionService.canDelete(db, "S1")

        assertEquals(SessionAudioDeletionRefusal.ProcessingInProgress, refusal)
    }

    @Test
    @Requirement("FR-STO-3")
    fun `canDelete refuses an unknown session`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        assertEquals(
            SessionAudioDeletionRefusal.SessionNotFound,
            SessionAudioDeletionService.canDelete(db, "no-such-session"),
        )
    }

    @Test
    @Requirement("FR-STO-3")
    fun `canDelete allows a session that is neither capturing nor being processed`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))

        assertNull(SessionAudioDeletionService.canDelete(db, "S1"))
    }

    @Test
    @Requirement("FR-STO-3")
    fun `a refused deletion never touches the audio directory`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        writeOverAudioBytes(filesDir, "S1", 100)
        CaptureState.capturing("S1")

        val result = SessionAudioDeletionService.delete(db, filesDir, "S1", SessionAudioTarget.OVER_AUDIO)

        assertEquals(SessionAudioDeletionResult.Refused(SessionAudioDeletionRefusal.SessionCapturing), result)
        assertTrue("a refused deletion must never touch the files", File(filesDir, "audio/S1").exists())
        assertNull(db.sessionDao().getById("S1")!!.overAudioRemovedAtMillis)
    }

    // -- deletion, per target ---------------------------------------------------------------------

    @Test
    @Requirement("FR-STO-3")
    fun `deleting OVER_AUDIO marks the session, removes the directory, and reports real bytes freed`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1", sessionId = "S1"))
        writeOverAudioBytes(filesDir, "S1", 500)
        val clock = TestClock(startWallMillis = 4_242L)

        val result = SessionAudioDeletionService.delete(
            db,
            filesDir,
            "S1",
            SessionAudioTarget.OVER_AUDIO,
            clock,
        ) as SessionAudioDeletionResult.Deleted

        assertEquals(500L, result.bytesFreed)
        assertEquals(4_242L, result.overAudioRemovedAtMillis)
        assertNull(result.archiveRemovedAtMillis)
        assertFalse("the over-audio directory must be gone", File(filesDir, "audio/S1").exists())
        assertEquals(4_242L, db.sessionDao().getById("S1")!!.overAudioRemovedAtMillis)

        // P9: the session and its over are still listed, transcripts/attributions untouched.
        assertTrue("the session row must still exist", db.sessionDao().getById("S1") != null)
        assertTrue("the over row must still exist", db.transmissionDao().getById("TX1") != null)
    }

    @Test
    @Requirement("FR-STO-3d")
    fun `deleting RAW_ARCHIVE reuses the existing archiveState write path`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.sessionDao().setArchiveKept("S1")
        writeArchiveBytes(filesDir, "S1", 900)
        val clock = TestClock(startWallMillis = 8_800L)

        val result = SessionAudioDeletionService.delete(
            db,
            filesDir,
            "S1",
            SessionAudioTarget.RAW_ARCHIVE,
            clock,
        ) as SessionAudioDeletionResult.Deleted

        assertEquals(900L, result.bytesFreed)
        assertEquals(8_800L, result.archiveRemovedAtMillis)
        assertNull(result.overAudioRemovedAtMillis)
        assertFalse(File(filesDir, "archive/S1").exists())
        val session = db.sessionDao().getById("S1")!!
        assertEquals("REMOVED", session.archiveState)
        assertEquals(8_800L, session.archiveRemovedAtMillis)
    }

    @Test
    @Requirement("FR-STO-3")
    fun `deleting RAW_ARCHIVE on a session with no archive is a harmless no-op`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))

        val result = SessionAudioDeletionService.delete(db, filesDir, "S1", SessionAudioTarget.RAW_ARCHIVE)
            as SessionAudioDeletionResult.Deleted

        assertEquals(0L, result.bytesFreed)
        assertNull("never fabricate a removal for an archive that never existed", result.archiveRemovedAtMillis)
        assertNull(db.sessionDao().getById("S1")!!.archiveState)
    }

    @Test
    @Requirement("FR-STO-3")
    fun `deleting BOTH removes over audio and archive together`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        db.sessionDao().setArchiveKept("S1")
        writeOverAudioBytes(filesDir, "S1", 200)
        writeArchiveBytes(filesDir, "S1", 300)

        val result = SessionAudioDeletionService.delete(db, filesDir, "S1", SessionAudioTarget.BOTH)
            as SessionAudioDeletionResult.Deleted

        assertEquals(500L, result.bytesFreed)
        assertFalse(File(filesDir, "audio/S1").exists())
        assertFalse(File(filesDir, "archive/S1").exists())
    }

    // -- crash safety and idempotency --------------------------------------------------------------

    /**
     * Simulates a crash between the mark step and the file-removal step: the session row is
     * already marked (as [org.ort.pipeline.archive.SessionAudioDeletionService.delete]'s first
     * half would have left it), but the directory was never actually deleted. A retry must find a
     * consistent, recoverable state — finish the deletion, keep the *original* removal timestamp,
     * and report the real bytes that are only freed *now*.
     */
    @Test
    @Requirement("FR-STO-3b")
    fun `retrying after a crash between marking and deleting finishes the deletion and keeps the original timestamp`() =
        runBlocking {
            val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
            val filesDir = tempFilesDir()
            db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
            writeOverAudioBytes(filesDir, "S1", 640)
            // The "crash" left the mark written but the directory untouched.
            db.sessionDao().setOverAudioRemoved("S1", removedAtMillis = 1_111L)
            assertTrue("simulated crash: the directory must still be there", File(filesDir, "audio/S1").exists())

            val result = SessionAudioDeletionService.delete(
                db,
                filesDir,
                "S1",
                SessionAudioTarget.OVER_AUDIO,
                TestClock(startWallMillis = 9_999L),
            ) as SessionAudioDeletionResult.Deleted

            assertFalse("the retry must finish the deletion", File(filesDir, "audio/S1").exists())
            assertEquals(640L, result.bytesFreed) // the real bytes, freed only by this retry
            assertEquals(
                "the original mark timestamp must never be overwritten by a retry",
                1_111L,
                result.overAudioRemovedAtMillis,
            )
            assertEquals(1_111L, db.sessionDao().getById("S1")!!.overAudioRemovedAtMillis)
        }

    @Test
    @Requirement("FR-STO-3b")
    fun `deleting the same target twice is idempotent and the second call frees nothing more`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        writeOverAudioBytes(filesDir, "S1", 128)
        val clock = TestClock(startWallMillis = 500L)

        val first = SessionAudioDeletionService.delete(db, filesDir, "S1", SessionAudioTarget.OVER_AUDIO, clock)
            as SessionAudioDeletionResult.Deleted
        clock.jumpWallTo(999_999L)
        val second = SessionAudioDeletionService.delete(db, filesDir, "S1", SessionAudioTarget.OVER_AUDIO, clock)
            as SessionAudioDeletionResult.Deleted

        assertEquals(128L, first.bytesFreed)
        assertEquals(0L, second.bytesFreed)
        assertEquals(
            "a repeat call must never move the removal date",
            first.overAudioRemovedAtMillis,
            second.overAudioRemovedAtMillis,
        )
    }
}
