package org.ort.app.ui.setup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.fake.FakeAudioIo
import org.robolectric.RobolectricTestRunner

/** R-081 (ui-conformance-plan WP9) — [InputRouteEnumerator] against
 * [org.ort.capture.android.fake.FakeAudioIo] (constitution II: the same fake capture's own tests
 * use, never a second one). */
@RunWith(RobolectricTestRunner::class)
class InputRouteEnumeratorTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `R_081 the built-in mic is listed and marked refused, never silently hidden`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone")),
        )

        val routes = InputRouteEnumerator(context, io).list()

        assertEquals(1, routes.size)
        assertTrue(routes.single().refused)
        assertTrue(
            "expected a refusal warning, got '${routes.single().subtitle}'",
            routes.single().subtitle.contains("refuse", ignoreCase = true),
        )
    }

    // --- R-122 (validator finding, register R-120..R-125): named by real device type ------------

    @Test
    fun `R_122 the built-in mic row names its type in the subtitle, not just Unknown`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone")),
        )

        val subtitle = InputRouteEnumerator(context, io).list().single().subtitle

        assertTrue("got '$subtitle'", subtitle.startsWith("Built-in microphone"))
    }

    @Test
    fun `R_122 an unrecognised device type is refused too, never silently treated as a radio`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("weird-0", AudioDeviceKind.UNKNOWN, "sdk_gphone64_x86_64")),
        )

        val route = InputRouteEnumerator(context, io).list().single()

        assertTrue(
            "an unrecognised type must default to refused, not silently pass as a radio",
            route.refused,
        )
    }

    @Test
    fun `R_122 a USB device is not refused and carries the USB audio icon`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")),
        )

        val route = InputRouteEnumerator(context, io).list().single()

        assertFalse(route.refused)
        assertEquals(org.ort.app.ui.components.OrtIcons.usbAudio, route.icon)
    }

    @Test
    fun `R_081 every enumerated device keeps the exact id RouteVerifier will compare against`() {
        val usb = AudioDeviceDescriptor("usb-7", AudioDeviceKind.USB_DEVICE, "USB Audio Device")
        val io = FakeAudioIo(devices = listOf(usb))

        val routes = InputRouteEnumerator(context, io).list()

        assertEquals("usb-7", routes.single().id)
        assertEquals("USB Audio Device", routes.single().label)
    }

    @Test
    fun `R_081 no native rate available is described by type alone, never a fabricated number`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")),
        )

        val subtitle = InputRouteEnumerator(context, io).list().single().subtitle

        assertFalse("no real rate was available; must not invent one, got '$subtitle'", subtitle.contains("kHz"))
        assertTrue(subtitle.isNotBlank())
    }

    @Test
    fun `R_081 an empty device list produces an empty route list, never a fabricated row`() {
        val io = FakeAudioIo(devices = emptyList())

        assertTrue(InputRouteEnumerator(context, io).list().isEmpty())
    }
}
