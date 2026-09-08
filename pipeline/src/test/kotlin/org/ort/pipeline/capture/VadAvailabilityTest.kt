package org.ort.pipeline.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Mirrors [AsrAvailabilityTest]'s pattern exactly, for the VAD backend (build-plan P12). */
class VadAvailabilityTest {

    @BeforeEach
    fun reset() {
        VadAvailability.reset()
    }

    @Test
    fun `defaults to the labelled stand-in, never silently claiming Silero`() {
        assertTrue(VadAvailability.statusLabel.contains("stand-in"))
    }

    @Test
    fun `real names itself as Silero`() {
        VadAvailability.real()
        assertEquals("VAD: Silero (real)", VadAvailability.statusLabel)
    }

    @Test
    fun `stub names its reason`() {
        VadAvailability.stub("no model installed at ...")
        assertTrue(VadAvailability.statusLabel.contains("RMS-energy stand-in"))
        assertTrue(VadAvailability.statusLabel.contains("no model installed"))
    }
}
