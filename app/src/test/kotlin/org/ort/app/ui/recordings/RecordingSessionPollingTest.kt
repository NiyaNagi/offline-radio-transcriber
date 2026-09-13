package org.ort.app.ui.recordings

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
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.ort.pipeline.archive.SessionAudioDeletionRefusal
import org.ort.pipeline.archive.SessionAudioExportRefusal
import org.ort.pipeline.capture.CaptureState
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `Recording-Session.dc.html` (RC02): [RecordingSessionPolling] wired against the real
 * [org.ort.pipeline.archive.SessionAudioDeletionService], [org.ort.pipeline.archive
 * .SessionAudioExport] and [org.ort.pipeline.label.TransmissionLabelRepository] — a real
 * [OrtDatabase] and a real temp `filesDir`, never a stand-in (the same discipline
 * [org.ort.pipeline.archive.SessionAudioDeletionServiceTest] already holds itself to).
 */
@RunWith(RobolectricTestRunner::class)
class RecordingSessionPollingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    // [RecordingSessionPolling] always reads/writes through `context.applicationContext.filesDir`
    // (it takes no `filesDir` parameter of its own — the real app's own files directory is the one
    // fact it and every pipeline service it calls must agree on), so this test's own fixtures write
    // audio there too, never to a separate temp directory the code under test never sees.
    private val filesDir: File get() = context.applicationContext.filesDir

    @Before
    fun setUp() {
        db = OrtDatabase.create(context)
        CaptureState.idle(clearSession = true)
    }

    @After
    fun tearDown() {
        db.close()
        CaptureState.idle(clearSession = true)
        File(filesDir, "audio").deleteRecursively()
        File(filesDir, "archive").deleteRecursively()
    }

    private fun session(id: String, endedAt: Long? = 3_600_000L) = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = endedAt,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(
        id: String,
        sessionId: String,
        stationId: String? = "W7NPC",
        processingState: TransmissionState = TransmissionState.COMPLETE,
    ) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 3_000L,
        durationMs = 3_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_520_000L,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = 7.0,
        channelName = null,
        voiceprintId = null,
        attributionState = if (stationId != null) AttributionState.CONFIRMED else AttributionState.UNKNOWN,
        stationId = stationId,
        attributionConfidence = if (stationId != null) 0.9 else null,
        attributionSourceTransmissionId = null,
        processingState = processingState,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun writeOverAudio(sessionId: String, transmissionId: String, bytes: Int) {
        val dir = File(filesDir, "audio/$sessionId")
        dir.mkdirs()
        File(dir, "$transmissionId.flac").writeBytes(ByteArray(bytes))
    }

    @Test
    @Requirement("R-1051")
    fun `state is null for an unknown session, never a fabricated default`() = runBlocking {
        assertNull(RecordingSessionPolling.state(context, "no-such-session", playingTransmissionId = null))
    }

    @Test
    @Requirement("RC02", "FR-RUN-12")
    fun `state builds the real session's own header and over row from the database`() = runBlocking {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        writeOverAudio("S1", "T1", 300)

        val state = RecordingSessionPolling.state(context, "S1", playingTransmissionId = null)!!
        assertEquals("S1", state.sessionId)
        val row = state.rows.single() as RecordingSessionRow.Over
        assertEquals("W7NPC", row.callsign)
        assertEquals(RecordingSessionOverStatus.RESOLVED, row.status)
        assertTrue(row.hasAudio)
    }

    @Test
    @Requirement("FR-STO-3", "constitution VI")
    fun `deletePreview reports the real, current bytes for both halves combined`() = runBlocking {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        writeOverAudio("S1", "T1", 610)

        val preview = RecordingSessionPolling.deletePreview(context, "S1")
        assertTrue(preview is RecordingSessionDeleteState.Preview)
        assertEquals(610L, (preview as RecordingSessionDeleteState.Preview).bytesToFree)
    }

    @Test
    @Requirement("FR-STO-3", "constitution IV")
    fun `deletePreview refuses a session that is capturing right now`() = runBlocking {
        db.sessionDao().insert(session("S1", endedAt = null))
        CaptureState.capturing("S1")

        val preview = RecordingSessionPolling.deletePreview(context, "S1")
        assertEquals(
            RecordingSessionDeleteState.Refused(SessionAudioDeletionRefusal.SessionCapturing),
            preview,
        )
    }

    @Test
    @Requirement("FR-STO-3", "P9")
    fun `delete removes the real files and the session stays listed afterward, with what was removed`() = runBlocking {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        writeOverAudio("S1", "T1", 610)

        val result = RecordingSessionPolling.delete(context, "S1")
        assertTrue(result is RecordingSessionDeleteState.Deleted)
        val deleted = result as RecordingSessionDeleteState.Deleted
        assertEquals(610L, deleted.bytesFreed)
        assertTrue(deleted.overAudioRemovedAtMillis != null)

        // P9: the session row itself is never removed by a Delete — only its audio is.
        assertTrue(db.sessionDao().getById("S1") != null)
        assertFalse(File(filesDir, "audio/S1/T1.flac").exists())
    }

    @Test
    @Requirement("D40", "constitution VI")
    fun `exportPreview lists the real file and its own real total bytes`() = runBlocking {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        writeOverAudio("S1", "T1", 500)

        val preview = RecordingSessionPolling.exportPreview(context, "S1")
        assertTrue(preview is RecordingSessionExportState.Preview)
        val ready = preview as RecordingSessionExportState.Preview
        assertEquals(1, ready.fileCount)
        assertTrue(ready.totalBytes > 500L) // the real file plus the real manifest.json
        assertTrue(ready.suggestedFileName.endsWith(".zip"))
    }

    @Test
    @Requirement("D40")
    fun `exportWrite streams a real zip through the given stream, off the calling thread`() = runBlocking {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        writeOverAudio("S1", "T1", 500)

        val out = ByteArrayOutputStream()
        var lastProgress: Pair<Long, Long>? = null
        val result = RecordingSessionPolling.exportWrite(context, "S1", out) { written, total ->
            lastProgress = written to total
        }
        assertTrue(result is RecordingSessionExportState.Written)
        assertTrue(out.size() > 500) // a real zip, not an empty stream
        assertTrue(lastProgress != null && lastProgress!!.first == lastProgress!!.second)
    }

    @Test
    @Requirement("constitution II")
    fun `exportPreview refuses a session with nothing to export, naming the real reason`() = runBlocking {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        db.sessionDao().setOverAudioRemoved("S1", 1L) // over audio already removed; no archive ever kept.

        val result = RecordingSessionPolling.exportPreview(context, "S1")
        assertTrue(result is RecordingSessionExportState.Refused)
        assertTrue((result as RecordingSessionExportState.Refused).reason is SessionAudioExportRefusal.NothingToExport)
    }

    @Test
    @Requirement("FR-OBS-4", "constitution I")
    fun `a label round-trips through the real repository, and never touches attribution`() = runBlocking {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1", stationId = null))

        var sheet = RecordingSessionPolling.labelSheetState(context, "T1", "unknown station")
        assertFalse(sheet.markedForTraining)
        assertNull(sheet.rating)

        RecordingSessionPolling.setMarkedForTraining(context, "T1", true)
        RecordingSessionPolling.setRating(context, "T1", "good")

        sheet = RecordingSessionPolling.labelSheetState(context, "T1", "unknown station")
        assertTrue(sheet.markedForTraining)
        assertEquals("good", sheet.rating)

        // The label never promoted this transmission's own attribution -- it stays UNKNOWN.
        val transmission = db.transmissionDao().listBySession("S1").single()
        assertEquals(AttributionState.UNKNOWN, transmission.attributionState)
        assertNull(transmission.stationId)
    }

    @Test
    @Requirement("FR-RUN-9")
    fun `retry requeues the real failed work-queue item for that transmission`() = runBlocking {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1", processingState = TransmissionState.FAILED))
        db.workQueueDao().insert(
            WorkQueueItemEntity(
                transmissionId = "T1",
                pass = PassId.B_OFFLINE,
                state = WorkQueueState.FAILED,
                priority = 0,
                attemptCount = 5,
                lastError = "boom",
                enqueuedAt = 0L,
            ),
        )

        val retried = RecordingSessionPolling.retry(context, "T1")
        assertTrue(retried)

        val item = db.workQueueDao().findByTransmissionAndPass("T1", PassId.B_OFFLINE.name).single()
        assertEquals(WorkQueueState.READY, item.state)
    }

    @Test
    @Requirement("FR-RUN-9")
    fun `retry against a transmission with no failed item does nothing, never throws`() = runBlocking {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        assertFalse(RecordingSessionPolling.retry(context, "T1"))
    }
}
