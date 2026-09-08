package org.ort.pipeline.reprocess

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * register R-091/R-143: the holder [ReprocessRunner] publishes progress on. Mirrors
 * [org.ort.pipeline.capture.ShedStatus]'s own plain-holder style.
 */
class ReprocessStatusTest {

    @BeforeEach
    fun reset() {
        ReprocessStatus.reset()
    }

    @Test
    @Requirement("R-091")
    fun `before any run, state is Idle`() {
        assertEquals(ReprocessStatus.State.Idle, ReprocessStatus.state)
    }

    @Test
    @Requirement("R-091")
    fun `running publishes done, total and the current id`() {
        ReprocessStatus.running(2, 5, "TX3")
        assertEquals(ReprocessStatus.State.Running(2, 5, "TX3"), ReprocessStatus.state)
    }

    @Test
    @Requirement("FR-REP-6")
    fun `paused publishes done and total without a current id`() {
        ReprocessStatus.paused(2, 5)
        assertEquals(ReprocessStatus.State.Paused(2, 5), ReprocessStatus.state)
    }

    @Test
    @Requirement("R-143")
    fun `done publishes the summary and changedCount sums transcript and attribution changes`() {
        val summary = ReprocessStatus.Summary(
            total = 5,
            transcriptsChanged = 2,
            attributionsChanged = 1,
            rejected = 1,
            failed = 0,
            correctedCount = 1,
        )
        ReprocessStatus.done(summary)

        assertEquals(ReprocessStatus.State.Done(summary), ReprocessStatus.state)
        assertEquals(3, summary.changedCount)
    }

    @Test
    @Requirement("R-091")
    fun `reset returns to Idle`() {
        ReprocessStatus.running(1, 2, "TX1")
        ReprocessStatus.reset()
        assertEquals(ReprocessStatus.State.Idle, ReprocessStatus.state)
    }
}
