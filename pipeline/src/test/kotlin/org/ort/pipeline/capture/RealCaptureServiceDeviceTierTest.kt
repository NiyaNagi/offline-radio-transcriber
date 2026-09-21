package org.ort.pipeline.capture

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.fake.FakeAudioIo
import org.ort.core.AssetRef
import org.ort.data.OrtDatabase
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController

/**
 * Register R-1111 (halt): `buildSessionEntity` used to write `SessionEntity.deviceTier = null`
 * unconditionally, so `ImprovePolling.root`'s whole reprocessing surface — drawer entry, artboard,
 * `RealImproveRunner`, `ReprocessRunner` — was reachable only from seeded debug scenarios, never a
 * real session, even though `ImprovePolling`'s own filter (`ImprovePollingTest`) already worked
 * correctly given a real value.
 *
 * Drives the real service through the same `ServiceController` harness `RealCaptureServiceTest`
 * establishes, and asserts the session row it inserts carries the device's real tier — computed by
 * the *same* `tierFromShedLevel()`/`ShedStatus.currentLevel` formula this class already uses for
 * its own notification text (`degradedReason()`) and `ReprocessRunner.currentTierFromShedLevel()`
 * already uses for reprocessing, never a second, invented notion of "current tier".
 */
@RunWith(RobolectricTestRunner::class)
public class RealCaptureServiceDeviceTierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    public fun setUp() {
        ShedStatus.reset()
    }

    @After
    public fun tearDown() {
        ShedStatus.reset()
    }

    private fun startedService(
        sessionId: String,
        shedSignals: FakeShedSignals,
    ): Pair<ServiceController<RealCaptureService>, OrtDatabase> {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("fake-mic-1", AudioDeviceKind.USB_DEVICE, "Fake test mic")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        val engine = FakeAsrEngine(FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult(text = "x")))

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Available(engine, AssetRef("fake-asr-model", "1"), "test-fake") },
            shedSignals = { _, _, _ -> shedSignals },
        )
        val startIntent = Intent(context, RealCaptureService::class.java)
            .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
        controller.withIntent(startIntent).startCommand(0, 0)
        return controller to db
    }

    private fun waitForSession(db: OrtDatabase, sessionId: String, timeoutMillis: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (runBlocking { db.sessionDao().getById(sessionId) } != null) return
            Thread.sleep(20)
        }
    }

    @Test
    @Requirement("FR-REP-1", "AC-39")
    public fun `R_1111 a healthy device records the top tier, never null`() {
        val sessionId = "TEST-SESSION-1111-HEALTHY"
        val (controller, db) = startedService(sessionId, FakeShedSignals())
        try {
            waitForSession(db, sessionId)
            val session = runBlocking { db.sessionDao().getById(sessionId) }
            assertNotNull("expected a session row", session)
            assertEquals(
                "a healthy device (shed level 0) captures at the top tier, T3 — never null",
                "T3",
                session!!.deviceTier,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("FR-REP-1", "AC-39")
    public fun `R_1111 a backlogged device records the same lower tier the shed formula computes`() {
        // Pre-seed the same level the fake signals below will also independently compute on the
        // shed monitor's own first tick (ShedController.sample(): backlog 20 >= threshold 20 ->
        // level 1) -- deterministic regardless of which of the two writers gets there first.
        ShedStatus.update(level = 1, backlog = 20)
        val sessionId = "TEST-SESSION-1111-BACKLOG"
        val (controller, db) = startedService(sessionId, FakeShedSignals(backlog = 20))
        try {
            waitForSession(db, sessionId)
            val session = runBlocking { db.sessionDao().getById(sessionId) }
            assertNotNull("expected a session row", session)
            assertEquals(
                "shed level 1 -> tier T2 (MAX_TIER 3 minus level 1), the identical formula " +
                    "ReprocessRunner.currentTierFromShedLevel()/ImprovePolling.currentTierOrdinal() use",
                "T2",
                session!!.deviceTier,
            )
        } finally {
            controller.destroy()
        }
    }
}
