package org.ort.capture.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.capture.BluetoothAudioProfile
import org.ort.testing.Requirement

class RouteVerifierTest {

    private val usb = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Adapter")
    private val builtIn = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in Microphone")
    private val bluetooth = AudioDeviceDescriptor(
        id = "bt-1",
        kind = AudioDeviceKind.BLUETOOTH,
        label = "Bluetooth Headset",
        bluetoothProfile = BluetoothAudioProfile.HFP_MSBC,
    )

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

    /**
     * E2-D03, FR-CAP-3, FR-CAP-3a: route verification treats Bluetooth exactly as any other
     * selection — it halts on route ≠ selection, never merely because the route is Bluetooth.
     */
    @Test
    @Requirement("FR-CAP-3", "FR-CAP-11")
    fun `E2_D03 a bluetooth selection routed to the built-in mic is a mismatch`() {
        val verdict = RouteVerifier.verify(selected = bluetooth, routed = builtIn)
        assertTrue(verdict is RouteVerdict.Mismatch)
        assertTrue((verdict as RouteVerdict.Mismatch).reason.contains("built", ignoreCase = true))
    }

    @Test
    @Requirement("FR-CAP-3a")
    fun `E2_D03 the built-in mic selected and routed verifies ok, unaffected by bluetooth being available`() {
        val verdict = RouteVerifier.verify(selected = builtIn, routed = builtIn)
        assertTrue(verdict is RouteVerdict.Ok)
        assertTrue((verdict as RouteVerdict.Ok).isBuiltInMic)
    }

    @Test
    @Requirement("FR-CAP-3", "FR-CAP-11", "D34")
    fun `E2_D03 a bluetooth selection routed to itself verifies ok and carries the negotiated profile`() {
        val verdict = RouteVerifier.verify(selected = bluetooth, routed = bluetooth)
        assertTrue(verdict is RouteVerdict.Ok)
        val ok = verdict as RouteVerdict.Ok
        assertEquals(false, ok.isBuiltInMic)
        assertEquals(BluetoothAudioProfile.HFP_MSBC, ok.routed.bluetoothProfile)
    }

    @Test
    @Requirement("FR-CAP-11")
    fun `a bluetooth device with no negotiated profile yet verifies ok, disclosing absence rather than guessing`() {
        val undetermined = bluetooth.copy(bluetoothProfile = null)
        val verdict = RouteVerifier.verify(selected = undetermined, routed = undetermined)
        assertTrue(verdict is RouteVerdict.Ok)
        assertNull((verdict as RouteVerdict.Ok).routed.bluetoothProfile)
    }
}
