package org.ort.pipeline.rig

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.capture.CaptureMode
import org.ort.rig.RigTransportKind
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * The real, `SharedPreferences`-backed [CaptureConfigurationStore] (FR-CAP-12, AC-131) — a shared,
 * file-backed contract: WPD's setup flow, WPE's Settings-Mode screen and
 * [org.ort.pipeline.capture.RealCaptureService] each open their own instance over the *same*
 * preferences file and must agree without passing an object between modules that cannot see each
 * other (module graph, constitution VII) — every test here opens a fresh instance to prove that.
 */
@RunWith(RobolectricTestRunner::class)
public class SharedPreferencesCaptureConfigurationStoreTest {

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

    private fun freshPrefs(name: String): android.content.SharedPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return prefs
    }

    @Test
    @Requirement("AC-131")
    public fun `AC_131 an update while not capturing takes effect immediately and survives a new store instance`() {
        val prefs = freshPrefs("test-capture-configuration-immediate")

        SharedPreferencesCaptureConfigurationStore(prefs, isCapturing = { false }).update(usbConfig)

        // A second instance over the SAME preferences file -- exactly how WPE's settings screen
        // and RealCaptureService's own read are two different objects agreeing through one file.
        val reader = SharedPreferencesCaptureConfigurationStore(prefs, isCapturing = { false })
        assertEquals(usbConfig, reader.current())
        assertNull(reader.pendingConfiguration())
    }

    @Test
    @Requirement("AC-131", "FR-CAP-12")
    public fun `AC_131 a write while capturing is pending and current is unchanged, then promoted at next session`() {
        val prefs = freshPrefs("test-capture-configuration-pending")

        SharedPreferencesCaptureConfigurationStore(prefs, isCapturing = { false }).update(usbConfig)
        val writerWhileCapturing = SharedPreferencesCaptureConfigurationStore(prefs, isCapturing = { true })
        writerWhileCapturing.update(bluetoothConfig)

        assertEquals(usbConfig, writerWhileCapturing.current())
        assertEquals(bluetoothConfig, writerWhileCapturing.pendingConfiguration())

        // The next session starts: a fresh reader, no longer capturing.
        val nextSessionReader = SharedPreferencesCaptureConfigurationStore(prefs, isCapturing = { false })
        val activated = nextSessionReader.activateForNewSession()
        assertEquals(bluetoothConfig, activated)
        assertEquals(bluetoothConfig, nextSessionReader.current())
        assertNull(nextSessionReader.pendingConfiguration())
    }

    @Test
    @Requirement("AC-131")
    public fun `an empty preferences file reads back the default configuration`() {
        val prefs = freshPrefs("test-capture-configuration-empty")
        val store = SharedPreferencesCaptureConfigurationStore(prefs, isCapturing = { false })
        assertEquals(CaptureConfiguration.DEFAULT, store.current())
        assertNull(store.pendingConfiguration())
    }

    @Test
    @Requirement("AC-131")
    public fun `rig params round-trip through the preferences file`() {
        val prefs = freshPrefs("test-capture-configuration-params")
        val withParams = usbConfig.copy(rigParams = mapOf("baud" to "9600", "parity" to "none"))
        SharedPreferencesCaptureConfigurationStore(prefs, isCapturing = { false }).update(withParams)

        val reader = SharedPreferencesCaptureConfigurationStore(prefs, isCapturing = { false })
        assertEquals(withParams, reader.current())
    }
}
