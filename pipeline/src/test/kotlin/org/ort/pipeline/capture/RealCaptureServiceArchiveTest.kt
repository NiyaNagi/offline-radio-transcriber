package org.ort.pipeline.capture

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.fake.FakeAudioIo
import org.ort.data.OrtDatabase
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * WPARC (FR-SEG-9, FR-STO-3d, D39): closes the loop this session's brief opens with — before
 * this, [org.ort.capture.android.archive.ContinuousArchiveWriter] and
 * [org.ort.pipeline.archive.ReSegmenter] existed and satisfied [AC-96] in isolation, but neither
 * was ever constructed by the running app. Follows [RealCaptureServiceTest]'s own harness: the
 * real [RealCaptureService] under Robolectric's `ServiceController`, with the four
 * genuinely-Android/IO-bound collaborators substituted through [RealCaptureService.Dependencies].
 */
@RunWith(RobolectricTestRunner::class)
public class RealCaptureServiceArchiveTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun loudBlock(n: Int = 1_600): ShortArray = ShortArray(n) { 6_000 }
    private fun silentBlock(n: Int = 1_600): ShortArray = ShortArray(n) { 0 }

    private fun waitUntil(timeoutMillis: Long, intervalMillis: Long = 100, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(intervalMillis)
        }
        assertTrue("condition not met within ${timeoutMillis}ms", predicate())
    }

    @Test
    @Requirement("FR-SEG-9", "FR-STO-3d", "D39", "AC-96")
    public fun `a session captured with the archive on writes real archive chunks and is marked KEPT on stop`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        repeat(5) { fakeIo.enqueueFrames(loudBlock()) }
        repeat(12) { fakeIo.enqueueFrames(silentBlock()) }
        val sessionId = "TEST-SESSION-ARCHIVE-ON"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Unavailable("no model in this test") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
            archiveSettingsStore = { InMemoryArchiveSettingsStore(archiveEnabled = true) },
        )

        try {
            val startIntent = Intent(context, RealCaptureService::class.java)
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            controller.withIntent(startIntent).startCommand(0, 0)

            waitUntil(10_000) { fakeIo.openCount > 0 }
            // This test's whole burst (~1.7s) is far short of DEFAULT_CHUNK_SAMPLES (30s at
            // 16 kHz) -- ContinuousArchiveWriter only ever flushes a trailing partial chunk on
            // finish(), which RealCaptureService calls from endSessionRow() on a clean stop
            // (ContinuousArchiveAttachment.finishAndAwait(), awaited synchronously there before
            // ACTION_STOP's own startCommand() call returns) -- so the real assertion is made
            // only after stopping, not before.
            waitUntil(5_000) { runBlocking { db.transmissionDao().listBySession(sessionId) }.isNotEmpty() }

            val stopIntent = Intent(context, RealCaptureService::class.java).setAction(RealCaptureService.ACTION_STOP)
            controller.withIntent(stopIntent).startCommand(0, 0)

            val archiveDir = File(service.filesDir, "archive/$sessionId")
            assertTrue("the archive directory must contain real, non-empty files", archiveDir.isDirectory)
            val chunkFiles = archiveDir.listFiles { f -> f.name.endsWith(".flac") }.orEmpty()
            assertTrue("at least one real FLAC chunk must have been written", chunkFiles.isNotEmpty())
            assertTrue("a written chunk must not be an empty file", chunkFiles.all { it.length() > 0 })

            val session = runBlocking { db.sessionDao().getById(sessionId) }
            assertEquals("KEPT", session?.archiveState)
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("FR-SEG-9", "D39")
    public fun `a session captured with the archive off writes nothing and is never marked KEPT`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        repeat(5) { fakeIo.enqueueFrames(loudBlock()) }
        repeat(12) { fakeIo.enqueueFrames(silentBlock()) }
        val sessionId = "TEST-SESSION-ARCHIVE-OFF"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Unavailable("no model in this test") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
            archiveSettingsStore = { InMemoryArchiveSettingsStore(archiveEnabled = false) },
        )

        try {
            val startIntent = Intent(context, RealCaptureService::class.java)
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            controller.withIntent(startIntent).startCommand(0, 0)
            waitUntil(10_000) { fakeIo.openCount > 0 }
            // Give the frame loop a real moment to have run before asserting a negative.
            waitUntil(2_000) { runBlocking { db.sessionDao().getById(sessionId) } != null }

            val stopIntent = Intent(context, RealCaptureService::class.java).setAction(RealCaptureService.ACTION_STOP)
            controller.withIntent(stopIntent).startCommand(0, 0)

            assertFalse(
                "no archive directory at all when the archive is off",
                File(service.filesDir, "archive/$sessionId").exists(),
            )
            val session = runBlocking { db.sessionDao().getById(sessionId) }
            assertNull("archiveState must stay null, never fabricated as KEPT", session?.archiveState)
        } finally {
            controller.destroy()
        }
    }
}
