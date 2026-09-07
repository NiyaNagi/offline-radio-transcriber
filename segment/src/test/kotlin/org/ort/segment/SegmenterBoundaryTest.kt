package org.ort.segment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import kotlin.math.abs

class SegmenterBoundaryTest {

    private val rate = FrameSpec.SAMPLE_RATE
    private val frameTolerance = FrameSpec.SIZE.toLong() // one frame, in samples

    @Test
    @Requirement("AC-69", "FR-SEG-1")
    fun `AC_69 detected boundaries match hand-marked keying times within one frame`() {
        val (vad, total) = regionScript(4500, 1000..2999)
        val sink = RecordingSegmentSink()
        val seg = Segmenter(SegmentConfig(), vad, sink)
        runToCompletion(seg, rampSignal(total))

        val record = sink.records.single { it.outcome == SegmentOutcome.SPEECH }
        val expectedStart = 1000L * rate / 1000
        val expectedEnd = 3000L * rate / 1000
        assertTrue(abs(record.vadStartSample - expectedStart) <= frameTolerance, "start=${record.vadStartSample}")
        assertTrue(abs(record.vadEndSample - expectedEnd) <= frameTolerance, "end=${record.vadEndSample}")
    }

    @Test
    @Requirement("AC-69")
    fun `AC_69 boundary precision and recall over several hand-marked transmissions is 1_0`() {
        val marks = listOf(1000..1999, 3000..3999, 6000..7999)
        val (vad, total) = regionScript(9000, *marks.toTypedArray())
        val sink = RecordingSegmentSink()
        runToCompletion(Segmenter(SegmentConfig(), vad, sink), rampSignal(total))

        val detected = sink.records.filter { it.outcome == SegmentOutcome.SPEECH }
        assertEquals(marks.size, detected.size)

        var truePositives = 0
        for ((mark, record) in marks.zip(detected)) {
            val startOk = abs(record.vadStartSample - mark.first.toLong() * rate / 1000) <= frameTolerance
            val endOk = abs(record.vadEndSample - (mark.last + 1).toLong() * rate / 1000) <= frameTolerance
            if (startOk && endOk) truePositives++
        }
        val precision = truePositives.toDouble() / detected.size
        val recall = truePositives.toDouble() / marks.size
        assertEquals(1.0, precision)
        assertEquals(1.0, recall)
    }
}
