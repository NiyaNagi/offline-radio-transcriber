package org.ort.app.ui.setup

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.fake.FakeAudioIo
import org.ort.core.capture.AudioRouteKind
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowLog

/** R-081 (ui-conformance-plan WP9) — [InputRouteEnumerator] against
 * [org.ort.capture.android.fake.FakeAudioIo] (constitution II: the same fake capture's own tests
 * use, never a second one). */
@RunWith(RobolectricTestRunner::class)
class InputRouteEnumeratorTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    /**
     * **AC-128 (FR-CAP-2b, FR-CAP-10).** This test previously asserted the exact opposite — that
     * the built-in mic row *must* be `refused` and *must* say "capture will refuse this route" —
     * and it passed, which is why the defect survived: FR-CAP-3a has always said in as many words
     * that "the built-in mic is a legitimate selection ... what is never acceptable is landing on
     * it *without having been chosen*". The old test pinned the requirement's inverse. D33 makes
     * local-microphone capture a named, first-class mode, so what the row carries now is a
     * **disclosure** ([RouteAdvisory.ROOM_AUDIO]), never a refusal.
     */
    @Test
    fun `AC_128 the built-in mic is offered as a real choice, never refused`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone")),
        )

        val route = InputRouteEnumerator(context, io).list().single()

        assertEquals(RouteAdvisory.ROOM_AUDIO, route.advisory)
        assertFalse(
            "FR-CAP-3a/FR-CAP-10: the mic is a legitimate selection, got '${route.subtitle}'",
            route.subtitle.contains("refuse", ignoreCase = true),
        )
        assertFalse(
            "FR-CAP-2b: never assert a route is not a radio, got '${route.subtitle}'",
            route.subtitle.contains("not a radio", ignoreCase = true),
        )
    }

    /** AC-129: the disclosure FR-CAP-3a requires is on the row itself, so a session recorded from
     * the room and one recorded from the radio can never be confused at the point of choosing. */
    @Test
    fun `AC_129 the built-in mic row discloses that it captures the room`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone")),
        )

        val subtitle = InputRouteEnumerator(context, io).list().single().subtitle

        assertTrue("got '$subtitle'", subtitle.contains("room", ignoreCase = true))
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

    /**
     * **AC-132 (D34, CON-CAP-1 as amended).** Bluetooth audio is now offered — the constraint no
     * longer forbids the route, it requires the route to be *marked*. The previous table had this
     * backwards in the opposite direction from the mic: it passed Bluetooth through as an ordinary
     * radio-capable source with no advisory at all, while CON-CAP-1 still banned it outright.
     */
    @Test
    fun `AC_132 the Bluetooth route is offered and discloses its narrowband degradation`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("bt-0", AudioDeviceKind.BLUETOOTH, "Handheld BT")),
        )

        val route = InputRouteEnumerator(context, io).list().single()

        assertEquals(RouteAdvisory.BLUETOOTH_DEGRADED, route.advisory)
        assertTrue(
            "CON-CAP-1: the cost must be stated where it is chosen, got '${route.subtitle}'",
            route.subtitle.contains("narrowband", ignoreCase = true),
        )
    }

    /** FR-CAP-2b: an unrecognised type is disclosed *as unrecognised* — the app says it cannot
     * tell what the device is, which is true, rather than asserting it is not a radio, which it
     * has no way to know. */
    @Test
    fun `FR_CAP_2b an unrecognised device type says so, never that it is not a radio`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("weird-0", AudioDeviceKind.UNKNOWN, "sdk_gphone64_x86_64")),
        )

        val route = InputRouteEnumerator(context, io).list().single()

        assertEquals(RouteAdvisory.UNRECOGNISED_TYPE, route.advisory)
        assertFalse(
            "must not assert what it cannot know, got '${route.subtitle}'",
            route.subtitle.contains("not a radio", ignoreCase = true),
        )
    }

    /** R-221 (validator pass 2): an unrecognised-type row had no leading icon at all, breaking the
     * icon column's alignment against every other row — a generic device outline now fills it. */
    @Test
    fun `R_221 an unrecognised device type still carries a generic device icon, never a blank column`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("weird-0", AudioDeviceKind.UNKNOWN, "sdk_gphone64_x86_64")),
        )

        val route = InputRouteEnumerator(context, io).list().single()

        assertEquals(DeviceTypeNaming.genericDevice, route.icon)
    }

    @Test
    fun `R_122 a USB device carries no advisory at all and shows the USB audio icon`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")),
        )

        val route = InputRouteEnumerator(context, io).list().single()

        assertNull("the cabled route is the clean one — nothing to disclose", route.advisory)
        assertEquals(org.ort.app.ui.components.OrtIcons.usbAudio, route.icon)
    }

    /** R-222 (validator pass 2): [InputRouteOption.typeLabel] is the field
     * `SetupActivity.RenderRouteMismatch` carries forward to S06 so the chosen device's real type
     * is not lost by the time `RouteCheckState.Mismatch` reaches [RouteMismatchScreen]. */
    @Test
    fun `R_222 typeLabel carries the same resolved type name the subtitle is built from`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")),
        )

        val route = InputRouteEnumerator(context, io).list().single()

        assertEquals("USB audio", route.typeLabel)
        assertTrue(route.subtitle.startsWith(route.typeLabel))
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

    // --- D33 (WPD): routeKind, read by ModeScreen's preset application ---------------------------

    @Test
    fun `D33 the built-in mic resolves to AudioRouteKind BUILT_IN_MIC`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone")),
        )

        val routeKind = InputRouteEnumerator(context, io).list().single().routeKind

        assertEquals(org.ort.core.capture.AudioRouteKind.BUILT_IN_MIC, routeKind)
    }

    @Test
    fun `D33 a USB device resolves to AudioRouteKind USB`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")),
        )

        val routeKind = InputRouteEnumerator(context, io).list().single().routeKind

        assertEquals(org.ort.core.capture.AudioRouteKind.USB, routeKind)
    }

    @Test
    fun `D33 a wired headset resolves to AudioRouteKind WIRED_HEADSET`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("wired-1", AudioDeviceKind.WIRED_HEADSET, "Wired headset")),
        )

        val routeKind = InputRouteEnumerator(context, io).list().single().routeKind

        assertEquals(org.ort.core.capture.AudioRouteKind.WIRED_HEADSET, routeKind)
    }

    @Test
    fun `D33 a Bluetooth device resolves to AudioRouteKind BLUETOOTH_SCO`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("bt-1", AudioDeviceKind.BLUETOOTH, "Handheld BT")),
        )

        val routeKind = InputRouteEnumerator(context, io).list().single().routeKind

        assertEquals(org.ort.core.capture.AudioRouteKind.BLUETOOTH_SCO, routeKind)
    }

    @Test
    fun `D33 an unrecognised device resolves to AudioRouteKind UNKNOWN, never guessed`() {
        val io = FakeAudioIo(
            devices = listOf(AudioDeviceDescriptor("mystery-1", AudioDeviceKind.UNKNOWN, "Mystery device")),
        )

        val routeKind = InputRouteEnumerator(context, io).list().single().routeKind

        assertEquals(org.ort.core.capture.AudioRouteKind.UNKNOWN, routeKind)
    }

    // --- R-1004 (device field report, Oppo Find X9 Ultra/ColorOS): dedupe + the device dump ------

    private fun observation(
        id: String,
        label: String = "Built-in microphone",
        kind: AudioDeviceKind = AudioDeviceKind.BUILT_IN_MIC,
        facts: DeviceHardwareFacts? = null,
    ): DeviceObservation {
        val resolved = DeviceTypeNaming.forKind(kind)
        return DeviceObservation(AudioDeviceDescriptor(id, kind, label), resolved, facts)
    }

    private fun facts(
        address: String = "",
        channelCounts: List<Int> = listOf(1),
        sampleRates: List<Int> = listOf(48_000),
        isSource: Boolean = true,
    ): DeviceHardwareFacts = DeviceHardwareFacts(address, channelCounts, sampleRates, isSource)

    /** R-1004: this is the reported bug reproduced directly — two platform ids that are, on every
     * queryable fact, the same device reported twice collapse to one row. */
    @Test
    fun `R_1004 two observations with identical real hardware facts collapse to one route`() {
        val enumerator = InputRouteEnumerator(context, FakeAudioIo())
        val shared = facts()
        val observations = listOf(observation(id = "mic-0", facts = shared), observation(id = "mic-1", facts = shared))

        val routes = enumerator.buildRouteOptions(observations)

        assertEquals(1, routes.size)
    }

    /** R-1004: a real difference in a queryable fact (here, `address`) must never be hidden — the
     * conservative key keeps both rows precisely because it cannot prove they are the same device. */
    @Test
    fun `R_1004 two observations that differ in a real hardware fact are both kept`() {
        val enumerator = InputRouteEnumerator(context, FakeAudioIo())
        val observations = listOf(
            observation(id = "mic-bottom", facts = facts(address = "bottom")),
            observation(id = "mic-top", facts = facts(address = "top")),
        )

        val routes = enumerator.buildRouteOptions(observations)

        assertEquals(2, routes.size)
    }

    /** R-1004: the two survivors above render identical subtitle text (subtitle never surfaces
     * `address`) — each must still be told apart, never silently merged by the UI text alone. */
    @Test
    fun `R_1004 two surviving routes that would render identical text are disambiguated`() {
        val enumerator = InputRouteEnumerator(context, FakeAudioIo())
        val observations = listOf(
            observation(id = "mic-bottom", facts = facts(address = "bottom")),
            observation(id = "mic-top", facts = facts(address = "top")),
        )

        val routes = enumerator.buildRouteOptions(observations)

        assertNotEquals(
            "the two rows must remain distinguishable in the rendered text",
            routes[0].subtitle,
            routes[1].subtitle,
        )
    }

    /** R-1004: with no real `AudioManager` match (every fake-backed device today), the conservative
     * fallback must never collapse two genuinely distinct fake descriptors just because they share
     * a type — this is the regression the naive "dedupe by type" key would have caused. */
    @Test
    fun `R_1004 observations with no matched hardware facts are never collapsed`() {
        val enumerator = InputRouteEnumerator(context, FakeAudioIo())
        val observations = listOf(observation(id = "mic-a", facts = null), observation(id = "mic-b", facts = null))

        val routes = enumerator.buildRouteOptions(observations)

        assertEquals(2, routes.size)
    }

    /** R-1004: the second, silent consequence — [presetInputRouteFor]'s own `singleOrNull` recovers
     * once the false duplicate is gone. */
    @Test
    fun `R_1004 the preset pre-select recovers once a true duplicate is collapsed`() {
        val enumerator = InputRouteEnumerator(context, FakeAudioIo())
        val shared = facts()
        val observations = listOf(
            observation(id = "mic-0", kind = AudioDeviceKind.BUILT_IN_MIC, facts = shared),
            observation(id = "mic-1", kind = AudioDeviceKind.BUILT_IN_MIC, facts = shared),
        )
        val routes = enumerator.buildRouteOptions(observations)

        val preset = presetInputRouteFor(org.ort.core.capture.CaptureMode.LOCAL_MICROPHONE, routes)

        assertEquals(AudioRouteKind.BUILT_IN_MIC, preset?.routeKind)
    }

    /** R-1004: the debug-visible dump — a future field report's only way to see what ColorOS (or
     * any other platform) really returned, since [DeviceHardwareFacts] cannot be captured any other
     * way from this environment. */
    @Test
    fun `R_1004 the device dump logs every real AudioDeviceInfo the platform reports`() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        Shadows.shadowOf(audioManager).addInputDevice(device, false)
        ShadowLog.clear()

        InputRouteEnumerator(context, FakeAudioIo()).list()

        // R-1004: this proves every field the dump promises is actually present in the written
        // line — not a specific `type=` value, which Robolectric's own AudioDeviceInfoBuilder does
        // not round-trip faithfully (confirmed directly: it reads back as `0`, not the constant
        // passed to `setType`, a shadow limitation this test must not depend on to discriminate).
        val logs = ShadowLog.getLogs().filter { it.tag == "InputRouteEnumerator" }
        assertTrue(
            "expected an audio_device dump line naming every real field, got $logs",
            logs.any { log ->
                log.msg.contains("audio_device") &&
                    log.msg.contains("id=") &&
                    log.msg.contains("type=") &&
                    log.msg.contains("isSource=") &&
                    log.msg.contains("productName=") &&
                    log.msg.contains("address=") &&
                    log.msg.contains("channelCounts=") &&
                    log.msg.contains("sampleRates=")
            },
        )
    }
}
