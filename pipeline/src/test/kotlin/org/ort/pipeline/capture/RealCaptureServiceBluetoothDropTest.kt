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
import org.ort.core.capture.BluetoothAudioProfile
import org.ort.core.capture.CaptureMode
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.InMemoryCaptureConfigurationStore
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.rig.NullRigModule
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * FR-CAP-5, F23, E2-D07: a Bluetooth **audio** drop (as opposed to [RealCaptureServiceRigDropTest]'s
 * rig **control** drop) produces [InputStatus.State.Lost] naming the Bluetooth route kind and
 * profile, opens a real [org.ort.data.entity.CaptureGapEntity] row, retries on the backoff ladder,
 * and resumes automatically -- capture never captures nothing silently.
 */
@RunWith(RobolectricTestRunner::class)
public class RealCaptureServiceBluetoothDropTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun waitUntil(timeoutMillis: Long, intervalMillis: Long = 100, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(intervalMillis)
        }
        assertTrue("condition not met within ${timeoutMillis}ms", predicate())
    }

    private fun loudBlock(n: Int = 1_600): ShortArray = ShortArray(n) { 6_000 }

    @Test
    @Requirement("FR-CAP-5", "F23")
    public fun `E2_D07 a Bluetooth audio drop opens a gap naming the route and profile, then recovers`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor(
            id = "bt-1",
            kind = AudioDeviceKind.BLUETOOTH,
            label = "Fake Bluetooth headset",
            bluetoothProfile = BluetoothAudioProfile.HFP_MSBC,
        )
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        // At least one real frame so AudioRecordSource's first-read route verification actually
        // runs (a device that only ever returns 0 never reaches that branch -- see
        // AudioRecordSource.start()'s `when` on `n`).
        repeat(5) { fakeIo.enqueueFrames(loudBlock()) }

        val config = CaptureConfiguration(
            mode = CaptureMode.BLUETOOTH_RADIO,
            selectedInputId = device.id,
            rigId = NullRigModule.ID,
            rigTransportKind = null,
        )
        val sessionId = "TEST-E2-D07"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Unavailable("no model in this test") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
            captureConfigurationStore = { InMemoryCaptureConfigurationStore(initial = config) },
        )

        try {
            val startIntent = Intent(context, RealCaptureService::class.java)
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            controller.withIntent(startIntent).startCommand(0, 0)

            waitUntil(10_000) {
                val s = InputStatus.state
                s is InputStatus.State.Opened && s.routeVerified
            }

            fakeIo.dropDeviceMidRead()

            waitUntil(10_000) { InputStatus.state is InputStatus.State.Lost }
            val lost = InputStatus.state as InputStatus.State.Lost
            assertEquals(
                "InputStatus.Lost must name the route kind that dropped (FR-CAP-5/FR-CAP-11)",
                AudioDeviceKind.BLUETOOTH,
                lost.lastKnown.descriptor.kind,
            )
            assertEquals(
                "InputStatus.Lost must carry the negotiated Bluetooth profile forward",
                BluetoothAudioProfile.HFP_MSBC,
                lost.lastKnown.descriptor.bluetoothProfile,
            )

            // BackoffLadder's first retry (1s) reopens FakeAudioIo, which clears droppedMidRead
            // unconditionally (a fresh open() mirrors a real reconnect) -- capture resumes on its
            // own, never captures nothing silently (constitution IV).
            waitUntil(10_000) {
                val s = InputStatus.state
                s is InputStatus.State.Opened && s.routeVerified
            }

            val gaps = runBlocking { db.captureGapDao().listBySession(sessionId) }
            assertTrue("a Bluetooth audio drop must open a real, bounded gap (FR-RUN-12)", gaps.isNotEmpty())
            val gap = gaps.first()
            assertEquals(sessionId, gap.sessionId)
            // See GapPersister.causeFor's own kdoc / this package's report: INPUT_LOST is the
            // closest existing CaptureGapCause value -- a dedicated BLUETOOTH_AUDIO_LOST value
            // does not exist in :data today (see this package's report for the exact addition).
            assertEquals(CaptureGapCause.INPUT_LOST, gap.cause)
            assertTrue("a recovered gap must be closed with a real end time", (gap.endedAt ?: -1L) >= gap.startedAt)
        } finally {
            controller.destroy()
        }
    }
}
