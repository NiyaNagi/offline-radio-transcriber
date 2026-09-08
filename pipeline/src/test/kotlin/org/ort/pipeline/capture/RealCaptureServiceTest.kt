package org.ort.pipeline.capture

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.fake.FakeAudioIo
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.core.AssetRef
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Build-plan P12's claim that `PassDrainRunner`/`PassB`/`RealSherpaDecoder` are "constructed and
 * wired into `RealCaptureService`, proven against `FakeAsrEngine` on Robolectric" had no test that
 * actually started the service (audit F-011) — `CaptureProcessingLoopTest` proves
 * `PassDrainRunner`+`PassBFactory` drain the queue, which is a different claim from proving
 * [RealCaptureService.startCapture]'s own composition (VAD/route selection, the capture flow,
 * the shed/gap relays, the processing-loop launch) actually runs end to end.
 *
 * This starts the **real** [RealCaptureService] under Robolectric's `ServiceController`, with the
 * four genuinely-Android/IO-bound collaborators substituted through [RealCaptureService.Dependencies]
 * (the smallest seam that lets a fake in without Hilt — see that class's kdoc): a [FakeAudioIo]
 * feeding one synthetic speech-then-silence burst, [FakeAsrEngine] standing in for the real
 * decoder, an in-memory [OrtDatabase], and [FakeShedSignals] at healthy defaults. The VAD itself is
 * **not** faked — [RealCaptureService.buildSegmenter] already falls back to the real
 * [EnergyVadModel] whenever no Silero model is installed (which it never is in this test
 * environment), so a loud-enough synthetic tone genuinely trips it.
 *
 * Everything here runs on real threads (the service's own `Dispatchers.IO` scope, unmodified) and
 * real wall-clock time (`CaptureProcessingLoop`'s 2s poll interval, `BackoffLadder`'s 1s first
 * retry) — there is no virtual/test dispatcher seam in the production class, so assertions poll
 * with a real timeout rather than advancing a scheduler. Robolectric/JVM only; nothing here is
 * on-device verification.
 */
@RunWith(RobolectricTestRunner::class)
public class RealCaptureServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun loudBlock(n: Int = 1_600): ShortArray = ShortArray(n) { 6_000 }
    private fun silentBlock(n: Int = 1_600): ShortArray = ShortArray(n) { 0 }

    /** Polls real wall-clock time — there is no test dispatcher to advance (see class kdoc). */
    private fun waitUntil(timeoutMillis: Long, intervalMillis: Long = 100, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(intervalMillis)
        }
        assertTrue("condition not met within ${timeoutMillis}ms", predicate())
    }

    @Test
    @Requirement("AC-31", "AC-5", "F-005", "F-011")
    public fun `AC_31 a captured burst becomes a transcript through the real service`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        // >= 250ms (SegmentConfig.DEFAULT_MIN_SPEECH_MS) of loud audio to confirm a segment, then
        // >= 600ms (DEFAULT_MIN_SILENCE_MS) of silence to close it via hangover -- generous margin
        // on both sides so timing jitter in frame accumulation cannot flake the test.
        repeat(5) { fakeIo.enqueueFrames(loudBlock()) }
        repeat(12) { fakeIo.enqueueFrames(silentBlock()) }

        val engine = FakeAsrEngine(
            FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult(text = "test transmission received")),
        )
        val sessionId = "TEST-SESSION-31"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Available(engine, AssetRef("fake-asr-model", "1"), "test-fake") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
        )

        try {
            val startIntent = Intent(context, RealCaptureService::class.java)
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            controller.withIntent(startIntent).startCommand(0, 0)

            val transmissionId = "$sessionId-0"

            // AC-31: the captured segment reaches COMPLETE with a transcript, through the real
            // service's own composition -- not a hand-assembled PassDrainRunner+PassB in a test.
            waitUntil(20_000) {
                val state = runBlocking { db.transmissionDao().getById(transmissionId) }?.processingState
                state == TransmissionState.COMPLETE
            }
            val transmission = runBlocking { db.transmissionDao().getById(transmissionId) }
            assertNotNull("expected a TransmissionEntity row", transmission)
            assertTrue("real timestamps, not a placeholder (F-001)", transmission!!.startedAtUtc > 0L)
            assertEquals(sessionId, transmission.sessionId)

            val transcript = runBlocking { db.transcriptDao().getCurrent(transmissionId) }
            assertNotNull("expected a current transcript row from FakeAsrEngine", transcript)
            assertEquals("test transmission received", transcript!!.text)
            assertTrue(transcript.isCurrent)

            // F-005: onHeartbeat() must report the segmenter's real sample position, not a
            // fabricated 0L -- by the time a segment has closed, audio has definitely been fed.
            val heartbeatStore = FileHeartbeatStore(File(service.filesDir, "heartbeat.txt"))
            val heartbeat = heartbeatStore.last()
            assertNotNull("expected a heartbeat record", heartbeat)
            assertEquals(sessionId, heartbeat!!.sessionId)
            assertTrue("heartbeat must carry a real, non-zero sample position", heartbeat.samplePosition > 0L)

            // AC-5: a deliberate stop (ACTION_STOP), not onDestroy(), is the clean-shutdown path.
            val stopIntent = Intent(context, RealCaptureService::class.java).setAction(RealCaptureService.ACTION_STOP)
            controller.withIntent(stopIntent).startCommand(0, 0)
            assertFalse(
                "a deliberate stop must mark the session's heartbeat clean (AC-5)",
                heartbeatStore.hadUncleanEnd(),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("AC-48", "F-028", "F-011")
    public fun `AC_48 an interruption produces a capture gap row through the running service`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        val sessionId = "TEST-SESSION-48"

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

            // Give the capture coroutine time to reach io.setEventListener(...) before raising the
            // interruption -- otherwise the event has no listener to deliver to.
            waitUntil(5_000) { fakeIo.openCount > 0 }
            fakeIo.raiseInterruption("focus loss")
            // AudioRecordSource retries via BackoffLadder (1s first attempt); FakeAudioIo.open()
            // defaults to succeeding, so recovery -> CaptureEvent.Resumed follows automatically.

            waitUntil(10_000) { runBlocking { db.captureGapDao().listBySession(sessionId) }.isNotEmpty() }
            val gaps = runBlocking { db.captureGapDao().listBySession(sessionId) }
            assertEquals("exactly one gap for one interruption/resume pair", 1, gaps.size)
            assertEquals(CaptureGapCause.INTERRUPTION, gaps[0].cause)
            val endedAt = gaps[0].endedAt
            assertNotNull("a resumed gap must be closed with an end time", endedAt)
            assertTrue("a gap must have a bounded, non-negative duration", endedAt!! >= gaps[0].startedAt)
            assertEquals(sessionId, gaps[0].sessionId)
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("FR-RUN-1")
    public fun `FR_RUN_1 capture keeps producing new transmissions while a pass is stalled on inference`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        // Segment 0 -- closed normally. This is the one that will be leased and stall in inference.
        repeat(5) { fakeIo.enqueueFrames(loudBlock()) }
        repeat(12) { fakeIo.enqueueFrames(silentBlock()) }
        // Segment 1 -- closed normally too. Capture must keep producing this while segment 0 sits
        // stuck in the (single) inference slot -- constitution IV: "capture MUST proceed with
        // every processing pass stalled".
        repeat(5) { fakeIo.enqueueFrames(loudBlock()) }
        repeat(12) { fakeIo.enqueueFrames(silentBlock()) }

        // Longer than this test's own assertions but shorter than RejectionPipeline's own 15s
        // engine timeout (DEFAULT_TIMEOUT_MS) -- the point is to observe capture continuing
        // *while* the engine is genuinely still stuck, not to watch it eventually recover.
        val engine = FakeAsrEngine(FakeAsrEngine.Behaviour.HangsFor(30_000))
        val sessionId = "TEST-SESSION-RUN1"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Available(engine, AssetRef("fake-asr-model", "1"), "test-fake") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
        )

        try {
            val startIntent = Intent(context, RealCaptureService::class.java)
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            controller.withIntent(startIntent).startCommand(0, 0)

            // Segment 0 reaches PROCESSING and stalls there -- the engine is genuinely hung.
            waitUntil(20_000) {
                runBlocking {
                    db.transmissionDao().getById("$sessionId-0")
                }?.processingState == TransmissionState.PROCESSING
            }

            // FR-RUN-1: capture keeps running regardless -- segment 1 is fully captured (closed,
            // FLAC-encoded, persisted, enqueued) while segment 0 is still stuck. Capture never
            // waited on the stalled inference call to produce this row.
            waitUntil(20_000) { runBlocking { db.transmissionDao().getById("$sessionId-1") } != null }

            val stalled = runBlocking { db.transmissionDao().getById("$sessionId-0") }!!
            assertEquals(
                "FR-RUN-1: the stalled segment must still be genuinely stuck in PROCESSING at the " +
                    "moment segment 1 was captured -- proving concurrency, not lucky sequencing",
                TransmissionState.PROCESSING,
                stalled.processingState,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("NFR-4a")
    public fun `NFR_4a an unexpected termination loses at most the in-flight segment`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        // Segment 0 -- closed normally, must survive the kill below.
        repeat(5) { fakeIo.enqueueFrames(loudBlock()) }
        repeat(12) { fakeIo.enqueueFrames(silentBlock()) }
        // Segment 1 -- opened (enough speech to confirm) but deliberately never closed (no
        // trailing silence follows it). This is the one and only in-flight segment when the kill
        // below happens -- NFR-4a permits losing at most this one, never a segment already closed.
        repeat(5) { fakeIo.enqueueFrames(loudBlock()) }

        val engine = FakeAsrEngine(
            FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult(text = "test transmission received")),
        )
        val sessionId = "TEST-SESSION-NFR4A"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Available(engine, AssetRef("fake-asr-model", "1"), "test-fake") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
        )

        val startIntent = Intent(context, RealCaptureService::class.java)
            .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
        controller.withIntent(startIntent).startCommand(0, 0)

        waitUntil(20_000) { runBlocking { db.transmissionDao().getById("$sessionId-0") } != null }
        // Real-time buffer, not a fixed contract: segment 1's raw frames are read and fed to the
        // segmenter only after segment 0's close() (FLAC encode + DB insert) returns on the same
        // sequential collector -- this gives that a moment to happen on real Robolectric threads,
        // consistent with the class kdoc's "no test dispatcher to advance" note.
        Thread.sleep(1_000)

        // Simulate an unexpected termination: onDestroy() directly, never ACTION_STOP -- no clean
        // shutdown path runs first (constitution IV, NFR-4a).
        controller.destroy()

        val closedSegment = runBlocking { db.transmissionDao().getById("$sessionId-0") }
        assertNotNull("the segment that closed before the kill must survive it", closedSegment)

        val inFlightSegment = runBlocking { db.transmissionDao().getById("$sessionId-1") }
        assertNull(
            "NFR-4a: at most the in-flight (never-closed) segment may be lost -- it never became " +
                "a persisted row because it was still open when the unexpected termination happened",
            inFlightSegment,
        )

        val heartbeatStore = FileHeartbeatStore(File(service.filesDir, "heartbeat.txt"))
        assertTrue(
            "an unexpected termination must be surfaced honestly as an unclean end, never silently",
            heartbeatStore.hadUncleanEnd(),
        )
    }

    /**
     * audit F-022: before this, nothing published the running session id anywhere `:app` could read
     * it -- `MainActivity` always minted a fresh one on relaunch, and `RealCaptureService`'s own
     * singleton guard (`onStartCommand`'s `if (source != null) return START_STICKY`) silently
     * ignored the second start, leaving the reader polling a session nothing was capturing into.
     * [CaptureState.sessionId] is the seam that fixes it: published the moment capture actually
     * starts, and cleared only by a deliberate `ACTION_STOP` -- never by `onDestroy` alone, which
     * a process death also triggers and which must not erase which session was last live.
     */
    @Test
    @Requirement("FR-UI-7", "F-022")
    public fun `FR_UI_7 CaptureState publishes the running session id and clears it only on a clean stop`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        val sessionId = "TEST-SESSION-22"

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

            assertTrue("the service must publish that it is capturing", CaptureState.isCapturing)
            assertEquals(
                "a relaunching MainActivity must be able to discover the live session id",
                sessionId,
                CaptureState.sessionId,
            )

            val stopIntent = Intent(context, RealCaptureService::class.java).setAction(RealCaptureService.ACTION_STOP)
            controller.withIntent(stopIntent).startCommand(0, 0)

            assertFalse("a deliberate stop must no longer report capturing", CaptureState.isCapturing)
            assertNull(
                "a deliberate stop must clear the session id -- otherwise a relaunch could mistake" +
                    " a dead session for a live one",
                CaptureState.sessionId,
            )
        } finally {
            controller.destroy()
        }
    }
}
