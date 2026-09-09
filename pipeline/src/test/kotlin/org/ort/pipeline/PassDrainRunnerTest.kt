package org.ort.pipeline

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.PassRunOutcome
import org.ort.data.WorkQueue
import org.ort.data.entity.WorkQueueState
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner

/**
 * The pass-timeout watchdog (FR-RUN-10a → AC-99), built here rather than in `:data` or
 * `:asr-*` per build-plan P8's instruction: `:data`'s `WorkQueue.runLeased` already owns the
 * deadline/cancellation mechanics (build-plan P5); this is `:pipeline`'s orchestration of it
 * against a fake [Pass], with no dependency on `:asr-*`.
 */
@RunWith(RobolectricTestRunner::class)
public class PassDrainRunnerTest {

    private val clock = TestClock()
    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test
    @Requirement("AC-99", "FR-RUN-10a")
    public fun `AC_99 a hanging pass is cancelled, marked FAILED, and the queue keeps draining`(): Unit = runTest {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-HANG"))
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-OK"))
        val queue = WorkQueue(db, clock, maxAttempts = 1)
        queue.enqueue("TX-HANG", PassId.B_OFFLINE)
        queue.enqueue("TX-OK", PassId.B_OFFLINE)

        val runner = PassDrainRunner(queue, runId = "run-1")
        var okRan = false
        val results = runner.drainBatch(limit = 10, deadlineMillis = 100) { item ->
            if (item.transmissionId == "TX-HANG") {
                HangingPass().run(item)
            } else {
                okRan = true
                PassRunOutcome.Finished(TransmissionState.COMPLETE)
            }
        }

        val hangOutcome = results.single { it.first.transmissionId == "TX-HANG" }.second
        assertTrue(hangOutcome is PassRunOutcome.Errored)
        val hangRow = db.workQueueDao().findByTransmissionAndPass("TX-HANG", "B_OFFLINE").single()
        assertEquals(WorkQueueState.FAILED, hangRow.state)
        assertEquals(TransmissionState.FAILED, db.transmissionDao().getById("TX-HANG")!!.processingState)

        assertTrue("the queue must keep draining past the hang", okRan)
        assertEquals(TransmissionState.COMPLETE, db.transmissionDao().getById("TX-OK")!!.processingState)
        val okRow = db.workQueueDao().findByTransmissionAndPass("TX-OK", "B_OFFLINE").singleOrNull()
        assertNull(okRow)
    }

    @Test
    @Requirement("R-426")
    public fun `R_426 a pass errored through the real drain path leaves a durable attempt row`(): Unit = runTest {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-BAD"))
        val queue = WorkQueue(db, clock, maxAttempts = 1)
        queue.enqueue("TX-BAD", PassId.B_OFFLINE)
        val runner = PassDrainRunner(queue, runId = "run-1")

        val results = runner.drainBatch(limit = 10, deadlineMillis = 60_000) {
            PassRunOutcome.Errored("out of memory in the decoder")
        }

        val badItem = results.single().first
        val attempt = db.workQueueDao().attemptsFor(badItem.id).single()
        assertEquals(1, attempt.attemptNo)
        assertEquals(org.ort.data.entity.WorkAttemptOutcome.FAILED, attempt.outcome)
        assertEquals("out of memory in the decoder", attempt.reason)
    }
}
