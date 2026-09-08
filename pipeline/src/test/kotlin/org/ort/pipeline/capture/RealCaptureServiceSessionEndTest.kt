package org.ort.pipeline.capture

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.fake.FakeAudioIo
import org.ort.data.OrtDatabase
import org.ort.data.entity.TerminationReason
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * register R-173 (halt), FR-RUN-16: before this, `RealCaptureService` never wrote
 * `SessionEntity.endedAt` on any path that ends a session, so a real session read "still running"
 * forever on `Now-Idle`/`Sessions` and survived the `empty` scenario. Follows the
 * [RealCaptureServiceTest]/[RealCaptureServiceUncleanEndGapTest] harness: the real service under
 * Robolectric's `ServiceController`, with the four genuinely-Android/IO-bound collaborators
 * substituted through [RealCaptureService.Dependencies].
 */
@RunWith(RobolectricTestRunner::class)
public class RealCaptureServiceSessionEndTest {

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
    @Requirement("R-173")
    public fun `R_173_stop_writes_endedAt`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        val sessionId = "TEST-SESSION-R173"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Unavailable("no model in this test") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
        )

        try {
            val startIntent = Intent(context, RealCaptureService::class.java)
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            controller.withIntent(startIntent).startCommand(0, 0)
            waitUntil(10_000) { fakeIo.openCount > 0 }

            val beforeStop = runBlocking { db.sessionDao().getById(sessionId) }
            assertNotNull("expected the session row startCapture() inserts", beforeStop)
            assertEquals("must not already be closed before any stop", null, beforeStop?.endedAt)

            val stopIntent = Intent(context, RealCaptureService::class.java).setAction(RealCaptureService.ACTION_STOP)
            controller.withIntent(stopIntent).startCommand(0, 0)

            val afterStop = runBlocking { db.sessionDao().getById(sessionId) }
            assertNotNull(afterStop?.endedAt)
            assertTrue("endedAt must be a real, non-zero timestamp", afterStop!!.endedAt!! > 0L)
            assertEquals(
                "a deliberate ACTION_STOP is TerminationReason.USER",
                TerminationReason.USER,
                afterStop.terminationReason,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("R-173")
    public fun `R_173_onDestroy_without_a_prior_ACTION_STOP_writes_endedAt_marked_KILLED`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        val sessionId = "TEST-SESSION-R173-KILLED"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Unavailable("no model in this test") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
        )

        try {
            val startIntent = Intent(context, RealCaptureService::class.java)
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            controller.withIntent(startIntent).startCommand(0, 0)
            waitUntil(10_000) { fakeIo.openCount > 0 }

            // Simulated unexpected termination: onDestroy() directly, never ACTION_STOP -- the
            // same "NFR_4a" shape RealCaptureServiceTest already uses for this distinction.
            controller.destroy()

            val afterDestroy = runBlocking { db.sessionDao().getById(sessionId) }
            assertNotNull(afterDestroy?.endedAt)
            assertEquals(TerminationReason.KILLED, afterDestroy?.terminationReason)
        } finally {
            // controller.destroy() already ran above; a second call would be a genuine double
            // stop, exactly the case sessionEndRecorded guards -- not repeated here on purpose.
        }
    }

    @Test
    @Requirement("R-173")
    public fun `R_173_a_second_stopCaptureInternal_call_never_overwrites_the_first_ending`() {
        // ACTION_STOP (USER) followed by the async stopSelf()-triggered onDestroy() (which would
        // otherwise call stopCaptureInternal(markClean = false) a second time) must not downgrade
        // the recorded reason from USER to KILLED. Exercised directly against a real Robolectric
        // service instance's private stopCaptureInternal, since driving stopSelf()'s real onDestroy
        // callback timing is not controllable from a test (see RealCaptureServiceTest's own note on
        // there being no test dispatcher seam in this class).
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        val sessionId = "TEST-SESSION-R173-DOUBLE-STOP"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Unavailable("no model in this test") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
        )

        try {
            val startIntent = Intent(context, RealCaptureService::class.java)
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            controller.withIntent(startIntent).startCommand(0, 0)
            waitUntil(10_000) { fakeIo.openCount > 0 }

            val stopIntent = Intent(context, RealCaptureService::class.java).setAction(RealCaptureService.ACTION_STOP)
            controller.withIntent(stopIntent).startCommand(0, 0) // stopCaptureInternal(markClean = true)

            val afterFirstStop = runBlocking { db.sessionDao().getById(sessionId) }
            val firstEndedAt = afterFirstStop?.endedAt
            assertEquals(TerminationReason.USER, afterFirstStop?.terminationReason)

            controller.destroy() // onDestroy() -> stopCaptureInternal(markClean = false), same session

            val afterSecondStop = runBlocking { db.sessionDao().getById(sessionId) }
            assertEquals(
                "the second (onDestroy) call must not have overwritten the first ending",
                TerminationReason.USER,
                afterSecondStop?.terminationReason,
            )
            assertEquals(firstEndedAt, afterSecondStop?.endedAt)
        } finally {
            // Already destroyed above.
        }
    }
}
