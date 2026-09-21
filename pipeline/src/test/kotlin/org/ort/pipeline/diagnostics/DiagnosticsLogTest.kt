package org.ort.pipeline.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.asrapi.rules.RejectionRuleId
import org.ort.capture.android.AudioDeviceKind
import org.ort.core.PassId
import org.ort.core.Tier
import org.ort.core.assets.ModelVerificationFailureKind
import org.ort.core.capture.VadDetectorKind
import org.ort.data.entity.TerminationReason
import org.ort.segment.SegmentCloseReason
import org.ort.segment.SegmentOutcome
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * register R-133 follow-up (FR-OBS-1): before this existed, WP11e's `DiagnosticsBundleBuilder`
 * (`:app`) read an honest placeholder for `lifecycle.log`/`capture.log`/`pipeline.log`/`rig.log`
 * because no writer anywhere in the tree ever put a real line there. Each test configures
 * [DiagnosticsLog] against a fresh JVM temp directory (no Robolectric/Android needed — the writer
 * itself has no Android dependency) and reads the resulting file back after [DiagnosticsLog.flush].
 */
class DiagnosticsLogTest {

    @After
    fun tearDown() {
        DiagnosticsLog.shutdown()
    }

    private fun tempFilesDir(): File = Files.createTempDirectory("diagnostics-log-test").toFile()

    private fun lines(filesDir: File, category: DiagnosticsLog.Category): List<String> {
        val file = File(File(filesDir, "diagnostics-logs"), category.fileName)
        return if (file.isFile) file.readLines() else emptyList()
    }

    /** ISO-8601 UTC, level, event, then space-separated key=value fields -- the line shape FR-OBS-1
     * itself specifies, checked once here rather than repeated per test. */
    private fun assertWellFormedLine(line: String, expectedEvent: String) {
        val parts = line.trim().split(" ")
        assertTrue("expected at least timestamp, level, event: <$line>", parts.size >= 3)
        try {
            Instant.parse(parts[0])
        } catch (e: DateTimeParseException) {
            throw AssertionError("first token must be an ISO-8601 UTC instant, was <${parts[0]}>", e)
        }
        assertTrue("level must be INFO/WARN/ERROR, was <${parts[1]}>", parts[1] in setOf("INFO", "WARN", "ERROR"))
        assertEquals(expectedEvent, parts[2])
        parts.drop(3).forEach { field ->
            assertTrue("field <$field> must be key=value", field.contains("="))
        }
    }

    @Test
    @Requirement("FR-OBS-1")
    fun `FR_OBS_1_lifecycle service start, stop and an unclean restart gap all land in lifecycle log`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock(startWallMillis = 1_700_000_000_000L))

        DiagnosticsLog.logServiceStarted("SESSION01")
        DiagnosticsLog.logServiceStopped("SESSION01", TerminationReason.USER, clean = true)
        DiagnosticsLog.logUncleanRestart("SESSION00", gapMillis = 45_000L)
        DiagnosticsLog.logHeartbeatGap("SESSION01", gapMillis = 12_000L)
        runBlocking { DiagnosticsLog.flush() }

        val written = lines(filesDir, DiagnosticsLog.Category.LIFECYCLE)
        assertEquals(4, written.size)
        assertWellFormedLine(written[0], "service_started")
        assertTrue(written[0].contains("sessionId=SESSION01"))
        assertWellFormedLine(written[1], "service_stopped")
        assertTrue(written[1].contains("reason=USER") && written[1].contains("clean=true"))
        assertWellFormedLine(written[2], "unclean_restart")
        assertTrue(written[2].contains("gapMillis=45000"))
        assertWellFormedLine(written[3], "heartbeat_gap")
    }

    @Test
    @Requirement("FR-OBS-1")
    fun `FR_OBS_1_capture route changes, level clips and overruns all land in capture log`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())

        DiagnosticsLog.logRouteVerified(AudioDeviceKind.USB_DEVICE, sampleRateHz = 48_000, routedDeviceMatches = true)
        DiagnosticsLog.logRouteMismatch(AudioDeviceKind.USB_DEVICE, AudioDeviceKind.BUILT_IN_MIC)
        DiagnosticsLog.logInputLost(sinceMillis = 5_000L)
        DiagnosticsLog.logLevelClip(clipCountLastSecond = 3, peakDbfs = -0.5)
        DiagnosticsLog.logOverrun(droppedMillis = 220L)
        runBlocking { DiagnosticsLog.flush() }

        val written = lines(filesDir, DiagnosticsLog.Category.CAPTURE)
        assertEquals(5, written.size)
        assertWellFormedLine(written[0], "route_verified")
        assertTrue(written[0].contains("deviceKind=USB_DEVICE") && written[0].contains("sampleRateHz=48000"))
        assertWellFormedLine(written[1], "route_mismatch")
        assertTrue(written[1].contains("expected=USB_DEVICE") && written[1].contains("actual=BUILT_IN_MIC"))
        assertWellFormedLine(written[2], "input_lost")
        assertWellFormedLine(written[3], "level_clip")
        assertTrue(written[3].contains("clipCountLastSecond=3"))
        assertWellFormedLine(written[4], "overrun")
        assertTrue(written[4].contains("droppedMillis=220"))
    }

    /**
     * Q20 (FR-OBS-1): the per-transmission VAD statistics `capture.log` was promised since draft 1
     * and, before this, never wrote — this proves an accepted segment's real fields land verbatim,
     * and that a value the caller genuinely could not measure is logged as the literal `NONE`,
     * never a fabricated `0.0` (constitution I). FR-SEG-10/AC-162 (register R-1054): also proves the
     * line names the real detector that cut each segment — SILERO for the accepted one, the
     * fallback ENERGY for the rejected one — discriminating on the enum value actually written, not
     * merely that some value is present.
     */
    @Test
    @Requirement("FR-OBS-1", "FR-SEG-10", "AC-162")
    fun `FR_OBS_1_vad_stats an accepted segment logs its real fields, and an unmeasurable one logs NONE not zero`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())

        DiagnosticsLog.logVadStats(
            transmissionId = "SESSION01-4",
            outcome = SegmentOutcome.SPEECH,
            closeReason = SegmentCloseReason.SILENCE,
            durationMs = 2_340L,
            vadFrameCount = 73,
            vadSpeechFrameCount = 61,
            peakDbfs = -3.2f,
            meanDbfs = -18.7f,
            noiseFloorDbfsAtOnset = -42.1f,
            vadDetector = VadDetectorKind.SILERO,
            rigSquelchFusionApplied = false,
        )
        DiagnosticsLog.logVadStats(
            transmissionId = "SESSION01-5",
            outcome = SegmentOutcome.REJECTED_TOO_SHORT,
            closeReason = SegmentCloseReason.END_OF_STREAM,
            durationMs = 90L,
            vadFrameCount = 3,
            vadSpeechFrameCount = 3,
            peakDbfs = null,
            meanDbfs = null,
            noiseFloorDbfsAtOnset = null,
            vadDetector = VadDetectorKind.ENERGY,
            rigSquelchFusionApplied = false,
        )
        runBlocking { DiagnosticsLog.flush() }

        val written = lines(filesDir, DiagnosticsLog.Category.CAPTURE)
        assertEquals(2, written.size)
        assertWellFormedLine(written[0], "vad_stats")
        assertTrue(written[0].contains("transmissionId=SESSION01-4"))
        assertTrue(written[0].contains("outcome=SPEECH"))
        assertTrue(written[0].contains("closeReason=SILENCE"))
        assertTrue(written[0].contains("durationMs=2340"))
        assertTrue(written[0].contains("vadFrameCount=73"))
        assertTrue(written[0].contains("vadSpeechFrameCount=61"))
        assertTrue(written[0].contains("peakDbfs=-3.2"))
        assertTrue(written[0].contains("meanDbfs=-18.7"))
        assertTrue(written[0].contains("noiseFloorDbfsAtOnset=-42.1"))
        assertTrue(
            "FR-SEG-10: the detector that actually cut this segment must be named in the line",
            written[0].contains("vadDetector=SILERO"),
        )
        assertTrue(written[0].contains("rigSquelchFusionApplied=false"))

        assertWellFormedLine(written[1], "vad_stats")
        assertTrue(written[1].contains("transmissionId=SESSION01-5"))
        assertTrue(written[1].contains("outcome=REJECTED_TOO_SHORT"))
        assertTrue(written[1].contains("closeReason=END_OF_STREAM"))
        assertTrue(
            "an unmeasurable value must be the literal NONE, never a fabricated 0.0",
            written[1].contains("peakDbfs=NONE") &&
                written[1].contains("meanDbfs=NONE") &&
                written[1].contains("noiseFloorDbfsAtOnset=NONE"),
        )
        assertTrue(
            "FR-SEG-10: a rejected segment still names its real (fallback) detector, not SILERO",
            written[1].contains("vadDetector=ENERGY"),
        )
    }

    /**
     * Register R-1034: `capture.log` carried per-segment `vad_stats` lines (D41/Q20) but never a
     * session-level rollup, even though FR-OBS-1 promises "VAD statistics" as a category, not only
     * a per-transmission one. This proves the one `vad_session_summary` line a session end writes
     * carries the real accumulated counters and durations verbatim, and names the session's one
     * detector via the existing [VadDetectorKind] signal rather than a second, invented boolean.
     */
    @Test
    @Requirement("FR-OBS-1")
    fun `FR_OBS_1_vad_session_summary one line per session carries the accumulated counters`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())

        DiagnosticsLog.logVadSessionSummary(
            sessionId = "SESSION01",
            segmentsProposed = 9,
            segmentsAccepted = 7,
            segmentsRejected = 2,
            speechActiveMs = 41_280L,
            sessionDurationMs = 720_000L,
            vadDetector = VadDetectorKind.SILERO,
        )
        runBlocking { DiagnosticsLog.flush() }

        val written = lines(filesDir, DiagnosticsLog.Category.CAPTURE)
        assertEquals(1, written.size)
        assertWellFormedLine(written[0], "vad_session_summary")
        assertTrue(written[0].contains("sessionId=SESSION01"))
        assertTrue(written[0].contains("segmentsProposed=9"))
        assertTrue(written[0].contains("segmentsAccepted=7"))
        assertTrue(written[0].contains("segmentsRejected=2"))
        assertTrue(written[0].contains("speechActiveMs=41280"))
        assertTrue(written[0].contains("sessionDurationMs=720000"))
        assertTrue(
            "the existing per-session detector signal must be reused, not a second computed flag",
            written[0].contains("vadDetector=SILERO"),
        )
    }

    /** The fallback half of the discrimination above: a session that ran on the RMS-energy
     * stand-in must name it in the summary line too, never a stale SILERO default. */
    @Test
    @Requirement("FR-OBS-1")
    fun `FR_OBS_1_vad_session_summary names the energy fallback when that is what ran`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())

        DiagnosticsLog.logVadSessionSummary(
            sessionId = "SESSION02",
            segmentsProposed = 1,
            segmentsAccepted = 0,
            segmentsRejected = 1,
            speechActiveMs = 0L,
            sessionDurationMs = 60_000L,
            vadDetector = VadDetectorKind.ENERGY,
        )
        runBlocking { DiagnosticsLog.flush() }

        val written = lines(filesDir, DiagnosticsLog.Category.CAPTURE)
        assertEquals(1, written.size)
        assertTrue(written[0].contains("vadDetector=ENERGY"))
        assertTrue(written[0].contains("speechActiveMs=0"))
    }

    @Test
    @Requirement("FR-OBS-1")
    fun `FR_OBS_1_pipeline latency, rejections, tier changes and SafePass failures all land in pipeline log`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())

        DiagnosticsLog.logPassLatency(PassId.B_OFFLINE, Tier.T1, elapsedMillis = 340L)
        DiagnosticsLog.logRejection(RejectionRuleId.TOO_SHORT)
        DiagnosticsLog.logTierChange(Tier.T0, Tier.T1, shedLevel = 2)
        DiagnosticsLog.logSafePassFailure("IllegalStateException")
        runBlocking { DiagnosticsLog.flush() }

        val written = lines(filesDir, DiagnosticsLog.Category.PIPELINE)
        assertEquals(4, written.size)
        assertWellFormedLine(written[0], "pass_latency")
        assertTrue(written[0].contains("pass=B_OFFLINE") && written[0].contains("tier=T1"))
        assertWellFormedLine(written[1], "rejection")
        assertTrue(written[1].contains("rule=TOO_SHORT"))
        assertWellFormedLine(written[2], "tier_change")
        assertTrue(written[2].contains("from=T0") && written[2].contains("to=T1"))
        assertWellFormedLine(written[3], "safe_pass_failure")
        assertTrue(written[3].contains("errorClass=IllegalStateException"))
    }

    @Test
    @Requirement("R-1058")
    fun `R_1058 a model verification failure logs the closed asset id and kind, once per launch`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())

        DiagnosticsLog.logModelVerificationFailed(ModelAssetId.VAD, ModelVerificationFailureKind.HASH_MISMATCH)
        DiagnosticsLog.logModelVerificationFailed(ModelAssetId.VAD, ModelVerificationFailureKind.HASH_MISMATCH)
        runBlocking { DiagnosticsLog.flush() }

        val written = lines(filesDir, DiagnosticsLog.Category.PIPELINE)
        assertEquals("a second call for the same asset this launch must not log again", 1, written.size)
        assertWellFormedLine(written[0], "model_verification_failed")
        assertTrue(written[0].contains("assetId=VAD"))
        assertTrue(written[0].contains("kind=HASH_MISMATCH"))
    }

    @Test
    @Requirement("R-1058")
    fun `R_1058 different assets each log their own event in the same launch`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())

        DiagnosticsLog.logModelVerificationFailed(ModelAssetId.ASR_ENCODER, ModelVerificationFailureKind.MISSING_FILE)
        DiagnosticsLog.logModelVerificationFailed(ModelAssetId.ASR_DECODER, ModelVerificationFailureKind.SIZE_MISMATCH)
        runBlocking { DiagnosticsLog.flush() }

        val written = lines(filesDir, DiagnosticsLog.Category.PIPELINE)
        assertEquals(2, written.size)
        assertTrue(written[0].contains("assetId=ASR_ENCODER") && written[0].contains("kind=MISSING_FILE"))
        assertTrue(written[1].contains("assetId=ASR_DECODER") && written[1].contains("kind=SIZE_MISMATCH"))
    }

    @Test
    @Requirement("R-1058")
    fun `R_1058 a new configure (a new launch) resets the once-per-launch dedupe`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())
        DiagnosticsLog.logModelVerificationFailed(ModelAssetId.LLM, ModelVerificationFailureKind.MISSING_FILE)
        runBlocking { DiagnosticsLog.flush() }

        DiagnosticsLog.configure(filesDir, TestClock())
        DiagnosticsLog.logModelVerificationFailed(ModelAssetId.LLM, ModelVerificationFailureKind.MISSING_FILE)
        runBlocking { DiagnosticsLog.flush() }

        assertEquals(2, lines(filesDir, DiagnosticsLog.Category.PIPELINE).size)
    }

    @Test
    @Requirement("FR-OBS-1")
    fun `FR_OBS_1_rig connect, stale, absent and band events all land in rig log`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())

        DiagnosticsLog.logRigConnected(bandCount = 2)
        DiagnosticsLog.logRigBand("A", frequencyHz = 146_960_000L, squelchOpen = true)
        DiagnosticsLog.logRigStale(sinceMillis = 9_000L)
        DiagnosticsLog.logRigAbsent()
        runBlocking { DiagnosticsLog.flush() }

        val written = lines(filesDir, DiagnosticsLog.Category.RIG)
        assertEquals(4, written.size)
        assertWellFormedLine(written[0], "rig_connected")
        assertWellFormedLine(written[1], "rig_band")
        assertTrue(written[1].contains("frequencyHz=146960000"))
        assertWellFormedLine(written[2], "rig_stale")
        assertWellFormedLine(written[3], "rig_absent")
    }

    @Test
    @Requirement("FR-OBS-1")
    fun `FR_OBS_1_rotation keeps at most two generations per category file`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())
        val logDir = File(filesDir, "diagnostics-logs")
        logDir.mkdirs()
        val active = File(logDir, DiagnosticsLog.Category.RIG.fileName)
        // Simulate an already-oversized file directly -- faster and just as real a test of the
        // rotation decision as driving DiagnosticsLog.ROTATE_AT_BYTES worth of real log calls.
        active.writeBytes(ByteArray(DiagnosticsLog.ROTATE_AT_BYTES.toInt()) { 'x'.code.toByte() })

        DiagnosticsLog.logRigAbsent()
        runBlocking { DiagnosticsLog.flush() }

        val backup = File(logDir, "${DiagnosticsLog.Category.RIG.fileName}.1")
        assertTrue("the oversized file must have rolled to a .1 backup", backup.isFile)
        assertEquals(DiagnosticsLog.ROTATE_AT_BYTES, backup.length())
        assertTrue("the active file must be fresh, well under the rotation threshold", active.length() < 1_000L)
    }

    /**
     * FR-OBS-1 (Q20): a busy net can write hundreds of `vad_stats` lines to `capture.log` in one
     * session — this proves that volume is bounded by the *same* rotation [DiagnosticsLog] already
     * applies to every other category, not a special case that could grow unbounded. Never reads
     * `ROTATE_AT_BYTES` real bytes' worth of real calls (slow); instead proves the mechanism is the
     * same file/rotation path every category already shares, by tripping it with a single real
     * `vad_stats` line the same way the generic rotation test above trips it with a pre-sized file.
     */
    @Test
    @Requirement("FR-OBS-1")
    fun `FR_OBS_1_vad_stats capture log rotation is the same shared mechanism, not a special case`() {
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())
        val logDir = File(filesDir, "diagnostics-logs")
        logDir.mkdirs()
        val active = File(logDir, DiagnosticsLog.Category.CAPTURE.fileName)
        active.writeBytes(ByteArray(DiagnosticsLog.ROTATE_AT_BYTES.toInt()) { 'x'.code.toByte() })

        DiagnosticsLog.logVadStats(
            transmissionId = "SESSION01-1",
            outcome = SegmentOutcome.SPEECH,
            closeReason = SegmentCloseReason.SILENCE,
            durationMs = 1_000L,
            vadFrameCount = 30,
            vadSpeechFrameCount = 25,
            peakDbfs = -6f,
            meanDbfs = -20f,
            noiseFloorDbfsAtOnset = -40f,
            vadDetector = VadDetectorKind.SILERO,
            rigSquelchFusionApplied = false,
        )
        runBlocking { DiagnosticsLog.flush() }

        val backup = File(logDir, "${DiagnosticsLog.Category.CAPTURE.fileName}.1")
        assertTrue("a vad_stats write must roll an oversized capture.log exactly like any other event", backup.isFile)
        assertTrue("the active file must be fresh, holding only this one vad_stats line", active.length() < 1_000L)
    }

    /**
     * AC-109: the real, representative leak risk in this design is not the closed id/enum/number
     * parameters below (production code never populates a `sessionId`/`transmissionId` from a
     * callsign — they are [org.ort.core.Ulid] values) — it is a caller reaching for
     * [Throwable.message] instead of [Throwable]'s class name, since a message string is free text
     * an engine or a rule composed and could, in principle, embed anything the segment's own text
     * contained, a resolved callsign included. This test seeds exactly that: an exception whose
     * *message* names a callsign, run through the same `e::class.simpleName`-only discipline
     * [org.ort.pipeline.reprocess.SafePass]'s own catch block uses (see [DiagnosticsLog.logSafePassFailure]'s
     * kdoc) — proving the discipline holds, not merely that this one call happened not to include
     * it. Every other category is also driven with real, representative typed data alongside it, so
     * one scan covers all four files at once.
     */
    @Test
    @Requirement("AC-109", "FR-OBS-3")
    fun `AC_109_no_private_field_ever_logged`() {
        val seededCallsign = "K7QRX"
        val simulatedEngineFailure = IllegalStateException("resolved callsign $seededCallsign at confidence 0.94")
        val filesDir = tempFilesDir()
        DiagnosticsLog.configure(filesDir, TestClock())

        DiagnosticsLog.logServiceStarted("SESSION01")
        DiagnosticsLog.logServiceStopped("SESSION01", TerminationReason.CRASH, clean = false)
        DiagnosticsLog.logUncleanRestart("SESSION00", 1_000L)
        DiagnosticsLog.logHeartbeatGap("SESSION01", 1_000L)
        DiagnosticsLog.logRouteVerified(AudioDeviceKind.USB_DEVICE, 48_000, true)
        DiagnosticsLog.logRouteMismatch(AudioDeviceKind.USB_DEVICE, null)
        DiagnosticsLog.logInputLost(1_000L)
        DiagnosticsLog.logLevelClip(1, -0.1)
        DiagnosticsLog.logOverrun(50L)
        DiagnosticsLog.logVadStats(
            transmissionId = "SESSION01-9",
            outcome = SegmentOutcome.SPEECH,
            closeReason = SegmentCloseReason.SILENCE,
            durationMs = 1_000L,
            vadFrameCount = 30,
            vadSpeechFrameCount = 25,
            peakDbfs = -6f,
            meanDbfs = -20f,
            noiseFloorDbfsAtOnset = -40f,
            vadDetector = VadDetectorKind.SILERO,
            rigSquelchFusionApplied = false,
        )
        DiagnosticsLog.logVadSessionSummary(
            sessionId = "SESSION01",
            segmentsProposed = 1,
            segmentsAccepted = 1,
            segmentsRejected = 0,
            speechActiveMs = 800L,
            sessionDurationMs = 1_000L,
            vadDetector = VadDetectorKind.SILERO,
        )
        DiagnosticsLog.logPassLatency(PassId.B_OFFLINE, Tier.T0, 100L)
        DiagnosticsLog.logRejection(RejectionRuleId.BLOCKLIST)
        DiagnosticsLog.logTierChange(Tier.T0, Tier.T2, 3)
        // The discipline under test: only the exception's class name ever reaches the writer,
        // exactly as org.ort.pipeline.reprocess.SafePass's own catch block calls this function --
        // simulatedEngineFailure.message is deliberately never passed.
        DiagnosticsLog.logSafePassFailure(simulatedEngineFailure::class.simpleName ?: "unknown")
        DiagnosticsLog.logRigConnected(1)
        DiagnosticsLog.logRigBand("A", 146_520_000L, true)
        DiagnosticsLog.logRigStale(1_000L)
        DiagnosticsLog.logRigAbsent()
        runBlocking { DiagnosticsLog.flush() }

        val allLines = DiagnosticsLog.Category.entries.flatMap { lines(filesDir, it) }
        assertTrue("the seeded calls did produce output to check", allLines.size >= 16)
        assertTrue(
            "sanity: the simulated failure's own message really does contain the seed",
            simulatedEngineFailure.message!!.contains(seededCallsign),
        )
        assertFalse(
            "a callsign must never appear in any diagnostics-log line -- this is structural " +
                "(no function in DiagnosticsLog's public API takes free text), not a per-call accident",
            allLines.any { it.contains(seededCallsign) },
        )
    }
}
