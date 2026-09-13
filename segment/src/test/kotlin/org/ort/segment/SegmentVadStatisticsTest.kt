package org.ort.segment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.segment.fake.ScriptedVad
import org.ort.testing.Requirement

/**
 * FR-OBS-1 (Q20, amended by D41 into AC-161): the per-transmission VAD statistics `capture.log`
 * was promised since draft 1 and never wrote (spec/open-questions.md Q20) —
 * [SegmentRecord.closeReason], [SegmentRecord.vadFrameCount] and [SegmentRecord.vadSpeechFrameCount]
 * are the segmenter's own honest half of that gap, and the ground truth AC-161's three
 * segmenter-side driven cases (closed by silence, forced at maximum duration, rejected as too
 * short) depend on. Each case here is a discriminating one: reverting the [Segmenter] changes that
 * thread [SegmentCloseReason] and the frame tally through [Segmenter.closeSpeech]/
 * [Segmenter.emitRejected] makes every one of these fail (either a compile error from the
 * reintroduced `forced: Boolean` parameter, or a wrong [SegmentCloseReason]/count once a
 * placeholder is substituted). The fourth driven case (no noise-floor reading available) and "the
 * same statistics in the debug dump" are proven downstream, in `:pipeline`'s
 * `RealSegmentSinkTest`/`DiagnosticsLogTest` and `:app`'s `DebugDumpBuilderTest`.
 */
class SegmentVadStatisticsTest {

    @Test
    @Requirement("FR-OBS-1", "AC-161")
    fun `a segment that closes on the VAD hangover timeout reports SILENCE and its real frame tally`() {
        // 10 SPEECH frames (confirms at frame 8, default minSpeechMs=250ms=8 frames of 32ms) then
        // 19 SILENCE frames (default minSilenceMs=600ms needs 19*32ms=608ms to trip the hangover
        // close) -- exactly enough silence to close naturally, not a single frame more.
        val script = ScriptedVad(List(10) { VadDecision.SPEECH } + List(19) { VadDecision.SILENCE })
        val sink = RecordingSegmentSink()
        val seg = Segmenter(SegmentConfig(), script, sink)
        runToCompletion(seg, rampSignal(29 * FrameSpec.SIZE))

        assertEquals(1, sink.records.size)
        val record = sink.records.single()
        assertEquals(SegmentOutcome.SPEECH, record.outcome)
        assertEquals(SegmentCloseReason.SILENCE, record.closeReason)
        assertEquals(false, record.forcedSplit)
        assertEquals(29, record.vadFrameCount)
        assertEquals(10, record.vadSpeechFrameCount)
    }

    @Test
    @Requirement("FR-OBS-1", "AC-70", "AC-161")
    fun `a segment forced closed by the maximum-duration cap reports MAX_DURATION, not SILENCE`() {
        val config = SegmentConfig(maxSegmentMs = 60_000)
        val totalMs = 150_000 // 150 s of continuous carrier, never a VAD close
        val totalSamples = (totalMs.toLong() * FrameSpec.SAMPLE_RATE / 1000).toInt()
        val frames = totalSamples / FrameSpec.SIZE
        val vad = ScriptedVad(List(frames + 10) { VadDecision.SPEECH })
        val sink = RecordingSegmentSink()
        val seg = Segmenter(config, vad, sink)
        runToCompletion(seg, rampSignal(totalSamples))

        // every segment but the last was cut by the cap; the stream then ended mid-speech, which
        // is a genuinely different reason (below) -- never mislabelled as the same SILENCE close a
        // hangover timeout would report.
        val forced = sink.records.dropLast(1)
        assertEquals(true, forced.isNotEmpty())
        for (r in forced) assertEquals(SegmentCloseReason.MAX_DURATION, r.closeReason)
        assertEquals(SegmentCloseReason.END_OF_STREAM, sink.records.last().closeReason)
    }

    @Test
    @Requirement("FR-OBS-1")
    fun `a candidate too short to confirm when the stream ends reports END_OF_STREAM, not SILENCE`() {
        // 3 SPEECH frames -- below the 8-frame confirm floor -- then the stream simply ends
        // without the VAD ever flipping back to silence.
        val script = ScriptedVad(List(3) { VadDecision.SPEECH })
        val sink = RecordingSegmentSink()
        val seg = Segmenter(SegmentConfig(), script, sink)
        runToCompletion(seg, rampSignal(3 * FrameSpec.SIZE))

        assertEquals(1, sink.records.size)
        val record = sink.records.single()
        assertEquals(SegmentOutcome.REJECTED_TOO_SHORT, record.outcome)
        assertEquals(SegmentCloseReason.END_OF_STREAM, record.closeReason)
        assertEquals(3, record.vadFrameCount)
        assertEquals(3, record.vadSpeechFrameCount)
    }

    @Test
    @Requirement("FR-OBS-1", "AC-161")
    fun `a candidate that returns to silence before confirming reports SILENCE, not END_OF_STREAM`() {
        // 3 SPEECH frames, then silence -- below the confirm floor, rejected while the stream
        // keeps running (never reaches Segmenter.finish() in a TENTATIVE state).
        val script = ScriptedVad(List(3) { VadDecision.SPEECH } + List(5) { VadDecision.SILENCE })
        val sink = RecordingSegmentSink()
        val seg = Segmenter(SegmentConfig(), script, sink)
        runToCompletion(seg, rampSignal(8 * FrameSpec.SIZE))

        assertEquals(1, sink.records.size)
        val record = sink.records.single()
        assertEquals(SegmentOutcome.REJECTED_TOO_SHORT, record.outcome)
        assertEquals(SegmentCloseReason.SILENCE, record.closeReason)
        // The 4th frame -- the first SILENCE decision, which is what triggers the rejection --
        // still belongs to this candidate's own window (it was fed while state was TENTATIVE), so
        // it counts too: 3 SPEECH frames plus the 1 SILENCE frame that closed the window.
        assertEquals(4, record.vadFrameCount)
        assertEquals(3, record.vadSpeechFrameCount)
    }

    @Test
    @Requirement("FR-OBS-1")
    fun `a segment truncated mid-hangover by end of stream reports END_OF_STREAM, tally intact`() {
        // 10 SPEECH frames (confirms), then 5 SILENCE frames -- well short of the 19 needed to
        // close the hangover naturally -- then the stream ends.
        val script = ScriptedVad(List(10) { VadDecision.SPEECH } + List(5) { VadDecision.SILENCE })
        val sink = RecordingSegmentSink()
        val seg = Segmenter(SegmentConfig(), script, sink)
        runToCompletion(seg, rampSignal(15 * FrameSpec.SIZE))

        assertEquals(1, sink.records.size)
        val record = sink.records.single()
        assertEquals(SegmentOutcome.SPEECH, record.outcome)
        assertEquals(SegmentCloseReason.END_OF_STREAM, record.closeReason)
        assertEquals(false, record.forcedSplit)
        assertEquals(15, record.vadFrameCount)
        assertEquals(10, record.vadSpeechFrameCount)
    }

    @Test
    @Requirement("FR-OBS-1")
    fun `each segment's frame tally resets and does not leak into the next one`() {
        // Two well-separated transmissions in one run: 10 speech + 19 silence (closes naturally),
        // then a gap of plain silence, then a second, shorter 10 speech + 19 silence run. The
        // second segment's tally must not carry over any of the first's frames.
        val gapFrames = 5
        val script = ScriptedVad(
            List(10) { VadDecision.SPEECH } + List(19) { VadDecision.SILENCE } +
                List(gapFrames) { VadDecision.SILENCE } +
                List(10) { VadDecision.SPEECH } + List(19) { VadDecision.SILENCE },
        )
        val sink = RecordingSegmentSink()
        val seg = Segmenter(SegmentConfig(), script, sink)
        runToCompletion(seg, rampSignal((29 + gapFrames + 29) * FrameSpec.SIZE))

        assertEquals(2, sink.records.size)
        for (record in sink.records) {
            assertEquals(29, record.vadFrameCount)
            assertEquals(10, record.vadSpeechFrameCount)
        }
    }
}
