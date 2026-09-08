package org.ort.pipeline.capture

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.pipeline.PipelineTestFixtures
import org.ort.segment.FrameSpec
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
}
