package org.ort.pipeline.latency

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AC-73's *mechanism* (build-plan P11): p95 segment-close-to-visible latency, computed correctly
 * from whatever samples it is fed. This session has no reference device and no real segment
 * timings, so what is proven here is the arithmetic and the threshold check — not the number
 * AC-73 requires (NOT MEASURED; see CHANGELOG). Feeding this real device timings later is a
 * drop-in, not a rewrite.
 */
class LatencyRecorderTest {

    @Test
    fun `p95 of a known sample set matches the standard nearest-rank definition`() {
        val recorder = LatencyRecorder()
        // 1..100 ms: the 95th percentile by nearest-rank is the 95th smallest value, i.e. 95.
        (1..100).forEach { recorder.record(it.toLong()) }
        assertEquals(95L, recorder.p95Millis())
    }

    @Test
    fun `a single sample's p95 is itself`() {
        val recorder = LatencyRecorder()
        recorder.record(1_234L)
        assertEquals(1_234L, recorder.p95Millis())
    }

    @Test
    fun `no samples yields null, never a fabricated zero`() {
        val recorder = LatencyRecorder()
        assertEquals(null, recorder.p95Millis())
    }

    @Test
    fun `meetsTarget is true iff p95 is at or under the target`() {
        val recorder = LatencyRecorder()
        (1..20).forEach { recorder.record(100L) } // p95 = 100ms, well under 2000ms
        assertTrue(recorder.meetsTarget(targetMillis = 2_000L))
        val slow = LatencyRecorder()
        (1..20).forEach { slow.record(5_000L) }
        assertFalse(slow.meetsTarget(targetMillis = 2_000L))
    }

    @Test
    fun `recordSpan derives the duration from monotonic close and visible timestamps`() {
        val recorder = LatencyRecorder()
        recorder.recordSpan(segmentCloseNanos = 0L, visibleNanos = 1_500_000_000L) // 1.5s
        assertEquals(1_500L, recorder.p95Millis())
    }
}
