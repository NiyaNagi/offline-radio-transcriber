package org.ort.capture.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class RouteVerifierTest {

    private val usb = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Adapter")
    private val builtIn = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in Microphone")

    @Test
    @Requirement("AC-2", "FR-CAP-3")
    fun `AC_2 a route landing on the built-in mic when USB was selected is a mismatch`() {
        val verdict = RouteVerifier.verify(selected = usb, routed = builtIn)
        assertTrue(verdict is RouteVerdict.Mismatch)
        assertTrue((verdict as RouteVerdict.Mismatch).reason.contains("built", ignoreCase = true))
    }

    @Test
    @Requirement("AC-2")
    fun `AC_2 no routed device at all is a mismatch, not a silent pass`() {
        val verdict = RouteVerifier.verify(selected = usb, routed = null)
        assertTrue(verdict is RouteVerdict.Mismatch)
    }

    @Test
    @Requirement("AC-98", "FR-CAP-3a")
    fun `AC_98 a deliberate selection of the built-in mic is legal and persistently labelled`() {
        val verdict = RouteVerifier.verify(selected = builtIn, routed = builtIn)
        assertTrue(verdict is RouteVerdict.Ok)
        assertTrue((verdict as RouteVerdict.Ok).isBuiltInMic)
    }

    @Test
    @Requirement("AC-2", "AC-1")
    fun `AC_2 a matching USB route verifies ok and is not labelled built-in`() {
        val verdict = RouteVerifier.verify(selected = usb, routed = usb)
        assertTrue(verdict is RouteVerdict.Ok)
        assertEquals(false, (verdict as RouteVerdict.Ok).isBuiltInMic)
    }
}
