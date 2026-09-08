package org.ort.pipeline.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ort.segment.FrameSpec
import org.ort.segment.SegmentConfig
import org.ort.segment.SegmentId
import org.ort.segment.SegmentRecord
import org.ort.segment.SegmentSink
import org.ort.segment.SegmentWriter
import org.ort.segment.Segmenter
import org.ort.segment.SileroVad
import org.ort.segment.VadModel
import org.ort.testing.Requirement

/**
 * F-005 (audit-2026-09-07): before this fix, [RealCaptureService.onHeartbeat] always wrote
 * `HeartbeatRecord(sessionId, monotonicNanos, wallMillis, 0L)` — the sample position was a
 * fabricated literal, never the real value the parallel `capture-android` `CaptureService`
 * threads through (FR-RUN-15..18; constitution VI: "no number without provenance"). Rather than
 * starting the whole `android.app.Service` under Robolectric (no `ServiceController` harness
 * exists yet for it — see F-011, still open), this drives the exact seam the fix touches: the
 * [Segmenter.position] the service now reads when it builds a heartbeat, fed real audio through
 * the same [Segmenter] class the service constructs.
 */
public class RealCaptureServiceHeartbeatTest {

    /** A VAD that always reports silence, so no segment ever opens -- only `position()` matters. */
    private val silentVad = object : VadModel {
        override fun speechProbability(frame: FloatArray): Float = 0f
    }

    private val noopSink = object : SegmentSink {
        override fun open(id: SegmentId, startSample: Long): SegmentWriter = object : SegmentWriter {
            override fun append(pcm: FloatArray) = Unit
            override fun close(record: SegmentRecord): SegmentRecord = record
        }
    }

    @Test
    @Requirement("FR-RUN-16")
    public fun FR_RUN_16_the_heartbeat_carries_the_last_captured_sample_position_not_zero() {
        val segmenter = Segmenter(SegmentConfig(), SileroVad(silentVad), noopSink)

        val frame = FloatArray(FrameSpec.SIZE) { 0f }
        val framesToFeed = 5
        repeat(framesToFeed) { segmenter.onAudio(frame) }
        val expectedSamplePosition = framesToFeed.toLong() * FrameSpec.SIZE

        // This is exactly the call RealCaptureService.onHeartbeat now makes: the record's sample
        // position comes from the segmenter that has actually consumed the audio, not a literal.
        val record = buildHeartbeatRecord(sessionId = "SESSION01", samplePosition = segmenter::position)

        assertEquals(expectedSamplePosition, record.samplePosition)
        assertEquals(expectedSamplePosition, segmenter.position())
    }

    @Test
    @Requirement("FR-RUN-16")
    public fun FR_RUN_16_zero_is_reported_only_before_any_sample_has_been_read() {
        val segmenter = Segmenter(SegmentConfig(), SileroVad(silentVad), noopSink)

        val record = buildHeartbeatRecord(sessionId = "SESSION01", samplePosition = segmenter::position)

        assertEquals(0L, record.samplePosition)
    }
}
