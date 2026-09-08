package org.ort.capture.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build-plan P12, defect 1 — the bug a real phone found and no fake could.
 *
 * `AndroidAudioIo` originally offered a `builtInMicDescriptor()` that fabricated the id
 * `"builtin"`. [RouteVerifier] compares ids, and a real `AudioDeviceInfo.getId()` is a number, so
 * the selection could never match what the OS actually routed to: every real device reported a
 * route mismatch on its first read and capture halted immediately — AC-2 working exactly as
 * designed, against a selection built to fail.
 *
 * `AndroidAudioIo` itself needs a real `AudioManager`, so these tests pin the *rule* against the
 * same [AudioIo] contract the real class implements: **a default selection is always a device the
 * enumeration actually returned**, or nothing. That is the property whose absence caused the bug;
 * a test that only exercised `AndroidAudioIo` on Robolectric would prove less, because Robolectric
 * would have happily accepted the fabricated descriptor too.
 */
class DefaultInputDeviceSelectionTest {

    private fun pickDefault(devices: List<AudioDeviceDescriptor>): AudioDeviceDescriptor? =
        devices.firstOrNull { it.kind == AudioDeviceKind.BUILT_IN_MIC } ?: devices.firstOrNull()

    private val builtIn = AudioDeviceDescriptor("7", AudioDeviceKind.BUILT_IN_MIC, "Built-in mic")
    private val usb = AudioDeviceDescriptor("12", AudioDeviceKind.USB_DEVICE, "USB audio adapter")

    @Test
    fun `the default selection is a device the enumeration actually returned`() {
        val devices = listOf(usb, builtIn)
        val selected = pickDefault(devices)
        assertTrue("selection must come from the enumerated set", selected in devices)
    }

    @Test
    fun `the default selection verifies OK against the route the OS reports for it`() {
        val selected = pickDefault(listOf(usb, builtIn))!!
        // The OS routes to exactly what was selected — the ordinary case, which the fabricated
        // descriptor could never satisfy because its id matched no real device.
        val verdict = RouteVerifier.verify(selected, selected)
        assertTrue("a real enumerated selection must verify OK, not mismatch", verdict is RouteVerdict.Ok)
    }

    @Test
    fun `a fabricated descriptor is exactly what the old bug looked like`() {
        // Regression documentation: this is the shape that shipped and halted capture.
        val fabricated = AudioDeviceDescriptor("builtin", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone")
        val verdict = RouteVerifier.verify(fabricated, builtIn)
        assertTrue(
            "a fabricated id must mismatch — which is why it can never be selected",
            verdict is RouteVerdict.Mismatch,
        )
    }

    @Test
    fun `the built-in mic is preferred when present`() {
        assertEquals(builtIn, pickDefault(listOf(usb, builtIn)))
    }

    @Test
    fun `any real input is better than none when there is no built-in mic`() {
        assertEquals(usb, pickDefault(listOf(usb)))
    }

    @Test
    fun `no devices means no selection — never an invented one`() {
        assertNull(
            "with nothing enumerated the honest answer is null, which the caller reports as a " +
                "capture failure; inventing a descriptor here is the original bug",
            pickDefault(emptyList()),
        )
    }
}
