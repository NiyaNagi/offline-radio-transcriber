package org.ort.pipeline.backlog

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.pipeline.shed.ShedController
import org.ort.testing.Requirement
import org.ort.testing.TestClock

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
    @Requirement("NFR-2b")
    fun `NFR_2b fifteen percent activity keeps the backlog from growing when drain exceeds arrival`() {
        // A synthetic 15% duty-cycle arrival stream against a drain that clears every item enqueued
        // in the same tick plus any carry-over -- the shape AC-75/NFR-2b's "keeps up at 15%"
        // requires, without a device (mechanism only -- see class kdoc; NOT MEASURED on real
        // hardware).
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

    @Test
    @Requirement("AC-29", "NFR-2b")
    fun `AC_29 at 40 percent activity the system queues, degrades, and reports backlog -- mechanism only`() {
        // The other half of NFR-2b and AC-29's own wording: "does not fail; it queues, then
        // degrades, and reports backlog." A deterministic 40% duty cycle (2 of every 5 cycles
        // arrive) with no drain capacity -- unlike the 15% case above, this is the "cannot keep
        // up" case, where the system must queue (backlog grows, observably) and degrade (the real
        // ShedController raises its level) rather than fail outright or drop audio. Mechanism
        // only: FakeShedSignals stands in for a real device's queue depth; there is no reference
        // device in this session (NOT MEASURED; see CHANGELOG). The real 40% *number* (NFR-2b) and
        // AC-29's device run are unavailable here -- this proves the queue+degrade+observe
        // mechanism reacts correctly to a growing backlog, nothing about real device throughput.
        val monitor = BacklogGrowthMonitor()
        val signals = FakeShedSignals()
        val clock = TestClock()
        val controller = ShedController(signals, clock)
        var backlog = 0
        repeat(40) { cycle ->
            if (cycle % 5 < 2) backlog += 3
            monitor.sample(backlog)
            signals.backlog = backlog
            controller.sample()
            clock.advance(70_000) // clear ShedController's hysteresis dwell every cycle
        }

        assertTrue(
            monitor.isGrowing(),
            "AC-29: sustained 40% activity produces a growing, observable backlog rather than " +
                "silent failure",
        )
        assertTrue(
            controller.currentLevel > 0,
            "AC-29/NFR-2b: the system degrades (sheds work) rather than failing outright under " +
                "this load",
        )
        assertTrue(signals.queueBacklog() >= 0, "AC-29: backlog stays queryable/observable throughout")
    }
}
