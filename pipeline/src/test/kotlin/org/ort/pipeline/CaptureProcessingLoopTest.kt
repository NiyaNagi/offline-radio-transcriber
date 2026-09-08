package org.ort.pipeline

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.capture.android.codec.DeflatePredictiveCodec
import org.ort.core.AssetRef
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.pipeline.passb.PassBFactory
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Build-plan P12, defect 3, "write first": before this test, `PassDrainRunner` (P8), `PassB`
 * (P11) and `RealSherpaDecoder` (P10 follow-up) all existed and none was constructed anywhere in
 * the running app -- a captured, enqueued transmission sat `CAPTURED` forever. This proves the
 * whole loop against [FakeAsrEngine], as the prompt asks for, before any real-model work: a queued
 * transmission is leased from the real [WorkQueue], decoded, run through the six-control rejection
 * pipeline, resolved through the real lexicon grammar, and reaches `COMPLETE` with exactly one
 * current transcript row (AC-31) and a written attribution.
 */
@RunWith(RobolectricTestRunner::class)
public class CaptureProcessingLoopTest {

    private val clock = TestClock()
    private lateinit var db: OrtDatabase
    private lateinit var filesDir: File

    @Before
    public fun setUp() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
    }

    private fun stageAudio(transmissionId: String, sessionId: String = "SESSION01") {
        val codec = DeflatePredictiveCodec()
        // 1s of silence at 16kHz/16-bit -- content doesn't matter, FakeAsrEngine ignores it.
        val pcm = ByteArray(16_000 * 2)
        val encoded = codec.encode(pcm)
        val audioFile = File(filesDir, "audio/$sessionId/$transmissionId.flac")
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(encoded)
    }

    @Test
    @Requirement("AC-31")
    public fun `a queued transmission reaches COMPLETE with exactly one current transcript row`(): Unit = runBlocking {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1"))
        stageAudio("TX1")

        val queue = WorkQueue(db, clock)
        queue.enqueue("TX1", PassId.B_OFFLINE)

        val engine = FakeAsrEngine(
            FakeAsrEngine.Behaviour.Returns(
                FakeAsrEngine.defaultResult(text = "kilo seven alpha bravo charlie"),
            ),
        )
        val modelRef = AssetRef("fake-asr-model", "1")
        val pass = PassBFactory.create(filesDir, db, engine, modelRef, provider = "cpu")
        val loop = CaptureProcessingLoop(PassDrainRunner(queue, runId = "run-1"), pass)

        val leased = loop.drainOnce()

        assertEquals(1, leased)
        assertEquals(TransmissionState.COMPLETE, db.transmissionDao().getById("TX1")!!.processingState)

        val current = db.transcriptDao().getCurrent("TX1")
        assertNotNull("expected a current transcript row", current)
        assertEquals("kilo seven alpha bravo charlie", current!!.text)
        assertTrue(current.isCurrent)

        val allVersions = db.transcriptDao().getAllVersions("TX1")
        assertEquals("AC-31: exactly one current, nothing deleted", 1, allVersions.count { it.isCurrent })

        val transmission = db.transmissionDao().getById("TX1")!!
        assertEquals(AttributionState.CONFIRMED, transmission.attributionState)
        assertEquals("K7ABC", transmission.stationId)

        val remainingQueueRow = db.workQueueDao().findByTransmissionAndPass("TX1", "B_OFFLINE").singleOrNull()
        assertNull("the queue row is deleted once the pass commits", remainingQueueRow)
    }

    @Test
    public fun `a rejected segment reaches REJECTED and records its reason, with no transcript row`(): Unit =
        runBlocking {
            db.sessionDao().insert(PipelineTestFixtures.session())
            // 50ms is below TooShortRule's 250ms floor -- rejected pre-decode, the engine never runs.
            db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-SHORT").copy(durationMs = 50L))
            stageAudio("TX-SHORT")

            val queue = WorkQueue(db, clock)
            queue.enqueue("TX-SHORT", PassId.B_OFFLINE)

            val engine = FakeAsrEngine()
            val pass = PassBFactory.create(filesDir, db, engine, AssetRef("fake-asr-model", "1"), provider = "cpu")
            val loop = CaptureProcessingLoop(PassDrainRunner(queue, runId = "run-1"), pass)

            loop.drainOnce()

            val transmission = db.transmissionDao().getById("TX-SHORT")!!
            assertEquals(TransmissionState.REJECTED, transmission.processingState)
            assertNotNull("a rejection must record its reason, not just its outcome", transmission.rejectionReason)
            assertNull(db.transcriptDao().getCurrent("TX-SHORT"))
        }

    @Test
    public fun `the loop keeps draining across multiple calls -- a captured transmission does not sit forever`(): Unit =
        runBlocking {
            db.sessionDao().insert(PipelineTestFixtures.session())
            db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-A"))
            db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-B"))
            stageAudio("TX-A")
            stageAudio("TX-B")

            val queue = WorkQueue(db, clock)
            val engine = FakeAsrEngine(
                FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult(text = "test transmission received")),
            )
            val pass = PassBFactory.create(filesDir, db, engine, AssetRef("fake-asr-model", "1"), provider = "cpu")
            val loop = CaptureProcessingLoop(PassDrainRunner(queue, runId = "run-1"), pass)

            assertEquals(0, loop.drainOnce())
            queue.enqueue("TX-A", PassId.B_OFFLINE)
            assertEquals(1, loop.drainOnce())
            queue.enqueue("TX-B", PassId.B_OFFLINE)
            assertEquals(1, loop.drainOnce())

            assertEquals(TransmissionState.COMPLETE, db.transmissionDao().getById("TX-A")!!.processingState)
            assertEquals(TransmissionState.COMPLETE, db.transmissionDao().getById("TX-B")!!.processingState)
        }
}
