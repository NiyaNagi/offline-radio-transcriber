package org.ort.pipeline.capture

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.fake.FakeAudioIo
import org.ort.core.capture.CaptureMode
import org.ort.data.OrtDatabase
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.InMemoryCaptureConfigurationStore
import org.ort.pipeline.rig.RigTransportFactory
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.rig.RigTransportKind
import org.ort.rig.fakes.FakeRigTransport
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * FR-RIG-15, FR-RIG-7, E2-D08: a rig **control** drop degrades to [RigStatus.State.Stale] — the
 * audio route is entirely unaffected, and in particular **no**
 * [org.ort.data.entity.CaptureGapEntity] row is ever produced for it. This is the discriminating
 * claim: the audio path (device, `FakeAudioIo`) is never touched by this test at all, only the
 * rig's own transport ([FakeRigTransport]) is dropped. Reconnection itself (FR-RIG-15's other half)
 * is the transport's own responsibility, not `RigSupervisor`'s — see [RigSupervisor]'s class kdoc —
 * and is proven against the real `UsbSerialTransport`/`BluetoothSppTransport` fakes in
 * `org.ort.pipeline.rig.RigSupervisorRealTransportTest`, not here: `FakeRigTransport` (`:rig`) has
 * no self-healing state machine of its own to exercise that with.
 */
@RunWith(RobolectricTestRunner::class)
public class RealCaptureServiceRigDropTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun waitUntil(timeoutMillis: Long, intervalMillis: Long = 100, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(intervalMillis)
        }
        assertTrue("condition not met within ${timeoutMillis}ms", predicate())
    }

    @Test
    @Requirement("FR-RIG-15", "FR-RIG-7")
    public fun `E2_D08 a rig control drop degrades to Stale and produces no CaptureGap row`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "Fake USB adapter")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)

        val rigTransport = FakeRigTransport()
        // generic-ascii-cat's poll commands (rig/src/main/resources/descriptors/generic-ascii-cat.json).
        rigTransport.scriptReply("FA;", "FA00014230000;")
        rigTransport.scriptReply("MD;", "MD4;")

        val config = CaptureConfiguration(
            mode = CaptureMode.USB_RADIO,
            selectedInputId = device.id,
            rigId = "generic-ascii-cat",
            rigTransportKind = RigTransportKind.USB_SERIAL,
        )
        val sessionId = "TEST-E2-D08"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Unavailable("no model in this test") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
            captureConfigurationStore = { InMemoryCaptureConfigurationStore(initial = config) },
            rigTransportFactory = { _ -> RigTransportFactory { _, _, _ -> rigTransport } },
        )

        try {
            val startIntent = Intent(context, RealCaptureService::class.java)
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            controller.withIntent(startIntent).startCommand(0, 0)

            waitUntil(10_000) { RigStatus.state is RigStatus.State.Connected }
            val connected = RigStatus.state as RigStatus.State.Connected
            assertEquals(RigTransportKind.USB_SERIAL, connected.transportKind)
            assertEquals("generic-ascii-cat", connected.descriptorId)

            rigTransport.dropMidStream("cable pulled")

            waitUntil(10_000) { RigStatus.state is RigStatus.State.Stale }
            val stale = RigStatus.state as RigStatus.State.Stale
            assertEquals(RigTransportKind.USB_SERIAL, stale.lastKnown.transportKind)

            // The discriminating assertion (E2-D08): a rig-only drop must never open a capture gap.
            // A generous settle window -- long enough that a wrongly-wired path *would* have
            // produced a row by now, short enough the test still runs quickly when it (correctly)
            // never does.
            Thread.sleep(500)
            val gaps = runBlocking { db.captureGapDao().listBySession(sessionId) }
            assertTrue("a rig control drop must never produce a CaptureGap row (FR-RIG-15)", gaps.isEmpty())
        } finally {
            controller.destroy()
        }
    }
}
