package org.ort.pipeline.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * register R-104: `:pipeline` had no thermal signal at all before this. Mirrors [AsrAvailabilityTest]'s
 * own style — never optimistic, always honest about what has not yet been measured.
 */
class ThermalStatusTest {

    @BeforeEach
    fun reset() {
        ThermalStatus.reset()
    }

    @Test
    @Requirement("F-007", "R-104")
    fun `before anything ticks, thermal is reported nominal with no measured RTF -- never invented`() {
        val state = ThermalStatus.state
        assertTrue(state is ThermalStatus.State.Nominal)
        assertEquals(ThermalStatus.THERMAL_STATUS_NONE, state.osThermalStatus)
        assertNull(state.realTimeFactor)
    }

    @Test
    @Requirement("F-007", "R-104")
    fun `F7_moderate_os_thermal_status_publishes_Warm`() {
        ThermalStatus.update(ThermalStatus.THERMAL_STATUS_MODERATE, realTimeFactor = 0.9)
        val state = ThermalStatus.state
        assertTrue(state is ThermalStatus.State.Warm)
        assertEquals(0.9, state.realTimeFactor)
    }

    @Test
    @Requirement("F-007", "R-104")
    fun `F7_severe_or_worse_os_thermal_status_publishes_Hot`() {
        ThermalStatus.update(ThermalStatus.THERMAL_STATUS_SEVERE, realTimeFactor = 1.4)
        assertTrue(ThermalStatus.state is ThermalStatus.State.Hot)

        ThermalStatus.update(ThermalStatus.THERMAL_STATUS_SHUTDOWN, realTimeFactor = 2.0)
        assertTrue(ThermalStatus.state is ThermalStatus.State.Hot)
    }

    @Test
    @Requirement("F-007", "R-104")
    fun `F7_light_os_thermal_status_still_reads_Nominal`() {
        ThermalStatus.update(ThermalStatus.THERMAL_STATUS_LIGHT, realTimeFactor = 0.4)
        assertTrue(ThermalStatus.state is ThermalStatus.State.Nominal)
    }

    @Test
    @Requirement("F-007", "R-104")
    fun `F7_recordPassTiming_smooths_and_sample_republishes_it_on_the_next_tick`() {
        ThermalStatus.recordPassTiming(1.0)
        // sample() alone (no update()) must not have touched anything yet -- recordPassTiming does
        // not by itself change published state.
        assertNull((ThermalStatus.state as ThermalStatus.State.Nominal).realTimeFactor)

        ThermalStatus.sample(ThermalStatus.THERMAL_STATUS_MODERATE)
        val afterFirstSample = ThermalStatus.state
        assertTrue(afterFirstSample is ThermalStatus.State.Warm)
        assertEquals(1.0, afterFirstSample.realTimeFactor)

        // A second, much faster pass pulls the smoothed value down, but not all the way to it.
        ThermalStatus.recordPassTiming(0.0, alpha = 0.5)
        ThermalStatus.sample(ThermalStatus.THERMAL_STATUS_MODERATE)
        val afterSecondSample = ThermalStatus.state
        assertEquals(0.5, afterSecondSample.realTimeFactor)
    }

    @Test
    @Requirement("F-007", "R-104")
    fun `F7_reset_returns_to_the_honest_not_yet_measured_default`() {
        ThermalStatus.update(ThermalStatus.THERMAL_STATUS_SEVERE, realTimeFactor = 2.0)
        ThermalStatus.reset()
        val state = ThermalStatus.state
        assertTrue(state is ThermalStatus.State.Nominal)
        assertEquals(ThermalStatus.THERMAL_STATUS_NONE, state.osThermalStatus)
        assertNull(state.realTimeFactor)
    }
}
