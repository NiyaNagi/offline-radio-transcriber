package org.ort.pipeline.passb

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.core.AssetRef
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.pipeline.CaptureProcessingLoop
import org.ort.pipeline.PassDrainRunner
import org.ort.pipeline.PipelineTestFixtures
import org.ort.pipeline.alerts.AlertEvaluationTrigger
import org.ort.pipeline.alerts.AlertMatchInput
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * audit F-013: [PassBFactory] stamped every fingerprint's `configHash` with the literal
 * `"v0-smoke"` and `provider` with the literal `"cpu"`, regardless of what actually ran
 * (constitution III: "every pass is a pure function of (audio, lexicon snapshot, model set,
 * config) and records the fingerprint of what produced it"; constitution VI: "provider is part
 * of provenance"; FR-REP-1). These pin the fix: two different configurations MUST hash
 * differently, the same configuration MUST hash identically across builds, the fingerprint's
 * `provider` MUST equal what the caller says the engine runs on, and the constant literal MUST
 * never appear again.
 *
 * Robolectric only (JVM) -- [PassBFactory.create] needs a real [OrtDatabase]/`filesDir`; nothing
 * here is device-verified.
 */
@RunWith(RobolectricTestRunner::class)
public class PassBFactoryTest {

    private lateinit var db: OrtDatabase
    private lateinit var filesDir: File
    private val modelRef = AssetRef("fake-asr-model", "1")

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
    }

    private fun engine() = FakeAsrEngine(FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult()))

    @Test
    @Requirement("FR-REP-1")
    public fun `FR_REP_1 two different configs yield two different configHashes`() {
        val passLow = PassBFactory.create(filesDir, db, engine(), modelRef, provider = "cpu", calibrator = null)
        val passHigh = PassBFactory.create(
            filesDir,
            db,
            engine(),
            modelRef,
            provider = "cpu",
            calibrator = FakeCalibrator(confirmThreshold = 0.5f),
        )

        assertNotEquals(
            "a different calibrator (or none at all) must produce a different configHash",
            passLow.fingerprint.configHash,
            passHigh.fingerprint.configHash,
        )
    }

    @Test
    @Requirement("FR-REP-1")
    public fun `FR_REP_1 the same inputs yield the same configHash across two builds`() {
        val first = PassBFactory.create(filesDir, db, engine(), modelRef, provider = "cpu")
        val second = PassBFactory.create(filesDir, db, engine(), modelRef, provider = "cpu")

        assertEquals(first.fingerprint.configHash, second.fingerprint.configHash)
    }

    @Test
    @Requirement("FR-REP-1")
    public fun `FR_REP_1 the fingerprint provider equals what the caller reports the engine runs on`() {
        val real = PassBFactory.create(filesDir, db, engine(), modelRef, provider = "cpu")
        assertEquals("cpu", real.fingerprint.provider)

        val unavailable = PassBFactory.create(
            filesDir,
            db,
            UnavailableAsrEngine("no model installed"),
            modelRef,
            provider = "none",
        )
        assertEquals("none", unavailable.fingerprint.provider)
    }

    @Test
    @Requirement("FR-REP-1")
    public fun `FR_REP_1 no fingerprint ever equals the old v0-smoke placeholder`() {
        val pass = PassBFactory.create(filesDir, db, engine(), modelRef, provider = "cpu")

        assertNotEquals("v0-smoke", pass.fingerprint.configHash)
    }

    // --- P31 follow-up: PassBFactory.create must actually hand its caller's alertTrigger to the
    // real DataPassBResultSink it builds, not silently keep the NoOp default (the gap this session
    // closes -- see this file's own class kdoc reference and PassBFactory.create's kdoc). These run
    // a real transmission end to end (WorkQueue -> CaptureProcessingLoop -> the factory's own
    // PassB), the same shape CaptureProcessingLoopTest already establishes, so the assertion is
    // about the actually-constructed PassB, never a hand-built substitute.

    private fun stageAudio(transmissionId: String, sessionId: String = "SESSION01") {
        val codec = org.ort.capture.android.codec.DeflatePredictiveCodec()
        val pcm = ByteArray(16_000 * 2)
        val encoded = codec.encode(pcm)
        val audioFile = File(filesDir, "audio/$sessionId/$transmissionId.flac")
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(encoded)
    }

    private class RecordingAlertTrigger : AlertEvaluationTrigger {
        val calls = mutableListOf<AlertMatchInput>()
        override fun fireAndForget(input: AlertMatchInput) {
            calls += input
        }
    }

    /** A resolvable transcript ("K7ABC") -- the one every alert-wiring test below needs so the
     * factory's own sink has a real station id to hand its alertTrigger. */
    private fun resolvableEngine(): FakeAsrEngine {
        val text = "kilo seven alpha bravo charlie"
        return FakeAsrEngine(FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult(text = text)))
    }

    @Test
    @Requirement("FR-ALR-3", "FR-ALR-4", "AC-194", "AC-195")
    public fun `FR_ALR_3 a caller-supplied alertTrigger receives the real PassB's own resolved result`(): Unit =
        runBlocking {
            db.sessionDao().insert(PipelineTestFixtures.session())
            db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-ALR"))
            stageAudio("TX-ALR")

            val trigger = RecordingAlertTrigger()
            val pass = PassBFactory.create(
                filesDir,
                db,
                resolvableEngine(),
                modelRef,
                provider = "cpu",
                // P33/R-1110: with no calibrator the resolver reports AMBIGUOUS (no stationId at
                // all), so this test -- about alertTrigger wiring, not calibration -- supplies a
                // fake one to reach a real, named station, same as PassBFactoryCalibrationTest.
                calibrator = FakeCalibrator(confirmThreshold = -1f),
                alertTrigger = trigger,
            )
            val queue = WorkQueue(db, TestClock())
            queue.enqueue("TX-ALR", PassId.B_OFFLINE)
            val loop = CaptureProcessingLoop(PassDrainRunner(queue, runId = "run-1"), pass)

            loop.drainOnce()

            assertEquals(
                TransmissionState.COMPLETE,
                db.transmissionDao().getById("TX-ALR")!!.processingState,
            )
            assertEquals(
                "the factory's own sink must call the trigger it was given, not the NoOp default",
                1,
                trigger.calls.size,
            )
            assertEquals("K7ABC", trigger.calls.single().stationId)
        }

    @Test
    @Requirement("FR-ALR-4", "AC-195")
    public fun `FR_ALR_4 a throwing alertTrigger from the factory never prevents the transmission completing`(): Unit =
        runBlocking {
            db.sessionDao().insert(PipelineTestFixtures.session())
            db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-ALR-THROW"))
            stageAudio("TX-ALR-THROW")

            val throwingTrigger = AlertEvaluationTrigger { error("boom") }
            val pass = PassBFactory.create(
                filesDir,
                db,
                resolvableEngine(),
                modelRef,
                provider = "cpu",
                alertTrigger = throwingTrigger,
            )
            val queue = WorkQueue(db, TestClock())
            queue.enqueue("TX-ALR-THROW", PassId.B_OFFLINE)
            val loop = CaptureProcessingLoop(PassDrainRunner(queue, runId = "run-1"), pass)

            val leased = loop.drainOnce()

            assertEquals(1, leased)
            assertEquals(
                "constitution IV: a throwing alert path must never stop the transmission's own write",
                TransmissionState.COMPLETE,
                db.transmissionDao().getById("TX-ALR-THROW")!!.processingState,
            )
            assertTrue(db.transcriptDao().getCurrent("TX-ALR-THROW") != null)
        }

    @Test
    @Requirement("FR-ALR-4")
    public fun `FR_ALR_4 the default alertTrigger stays NoOp when a caller supplies none`(): Unit = runBlocking {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-ALR-DEFAULT"))
        stageAudio("TX-ALR-DEFAULT")

        // No alertTrigger argument at all -- exercises PassBFactory.create's own default, exactly
        // as ReprocessRunner.realPassBFor and every other pre-existing caller does today.
        val pass = PassBFactory.create(
            filesDir,
            db,
            resolvableEngine(),
            modelRef,
            provider = "cpu",
        )
        val queue = WorkQueue(db, TestClock())
        queue.enqueue("TX-ALR-DEFAULT", PassId.B_OFFLINE)
        val loop = CaptureProcessingLoop(PassDrainRunner(queue, runId = "run-1"), pass)

        loop.drainOnce()

        // Nothing to assert on the trigger itself (NoOp has no observable state) -- the meaningful
        // assertion is that omitting alertTrigger entirely still compiles and still completes the
        // transmission normally, i.e. the new parameter is genuinely additive.
        assertEquals(
            TransmissionState.COMPLETE,
            db.transmissionDao().getById("TX-ALR-DEFAULT")!!.processingState,
        )
        assertNull(db.workQueueDao().findByTransmissionAndPass("TX-ALR-DEFAULT", "B_OFFLINE").singleOrNull())
    }
}
