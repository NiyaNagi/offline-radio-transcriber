package org.ort.segment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * AC-71: the merge/split trade is a **measured function of minSilenceMs**, not something tuned
 * by ear. Two speech bursts separated by a fixed 500 ms gap either merge into one segment or
 * stay split into two, purely as a function of the configured threshold.
 */
class MergeSplitTradeTest {

    private fun segmentCountFor(minSilenceMs: Int): Int {
        val (vad, total) = regionScript(4000, 1000..1999, 2500..3499) // 500 ms gap between bursts
        val sink = RecordingSegmentSink()
        val seg = Segmenter(SegmentConfig(minSilenceMs = minSilenceMs), vad, sink)
        runToCompletion(seg, rampSignal(total))
        return sink.records.count { it.outcome == SegmentOutcome.SPEECH }
    }

    @Test
    @Requirement("AC-71", "FR-SEG-2")
    fun `AC_71 a short minSilenceMs splits a 500ms gap into two segments`() {
        assertEquals(2, segmentCountFor(minSilenceMs = 300))
    }

    @Test
    @Requirement("AC-71", "FR-SEG-2")
    fun `AC_71 a long minSilenceMs merges the same 500ms gap into one segment`() {
        assertEquals(1, segmentCountFor(minSilenceMs = 800))
    }
}
