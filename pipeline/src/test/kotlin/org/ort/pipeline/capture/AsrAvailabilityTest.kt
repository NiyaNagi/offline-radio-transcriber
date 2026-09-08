package org.ort.pipeline.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Build-plan P12: "do NOT let it silently look like transcription is working when no model is
 * present." Mirrors [CaptureState]'s own pattern (never optimistic, always names a reason) for
 * ASR specifically, since capture succeeding and ASR being unavailable are independent facts a
 * status surface must be able to tell apart.
 */
class AsrAvailabilityTest {

    @BeforeEach
    fun reset() {
        AsrAvailability.reset()
    }

    @Test
    fun `before anything checks, ASR is reported as not yet known -- never optimistically available`() {
        assertFalse(AsrAvailability.isAvailable)
        assertTrue(AsrAvailability.statusLabel.contains("not yet checked"))
    }

    @Test
    fun `available names the installed model`() {
        AsrAvailability.available("whisper-tiny-en-int8@1")
        assertTrue(AsrAvailability.isAvailable)
        assertEquals("ASR: whisper-tiny-en-int8@1", AsrAvailability.statusLabel)
    }

    @Test
    fun `unavailable is never reported as available, and names the reason`() {
        AsrAvailability.unavailable("no model installed at .../models/whisper-tiny-en-int8")
        assertFalse(AsrAvailability.isAvailable)
        assertTrue(AsrAvailability.statusLabel.contains("ASR unavailable"))
        assertTrue(AsrAvailability.statusLabel.contains("no model installed"))
    }
}
