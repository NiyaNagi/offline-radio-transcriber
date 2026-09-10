package org.ort.pipeline.capture

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.fake.FakeAudioIo
import org.ort.core.capture.BluetoothAudioProfile
import org.ort.core.capture.CaptureMode
import org.ort.data.OrtDatabase
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.InMemoryCaptureConfigurationStore
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.rig.NullRigModule
import org.ort.rig.RigTransportKind
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * FR-CAP-13, AC-129 (E2-D04) and FR-CAP-12, AC-131 (E2-D05) against the real
 * [RealCaptureService], following [RealCaptureServiceTest]'s own pattern (real `ServiceController`,
 * real threads, no test-dispatcher seam in the production class).
 */
@RunWith(RobolectricTestRunner::class)
public class RealCaptureServiceCaptureModeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun waitUntil(timeoutMillis: Long, intervalMillis: Long = 100, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(intervalMillis)
        }
        assertTrue("condition not met within ${timeoutMillis}ms", predicate())
    }

    private fun startedService(
        db: OrtDatabase,
        fakeIo: FakeAudioIo,
        device: AudioDeviceDescriptor,
        store: InMemoryCaptureConfigurationStore,
        sessionId: String,
    ) = run {
        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Unavailable("no model in this test") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
            captureConfigurationStore = { store },
        )
        val startIntent = Intent(context, RealCaptureService::class.java)
            .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
        controller.withIntent(startIntent).startCommand(0, 0)
        controller to service
    }

    @Test
    @Requirement("AC-129", "FR-CAP-13")
    public fun `AC_129 session records mode, route, label, bluetooth profile and rig transport`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor(
            id = "bt-1",
            kind = AudioDeviceKind.BLUETOOTH,
            label = "Fake Bluetooth headset",
            bluetoothProfile = BluetoothAudioProfile.HFP_MSBC,
        )
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        val config = CaptureConfiguration(
            mode = CaptureMode.BLUETOOTH_RADIO,
            selectedInputId = device.id,
            rigId = NullRigModule.ID,
            rigTransportKind = null,
        )
        val store = InMemoryCaptureConfigurationStore(initial = config, isCapturing = { CaptureState.isCapturing })
        val sessionId = "TEST-AC-129"

        val (controller, _) = startedService(db, fakeIo, device, store, sessionId)
        try {
            waitUntil(10_000) { runBlocking { db.sessionDao().getById(sessionId) } != null }
            val row = runBlocking { db.sessionDao().getCaptureInfo(sessionId) }!!

            assertEquals(CaptureMode.BLUETOOTH_RADIO.name, row.captureMode)
            assertEquals("BLUETOOTH_SCO", row.audioRouteKind)
            assertEquals("Fake Bluetooth headset", row.audioRouteLabel)
            assertEquals(BluetoothAudioProfile.HFP_MSBC.name, row.bluetoothProfile)
            assertNull("no rig transport was configured", row.rigTransport)
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("AC-129", "FR-CAP-13")
    public fun `AC_129 a USB session with a rig transport records it, and a mic session records none`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "Fake USB adapter")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        val config = CaptureConfiguration(
            mode = CaptureMode.USB_RADIO,
            selectedInputId = device.id,
            rigId = "kenwood-thd75a",
            rigTransportKind = RigTransportKind.USB_SERIAL,
        )
        val store = InMemoryCaptureConfigurationStore(initial = config, isCapturing = { CaptureState.isCapturing })
        val sessionId = "TEST-AC-129-USB"

        val (controller, _) = startedService(db, fakeIo, device, store, sessionId)
        try {
            waitUntil(10_000) { runBlocking { db.sessionDao().getById(sessionId) } != null }
            val row = runBlocking { db.sessionDao().getCaptureInfo(sessionId) }!!

            assertEquals(CaptureMode.USB_RADIO.name, row.captureMode)
            assertEquals("USB", row.audioRouteKind)
            assertEquals("Fake USB adapter", row.audioRouteLabel)
            assertNull("a non-Bluetooth route carries no profile", row.bluetoothProfile)
            assertEquals(RigTransportKind.USB_SERIAL.name, row.rigTransport)
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("AC-131", "FR-CAP-12")
    public fun `AC_131 a mode change written mid-session leaves the running session's row unchanged`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val deviceA = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "Fake USB adapter")
        val fakeIoA = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(deviceA))
        fakeIoA.forceRoutedDevice(deviceA)
        val configA = CaptureConfiguration(
            mode = CaptureMode.USB_RADIO,
            selectedInputId = deviceA.id,
            rigId = NullRigModule.ID,
            rigTransportKind = null,
        )
        val store = InMemoryCaptureConfigurationStore(initial = configA, isCapturing = { CaptureState.isCapturing })
        val sessionIdA = "TEST-AC-131-A"

        val (controllerA, _) = startedService(db, fakeIoA, deviceA, store, sessionIdA)
        try {
            waitUntil(10_000) { runBlocking { db.sessionDao().getById(sessionIdA) } != null }

            // Written while session A is still capturing (CaptureState.isCapturing is true) --
            // FR-CAP-12: this must NOT change session A's already-written row.
            assertTrue(
                "the fake service must genuinely be capturing for this test to mean anything",
                CaptureState.isCapturing,
            )
            val configB = configA.copy(mode = CaptureMode.BLUETOOTH_RADIO, selectedInputId = "bt-1")
            store.update(configB)

            val rowAAfterUpdate = runBlocking { db.sessionDao().getCaptureInfo(sessionIdA) }!!
            assertEquals(
                "FR-CAP-12: a mode change SHALL NOT take effect mid-session",
                CaptureMode.USB_RADIO.name,
                rowAAfterUpdate.captureMode,
            )
            assertEquals(configB, store.pendingConfiguration())

            // Stop A, then start a NEW session -- only now does the pending change apply (AC-131).
            val stopIntent = Intent(context, RealCaptureService::class.java).setAction(RealCaptureService.ACTION_STOP)
            controllerA.withIntent(stopIntent).startCommand(0, 0)
            waitUntil(5_000) { !CaptureState.isCapturing }
        } finally {
            controllerA.destroy()
        }

        val deviceB = AudioDeviceDescriptor("bt-1", AudioDeviceKind.BLUETOOTH, "Fake Bluetooth headset")
        val fakeIoB = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(deviceB))
        fakeIoB.forceRoutedDevice(deviceB)
        val sessionIdB = "TEST-AC-131-B"
        val (controllerB, _) = startedService(db, fakeIoB, deviceB, store, sessionIdB)
        try {
            waitUntil(10_000) { runBlocking { db.sessionDao().getById(sessionIdB) } != null }
            val rowB = runBlocking { db.sessionDao().getCaptureInfo(sessionIdB) }!!
            assertEquals(
                "AC-131: the NEXT session must use the newly-pending mode",
                CaptureMode.BLUETOOTH_RADIO.name,
                rowB.captureMode,
            )
            assertNull("the pending change is applied and cleared", store.pendingConfiguration())
        } finally {
            controllerB.destroy()
        }
    }
}
