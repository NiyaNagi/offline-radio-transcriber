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
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.pipeline.PipelineTestFixtures
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * F5 (register R-106, `CaptureGapCause.OS_STOPPED`): before this, nothing in the running service
 * ever checked for an unclean end from the previous launch at all -- `:app`'s `ReaderPolling`
 * reads `CaptureStatusRepository.uncleanEndFromPreviousLaunch()` for the banner (out of this
 * package's ownership), but no gap was ever persisted on the previous session for it. Follows the
 * [RealCaptureServiceTest] harness: the real service under Robolectric's `ServiceController`, with
 * [RealCaptureService.Dependencies] substituting the four genuinely-Android/IO-bound collaborators.
 */
@RunWith(RobolectricTestRunner::class)
public class RealCaptureServiceUncleanEndGapTest {

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
    @Requirement("F-005", "R-106")
    public fun `F5_an_unclean_end_from_the_previous_launch_persists_an_OS_STOPPED_gap_on_the_previous_session`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val previousSessionId = "PREVIOUS-SESSION-01"
        runBlocking { db.sessionDao().insert(PipelineTestFixtures.session(id = previousSessionId)) }

        // The previous launch's last heartbeat, never marked with a clean shutdown -- exactly what
        // FileHeartbeatStore.write()'s own "clean" marker being "false" means (see its own kdoc).
        val heartbeatStore = FileHeartbeatStore(File(context.filesDir, "heartbeat.txt"))
        val lastHeartbeatWallMillis = System.currentTimeMillis() - 3 * 3_600_000L
        heartbeatStore.write(
            HeartbeatRecord(
                sessionId = previousSessionId,
                monotonicNanos = 0L,
                wallMillis = lastHeartbeatWallMillis,
                samplePosition = 118_000L,
            ),
        )
        assertTrue("the fixture itself must read as an unclean end", heartbeatStore.hadUncleanEnd())

        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        val newSessionId = "NEW-SESSION-01"

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
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, newSessionId)
            controller.withIntent(startIntent).startCommand(0, 0)

            waitUntil(10_000) { runBlocking { db.captureGapDao().listBySession(previousSessionId) }.isNotEmpty() }
            val gaps = runBlocking { db.captureGapDao().listBySession(previousSessionId) }
            assertEquals("exactly one OS_STOPPED gap on the PREVIOUS session", 1, gaps.size)
            val gap = gaps.single()
            assertEquals(CaptureGapCause.OS_STOPPED, gap.cause)
            assertEquals(lastHeartbeatWallMillis, gap.startedAt)
            assertTrue("the gap must end at/after the last heartbeat", gap.endedAt!! >= gap.startedAt)

            // Never on the session about to start.
            assertTrue(
                "no gap must land on the NEW session from this check",
                runBlocking { db.captureGapDao().listBySession(newSessionId) }.isEmpty(),
            )

            // "Does not reopen the previous session" -- its own row is untouched (still endedAt = null
            // from the fixture above, not overwritten to look like it continued).
            val previousSession = runBlocking { db.sessionDao().getById(previousSessionId) }
            assertNull(previousSession?.endedAt)
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("F-005", "R-106")
    public fun `a clean previous shutdown persists no OS_STOPPED gap at all`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val previousSessionId = "PREVIOUS-SESSION-CLEAN"
        runBlocking { db.sessionDao().insert(PipelineTestFixtures.session(id = previousSessionId)) }

        val heartbeatStore = FileHeartbeatStore(File(context.filesDir, "heartbeat.txt"))
        heartbeatStore.write(
            HeartbeatRecord(
                sessionId = previousSessionId,
                monotonicNanos = 0L,
                wallMillis = System.currentTimeMillis(),
                samplePosition = 1L,
            ),
        )
        heartbeatStore.markCleanShutdown(previousSessionId)
        assertTrue("the fixture itself must read as a clean end", !heartbeatStore.hadUncleanEnd())

        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)

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
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, "NEW-SESSION-CLEAN")
            controller.withIntent(startIntent).startCommand(0, 0)

            waitUntil(5_000) { fakeIo.openCount > 0 }
            // No polling target for "stays empty forever" -- a short real-time settle is the
            // established pattern this test class already uses (see RealCaptureServiceTest kdoc).
            Thread.sleep(500)
            assertTrue(runBlocking { db.captureGapDao().listBySession(previousSessionId) }.isEmpty())
        } finally {
            controller.destroy()
        }
    }
}
