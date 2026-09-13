package org.ort.pipeline.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.SampleClock
import org.ort.pipeline.rig.RigSquelchTransition
import org.ort.segment.FrameSpec
import org.ort.segment.SegmentConfig
import org.ort.segment.Segmenter
import org.ort.segment.SquelchGate
import org.ort.segment.fake.ScriptedVad
import org.ort.testing.Requirement

/**
 * WPSQUELCH (FR-SEG-5 / FR-RUN-16/17): [pushSquelchTransition] is the one place a rig's monotonic
 * receipt timestamp becomes a sample position — see that function's own kdoc for why
 * [SampleClock.samplePositionAtMonotonic] already *is* the correlation rule, and why the clamp
 * exists. Verified end to end through a real [Segmenter] (public API only, no reach into
 * `:segment`'s internal [SquelchGate] queue) — the only thing that actually matters is where the
 * resulting segment boundary lands.
 */
class SquelchSampleBridgeTest {

    private val sampleClock = SampleClock(
        anchorMonotonicNanos = 1_000_000_000L,
        anchorWallMillis = 1_700_000_000_000L,
        anchorUtcOffsetMinutes = 0,
        sampleRate = FrameSpec.SAMPLE_RATE,
    )

    @Test
    @Requirement("FR-SEG-5", "FR-RUN-17")
    fun `FR_RUN_17 a transition one second after the anchor lands the boundary at sample 16000, within one frame`() {
        val gate = SquelchGate()
        // Two seconds of monotonic time since the anchor at 16kHz is sample 16000 -- one second
        // of anchor offset plus one more second of elapsed time.
        pushSquelchTransition(gate, sampleClock, RigSquelchTransition(open = true, timestampNanos = 2_000_000_000L))

        // SILENCE throughout -- squelch is not yet known before sample 16000, and this test must
        // isolate the squelch-driven open from the pre-fusion "VAD decides while squelch is
        // unknown" fallback (a SPEECH-scripted VAD would open a segment at frame 0 instead).
        val vad = ScriptedVad(List(200) { org.ort.segment.VadDecision.SILENCE })
        val sink = org.ort.segment.RecordingSegmentSink()
        val seg = Segmenter(SegmentConfig(), vad, sink, squelchGate = gate)
        seg.onAudio(FloatArray(20_000)) // well past sample 16000, so the pushed transition is drained
        seg.finish()

        val record = sink.records.single()
        assertTrue(
            kotlin.math.abs(record.vadStartSample - 16_000L) < FrameSpec.SIZE,
            "boundary landed at ${record.vadStartSample}, expected within one frame of sample 16000",
        )
    }

    @Test
    @Requirement("FR-SEG-5", "FR-RUN-1")
    fun `FR_RUN_1 a transition timestamped before the session anchor is clamped to sample 0, never throws`() {
        val gate = SquelchGate()
        // The rig reported before the first audio frame was ever read -- an ordering this
        // function must survive rather than crash the bridging coroutine over
        // (SampleClock.samplePositionAtMonotonic would otherwise throw on a negative delta).
        pushSquelchTransition(gate, sampleClock, RigSquelchTransition(open = true, timestampNanos = 0L))

        val vad = ScriptedVad(List(50) { org.ort.segment.VadDecision.SPEECH })
        val sink = org.ort.segment.RecordingSegmentSink()
        val seg = Segmenter(SegmentConfig(), vad, sink, squelchGate = gate)
        seg.onAudio(FloatArray(4_000))
        seg.finish()

        val record = sink.records.single()
        assertEquals(0L, record.vadStartSample, "clamped to the session anchor, not thrown")
    }
}
