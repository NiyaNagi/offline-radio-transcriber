package org.ort.segment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

private const val RATE = FrameSpec.SAMPLE_RATE

private fun msToSamples(ms: Long): Long = ms * RATE / 1000

/**
 * FR-SEG-5 (WPSQUELCH): rig squelch fusion. Register R-1062 — "nothing in `:pipeline` or
 * `:segment` gates a segment boundary on rig squelch" — these are the discriminating tests for
 * the fix: reverting [Segmenter]'s fusion branches (the `SQUELCH_OPEN`/`SQUELCH_POSTROLL` states
 * and the `squelchOpen`-aware dispatch in `handleFrame`) makes every test below fail for the
 * right reason — either the boundary reported is a VAD one (wrong `closeReason`), or the
 * `rigSquelchFusionApplied` flag on the resulting [SegmentRecord] does not match.
 */
class SquelchFusionTest {

    @Test
    @Requirement("FR-SEG-5")
    fun `FR_SEG_5 squelch open and close decide the boundary, not VAD hangover`() {
        val gate = SquelchGate()
        gate.push(open = true, atSample = 0L)
        val closeAtMs = 3000L
        gate.push(open = false, atSample = msToSamples(closeAtMs))

        // VAD: speech for the first 300ms only, then silence for 2700ms more before the squelch
        // actually closes -- default minSilenceMs (600ms) would have closed a VAD-only segment
        // via hangover long before the squelch does. Fed on top of a total 500ms past the close.
        val (vad, total) = regionScript((closeAtMs + 500).toInt(), 0..299)
        val sink = RecordingSegmentSink()
        val seg = Segmenter(SegmentConfig(), vad, sink, squelchGate = gate)
        runToCompletion(seg, rampSignal(total))

        val record = sink.records.single { it.outcome == SegmentOutcome.SPEECH }
        assertEquals(SegmentCloseReason.SQUELCH_CLOSE, record.closeReason, "squelch, not VAD hangover, closed this")
        assertFalse(record.forcedSplit)
        assertTrue(record.rigSquelchFusionApplied, "both edges were squelch-decided")
    }

    @Test
    @Requirement("FR-SEG-5", "FR-SEG-6")
    fun `FR_SEG_5 a squelch-gated interval with no speech is rejected with its own reason, never dropped`() {
        val gate = SquelchGate()
        gate.push(open = true, atSample = 0L)
        val closeAtMs = 1000L
        gate.push(open = false, atSample = msToSamples(closeAtMs))

        // No speech regions at all -- the whole interval is silence per VAD.
        val (vad, total) = regionScript((closeAtMs + 500).toInt())
        val sink = RecordingSegmentSink()
        val seg = Segmenter(SegmentConfig(), vad, sink, squelchGate = gate)
        runToCompletion(seg, rampSignal(total))

        val record = sink.records.single()
        assertEquals(SegmentOutcome.REJECTED_NO_SPEECH, record.outcome)
        assertEquals(SegmentCloseReason.SQUELCH_CLOSE, record.closeReason)
        assertTrue(record.rigSquelchFusionApplied)
        // constitution III: never deleted quietly -- the audio is retained even though rejected.
        assertTrue(sink.audioFor(record.id).isNotEmpty())
    }

    @Test
    @Requirement("FR-SEG-5", "FR-SEG-8", "FR-CAP-4")
    fun `FR_SEG_5 pre-roll and post-roll are kept around squelch edges`() {
        val config = SegmentConfig(preRollMs = 1200, postRollMs = 400)
        val openAtMs = 2000L
        val closeAtMs = 3000L
        val gate = SquelchGate()
        gate.push(true, msToSamples(openAtMs))
        gate.push(false, msToSamples(closeAtMs))

        val (vad, total) = regionScript(6000, openAtMs.toInt() until closeAtMs.toInt())
        val sink = RecordingSegmentSink()
        runToCompletion(Segmenter(config, vad, sink, squelchGate = gate), rampSignal(total))

        val record = sink.records.single { it.outcome == SegmentOutcome.SPEECH }
        assertTrue(record.startSample <= msToSamples(openAtMs) - msToSamples(1200) + FrameSpec.SIZE)
        assertTrue(record.endSample >= msToSamples(closeAtMs) + msToSamples(400) - FrameSpec.SIZE)

        // Exact slice: nothing clipped, nothing fabricated (same technique as PrePostRollTest).
        val expected = rampSignal(total).copyOfRange(record.startSample.toInt(), record.endSample.toInt())
        assertTrue(expected.contentEquals(sink.audioFor(record.id)))
        assertEquals(record.endSample - record.startSample, record.sampleCount)
    }

    @Test
    @Requirement("FR-SEG-5")
    fun `FR_SEG_5 no SquelchGate at all is byte-for-byte the pre-fusion VAD-only segmenter, flag false`() {
        val (vad, total) = regionScript(4500, 1000..2999)
        val sink = RecordingSegmentSink()
        runToCompletion(Segmenter(SegmentConfig(), vad, sink), rampSignal(total)) // no squelchGate

        val record = sink.records.single { it.outcome == SegmentOutcome.SPEECH }
        assertEquals(SegmentCloseReason.SILENCE, record.closeReason)
        assertFalse(record.rigSquelchFusionApplied)
    }

    @Test
    @Requirement("FR-SEG-5", "FR-RUN-1")
    fun `FR_SEG_5 late rig state never blocks the audio path -- VAD decides until squelch is known`() {
        val gate = SquelchGate()
        val knownAtMs = 5000L
        gate.push(true, msToSamples(knownAtMs))
        gate.push(false, msToSamples(knownAtMs + 1000))

        // First transmission (1000-2000ms) happens entirely before squelch is ever known -- must
        // be decided by VAD alone. Second (5000-5800ms) happens after squelch is known.
        val (vad, total) = regionScript(7000, 1000..1999, knownAtMs.toInt()..(knownAtMs.toInt() + 799))
        val sink = RecordingSegmentSink()
        runToCompletion(Segmenter(SegmentConfig(), vad, sink, squelchGate = gate), rampSignal(total))

        val records = sink.records.filter { it.outcome == SegmentOutcome.SPEECH }
        assertEquals(2, records.size)
        assertEquals(SegmentCloseReason.SILENCE, records[0].closeReason, "squelch not yet known: VAD decided")
        assertFalse(records[0].rigSquelchFusionApplied)
        assertEquals(SegmentCloseReason.SQUELCH_CLOSE, records[1].closeReason, "squelch known: fusion decided")
        assertTrue(records[1].rigSquelchFusionApplied)
    }

    @Test
    @Requirement("FR-SEG-5", "FR-SEG-3")
    fun `FR_SEG_5 a rig that drops mid-over closes honestly via the max-duration safety net, flag false`() {
        // Squelch opens and never closes again (the rig "drops" -- no more transitions ever
        // arrive) -- FR-SEG-3's own stuck-carrier safety net is what eventually closes this, not
        // a fusion decision.
        val config = SegmentConfig(maxSegmentMs = 2000)
        val gate = SquelchGate()
        gate.push(true, 0L)

        val totalMs = 5000
        val (vad, total) = regionScript(totalMs, 0 until totalMs)
        val sink = RecordingSegmentSink()
        runToCompletion(Segmenter(config, vad, sink, squelchGate = gate), rampSignal(total))

        assertTrue(sink.records.size >= 2, "the safety net must have force-split at least once")
        for (record in sink.records) {
            assertFalse(record.rigSquelchFusionApplied, "an administrative close is never a fusion decision")
            assertTrue(
                record.closeReason == SegmentCloseReason.MAX_DURATION ||
                    record.closeReason == SegmentCloseReason.END_OF_STREAM,
                "never SQUELCH_CLOSE -- the rig never reported one",
            )
        }
    }

    @Test
    @Requirement("FR-SEG-5", "FR-SEG-10")
    fun `FR_SEG_5 the flag is true exactly when fusion decided both edges, false for a forced-split continuation`() {
        // Squelch opens at 0 and stays open past a short maxSegmentMs, forcing a split; the
        // rig THEN closes squelch naturally on the continuation. The first (truncated) record's
        // close was administrative (MAX_DURATION); the continuation's open was administrative too
        // (a forced split, not a real squelch-open event) even though its close was real --
        // fusion never applied to either.
        val config = SegmentConfig(maxSegmentMs = 1000)
        val gate = SquelchGate()
        gate.push(true, 0L)
        gate.push(false, msToSamples(1500))

        val (vad, total) = regionScript(2000, 0 until 1500)
        val sink = RecordingSegmentSink()
        runToCompletion(Segmenter(config, vad, sink, squelchGate = gate), rampSignal(total))

        assertEquals(2, sink.records.size)
        val (first, second) = sink.records
        assertEquals(SegmentCloseReason.MAX_DURATION, first.closeReason)
        assertFalse(first.rigSquelchFusionApplied)
        assertEquals(SegmentCloseReason.SQUELCH_CLOSE, second.closeReason)
        assertFalse(
            second.rigSquelchFusionApplied,
            "this segment's own start was a forced-split continuation, not a real squelch open",
        )
    }

    @Test
    @Requirement("FR-SEG-5")
    fun `FR_SEG_5 a brief squelch flap during post-roll resumes the same segment, does not split it`() {
        val config = SegmentConfig(postRollMs = 400)
        val gate = SquelchGate()
        gate.push(true, 0L)
        gate.push(false, msToSamples(1000)) // closes...
        gate.push(true, msToSamples(1000) + FrameSpec.SIZE) // ...but reopens one frame later
        gate.push(false, msToSamples(2000))

        val (vad, total) = regionScript(2500, 0 until 2000)
        val sink = RecordingSegmentSink()
        runToCompletion(Segmenter(config, vad, sink, squelchGate = gate), rampSignal(total))

        val speechRecords = sink.records.filter { it.outcome == SegmentOutcome.SPEECH }
        assertEquals(1, speechRecords.size, "the flap must not fragment one transmission into two")
        assertTrue(speechRecords.single().rigSquelchFusionApplied)
    }

    @Test
    @Requirement("FR-SEG-5", "FR-RUN-1")
    fun `FR_SEG_5 SquelchGate push and drain never suspend -- no coroutine machinery in either method`() {
        // Structural proof that the rig's own asynchronous push can never block the audio thread
        // (constitution IV / FR-RUN-1): a suspend fun compiles to a method taking a trailing
        // kotlin.coroutines.Continuation parameter. Neither SquelchGate.push nor
        // Segmenter.onAudio (the only caller of SquelchGate's drain) has one.
        val pushMethod = SquelchGate::class.java.getMethod("push", Boolean::class.java, Long::class.java)
        assertFalse(
            pushMethod.parameterTypes.any { it.name == "kotlin.coroutines.Continuation" },
            "SquelchGate.push must be a plain, non-suspending method",
        )
        val onAudioMethod = Segmenter::class.java.getMethod("onAudio", FloatArray::class.java)
        assertFalse(
            onAudioMethod.parameterTypes.any { it.name == "kotlin.coroutines.Continuation" },
            "Segmenter.onAudio must be a plain, non-suspending method",
        )
    }
}
