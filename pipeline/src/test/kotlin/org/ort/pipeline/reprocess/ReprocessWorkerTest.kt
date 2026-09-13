package org.ort.pipeline.reprocess

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.pipeline.PipelineTestFixtures
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * register R-1067 (FR-REP-9, FR-REP-11): [ReprocessWorker] is the lifecycle the operator-started
 * reprocess run now lives in, instead of the `Improve-Running` screen's own composition (whose
 * destruction — rotation, font-scale, dark mode, or ColorOS killing the Activity in the
 * background — used to cancel [ReprocessRunner] outright). Covers exactly the four behaviours the
 * register row names: unique work (no duplicate run), a checkpoint that resumes a worker attempt
 * restarted after simulated process death without ever reprocessing an id already marked done, and
 * that an operator cancel still cancels. (a) — a real `ReaderActivity` recreation not cancelling the
 * work — is `ImproveContentActivityTest`'s own `R_1067` case, which needs a real Activity and so
 * cannot live in this Robolectric-but-no-UI file.
 *
 * No fake ASR engine is injected — there is no seam for one on the real `CoroutineWorker` (matching
 * `ProseDigestRunnerTest`'s own precedent of testing the adapter honestly against real-but-absent
 * models rather than a fake engine). With no ASR model installed, every seeded transmission's Pass B
 * attempt is an honest, real [org.ort.data.PassRunOutcome.Errored] → bounded-retry → terminal
 * `WorkQueueState.FAILED` (`ReprocessRunner`'s own kdoc) — real work, real DB writes, a real
 * terminal `work_queue_item` row per attempted id, which is exactly the fact these tests check for
 * to prove an id was or was not attempted.
 */
@RunWith(RobolectricTestRunner::class)
class ReprocessWorkerTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun setUp() = runBlocking {
        // Never `inMemory = true` here -- `ReprocessWorker.doWork()` itself always calls the
        // plain `OrtDatabase.create(applicationContext)` (the cached, file-backed singleton,
        // matching every other real caller), so this test must resolve to that exact same
        // instance to see what the worker under test actually wrote.
        db = OrtDatabase.create(context)
        ReprocessStatus.reset()
        ReprocessPauseControl.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        db.sessionDao().insert(PipelineTestFixtures.session("S1"))
    }

    private suspend fun seed(id: String) {
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission(id, sessionId = "S1")
                .copy(processingState = TransmissionState.COMPLETE),
        )
    }

    private fun inputDataFor(ids: List<String>): Data = Data.Builder()
        .putStringArray(ReprocessWorker.KEY_TRANSMISSION_IDS, ids.toTypedArray())
        .putStringArray(ReprocessWorker.KEY_PASSES, arrayOf(PassId.B_OFFLINE.name))
        .build()

    private fun initTestWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    /** Enqueues a real request under [ReprocessWorker.UNIQUE_WORK_NAME] with a long initial delay,
     * so it deterministically stays `ENQUEUED` for the test to act on — never racing a real
     * `doWork()` to completion the way an immediate, unconstrained request could. */
    private fun enqueuePendingWork(ids: List<String>): UUID {
        val request = androidx.work.OneTimeWorkRequestBuilder<ReprocessWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS)
            .setInputData(inputDataFor(ids))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ReprocessWorker.UNIQUE_WORK_NAME, androidx.work.ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    // ---------------------------------------------------------------------------------------
    // (c) resume after simulated process death — the checkpoint, not timing, does the proving.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `R_1067 a resumed worker never reprocesses an id its checkpoint already marked done`() = runBlocking {
        seed("TX1")
        seed("TX2")
        seed("TX3")
        val workId = UUID.randomUUID()
        // Simulates an earlier attempt for this exact work id that finished TX1 and was then
        // interrupted (process death) before TX2/TX3 — written directly, not produced by
        // actually running a first attempt, so this test does not depend on winning a timing
        // race against a real drain loop to capture the interrupted moment.
        ReprocessRunState(context.filesDir, workId.toString()).apply {
            remainingOrInit(listOf("TX1", "TX2", "TX3"))
            markDone("TX1")
        }
        val worker = TestListenableWorkerBuilder<ReprocessWorker>(context)
            .setId(workId)
            .setInputData(inputDataFor(listOf("TX1", "TX2", "TX3")))
            .build()

        val result = worker.doWork()

        assertTrue(
            "a full resume must report success, not a bare retry/failure",
            result is ListenableWorker.Result.Success,
        )
        val output = (result as ListenableWorker.Result.Success).outputData
        // The discriminating count: honest CUMULATIVE progress across the resume (3 of 3
        // original), even though this attempt itself only ever iterated 2 ids.
        assertEquals(3, output.getInt(ReprocessWorker.KEY_DONE, -1))
        assertEquals(3, output.getInt(ReprocessWorker.KEY_TOTAL, -1))
        // The structural proof TX1 was never handed to ReprocessRunner again: reprocessing it
        // (with no ASR model installed) would leave a real terminal FAILED work_queue_item row
        // for (TX1, B_OFFLINE) -- see this class's own kdoc. None exists because this attempt
        // never enqueued it.
        assertTrue(
            "TX1 was already marked done by the checkpoint -- it must never be reprocessed",
            db.workQueueDao().findByTransmissionAndPass("TX1", PassId.B_OFFLINE.name).isEmpty(),
        )
        // TX2 and TX3 *were* really attempted this run (real work, no model -- Errored -> FAILED).
        assertTrue(db.workQueueDao().findByTransmissionAndPass("TX2", PassId.B_OFFLINE.name).isNotEmpty())
        assertTrue(db.workQueueDao().findByTransmissionAndPass("TX3", PassId.B_OFFLINE.name).isNotEmpty())
        assertTrue(ReprocessRunState(context.filesDir, workId.toString()).remaining().isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // (b) unique work — a second start is a no-op, never a duplicate run.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `R_1067 a second start while one is already enqueued does not create a second run`() {
        initTestWorkManager()

        ReprocessWorker.start(context, listOf("TX1"))
        ReprocessWorker.start(context, listOf("TX2"))

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReprocessWorker.UNIQUE_WORK_NAME).get()
        assertEquals(1, infos.size)
    }

    // ---------------------------------------------------------------------------------------
    // (d) operator cancel still cancels, and clears the checkpoint so nothing resumes it later.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `R_1067 an operator cancel actually cancels the enqueued work`() = runBlocking {
        initTestWorkManager()
        enqueuePendingWork(listOf("TX1"))

        ReprocessWorker.cancel(context)

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReprocessWorker.UNIQUE_WORK_NAME).get()
        assertTrue(infos.isNotEmpty())
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `R_1067 a checkpoint is cleared on operator cancel so a stray retry can never resume what was stopped`() =
        runBlocking {
            initTestWorkManager()
            val id = enqueuePendingWork(listOf("TX1"))
            // A partial checkpoint already on disk for this id, as a real interrupted attempt
            // would leave -- the operator's own cancel must discard it, never let it resume later.
            ReprocessRunState(context.filesDir, id.toString()).remainingOrInit(listOf("TX1"))

            ReprocessWorker.cancel(context)

            assertTrue(ReprocessRunState(context.filesDir, id.toString()).remaining().isEmpty())
        }

    // ---------------------------------------------------------------------------------------
    // Round 2, coordinator item 1/2: an honest board while ENQUEUED (waiting to resume) or
    // finished while nobody was watching -- a pure mapping, tested without a live WorkManager
    // attempt at all (no public API forces progress Data onto a real WorkInfo outside a running
    // worker; a manufactured WorkInfo is the direct, deterministic way to test this mapping).
    // ---------------------------------------------------------------------------------------

    @Test
    fun `R_1067_round2 a manufactured ENQUEUED WorkInfo with real progress maps to Waiting, never Running or Done`() {
        val info = WorkInfo(
            UUID.randomUUID(),
            WorkInfo.State.ENQUEUED,
            emptySet(),
            Data.EMPTY,
            progressDataFor(done = 5, total = 12),
            2,
            0,
        )

        val snapshot = info.toReprocessRunSnapshot()

        assertEquals(ReprocessRunSnapshot.Waiting(done = 5, total = 12), snapshot)
    }

    @Test
    fun `R_1067_round2 a RUNNING WorkInfo maps to Running with the real current id`() {
        val info = WorkInfo(
            UUID.randomUUID(),
            WorkInfo.State.RUNNING,
            emptySet(),
            Data.EMPTY,
            progressDataFor(done = 3, total = 12, currentId = "TX4"),
            1,
            0,
        )

        val snapshot = info.toReprocessRunSnapshot()

        assertEquals(ReprocessRunSnapshot.Running(done = 3, total = 12, currentId = "TX4"), snapshot)
    }

    @Test
    fun `R_1067_round2 a SUCCEEDED WorkInfo maps to Finished from its real output data, never a fabricated summary`() {
        val info = WorkInfo(
            UUID.randomUUID(),
            WorkInfo.State.SUCCEEDED,
            emptySet(),
            progressDataFor(done = 12, total = 12, finishedAtMillis = 999L),
            Data.EMPTY,
            1,
            0,
        )

        val snapshot = info.toReprocessRunSnapshot()

        assertEquals(ReprocessRunSnapshot.Finished(done = 12, total = 12, finishedAtMillis = 999L), snapshot)
    }

    @Test
    fun `R_1067_round2 no WorkInfo at all maps to NotRunning`() {
        assertEquals(ReprocessRunSnapshot.NotRunning, emptyList<WorkInfo>().toReprocessRunSnapshot())
    }

    private fun progressDataFor(
        done: Int,
        total: Int,
        currentId: String? = null,
        finishedAtMillis: Long? = null,
    ): Data = Data.Builder()
        .putInt(ReprocessWorker.KEY_DONE, done)
        .putInt(ReprocessWorker.KEY_TOTAL, total)
        .putString(ReprocessWorker.KEY_CURRENT_ID, currentId)
        .apply { if (finishedAtMillis != null) putLong(ReprocessWorker.KEY_FINISHED_AT_MILLIS, finishedAtMillis) }
        .build()

    // ---------------------------------------------------------------------------------------
    // Round 2, coordinator item 2: WorkManager's execution-time limit. A real OS-triggered stop
    // is not something WorkManagerTestInitHelper/TestDriver can simulate -- TestDriver's public
    // surface is `setAllConstraintsMet`/`setPeriodDelayMet` only; an execution-time-limit stop is
    // enforced by the platform's own JobScheduler, never exposed for simulation. Cancelling the
    // coroutine `doWork()` is suspended inside, at a real in-flight suspension point, is the
    // identical signal reaching this worker either way (`onStopped()` on a `CoroutineWorker`
    // cancels that same coroutine) -- proven end to end: stopped mid-run, checkpoint retained, a
    // fresh attempt finishes only the real remainder.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `R_1067_round2 a worker stopped mid-run leaves its checkpoint intact for a fresh attempt to finish`() =
        runBlocking {
            seed("TX1")
            seed("TX2")
            seed("TX3")
            val workId = UUID.randomUUID()
            val checkpoint = ReprocessRunState(context.filesDir, workId.toString())
            // Frozen via the operator-pause signal *before* `doWork()` ever starts item 1 (the
            // engine's own capture-priority yield loop, `ReprocessRunner.awaitCaptureNotBusy`) --
            // a real, if short, wall-clock window this test can reliably interrupt inside, unlike
            // waiting on real per-item progress: with no ASR model installed, these three items'
            // real (Errored -> terminal FAILED) outcomes can resolve in single-digit milliseconds,
            // faster than any polling loop can reliably catch mid-flight.
            ReprocessPauseControl.paused = true
            try {
                val worker = TestListenableWorkerBuilder<ReprocessWorker>(context)
                    .setId(workId)
                    .setInputData(inputDataFor(listOf("TX1", "TX2", "TX3")))
                    .build()

                val firstAttempt = async(Dispatchers.Default) { worker.doWork() }
                delay(200) // a real margin for the coroutine to actually reach the frozen wait loop
                // Simulates the system stopping this attempt (WorkManager's own `onStopped()` ->
                // for a `CoroutineWorker`, cancelling its coroutine -- the exact same signal
                // reaching `doWork()` either way): cancel the coroutine at its real, in-flight
                // suspension point.
                firstAttempt.cancel()
                val stoppedOutcome = runCatching { firstAttempt.await() }

                assertTrue("a system-triggered stop must never resolve as a normal Result", stoppedOutcome.isFailure)
                assertEquals(
                    "nothing was ever marked done (frozen before item 1) -- the checkpoint must " +
                        "still hold the real, untouched original list, never cleared by a stop " +
                        "this class did not choose",
                    listOf("TX1", "TX2", "TX3"),
                    checkpoint.remaining(),
                )
            } finally {
                ReprocessPauseControl.paused = false // lift the freeze for the resumed attempt below
            }

            val secondAttempt = TestListenableWorkerBuilder<ReprocessWorker>(context)
                .setId(workId)
                .setInputData(inputDataFor(listOf("TX1", "TX2", "TX3")))
                .build()
            val finalResult = secondAttempt.doWork()

            assertTrue(finalResult is ListenableWorker.Result.Success)
            val output = (finalResult as ListenableWorker.Result.Success).outputData
            assertEquals(3, output.getInt(ReprocessWorker.KEY_DONE, -1))
            assertEquals(3, output.getInt(ReprocessWorker.KEY_TOTAL, -1))
            assertTrue(checkpoint.remaining().isEmpty())
        }
}
