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

        assertNull(SetupCaptureConfigurationAdapter.toCaptureConfiguration(store, manualFrequencyHz = null))
    }

    @Test
    fun `local microphone mode round-trips with no rig at all`() {
        val store = InMemorySetupStore(
            captureMode = CaptureMode.LOCAL_MICROPHONE,
            selectedInputId = "mic-0",
            rigId = NullRigModule.ID,
            rigTransport = null,
        )

        val config = SetupCaptureConfigurationAdapter.toCaptureConfiguration(store, manualFrequencyHz = null)

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

        val config = SetupCaptureConfigurationAdapter.toCaptureConfiguration(store, manualFrequencyHz = null)

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

        val config = SetupCaptureConfigurationAdapter.toCaptureConfiguration(store, manualFrequencyHz = null)

        assertEquals(CaptureMode.BLUETOOTH_RADIO, config?.mode)
        assertEquals("usb-1", config?.selectedInputId)
        assertEquals(RigTransportKind.BLUETOOTH_SPP, config?.rigTransportKind)
    }

    /** Register R-1030 (FR-CAP-13): the "logged by hand" frequency must reach the
     * [org.ort.pipeline.rig.CaptureConfiguration] `RealCaptureService` actually reads, or the screen's
     * own claim that it does is false. **P39: it is now carried through from the configuration store
     * rather than read from [SetupStore]** — see [SetupCaptureConfigurationAdapter]'s own doc comment. */
    @Test
    fun `R_1030 a manually entered frequency carries through to the capture configuration`() {
        val store = InMemorySetupStore(captureMode = CaptureMode.LOCAL_MICROPHONE, rigId = NullRigModule.ID)

        val config = SetupCaptureConfigurationAdapter.toCaptureConfiguration(store, manualFrequencyHz = 146_520_000L)

        assertEquals(146_520_000L, config?.manualFrequencyHz)
    }

    @Test
    fun `R_1030 no frequency ever entered carries through as null, never a fabricated 0`() {
        val store = InMemorySetupStore(captureMode = CaptureMode.LOCAL_MICROPHONE, rigId = NullRigModule.ID)

        val config = SetupCaptureConfigurationAdapter.toCaptureConfiguration(store, manualFrequencyHz = null)

        assertNull(config?.manualFrequencyHz)
    }

    /**
     * **AC-202 / R-1167: the silent data loss this parameter exists to prevent.**
     *
     * P39 moves the frequency prompt out of onboarding and into the log's own header, so nothing writes
     * `SetupStore.manualFrequencyHz` any more. `CaptureConfigurationStore.update` replaces the whole
     * configuration, so an adapter still sourcing that field from [SetupStore] would push `null` over
     * whatever the operator typed — on any later trip through setup, with no error, and with no symptom
     * but every subsequent over logged without a frequency.
     *
     * This is the pure half of the discrimination: the adapter must return the value it was handed even
     * though nothing in the store mentions it. Reverting the parameter to `store.manualFrequencyHz`
     * cannot make this pass, because the store has no such property to read.
     */
    @Test
    fun `AC_202 a frequency set outside setup survives an adapted configuration untouched`() {
        val store = InMemorySetupStore(
            captureMode = CaptureMode.USB_RADIO,
            selectedInputId = "usb-1",
            rigId = NullRigModule.ID,
        )

        val config = SetupCaptureConfigurationAdapter.toCaptureConfiguration(store, manualFrequencyHz = 145_230_000L)

        assertEquals(
            145_230_000L,
            config?.manualFrequencyHz,
            "setup must carry the operator's own frequency through, never replace it",
        )
    }

    @Test
    fun `USB rig transport carries no bluetooth address param, never a stale one`() {
        val store = InMemorySetupStore(
            captureMode = CaptureMode.USB_RADIO,
            rigId = "kenwood-thd75a",
            rigTransport = PresetRigTransportKind.USB_SERIAL,
            rigBluetoothAddress = null,
        )

        val config = SetupCaptureConfigurationAdapter.toCaptureConfiguration(store, manualFrequencyHz = null)

        assertEquals(RigTransportKind.USB_SERIAL, config?.rigTransportKind)
        assertEquals(emptyMap<String, String>(), config?.rigParams)
    }
}
