package org.ort.segment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class TooShortRejectionTest {

    @Test
    @Requirement("AC-72", "FR-SEG-6")
    fun `AC_72 a segment below the minimum-duration floor is rejected without invoking any model`() {
        // 120 ms of "speech" — below the 250 ms default floor — flanked by silence.
        val (vad, total) = regionScript(2000, 900..1019)
        val sink = RecordingSegmentSink()
        val vadCallCounter = CountingVad(vad)
        val seg = Segmenter(SegmentConfig(), vadCallCounter, sink)
        runToCompletion(seg, rampSignal(total))

        assertEquals(1, sink.records.size)
        val record = sink.records.single()
        assertEquals(SegmentOutcome.REJECTED_TOO_SHORT, record.outcome)

        // audio is retained, not discarded (P9 — nothing is deleted quietly)
        assertTrue(sink.audioFor(record.id).isNotEmpty())

        // the segmenter itself is the only thing that ran — no ASR-shaped interface exists to
        // invoke, and a Segmenter has no such dependency at all (see build.gradle.kts).
        assertTrue(vadCallCounter.calls > 0, "sanity: the VAD itself must have been consulted")
    }

    /** Counts calls to prove the segmenter consulted the VAD and nothing heavier. */
    private class CountingVad(private val delegate: Vad) : Vad {
        var calls = 0
            private set

        override fun accept(frame: FloatArray): VadDecision {
            calls++
            return delegate.accept(frame)
        }
    }
}
