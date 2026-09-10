package org.ort.app.ui.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.rig.DefaultRigTransportFactory
import org.ort.rig.NullRigModule
import org.ort.rig.RigTransportKind
import org.ort.core.capture.RigTransportKind as PresetRigTransportKind

/**
 * WPC2's own `CaptureConfigurationStore` (`:pipeline`) is what `RealCaptureService` actually reads
 * — this proves [SetupStore]'s facts round-trip into a [org.ort.pipeline.rig.CaptureConfiguration]
 * correctly, including AC-130's own combination (Bluetooth rig control with wired/overridden
 * audio).
 */
class SetupCaptureConfigurationAdapterTest {

    @Test
    fun `no capture mode chosen yet returns null, never a fabricated default`() {
        val store = InMemorySetupStore(captureMode = null)

        assertNull(SetupCaptureConfigurationAdapter.toCaptureConfiguration(store))
    }

    @Test
    fun `local microphone mode round-trips with no rig at all`() {
        val store = InMemorySetupStore(
            captureMode = CaptureMode.LOCAL_MICROPHONE,
            selectedInputId = "mic-0",
            rigId = NullRigModule.ID,
            rigTransport = null,
        )

        val config = SetupCaptureConfigurationAdapter.toCaptureConfiguration(store)

        assertEquals(CaptureMode.LOCAL_MICROPHONE, config?.mode)
        assertEquals("mic-0", config?.selectedInputId)
        assertEquals(NullRigModule.ID, config?.rigId)
        assertNull(config?.rigTransportKind)
        assertEquals(emptyMap<String, String>(), config?.rigParams)
    }

    @Test
    fun `a Bluetooth rig link supplies the verified address as a rig param`() {
        val store = InMemorySetupStore(
            captureMode = CaptureMode.BLUETOOTH_RADIO,
            rigId = "kenwood-thd75a",
            rigTransport = PresetRigTransportKind.BLUETOOTH_SPP,
            rigBluetoothAddress = "AA:BB:CC:DD:EE:FF",
        )

        val config = SetupCaptureConfigurationAdapter.toCaptureConfiguration(store)

        assertEquals(RigTransportKind.BLUETOOTH_SPP, config?.rigTransportKind)
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            config?.rigParams?.get(DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS),
        )
    }

    /**
     * AC-130 (FR-CAP-9, FR-RIG-13): Bluetooth mode, USB audio override, Bluetooth rig transport —
     * the combination no lane names by default — arrives in the adapted configuration exactly as
     * the operator chose it, with neither axis silently corrected toward the other.
     */
    @Test
    fun `AC_130_bluetooth_control_with_wired_audio_arrives_in_the_configuration_unmodified`() {
        val store = InMemorySetupStore(
            captureMode = CaptureMode.BLUETOOTH_RADIO,
            selectedInputId = "usb-1",
            rigId = "kenwood-thd75a",
            rigTransport = PresetRigTransportKind.BLUETOOTH_SPP,
            rigBluetoothAddress = "AA:BB:CC:DD:EE:FF",
        )

        val config = SetupCaptureConfigurationAdapter.toCaptureConfiguration(store)

        assertEquals(CaptureMode.BLUETOOTH_RADIO, config?.mode)
        assertEquals("usb-1", config?.selectedInputId)
        assertEquals(RigTransportKind.BLUETOOTH_SPP, config?.rigTransportKind)
    }

    @Test
    fun `USB rig transport carries no bluetooth address param, never a stale one`() {
        val store = InMemorySetupStore(
            captureMode = CaptureMode.USB_RADIO,
            rigId = "kenwood-thd75a",
            rigTransport = PresetRigTransportKind.USB_SERIAL,
            rigBluetoothAddress = null,
        )

        val config = SetupCaptureConfigurationAdapter.toCaptureConfiguration(store)

        assertEquals(RigTransportKind.USB_SERIAL, config?.rigTransportKind)
        assertEquals(emptyMap<String, String>(), config?.rigParams)
    }
}
