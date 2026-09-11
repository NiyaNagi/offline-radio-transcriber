package org.ort.pipeline.rig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.capture.CaptureMode
import org.ort.rig.NullRigModule
import org.ort.rig.RigTransportKind
import org.ort.testing.Requirement

/**
 * FR-CAP-12, AC-131: a mode/rig change written while capture is running never lands in [current]
 * until the next session starts. [InMemoryCaptureConfigurationStore] takes [isCapturing] as a
 * plain injected lambda, so this file exercises the freeze rule with no Android/Robolectric
 * dependency at all — see [SharedPreferencesCaptureConfigurationStoreTest] for the real,
 * `SharedPreferences`-backed implementation (which needs Robolectric for a `Context`).
 */
class CaptureConfigurationStoreTest {

    private val usbConfig = CaptureConfiguration(
        mode = CaptureMode.USB_RADIO,
        selectedInputId = "usb-1",
        rigId = "kenwood-thd75a",
        rigTransportKind = RigTransportKind.USB_SERIAL,
    )
    private val bluetoothConfig = CaptureConfiguration(
        mode = CaptureMode.BLUETOOTH_RADIO,
        selectedInputId = "bt-1",
        rigId = "kenwood-thd75a",
        rigTransportKind = RigTransportKind.BLUETOOTH_SPP,
    )

    @Test
    @Requirement("AC-131")
    fun `AC_131 an update while not capturing takes effect immediately`() {
        val store = InMemoryCaptureConfigurationStore(isCapturing = { false })
        store.update(usbConfig)
        assertEquals(usbConfig, store.current())
        assertNull(store.pendingConfiguration())
    }

    @Test
    @Requirement("AC-131", "FR-CAP-12")
    fun `AC_131 an update while capturing is recorded as pending and current is unchanged`() {
        val store = InMemoryCaptureConfigurationStore(initial = usbConfig, isCapturing = { true })
        store.update(bluetoothConfig)

        assertEquals(usbConfig, store.current(), "the running session's configuration must be unchanged")
        assertEquals(bluetoothConfig, store.pendingConfiguration())
    }

    @Test
    @Requirement("AC-131", "FR-CAP-12")
    fun `AC_131 activateForNewSession promotes the pending configuration and clears it`() {
        val store = InMemoryCaptureConfigurationStore(initial = usbConfig, isCapturing = { true })
        store.update(bluetoothConfig)

        val activated = store.activateForNewSession()

        assertEquals(bluetoothConfig, activated)
        assertEquals(bluetoothConfig, store.current())
        assertNull(store.pendingConfiguration(), "a promoted pending change must be cleared")
    }

    @Test
    @Requirement("AC-131")
    fun `AC_131 activateForNewSession with nothing pending just returns current`() {
        val store = InMemoryCaptureConfigurationStore(initial = usbConfig, isCapturing = { false })
        assertEquals(usbConfig, store.activateForNewSession())
        assertNull(store.pendingConfiguration())
    }

    @Test
    @Requirement("AC-131")
    fun `default configuration is local microphone with no rig`() {
        assertEquals(CaptureMode.LOCAL_MICROPHONE, CaptureConfiguration.DEFAULT.mode)
        assertEquals(NullRigModule.ID, CaptureConfiguration.DEFAULT.rigId)
        assertNull(CaptureConfiguration.DEFAULT.rigTransportKind)
    }

    // R-821 follow-up (E2-A07): hasBeenConfigured() -- see SharedPreferencesCaptureConfigurationStoreTest
    // for why this exists at all.

    @Test
    @Requirement("R-821")
    fun `R_821 a fresh store has never been configured`() {
        val store = InMemoryCaptureConfigurationStore(isCapturing = { false })
        assertFalse(store.hasBeenConfigured())
    }

    @Test
    @Requirement("R-821")
    fun `R_821 an update while not capturing marks the store configured`() {
        val store = InMemoryCaptureConfigurationStore(isCapturing = { false })
        store.update(usbConfig)
        assertTrue(store.hasBeenConfigured())
    }

    @Test
    @Requirement("R-821")
    fun `R_821 a write while capturing does not itself mark the store configured until activated`() {
        val store = InMemoryCaptureConfigurationStore(isCapturing = { true })
        store.update(usbConfig)
        assertFalse(
            store.hasBeenConfigured(),
            "a pending write with nothing ever promoted to current is still an unconfigured store",
        )

        store.activateForNewSession()
        assertTrue(
            store.hasBeenConfigured(),
            "activateForNewSession promoting the pending write must mark the store configured",
        )
    }
}
