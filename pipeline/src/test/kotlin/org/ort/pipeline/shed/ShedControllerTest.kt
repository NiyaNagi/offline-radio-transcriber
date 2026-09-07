package org.ort.pipeline.shed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import org.ort.testing.TestClock

class ShedControllerTest {

    @Test
    @Requirement("AC-46", "FR-RUN-3")
    fun `AC_46 sheds in the documented order as backlog grows`() {
        val signals = FakeShedSignals()
        val clock = TestClock()
        val controller = ShedController(signals, clock)

        signals.backlog = 25
        controller.sample()
        assertEquals(1, controller.currentLevel)

        clock.advance(70_000) // clear the minimum dwell before the next rise is allowed to register
        signals.backlog = 60
        controller.sample()
        assertEquals(2, controller.currentLevel)

        clock.advance(70_000)
        signals.backlog = 150
        controller.sample()
        assertEquals(3, controller.currentLevel)
    }

    @Test
    @Requirement("AC-46")
    fun `AC_46 hysteresis holds the level until backlog falls to 0_7x threshold and the dwell elapses`() {
        val signals = FakeShedSignals(backlog = 25)
        val clock = TestClock()
        val controller = ShedController(signals, clock)
        controller.sample()
        assertEquals(1, controller.currentLevel)

        // Backlog drops just under the entry threshold but above the leave threshold (14) —
        // and the dwell has not elapsed yet either. Must not leave level 1.
        signals.backlog = 18
        controller.sample()
        assertEquals(1, controller.currentLevel, "must not oscillate before the leave threshold and dwell are both met")

        clock.advance(70_000)
        signals.backlog = 18 // still above 0.7 * 20 = 14
        controller.sample()
        assertEquals(1, controller.currentLevel, "leave threshold not yet reached")

        signals.backlog = 10 // <= 14
        controller.sample()
        assertEquals(0, controller.currentLevel)
    }

    @Test
    @Requirement("AC-103", "FR-TIER-8")
    fun `battery below the critical threshold and not charging forces level 4 immediately`() {
        val signals = FakeShedSignals(batteryPercent = 10, charging = false, backlog = 0)
        val controller = ShedController(signals, TestClock())
        controller.sample()
        assertEquals(4, controller.currentLevel)
    }

    @Test
    @Requirement("AC-103")
    fun `charging at a low battery percentage does not force level 4`() {
        val signals = FakeShedSignals(batteryPercent = 10, charging = true, backlog = 0)
        val controller = ShedController(signals, TestClock())
        controller.sample()
        assertEquals(0, controller.currentLevel)
    }
}
