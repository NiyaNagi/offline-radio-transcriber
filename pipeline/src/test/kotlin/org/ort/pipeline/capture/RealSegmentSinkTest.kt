package org.ort.pipeline.capture

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.PassId
import org.ort.core.SampleClock
import org.ort.core.TransmissionState
import org.ort.core.capture.VadDetectorKind
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.pipeline.PipelineTestFixtures
import org.ort.pipeline.diagnostics.DiagnosticsLog
import org.ort.pipeline.rig.FrequencyProvenance
import org.ort.pipeline.rig.FrequencyReading
import org.ort.segment.FrameSpec
import org.ort.segment.SegmentCloseReason
import org.ort.segment.SegmentConfig
import org.ort.segment.SegmentId
import org.ort.segment.SegmentOutcome
import org.ort.segment.SegmentRecord
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * F-001 (audit-2026-09-07): before this test, every real transmission persisted by
 * [RealSegmentSink] carried `startedAtUtc = 0L`, `endedAtUtc = null`,
 * `monotonicStartNanos = 0L` and `utcOffsetMinutes = 0` — fabricated values, never the session's
 * actual clock anchor (FR-RUN-15, FR-RUN-16, FR-RUN-18; constitution I: "never fabricate a
 * healthy-looking value"). This proves the sink derives every timestamp from the session's
 * [SampleClock] anchor plus the segment's real sample position, and carries the real
 * [SegmentConfig] pre-/post-roll instead of a duplicated literal.
 */
@RunWith(RobolectricTestRunner::class)
public class RealSegmentSinkTest {

    private lateinit var db: OrtDatabase
    private lateinit var filesDir: File
    private val sessionId = "SESSION01"

    @Before
    public fun setUp() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
        runBlocking { db.sessionDao().insert(PipelineTestFixtures.session(sessionId)) }
    }

    @After
    public fun tearDown() {
        DiagnosticsLog.shutdown()
    }

    @Test
    @Requirement("FR-RUN-15")
    public fun `a closed segment carries real timestamps derived from its sample position`(): Unit = runBlocking {
        val clock = TestClock(startMonotonicNanos = 500_000_000L, startWallMillis = 1_700_000_000_000L)
        clock.setUtcOffsetMinutes(-300)
        val sessionStartWall = clock.wallMillis()
        val sessionStartMonotonic = clock.monotonicNanos()
        val sampleClock = SampleClock(
            anchorMonotonicNanos = sessionStartMonotonic,
            anchorWallMillis = sessionStartWall,
            anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
            sampleRate = FrameSpec.SAMPLE_RATE,
        )
        val segmentConfig = SegmentConfig(preRollMs = 1_100, postRollMs = 350)
        val queue = WorkQueue(db, clock)

        var persistedCalls = 0
        val sink = RealSegmentSink(filesDir, sessionId, db, queue, sampleClock, segmentConfig) {
            persistedCalls++
        }

        // Starts three seconds into the session, not at session start -- the point of this
        // test is that the stored timestamps come from *this offset*, not a fresh clock read.
        val startSample = 3L * FrameSpec.SAMPLE_RATE
        val endSample = startSample + FrameSpec.SAMPLE_RATE // one second of "speech"
        val writer = sink.open(SegmentId(0), startSample)
        writer.append(FloatArray(FrameSpec.SAMPLE_RATE) { 0.1f })
        writer.close(
            SegmentRecord(
                id = SegmentId(0),
                startSample = startSample,
                endSample = endSample,
                vadStartSample = startSample,
                vadEndSample = endSample,
                sampleCount = FrameSpec.SAMPLE_RATE.toLong(),
                outcome = SegmentOutcome.SPEECH,
                closeReason = SegmentCloseReason.SILENCE,
                vadFrameCount = 30,
                vadSpeechFrameCount = 30,
            ),
        )

        assertEquals(1, persistedCalls)
        val transmissionId = "$sessionId-0"
        val persisted = db.transmissionDao().getById(transmissionId)
        assertNotNull("expected the segment to be persisted", persisted)

        val expectedStartedAtUtc = sessionStartWall + startSample * 1000 / FrameSpec.SAMPLE_RATE
        val expectedEndedAtUtc = sessionStartWall + endSample * 1000 / FrameSpec.SAMPLE_RATE
        val expectedMonotonicStart =
            sessionStartMonotonic + startSample * 1_000_000_000L / FrameSpec.SAMPLE_RATE

        assertEquals(expectedStartedAtUtc, persisted!!.startedAtUtc)
        assertNotNull("endedAtUtc must be derived, not left null", persisted.endedAtUtc)
        assertEquals(expectedEndedAtUtc, persisted.endedAtUtc)
        assertNotEquals(0L, persisted.monotonicStartNanos)
        assertEquals(expectedMonotonicStart, persisted.monotonicStartNanos)
        assertEquals(-300, persisted.utcOffsetMinutes)
        assertEquals(segmentConfig.preRollMs, persisted.preRollMs)
        assertEquals(segmentConfig.postRollMs, persisted.postRollMs)
    }

    /**
     * F-006 (audit-2026-09-07): before this fix, `RealSegmentSink.close()` deleted the staged
     * PCM for every non-`SPEECH` outcome and returned without recording anything — a too-short
     * segment vanished with no trace (constitution III "nothing is deleted quietly"; FR-SEG-6 →
     * AC-72, "recording them as `rejected:too_short` rather than deleting them"). This proves a
     * `REJECTED_TOO_SHORT` segment is instead FLAC-encoded and persisted as a `REJECTED`
     * transmission row with its rejection reason set, its audio reachable on disk, and never
     * enqueued for Pass B.
     */
    @Test
    @Requirement("AC-72")
    public fun `AC_72_a_too_short_segment_is_retained_as_a_rejected_row_with_its_audio_and_never_enqueued`(): Unit =
        runBlocking {
            val clock = TestClock(startMonotonicNanos = 500_000_000L, startWallMillis = 1_700_000_000_000L)
            val sampleClock = SampleClock(
                anchorMonotonicNanos = clock.monotonicNanos(),
                anchorWallMillis = clock.wallMillis(),
                anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
                sampleRate = FrameSpec.SAMPLE_RATE,
            )
            val segmentConfig = SegmentConfig()
            val queue = WorkQueue(db, clock)

            var persistedCalls = 0
            val sink = RealSegmentSink(filesDir, sessionId, db, queue, sampleClock, segmentConfig) {
                persistedCalls++
            }

            val startSample = 0L
            val endSample = FrameSpec.SAMPLE_RATE / 10L // 100ms: below the too-short floor
            val writer = sink.open(SegmentId(0), startSample)
            writer.append(FloatArray(endSample.toInt()) { 0.1f })
            writer.close(
                SegmentRecord(
                    id = SegmentId(0),
                    startSample = startSample,
                    endSample = endSample,
                    vadStartSample = startSample,
                    vadEndSample = endSample,
                    sampleCount = endSample,
                    outcome = SegmentOutcome.REJECTED_TOO_SHORT,
                    closeReason = SegmentCloseReason.SILENCE,
                    vadFrameCount = 3,
                    vadSpeechFrameCount = 3,
                ),
            )

            val transmissionId = "$sessionId-0"
            val persisted = db.transmissionDao().getById(transmissionId)
            assertNotNull("a too-short segment must still be recorded as a row", persisted)
            assertEquals(TransmissionState.REJECTED, persisted!!.processingState)
            assertEquals("too_short", persisted.rejectionReason)

            val encoded = File(filesDir, persisted.audioPath())
            assertTrue("the audio must be retained, not deleted", encoded.exists())

            val staged = File(filesDir, "staging/${sessionId}_0.pcm")
            assertFalse("the staged PCM is gone only because it was encoded, not left behind", staged.exists())

            val queued = db.workQueueDao().findByTransmissionAndPass(transmissionId, PassId.B_OFFLINE.name)
            assertTrue("a too-short segment must never be enqueued for Pass B", queued.isEmpty())
            assertEquals(1, persistedCalls)
        }

    /**
     * WPC3 (FR-RIG-6): [org.ort.data.entity.TransmissionEntity.rigStateChangedMidTransmission]
     * must carry the [FrequencyReading.changedDuringTransmission] flag the frequency provider
     * reports — before this, the flag had nowhere to persist to (E2-B09's own report).
     */
    @Test
    @Requirement("FR-RIG-6")
    public fun `a frequency reading flagged changed mid-transmission persists that flag on the row`(): Unit =
        runBlocking {
            val clock = TestClock(startMonotonicNanos = 500_000_000L, startWallMillis = 1_700_000_000_000L)
            val sampleClock = SampleClock(
                anchorMonotonicNanos = clock.monotonicNanos(),
                anchorWallMillis = clock.wallMillis(),
                anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
                sampleRate = FrameSpec.SAMPLE_RATE,
            )
            val segmentConfig = SegmentConfig()
            val queue = WorkQueue(db, clock)

            val sink = RealSegmentSink(
                filesDir,
                sessionId,
                db,
                queue,
                sampleClock,
                segmentConfig,
                frequencyProvider = { _, _ ->
                    FrequencyReading(14_250_000L, FrequencyProvenance.RIG, changedDuringTransmission = true)
                },
            ) {}

            val startSample = 0L
            val endSample = FrameSpec.SAMPLE_RATE.toLong()
            val writer = sink.open(SegmentId(0), startSample)
            writer.append(FloatArray(endSample.toInt()) { 0.1f })
            writer.close(
                SegmentRecord(
                    id = SegmentId(0),
                    startSample = startSample,
                    endSample = endSample,
                    vadStartSample = startSample,
                    vadEndSample = endSample,
                    sampleCount = endSample,
                    outcome = SegmentOutcome.SPEECH,
                    closeReason = SegmentCloseReason.SILENCE,
                    vadFrameCount = 31,
                    vadSpeechFrameCount = 31,
                ),
            )

            val persisted = db.transmissionDao().getById("$sessionId-0")
            assertNotNull(persisted)
            assertTrue(
                "a mid-transmission rig change must be flagged on the persisted row",
                persisted!!.rigStateChangedMidTransmission,
            )
        }

    /** The other half of the flag above: an unflagged reading must persist `false`, never `true`
     * by default -- the flag names a real event, not a fabricated one. */
    @Test
    @Requirement("FR-RIG-6")
    public fun `an unflagged frequency reading persists rigStateChangedMidTransmission as false`(): Unit = runBlocking {
        val clock = TestClock(startMonotonicNanos = 500_000_000L, startWallMillis = 1_700_000_000_000L)
        val sampleClock = SampleClock(
            anchorMonotonicNanos = clock.monotonicNanos(),
            anchorWallMillis = clock.wallMillis(),
            anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
            sampleRate = FrameSpec.SAMPLE_RATE,
        )
        val segmentConfig = SegmentConfig()
        val queue = WorkQueue(db, clock)

        val sink = RealSegmentSink(filesDir, sessionId, db, queue, sampleClock, segmentConfig) {}

        val startSample = 0L
        val endSample = FrameSpec.SAMPLE_RATE.toLong()
        val writer = sink.open(SegmentId(0), startSample)
        writer.append(FloatArray(endSample.toInt()) { 0.1f })
        writer.close(
            SegmentRecord(
                id = SegmentId(0),
                startSample = startSample,
                endSample = endSample,
                vadStartSample = startSample,
                vadEndSample = endSample,
                sampleCount = endSample,
                outcome = SegmentOutcome.SPEECH,
                closeReason = SegmentCloseReason.SILENCE,
                vadFrameCount = 31,
                vadSpeechFrameCount = 31,
            ),
        )

        val persisted = db.transmissionDao().getById("$sessionId-0")
        assertNotNull(persisted)
        assertFalse(persisted!!.rigStateChangedMidTransmission)
    }

    private fun captureLogLines(): List<String> {
        val file = File(File(filesDir, "diagnostics-logs"), DiagnosticsLog.Category.CAPTURE.fileName)
        return if (file.isFile) file.readLines() else emptyList()
    }

    private fun field(line: String, key: String): String =
        line.trim().split(" ").single { it.startsWith("$key=") }.substringAfter("=")

    /**
     * FR-OBS-1 (Q20): before this, `capture.log` carried only session/fault-level events — a
     * segment that closed cleanly produced no line of any kind. This proves an accepted segment's
     * real peak/mean dBFS (computed from the exact PCM this writer's own [SegmentWriter.append]
     * saw, not invented), the injected onset noise floor, and every [SegmentRecord] field it
     * carries all land in one `vad_stats` line. AC-161's first driven case: a segment closed by
     * silence.
     */
    @Test
    @Requirement("FR-OBS-1", "AC-161")
    public fun `a closed SPEECH segment logs its real peak, mean and onset noise floor to capture log`(): Unit =
        runBlocking {
            DiagnosticsLog.configure(filesDir, TestClock())
            val clock = TestClock(startMonotonicNanos = 0L, startWallMillis = 1_700_000_000_000L)
            val sampleClock = SampleClock(
                anchorMonotonicNanos = clock.monotonicNanos(),
                anchorWallMillis = clock.wallMillis(),
                anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
                sampleRate = FrameSpec.SAMPLE_RATE,
            )
            val queue = WorkQueue(db, clock)
            val sink = RealSegmentSink(
                filesDir,
                sessionId,
                db,
                queue,
                sampleClock,
                SegmentConfig(),
                noiseFloorDbfsProvider = { -37.5f },
            ) {}

            val endSample = FrameSpec.SAMPLE_RATE.toLong()
            val writer = sink.open(SegmentId(0), 0L)
            // Constant 0.25 amplitude: peak and mean dBFS are then the same, known value --
            // 20*log10(0.25) ~= -12.04 dBFS -- an exact hand check, not a fabricated expectation.
            writer.append(FloatArray(endSample.toInt()) { 0.25f })
            writer.close(
                SegmentRecord(
                    id = SegmentId(0),
                    startSample = 0L,
                    endSample = endSample,
                    vadStartSample = 0L,
                    vadEndSample = endSample,
                    sampleCount = endSample,
                    outcome = SegmentOutcome.SPEECH,
                    closeReason = SegmentCloseReason.SILENCE,
                    vadFrameCount = 31,
                    vadSpeechFrameCount = 20,
                ),
            )
            DiagnosticsLog.flush()

            val lines = captureLogLines()
            assertEquals(1, lines.size)
            val line = lines.single()
            assertTrue(line.contains("vad_stats"))
            assertEquals("$sessionId-0", field(line, "transmissionId"))
            assertEquals("SPEECH", field(line, "outcome"))
            assertEquals("SILENCE", field(line, "closeReason"))
            assertEquals("31", field(line, "vadFrameCount"))
            assertEquals("20", field(line, "vadSpeechFrameCount"))
            assertEquals(1_000L.toString(), field(line, "durationMs"))
            assertEquals(-12.04f, field(line, "peakDbfs").toFloat(), 0.01f)
            assertEquals(-12.04f, field(line, "meanDbfs").toFloat(), 0.01f)
            assertEquals(-37.5f, field(line, "noiseFloorDbfsAtOnset").toFloat(), 0.0f)
        }

    /**
     * FR-OBS-1 (Q20): the other half of the discrimination above -- when the noise-floor meter
     * genuinely has nothing to report yet, the line must say so honestly (the literal `NONE`),
     * never a fabricated `0.0` (constitution I). Also covers a `REJECTED_TOO_SHORT` segment, which
     * gets a `vad_stats` line exactly like an accepted one (constitution III: rejected segments
     * stay reachable). AC-161's third and fourth driven cases together: a segment rejected as too
     * short, with no noise-floor reading available.
     */
    @Test
    @Requirement("FR-OBS-1", "AC-161")
    public fun `a rejected segment still logs vad_stats, with an unmeasurable noise floor as NONE`(): Unit =
        runBlocking {
            DiagnosticsLog.configure(filesDir, TestClock())
            val clock = TestClock(startMonotonicNanos = 0L, startWallMillis = 1_700_000_000_000L)
            val sampleClock = SampleClock(
                anchorMonotonicNanos = clock.monotonicNanos(),
                anchorWallMillis = clock.wallMillis(),
                anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
                sampleRate = FrameSpec.SAMPLE_RATE,
            )
            val queue = WorkQueue(db, clock)
            val sink = RealSegmentSink(
                filesDir,
                sessionId,
                db,
                queue,
                sampleClock,
                SegmentConfig(),
                noiseFloorDbfsProvider = { null },
            ) {}

            val endSample = FrameSpec.SAMPLE_RATE / 10L
            val writer = sink.open(SegmentId(0), 0L)
            writer.append(FloatArray(endSample.toInt()) { 0.1f })
            writer.close(
                SegmentRecord(
                    id = SegmentId(0),
                    startSample = 0L,
                    endSample = endSample,
                    vadStartSample = 0L,
                    vadEndSample = endSample,
                    sampleCount = endSample,
                    outcome = SegmentOutcome.REJECTED_TOO_SHORT,
                    closeReason = SegmentCloseReason.SILENCE,
                    vadFrameCount = 3,
                    vadSpeechFrameCount = 3,
                ),
            )
            DiagnosticsLog.flush()

            val lines = captureLogLines()
            assertEquals(1, lines.size)
            val line = lines.single()
            assertEquals("REJECTED_TOO_SHORT", field(line, "outcome"))
            assertEquals(
                "an unmeasurable noise floor must be the literal NONE, never a fabricated 0.0",
                "NONE",
                field(line, "noiseFloorDbfsAtOnset"),
            )
        }

    /**
     * AC-161's second driven case: a segment forced closed at the maximum-duration cap must log
     * its real `MAX_DURATION` close reason in `capture.log` -- not the `SILENCE` a hangover timeout
     * would report, and not a value hardcoded regardless of what the segment actually did.
     */
    @Test
    @Requirement("AC-161", "AC-70")
    public fun `a MAX_DURATION forced segment logs its real close reason to capture log`(): Unit = runBlocking {
        DiagnosticsLog.configure(filesDir, TestClock())
        val clock = TestClock(startMonotonicNanos = 0L, startWallMillis = 1_700_000_000_000L)
        val sampleClock = SampleClock(
            anchorMonotonicNanos = clock.monotonicNanos(),
            anchorWallMillis = clock.wallMillis(),
            anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
            sampleRate = FrameSpec.SAMPLE_RATE,
        )
        val queue = WorkQueue(db, clock)
        val sink = RealSegmentSink(filesDir, sessionId, db, queue, sampleClock, SegmentConfig()) {}

        val endSample = FrameSpec.SAMPLE_RATE.toLong() * 60 // the maximum-segment cap itself
        val writer = sink.open(SegmentId(0), 0L)
        writer.append(FloatArray(1_000) { 0.2f })
        writer.close(
            SegmentRecord(
                id = SegmentId(0),
                startSample = 0L,
                endSample = endSample,
                vadStartSample = 0L,
                vadEndSample = endSample,
                sampleCount = endSample,
                outcome = SegmentOutcome.SPEECH,
                forcedSplit = true,
                closeReason = SegmentCloseReason.MAX_DURATION,
                vadFrameCount = 1_875,
                vadSpeechFrameCount = 1_875,
            ),
        )
        DiagnosticsLog.flush()

        val lines = captureLogLines()
        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals("SPEECH", field(line, "outcome"))
        assertEquals("MAX_DURATION", field(line, "closeReason"))
        assertEquals(60_000L.toString(), field(line, "durationMs"))
        assertEquals("1875", field(line, "vadFrameCount"))
    }

    /**
     * FR-SEG-10, AC-162 (register R-1054): with the real Silero detector running this session, the
     * persisted row must name it -- [org.ort.data.entity.TransmissionEntity.vadDetector] is a plain
     * constructor value on [RealSegmentSink] (set once by `RealCaptureService.resolveVad`, not
     * re-derived here), so this proves the sink actually wires it through to the row rather than
     * silently defaulting to [VadDetectorKind.UNKNOWN]. The row also reads as conforming to
     * FR-SEG-1, and the version string this session happened to know is carried unchanged.
     */
    @Test
    @Requirement("FR-SEG-10", "AC-162")
    public fun `FR_SEG_10 with Silero running this session, the persisted row names it and conforms`(): Unit =
        runBlocking {
            val clock = TestClock(startMonotonicNanos = 0L, startWallMillis = 1_700_000_000_000L)
            val sampleClock = SampleClock(
                anchorMonotonicNanos = clock.monotonicNanos(),
                anchorWallMillis = clock.wallMillis(),
                anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
                sampleRate = FrameSpec.SAMPLE_RATE,
            )
            val queue = WorkQueue(db, clock)
            val sink = RealSegmentSink(
                filesDir,
                sessionId,
                db,
                queue,
                sampleClock,
                SegmentConfig(),
                vadDetector = VadDetectorKind.SILERO,
                vadDetectorVersion = "silero-v5",
            ) {}

            val endSample = FrameSpec.SAMPLE_RATE.toLong()
            val writer = sink.open(SegmentId(0), 0L)
            writer.append(FloatArray(endSample.toInt()) { 0.1f })
            writer.close(
                SegmentRecord(
                    id = SegmentId(0),
                    startSample = 0L,
                    endSample = endSample,
                    vadStartSample = 0L,
                    vadEndSample = endSample,
                    sampleCount = endSample,
                    outcome = SegmentOutcome.SPEECH,
                    closeReason = SegmentCloseReason.SILENCE,
                    vadFrameCount = 31,
                    vadSpeechFrameCount = 31,
                ),
            )

            val persisted = db.transmissionDao().getById("$sessionId-0")
            assertNotNull(persisted)
            assertEquals(VadDetectorKind.SILERO, persisted!!.vadDetector)
            assertEquals("silero-v5", persisted.vadDetectorVersion)
            assertTrue("Silero is one of the two detectors FR-SEG-1 names", persisted.conformsToFrSeg1())
        }

    /**
     * The discriminating other half: with the RMS-energy fallback running this session (no Silero
     * model available), the persisted row must name **that** detector, never [VadDetectorKind.SILERO]
     * by a stale default, and must read as **not** conforming to FR-SEG-1 -- exactly AC-162's second
     * driven case (segmentation is the one decision reprocessing cannot undo, CON-SEG-1, so a
     * boundary cut by an unnamed or wrongly-named detector is the provenance hole FR-SEG-10 exists
     * to close). Capture still proceeds and the row is still persisted -- constitution IV holds.
     */
    @Test
    @Requirement("FR-SEG-10", "AC-162")
    public fun `FR_SEG_10 with the energy fallback, the row names it and does not conform`(): Unit = runBlocking {
        val clock = TestClock(startMonotonicNanos = 0L, startWallMillis = 1_700_000_000_000L)
        val sampleClock = SampleClock(
            anchorMonotonicNanos = clock.monotonicNanos(),
            anchorWallMillis = clock.wallMillis(),
            anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
            sampleRate = FrameSpec.SAMPLE_RATE,
        )
        val queue = WorkQueue(db, clock)
        val sink = RealSegmentSink(
            filesDir,
            sessionId,
            db,
            queue,
            sampleClock,
            SegmentConfig(),
            vadDetector = VadDetectorKind.ENERGY,
        ) {}

        val endSample = FrameSpec.SAMPLE_RATE.toLong()
        val writer = sink.open(SegmentId(0), 0L)
        writer.append(FloatArray(endSample.toInt()) { 0.1f })
        writer.close(
            SegmentRecord(
                id = SegmentId(0),
                startSample = 0L,
                endSample = endSample,
                vadStartSample = 0L,
                vadEndSample = endSample,
                sampleCount = endSample,
                outcome = SegmentOutcome.SPEECH,
                closeReason = SegmentCloseReason.SILENCE,
                vadFrameCount = 31,
                vadSpeechFrameCount = 31,
            ),
        )

        val persisted = db.transmissionDao().getById("$sessionId-0")
        assertNotNull(persisted)
        assertEquals(VadDetectorKind.ENERGY, persisted!!.vadDetector)
        assertFalse(
            "the energy fallback is never one of the detectors FR-SEG-1 names",
            persisted.conformsToFrSeg1(),
        )
        assertEquals(TransmissionState.CAPTURED, persisted.processingState) // capture still proceeded
    }

    /** A too-short (rejected) segment gets the same treatment as an accepted one -- FR-SEG-10 makes
     * no exception for a rejected row, matching constitution III's "nothing is deleted quietly". */
    @Test
    @Requirement("FR-SEG-10", "AC-162")
    public fun `FR_SEG_10 a rejected segment still names its real detector`(): Unit = runBlocking {
        val clock = TestClock(startMonotonicNanos = 0L, startWallMillis = 1_700_000_000_000L)
        val sampleClock = SampleClock(
            anchorMonotonicNanos = clock.monotonicNanos(),
            anchorWallMillis = clock.wallMillis(),
            anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
            sampleRate = FrameSpec.SAMPLE_RATE,
        )
        val queue = WorkQueue(db, clock)
        val sink = RealSegmentSink(
            filesDir,
            sessionId,
            db,
            queue,
            sampleClock,
            SegmentConfig(),
            vadDetector = VadDetectorKind.ENERGY,
        ) {}

        val endSample = FrameSpec.SAMPLE_RATE / 10L
        val writer = sink.open(SegmentId(0), 0L)
        writer.append(FloatArray(endSample.toInt()) { 0.1f })
        writer.close(
            SegmentRecord(
                id = SegmentId(0),
                startSample = 0L,
                endSample = endSample,
                vadStartSample = 0L,
                vadEndSample = endSample,
                sampleCount = endSample,
                outcome = SegmentOutcome.REJECTED_TOO_SHORT,
                closeReason = SegmentCloseReason.SILENCE,
                vadFrameCount = 3,
                vadSpeechFrameCount = 3,
            ),
        )

        val persisted = db.transmissionDao().getById("$sessionId-0")
        assertNotNull(persisted)
        assertEquals(TransmissionState.REJECTED, persisted!!.processingState)
        assertEquals(VadDetectorKind.ENERGY, persisted.vadDetector)
        assertFalse(persisted.conformsToFrSeg1())
    }

    /** A sink built with no explicit [VadDetectorKind] (every pre-existing call site in this file)
     * must default to [VadDetectorKind.UNKNOWN], never a fabricated [VadDetectorKind.SILERO] --
     * constitution I. */
    @Test
    @Requirement("FR-SEG-10")
    public fun `FR_SEG_10 a sink built with no explicit detector persists the honest UNKNOWN default`(): Unit =
        runBlocking {
            val clock = TestClock(startMonotonicNanos = 0L, startWallMillis = 1_700_000_000_000L)
            val sampleClock = SampleClock(
                anchorMonotonicNanos = clock.monotonicNanos(),
                anchorWallMillis = clock.wallMillis(),
                anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
                sampleRate = FrameSpec.SAMPLE_RATE,
            )
            val queue = WorkQueue(db, clock)
            val sink = RealSegmentSink(filesDir, sessionId, db, queue, sampleClock, SegmentConfig()) {}

            val endSample = FrameSpec.SAMPLE_RATE.toLong()
            val writer = sink.open(SegmentId(0), 0L)
            writer.append(FloatArray(endSample.toInt()) { 0.1f })
            writer.close(
                SegmentRecord(
                    id = SegmentId(0),
                    startSample = 0L,
                    endSample = endSample,
                    vadStartSample = 0L,
                    vadEndSample = endSample,
                    sampleCount = endSample,
                    outcome = SegmentOutcome.SPEECH,
                    closeReason = SegmentCloseReason.SILENCE,
                    vadFrameCount = 31,
                    vadSpeechFrameCount = 31,
                ),
            )

            val persisted = db.transmissionDao().getById("$sessionId-0")
            assertNotNull(persisted)
            assertEquals(VadDetectorKind.UNKNOWN, persisted!!.vadDetector)
            assertFalse(persisted.conformsToFrSeg1())
        }

    /**
     * FR-SEG-10's `vad_stats` half: the detector identity and the fusion flag must land in the
     * `capture.log` line itself, not only the database row -- the two carriers FR-SEG-10 names
     * explicitly.
     */
    @Test
    @Requirement("FR-SEG-10", "AC-162")
    public fun `FR_SEG_10 the vad_stats line carries the real detector and fusion flag`(): Unit = runBlocking {
        DiagnosticsLog.configure(filesDir, TestClock())
        val clock = TestClock(startMonotonicNanos = 0L, startWallMillis = 1_700_000_000_000L)
        val sampleClock = SampleClock(
            anchorMonotonicNanos = clock.monotonicNanos(),
            anchorWallMillis = clock.wallMillis(),
            anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
            sampleRate = FrameSpec.SAMPLE_RATE,
        )
        val queue = WorkQueue(db, clock)
        val sink = RealSegmentSink(
            filesDir,
            sessionId,
            db,
            queue,
            sampleClock,
            SegmentConfig(),
            vadDetector = VadDetectorKind.SILERO,
        ) {}

        val endSample = FrameSpec.SAMPLE_RATE.toLong()
        val writer = sink.open(SegmentId(0), 0L)
        writer.append(FloatArray(endSample.toInt()) { 0.1f })
        writer.close(
            SegmentRecord(
                id = SegmentId(0),
                startSample = 0L,
                endSample = endSample,
                vadStartSample = 0L,
                vadEndSample = endSample,
                sampleCount = endSample,
                outcome = SegmentOutcome.SPEECH,
                closeReason = SegmentCloseReason.SILENCE,
                vadFrameCount = 31,
                vadSpeechFrameCount = 31,
            ),
        )
        DiagnosticsLog.flush()

        val lines = captureLogLines()
        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals("SILERO", field(line, "vadDetector"))
        assertEquals("false", field(line, "rigSquelchFusionApplied"))
    }
}
