package org.ort.segment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.segment.fake.ScriptedVad
import org.ort.testing.Requirement

class StuckCarrierSplitTest {

    @Test
    @Requirement("AC-70", "FR-SEG-3")
    fun `AC_70 a stuck carrier is split at the configured maximum`() {
        val config = SegmentConfig(maxSegmentMs = 60_000)
        val totalMs = 150_000 // 150 s of continuous carrier — no VAD close ever offered
        val totalSamples = (totalMs.toLong() * FrameSpec.SAMPLE_RATE / 1000).toInt()
        val frames = totalSamples / FrameSpec.SIZE
        val vad = ScriptedVad(List(frames + 10) { VadDecision.SPEECH })
        val sink = RecordingSegmentSink()
        val seg = Segmenter(config, vad, sink)
        runToCompletion(seg, rampSignal(totalSamples))

        assertTrue(sink.records.size >= 3, "150s at a 60s cap must yield at least 3 segments")
        val maxSamples = config.msToSamples(config.maxSegmentMs).toLong()
        for (r in sink.records) {
            assertTrue(r.endSample - r.startSample <= maxSamples, "segment exceeded the configured maximum")
        }
        // every segment but the last was a forced split, not a silence close
        assertTrue(sink.records.dropLast(1).all { it.forcedSplit })
        assertTrue(!sink.records.last().forcedSplit)

        // segments are contiguous — nothing was lost at the cut points
        for (i in 1 until sink.records.size) {
            assertEquals(sink.records[i - 1].endSample, sink.records[i].startSample)
        }
    }
}
