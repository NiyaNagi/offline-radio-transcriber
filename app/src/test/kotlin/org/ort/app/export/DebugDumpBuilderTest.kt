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
import org.ort.app.diagnostics.DiagnosticsLogPaths
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.core.capture.VadDetectorKind
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
import java.io.File

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

    /**
     * FR-SEG-10 (register R-1054, AC-162): the over's own database-recorded VAD provenance --
     * detector, version, whether rig squelch fusion applied, and the single derived "conforms to
     * FR-SEG-1" answer -- must all reach the dump, discriminating the two real detector identities
     * against each other rather than checking only that some value is present.
     */
    @Test
    fun `AC_162 an over records its real VAD detector, version, fusion flag and conformance in the dump`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(
            transmission("T1", "S1").copy(
                vadDetector = VadDetectorKind.SILERO,
                vadDetectorVersion = "silero-v5",
                rigSquelchFusionApplied = true,
            ),
        )
        db.transmissionDao().insert(
            transmission("T2", "S1").copy(vadDetector = VadDetectorKind.ENERGY),
        )
        val lines = readLines(DebugDumpBuilder.build(context))

        val sileroOver = lines.first { it.optString("type") == "over" && it.getString("id") == "T1" }
        assertEquals("SILERO", sileroOver.getString("vadDetector"))
        assertEquals("silero-v5", sileroOver.getString("vadDetectorVersion"))
        assertTrue(sileroOver.getBoolean("rigSquelchFusionApplied"))
        assertTrue(
            "Silero is one of the two detectors FR-SEG-1 names",
            sileroOver.getBoolean("conformsToFrSeg1"),
        )

        val energyOver = lines.first { it.optString("type") == "over" && it.getString("id") == "T2" }
        assertEquals("ENERGY", energyOver.getString("vadDetector"))
        assertTrue("no version is known for the energy fallback", energyOver.isNull("vadDetectorVersion"))
        assertFalse(energyOver.getBoolean("rigSquelchFusionApplied"))
        assertFalse(
            "the energy fallback must never be presented as conforming to FR-SEG-1",
            energyOver.getBoolean("conformsToFrSeg1"),
        )
    }

    /** FR-SEG-10: the session row's own denormalized detector -- see
     * [org.ort.data.entity.SessionEntity.vadDetector]'s own kdoc for why a session names it too. */
    @Test
    fun `AC_162 a session records its own real VAD detector in the dump`() = runTest {
        db.sessionDao().insert(session("S1").copy(vadDetector = VadDetectorKind.SILERO, vadDetectorVersion = "v5"))
        val lines = readLines(DebugDumpBuilder.build(context))
        val sessionLine = lines.first { it.optString("type") == "session" && it.getString("id") == "S1" }
        assertEquals("SILERO", sessionLine.getString("vadDetector"))
        assertEquals("v5", sessionLine.getString("vadDetectorVersion"))
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

    /**
     * FR-OBS-1 (Q20), AC-161 (D41): a `vad_stats` line — accepted and rejected alike — is parsed
     * straight out of `capture.log`'s own real file format, not fabricated, and every value the
     * writer logged as the literal `NONE` round-trips here as JSON `null`, never `0`/`0.0`
     * (constitution I). Covers AC-161's "closed by silence" and "rejected as too short"/"no
     * noise-floor reading available" driven cases, and its "same statistics in the debug dump"
     * clause; the "forced at maximum duration" case is proven separately below.
     */
    @Test
    fun `FR_OBS_1 AC_161 a capture log vad_stats line for both an accepted and a rejected segment lands in the dump`() =
        runTest {
            val logDir = DiagnosticsLogPaths.logDir(context)
            logDir.mkdirs()
            File(logDir, "capture.log").writeText(
                "2026-01-01T00:00:00Z INFO route_verified deviceKind=USB_DEVICE sampleRateHz=48000 matches=true\n" +
                    "2026-01-01T00:00:01Z INFO vad_stats transmissionId=SESSION01-4 outcome=SPEECH " +
                    "closeReason=SILENCE durationMs=2340 vadFrameCount=73 vadSpeechFrameCount=61 " +
                    "peakDbfs=-3.2 meanDbfs=-18.7 noiseFloorDbfsAtOnset=-42.1\n" +
                    "2026-01-01T00:00:02Z INFO vad_stats transmissionId=SESSION01-5 outcome=REJECTED_TOO_SHORT " +
                    "closeReason=END_OF_STREAM durationMs=90 vadFrameCount=3 vadSpeechFrameCount=3 " +
                    "peakDbfs=NONE meanDbfs=NONE noiseFloorDbfsAtOnset=NONE\n",
            )

            val lines = readLines(DebugDumpBuilder.build(context))
            val vadStatsLines = lines.filter { it.optString("type") == "vad_stats" }
            assertEquals(2, vadStatsLines.size)

            val accepted = vadStatsLines.first { it.getString("transmissionId") == "SESSION01-4" }
            assertEquals("SPEECH", accepted.getString("outcome"))
            assertEquals("SILENCE", accepted.getString("closeReason"))
            assertEquals(2340L, accepted.getLong("durationMs"))
            assertEquals(73, accepted.getInt("vadFrameCount"))
            assertEquals(61, accepted.getInt("vadSpeechFrameCount"))
            assertEquals(-3.2, accepted.getDouble("peakDbfs"), 0.001)
            assertEquals(-18.7, accepted.getDouble("meanDbfs"), 0.001)
            assertEquals(-42.1, accepted.getDouble("noiseFloorDbfsAtOnset"), 0.001)

            val rejected = vadStatsLines.first { it.getString("transmissionId") == "SESSION01-5" }
            assertEquals("REJECTED_TOO_SHORT", rejected.getString("outcome"))
            assertEquals("END_OF_STREAM", rejected.getString("closeReason"))
            assertTrue(
                "an unmeasurable NONE value must round-trip as JSON null, never a fabricated 0.0",
                rejected.isNull("peakDbfs") && rejected.isNull("meanDbfs") && rejected.isNull("noiseFloorDbfsAtOnset"),
            )
        }

    /**
     * AC-161's second driven case, proven at the debug-dump layer: a segment forced closed at the
     * maximum-duration cap carries its real `MAX_DURATION` close reason in the dump too, not just
     * in `capture.log` (proven separately by `RealSegmentSinkTest`).
     */
    @Test
    fun `AC_161 a MAX_DURATION forced segment's vad_stats line also lands in the debug dump`() = runTest {
        val logDir = DiagnosticsLogPaths.logDir(context)
        logDir.mkdirs()
        File(logDir, "capture.log").writeText(
            "2026-01-01T00:00:03Z INFO vad_stats transmissionId=SESSION01-6 outcome=SPEECH " +
                "closeReason=MAX_DURATION durationMs=60000 vadFrameCount=1875 vadSpeechFrameCount=1875 " +
                "peakDbfs=-1.0 meanDbfs=-6.5 noiseFloorDbfsAtOnset=-50.0\n",
        )

        val lines = readLines(DebugDumpBuilder.build(context))
        val line = lines.single { it.optString("type") == "vad_stats" }
        assertEquals("SESSION01-6", line.getString("transmissionId"))
        assertEquals("SPEECH", line.getString("outcome"))
        assertEquals("MAX_DURATION", line.getString("closeReason"))
        assertEquals(60_000L, line.getLong("durationMs"))
        assertEquals(1_875, line.getInt("vadFrameCount"))
    }

    /**
     * FR-SEG-10 (register R-1054, AC-162): the `vad_stats` line's own detector/fusion fields, parsed
     * straight out of `capture.log` exactly like every other field on that line (see the class kdoc
     * on why this producer reads the log rather than the database for `vad_stats`).
     */
    @Test
    fun `AC_162 a vad_stats line's real detector and fusion flag land in the dump`() = runTest {
        val logDir = DiagnosticsLogPaths.logDir(context)
        logDir.mkdirs()
        File(logDir, "capture.log").writeText(
            "2026-01-01T00:00:04Z INFO vad_stats transmissionId=SESSION01-7 outcome=SPEECH " +
                "closeReason=SILENCE durationMs=1000 vadFrameCount=30 vadSpeechFrameCount=25 " +
                "peakDbfs=-6.0 meanDbfs=-20.0 noiseFloorDbfsAtOnset=-40.0 vadDetector=SILERO " +
                "rigSquelchFusionApplied=false\n",
        )

        val lines = readLines(DebugDumpBuilder.build(context))
        val line = lines.single { it.optString("type") == "vad_stats" }
        assertEquals("SILERO", line.getString("vadDetector"))
        assertFalse(line.getBoolean("rigSquelchFusionApplied"))
    }

    /** A pre-WPSEGPROV `capture.log` line (no `vadDetector`/`rigSquelchFusionApplied` tokens at all)
     * must still parse -- the new fields read as JSON `null`, never throw and never guess a value
     * that line never wrote. */
    @Test
    fun `AC_162 a pre-WPSEGPROV vad_stats line parses, missing detector fields read as null`() = runTest {
        val logDir = DiagnosticsLogPaths.logDir(context)
        logDir.mkdirs()
        File(logDir, "capture.log").writeText(
            "2026-01-01T00:00:05Z INFO vad_stats transmissionId=SESSION01-8 outcome=SPEECH " +
                "closeReason=SILENCE durationMs=1000 vadFrameCount=30 vadSpeechFrameCount=25 " +
                "peakDbfs=-6.0 meanDbfs=-20.0 noiseFloorDbfsAtOnset=-40.0\n",
        )

        val lines = readLines(DebugDumpBuilder.build(context))
        val line = lines.single { it.optString("type") == "vad_stats" }
        assertTrue(line.isNull("vadDetector"))
        assertTrue(line.isNull("rigSquelchFusionApplied"))
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
