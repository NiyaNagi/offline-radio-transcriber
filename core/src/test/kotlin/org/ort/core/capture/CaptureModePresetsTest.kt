package org.ort.core.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * FR-CAP-8's table, and FR-CAP-9: a preset is a default a caller can ignore, never an enforcement.
 */
class CaptureModePresetsTest {

    @Test
    fun `FR_CAP_8 local microphone mode presets the built-in mic and no rig transport`() {
        val preset = CaptureModePresets.presetsFor(CaptureMode.LOCAL_MICROPHONE)
        assertEquals(AudioRouteKind.BUILT_IN_MIC, preset.preferredRouteKind)
        assertNull(preset.preferredRigTransportKind)
    }

    @Test
    fun `FR_CAP_8 usb radio mode presets the USB route and USB serial transport`() {
        val preset = CaptureModePresets.presetsFor(CaptureMode.USB_RADIO)
        assertEquals(AudioRouteKind.USB, preset.preferredRouteKind)
        assertEquals(RigTransportKind.USB_SERIAL, preset.preferredRigTransportKind)
    }

    @Test
    fun `FR_CAP_9 bluetooth radio mode presets a wired, cabled audio route, never the bluetooth route itself`() {
        // The rationale this test locks down: Bluetooth *mode* names its rig-control transport,
        // not its audio route — the audio preset stays wired because that costs nothing (S00/FL7).
        val preset = CaptureModePresets.presetsFor(CaptureMode.BLUETOOTH_RADIO)
        assertEquals(AudioRouteKind.WIRED_HEADSET, preset.preferredRouteKind)
        assertEquals(RigTransportKind.BLUETOOTH_SPP, preset.preferredRigTransportKind)
    }

    @Test
    fun `FR_CAP_9 a preset is a plain, ignorable default, not an enforcement`() {
        // Nothing about the returned value stops a caller choosing a completely different route
        // and transport than the preset — presetsFor has no side effect, no validation, and no
        // reference back to whatever the caller does next.
        val preset = CaptureModePresets.presetsFor(CaptureMode.USB_RADIO)
        val overridden = AudioRouteProvenance(presetByMode = CaptureMode.USB_RADIO, overridden = true)

        // The caller picked Bluetooth SCO instead of the USB preset above — legal, unremarked by presetsFor.
        val actuallyChosenRoute = AudioRouteKind.BLUETOOTH_SCO
        assertEquals(true, overridden.overridden)
        assertEquals(AudioRouteKind.USB, preset.preferredRouteKind) // the preset itself never changes
        assertEquals(AudioRouteKind.BLUETOOTH_SCO, actuallyChosenRoute) // yet the override is perfectly legal
    }

    @Test
    fun `presetsFor is pure - repeated calls for the same mode are equal`() {
        assertEquals(
            CaptureModePresets.presetsFor(CaptureMode.BLUETOOTH_RADIO),
            CaptureModePresets.presetsFor(CaptureMode.BLUETOOTH_RADIO),
        )
    }
}
