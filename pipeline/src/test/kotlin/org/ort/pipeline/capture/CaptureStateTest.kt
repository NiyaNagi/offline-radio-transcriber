package org.ort.pipeline.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Build-plan P12, defect 2: the v0 status surface reported `isCapturing = true` unconditionally,
 * so a halted capture still read "Capturing" — the silent failure constitution IV exists to
 * forbid, in the one screen whose job is to report it. These tests pin the property that made
 * that bug possible: capture state is never optimistic, and a stopped capture always carries a
 * reason.
 */
class CaptureStateTest {

    @BeforeEach
    fun reset() {
        CaptureState.idle()
    }

    @Test
    fun `idle is not capturing and has no failure reason`() {
        assertFalse(CaptureState.isCapturing)
        assertNull(CaptureState.failureReason)
    }

    @Test
    fun `capturing is capturing and has no failure reason`() {
        CaptureState.capturing("SESSION01")
        assertTrue(CaptureState.isCapturing)
        assertNull(CaptureState.failureReason)
        assertEquals("SESSION01", CaptureState.sessionId)
    }

    @Test
    fun `a failed capture never reports as capturing, and names its reason`() {
        CaptureState.capturing("SESSION01")
        CaptureState.failed("route mismatch: routed to 'Built-in mic'; selected 'USB adapter'")

        assertFalse(CaptureState.isCapturing, "a halted capture must never still read as capturing")
        assertEquals(
            "route mismatch: routed to 'Built-in mic'; selected 'USB adapter'",
            CaptureState.failureReason,
        )
    }

    @Test
    fun `an interrupted capture is not capturing and names its cause`() {
        CaptureState.capturing("SESSION01")
        CaptureState.interrupted("audio focus lost")

        assertFalse(CaptureState.isCapturing)
        assertEquals("interrupted: audio focus lost", CaptureState.failureReason)
    }

    @Test
    fun `resuming after an interruption clears the reason`() {
        CaptureState.capturing("SESSION01")
        CaptureState.interrupted("audio focus lost")
        CaptureState.capturing("SESSION01")

        assertTrue(CaptureState.isCapturing)
        assertNull(CaptureState.failureReason)
    }
}
