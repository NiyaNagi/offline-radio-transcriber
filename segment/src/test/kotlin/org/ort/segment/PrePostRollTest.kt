package org.ort.segment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class PrePostRollTest {

    @Test
    @Requirement("AC-95", "FR-SEG-4", "FR-SEG-8")
    fun `AC_95 pre-roll and post-roll together retain the transmission complete`() {
        val config = SegmentConfig(preRollMs = 1200, postRollMs = 400)
        val rate = FrameSpec.SAMPLE_RATE
        val (vad, total) = regionScript(6000, 2000..2999)
        val sink = RecordingSegmentSink()
        runToCompletion(Segmenter(config, vad, sink), rampSignal(total))

        val record = sink.records.single { it.outcome == SegmentOutcome.SPEECH }

        // padded boundaries reach at least 1200 ms before and 400 ms after the keying edges
        assertTrue(record.startSample <= record.vadStartSample - (1200L * rate / 1000) + FrameSpec.SIZE)
        assertTrue(record.endSample >= record.vadEndSample + (400L * rate / 1000) - FrameSpec.SIZE)

        // the retained audio is the exact corresponding slice of the source — nothing clipped,
        // nothing fabricated (the ramp signal makes an exact slice comparison meaningful).
        val expected = rampSignal(total).copyOfRange(record.startSample.toInt(), record.endSample.toInt())
        assertTrue(expected.contentEquals(sink.audioFor(record.id)))
        assertEquals(record.endSample - record.startSample, record.sampleCount)
    }

    @Test
    @Requirement("AC-95")
    fun `AC_95 pre-roll below the FR-CAP-4 floor is rejected by SegmentConfig`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            SegmentConfig(preRollMs = 500)
        }
    }
}
