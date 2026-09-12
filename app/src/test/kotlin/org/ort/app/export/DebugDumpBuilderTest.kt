package org.ort.app.export

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.WorkAttemptEntity
import org.ort.data.entity.WorkAttemptOutcome
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1009 (WPX) — the debug dump: "An NDJSON export of sessions, overs, attributions,
 * gaps and pass outcomes, so a failed field session is analysable on the workstation... Include
 * the WorkQueueItemEntity attempt history and lastError, because that is exactly what nineteen
 * silently-failed overs looked like from the outside this week." Constitution VI: every line
 * carries its own provenance.
 */
@RunWith(RobolectricTestRunner::class)
class DebugDumpBuilderTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    private fun session(id: String) = SessionEntity(
        id = id,
        startedAt = 1_000L,
        endedAt = 2_000L,
        profileId = null,
        deviceTier = "MID",
        appVersion = "0.1.1",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(id: String, sessionId: String) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = 1_757_477_520_000L,
        endedAtUtc = 1_757_477_525_000L,
        durationMs = 5_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_520_000L,
        frequencyProvenance = "measured",
        mode = "FM",
        signalStrength = null,
        channelName = "Repeater 1",
        voiceprintId = null,
        attributionState = AttributionState.UNKNOWN,
        stationId = null,
        attributionConfidence = null,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = "cal-1",
        executionProvider = "cpu",
    )

    private fun readLines(bytes: ByteArray): List<JSONObject> =
        bytes.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { JSONObject(it) }

    @Test
    fun `R_1009 the first line is a meta record carrying schema version and export time`() = runTest {
        val lines = readLines(DebugDumpBuilder.build(context))
        val meta = lines.first()
        assertEquals("meta", meta.getString("type"))
        assertEquals(OrtDatabase.SCHEMA_VERSION, meta.getInt("schemaVersion"))
        assertTrue(meta.has("exportedAtUtc"))
        assertTrue(meta.has("appVersion"))
    }

    @Test
    fun `R_1009 every session becomes its own real line`() = runTest {
        db.sessionDao().insert(session("S1"))
        val lines = readLines(DebugDumpBuilder.build(context))
        val sessionLine = lines.first { it.optString("type") == "session" && it.getString("id") == "S1" }
        assertEquals(1_000L, sessionLine.getLong("startedAt"))
        assertEquals(2_000L, sessionLine.getLong("endedAt"))
        assertEquals("0.1.1", sessionLine.getString("appVersion"))
    }

    @Test
    fun `R_1009 every over carries its real attribution state and processing provenance`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        val lines = readLines(DebugDumpBuilder.build(context))
        val overLine = lines.first { it.optString("type") == "over" && it.getString("id") == "T1" }
        assertEquals("UNKNOWN", overLine.getString("attributionState"))
        assertEquals("cpu", overLine.getString("executionProvider"))
        assertEquals("cal-1", overLine.getString("calibrationId"))
        assertEquals(146_520_000L, overLine.getLong("frequencyHz"))
    }

    @Test
    fun `R_1009 a gap becomes its own real line naming session, cause and recovery`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "G1",
                sessionId = "S1",
                startedAt = 1_500L,
                endedAt = 1_800L,
                cause = CaptureGapCause.INPUT_LOST,
                recoveredAutomatically = true,
            ),
        )
        val lines = readLines(DebugDumpBuilder.build(context))
        val gapLine = lines.first { it.optString("type") == "gap" }
        assertEquals("S1", gapLine.getString("sessionId"))
        assertEquals("INPUT_LOST", gapLine.getString("cause"))
        assertTrue(gapLine.getBoolean("recoveredAutomatically"))
    }

    @Test
    fun `R_1009 a failed pass carries its lastError and its full attempt history`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        val itemId = db.workQueueDao().insert(
            WorkQueueItemEntity(
                transmissionId = "T1",
                pass = PassId.B_OFFLINE,
                state = WorkQueueState.FAILED,
                priority = 0,
                attemptCount = 2,
                lastError = "no ASR model installed",
                enqueuedAt = 1_000L,
            ),
        )
        db.workQueueDao().insert(
            WorkAttemptEntity(
                itemId = itemId,
                attemptNo = 1,
                startedAtMillis = 1_000L,
                finishedAtMillis = 1_100L,
                outcome = WorkAttemptOutcome.FAILED,
                reason = "no ASR model installed",
            ),
        )
        db.workQueueDao().insert(
            WorkAttemptEntity(
                itemId = itemId,
                attemptNo = 2,
                startedAtMillis = 2_000L,
                finishedAtMillis = 2_050L,
                outcome = WorkAttemptOutcome.TIMEOUT,
                reason = "deadline exceeded",
            ),
        )

        val lines = readLines(DebugDumpBuilder.build(context))
        val outcomeLine = lines.first { it.optString("type") == "pass_outcome" }
        assertEquals("T1", outcomeLine.getString("transmissionId"))
        assertEquals("B_OFFLINE", outcomeLine.getString("pass"))
        assertEquals("FAILED", outcomeLine.getString("state"))
        assertEquals(2, outcomeLine.getInt("attemptCount"))
        assertEquals("no ASR model installed", outcomeLine.getString("lastError"))
        val attempts = outcomeLine.getJSONArray("attempts")
        assertEquals(2, attempts.length())
        assertEquals("FAILED", attempts.getJSONObject(0).getString("outcome"))
        assertEquals("TIMEOUT", attempts.getJSONObject(1).getString("outcome"))
        assertEquals("deadline exceeded", attempts.getJSONObject(1).getString("reason"))
    }

    @Test
    fun `R_1009 a READY (not yet failed) queue item never appears — only terminal failures are diagnosable history`() =
        runTest {
            db.sessionDao().insert(session("S1"))
            db.transmissionDao().insert(transmission("T1", "S1"))
            db.workQueueDao().insert(
                WorkQueueItemEntity(
                    transmissionId = "T1",
                    pass = PassId.B_OFFLINE,
                    state = WorkQueueState.READY,
                    priority = 0,
                    enqueuedAt = 1_000L,
                ),
            )
            val lines = readLines(DebugDumpBuilder.build(context))
            assertFalse(lines.any { it.optString("type") == "pass_outcome" })
        }

    @Test
    fun `R_1009 no voiceprint, user name or note ever appears in the dump`() = runTest {
        // The debug dump is a diagnostic artifact an operator may share, exactly like
        // DiagnosticsBundleBuilder's own zip — it holds the same discipline (AC-109/AC-120) even
        // though it is a different producer: real callsigns/station ids (public over-the-air
        // identity) may appear, but voiceprint embeddings and user-supplied names/notes never do,
        // because this producer never reads them at all.
        val bytes = DebugDumpBuilder.build(context)
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("voiceprint", ignoreCase = true))
        assertFalse(text.contains("userName"))
        assertFalse(text.contains("notes"))
    }
}
