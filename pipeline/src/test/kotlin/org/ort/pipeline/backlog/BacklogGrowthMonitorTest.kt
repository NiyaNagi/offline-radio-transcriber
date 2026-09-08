package org.ort.pipeline.backlog

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AC-75's *mechanism* (build-plan P11, NFR-2b): "the system keeps up indefinitely with no
 * backlog growth" is a claim about a *trend* over a sustained run, not one sample — a backlog
 * that oscillates between 0 and 5 is fine; one climbing 10, 20, 40, 80 is not, even if every
 * individual sample is small. [BacklogGrowthMonitor] detects the trend from whatever samples it
 * is given. This session has no reference device to run a real sustained 15%-activity session
 * on, so what is proven here is the trend detector's arithmetic against synthetic sample series
 * standing in for "keeps up" and "falls behind" — not AC-75 itself (NOT MEASURED; see
 * CHANGELOG).
 */
class BacklogGrowthMonitorTest {

    @Test
    fun `a backlog oscillating in a bounded range is not growth`() {
        val monitor = BacklogGrowthMonitor()
        listOf(0, 3, 5, 2, 4, 1, 5, 0, 3, 2).forEach { monitor.sample(it) }
        assertFalse(monitor.isGrowing())
    }

    @Test
    fun `a backlog that keeps draining to zero every cycle is not growth`() {
        val monitor = BacklogGrowthMonitor()
        repeat(20) { cycle ->
            monitor.sample(if (cycle % 2 == 0) 3 else 0)
        }
        assertFalse(monitor.isGrowing())
    }

    @Test
    fun `a monotonically climbing backlog is growth`() {
        val monitor = BacklogGrowthMonitor()
        (0 until 20).forEach { monitor.sample(it * 5) }
        assertTrue(monitor.isGrowing())
    }

    @Test
    fun `too few samples cannot support a growth verdict either way`() {
        val monitor = BacklogGrowthMonitor()
        monitor.sample(0)
        monitor.sample(100)
        assertFalse(monitor.isGrowing())
    }

    @Test
    fun `simulateFifteenPercentActivity keeps the backlog from growing when drain capacity exceeds arrival rate`() {
        // A synthetic 15% duty-cycle arrival stream against a drain that clears every item enqueued
        // in the same tick plus any carry-over -- the shape AC-75 requires, without a device.
        val monitor = BacklogGrowthMonitor()
        var backlog = 0
        val random = java.util.Random(42)
        repeat(500) {
            if (random.nextDouble() < 0.15) backlog += 1
            val drained = minOf(backlog, 2) // drain capacity comfortably exceeds 15% arrival rate
            backlog -= drained
            monitor.sample(backlog)
        }
        assertFalse(monitor.isGrowing())
    }
}
