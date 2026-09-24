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

    private fun inputDataFor(ids: List<String>, headline: String = "All groups"): Data = Data.Builder()
        .putStringArray(ReprocessWorker.KEY_TRANSMISSION_IDS, ids.toTypedArray())
        .putString(ReprocessWorker.KEY_HEADLINE, headline)
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
    // Round 4 (coordinator review, constitution I/FR-REP-11): a real device trace found a
    // checkpoint that recorded an id as done while the transmission row was still a genuine
    // reprocess candidate (a kill between an interrupted attempt and its resume, three ids left
    // with isReprocessCandidate=0 but processedTier never stamped) -- the resumed attempt then
    // skipped those ids forever while reporting the whole run "processed." These two tests cover
    // the resume-time reconciliation this class must do, and the honest finished count that must
    // follow from it.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `R_1067_round4 a resumed worker redoes an id checkpointed done that the DB still shows as a candidate`() =
        runBlocking {
            seed("TX1")
            seed("TX2")
            // The exact shape the round-2 device trace found: a real candidate row (the write this
            // id's earlier attempt should have durably cleared never actually landed) whose
            // checkpoint nonetheless says "done" -- see `TransmissionDao.markProcessedAtTier`'s own
            // kdoc for the real trace this reproduces.
            db.transmissionDao().setReprocessCandidate("TX1", true)
            val workId = UUID.randomUUID()
            ReprocessRunState(context.filesDir, workId.toString()).apply {
                remainingOrInit(listOf("TX1", "TX2"))
                markDone("TX1")
            }
            val worker = TestListenableWorkerBuilder<ReprocessWorker>(context)
                .setId(workId)
                .setInputData(inputDataFor(listOf("TX1", "TX2")))
                .build()

            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertTrue(
                "TX1's checkpoint said done, but it was still a genuine candidate in the DB -- the " +
                    "resumed attempt must redo it, never skip it forever",
                db.workQueueDao().findByTransmissionAndPass("TX1", PassId.B_OFFLINE.name).isNotEmpty(),
            )
        }

    @Test
    fun `R_1067_round4 the finished-while-away count comes from the DB, never a blind trust of the checkpoint`() =
        runBlocking {
            seed("TX1")
            seed("TX2")
            db.transmissionDao().setReprocessCandidate("TX1", true)
            val workId = UUID.randomUUID()
            ReprocessRunState(context.filesDir, workId.toString()).apply {
                remainingOrInit(listOf("TX1", "TX2"))
                markDone("TX1")
            }
            val worker = TestListenableWorkerBuilder<ReprocessWorker>(context)
                .setId(workId)
                .setInputData(inputDataFor(listOf("TX1", "TX2")))
                .build()

            val result = worker.doWork()

            val output = (result as ListenableWorker.Result.Success).outputData
            // KEY_DONE/KEY_TOTAL stay "attempts concluded" (ImproveRunner.run's own documented
            // done == total contract, unaffected by this fix) -- both ids genuinely were attempted
            // this run (TX1 redone via reconciliation, TX2 normally), regardless of outcome.
            assertEquals(2, output.getInt(ReprocessWorker.KEY_DONE, -1))
            assertEquals(2, output.getInt(ReprocessWorker.KEY_TOTAL, -1))
            // KEY_IMPROVED_COUNT is the separate, narrower, DB-verified fact Root's own "finished
            // while you were away" line reads. TX1 is redone but -- no ASR model installed --
            // genuinely FAILS again (Errored, bounded retry exhausted), which never clears
            // isReprocessCandidate (see `ReprocessRunner.recordOutcome`'s own comment: only
            // Completed/Rejected does). TX2's own default fixture is never a candidate to begin
            // with. The honest count is 1 (TX2 alone), never the naive "both attempted so both
            // improved" the checkpoint alone would report.
            assertEquals(
                "the finished-while-away count must reflect the real DB state (TX1 is still a " +
                    "genuine candidate after failing again), not just 'both ids were attempted'",
                1,
                output.getInt(ReprocessWorker.KEY_IMPROVED_COUNT, -1),
            )
        }

    // ---------------------------------------------------------------------------------------
    // (b) unique work — a second start is a no-op, never a duplicate run.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `R_1067 a second start while one is already enqueued does not create a second run`() {
        initTestWorkManager()

        ReprocessWorker.start(context, listOf("TX1"), headline = "All groups")
        ReprocessWorker.start(context, listOf("TX2"), headline = "All groups")

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
            progressDataFor(done = 5, total = 12, headline = "All groups"),
            2,
            0,
        )

        val snapshot = info.toReprocessRunSnapshot()

        assertEquals(ReprocessRunSnapshot.Waiting(done = 5, total = 12, headline = "All groups"), snapshot)
    }

    @Test
    fun `R_1067_round2 a RUNNING WorkInfo maps to Running with the real current id`() {
        val info = WorkInfo(
            UUID.randomUUID(),
            WorkInfo.State.RUNNING,
            emptySet(),
            Data.EMPTY,
            progressDataFor(done = 3, total = 12, currentId = "TX4", headline = "Captured at tier 1"),
            1,
            0,
        )

        val snapshot = info.toReprocessRunSnapshot()

        assertEquals(
            ReprocessRunSnapshot.Running(done = 3, total = 12, currentId = "TX4", headline = "Captured at tier 1"),
            snapshot,
        )
    }

    @Test
    fun `R_1067_round2 a SUCCEEDED WorkInfo maps to Finished from its real output data, never a fabricated summary`() {
        val info = WorkInfo(
            UUID.randomUUID(),
            WorkInfo.State.SUCCEEDED,
            emptySet(),
            progressDataFor(done = 12, total = 12, finishedAtMillis = 999L, headline = "All groups"),
            Data.EMPTY,
            1,
            0,
        )

        val snapshot = info.toReprocessRunSnapshot()

        assertEquals(
            ReprocessRunSnapshot.Finished(
                done = 12,
                total = 12,
                finishedAtMillis = 999L,
                headline = "All groups",
                improvedCount = 12,
            ),
            snapshot,
        )
    }

    @Test
    fun `R_1067_round4 a manufactured Running WorkInfo with no headline in its progress data falls back honestly`() {
        val info = WorkInfo(
            UUID.randomUUID(),
            WorkInfo.State.RUNNING,
            emptySet(),
            Data.EMPTY,
            Data.Builder().putInt(ReprocessWorker.KEY_DONE, 1).putInt(ReprocessWorker.KEY_TOTAL, 2).build(),
            1,
            0,
        )

        val snapshot = info.toReprocessRunSnapshot()

        assertEquals(
            ReprocessRunSnapshot.Running(
                done = 1,
                total = 2,
                currentId = null,
                headline = ReprocessWorker.DEFAULT_HEADLINE,
            ),
            snapshot,
        )
    }

    @Test
    fun `R_1067_round2 no WorkInfo at all maps to NotRunning`() {
        assertEquals(ReprocessRunSnapshot.NotRunning, emptyList<WorkInfo>().toReprocessRunSnapshot())
    }

    /**
     * Register R-1191. This used to be `delay(200) // a real margin for the coroutine to actually
     * reach the frozen wait loop`, racing an `async(Dispatchers.Default)`. **A margin is not a
     * bound**: on a saturated hosted runner 200 ms is not reliably enough for a coroutine on a
     * contended dispatcher to reach a particular line, and when it is not, this test fails for a
     * reason having nothing to do with what it asserts - then passes on a re-run, which is how
     * R-1174 and R-1180 both nearly escaped.
     *
     * The replacement is a real synchronisation point the production path already publishes:
     * `ReprocessRunner.awaitCaptureNotBusy` sets [ReprocessStatus.State.Paused] on every poll while
     * the freeze holds, so reaching that state *is* "the coroutine is in the wait loop". The bound
     * below is deliberately generous - it is a liveness guard for a hang, not a timing assumption -
     * and it fails saying what the state actually was, so a real regression is diagnosable instead
     * of arriving as an unexplained assertion two screens away.
     */
    private suspend fun awaitFrozenInTheWaitLoop() {
        val deadlineNanos = System.nanoTime() + FROZEN_WAIT_BOUND_MILLIS * NANOS_PER_MILLI
        while (ReprocessStatus.state !is ReprocessStatus.State.Paused) {
            check(System.nanoTime() < deadlineNanos) {
                "the worker never reached ReprocessRunner's frozen wait loop within " +
                    "${FROZEN_WAIT_BOUND_MILLIS}ms - last published state was " +
                    "${ReprocessStatus.state} (register R-1191)"
            }
            delay(FROZEN_WAIT_POLL_MILLIS)
        }
    }

    private fun progressDataFor(
        done: Int,
        total: Int,
        currentId: String? = null,
        finishedAtMillis: Long? = null,
        headline: String = "All groups",
    ): Data = Data.Builder()
        .putInt(ReprocessWorker.KEY_DONE, done)
        .putInt(ReprocessWorker.KEY_TOTAL, total)
        .putString(ReprocessWorker.KEY_CURRENT_ID, currentId)
        .putString(ReprocessWorker.KEY_HEADLINE, headline)
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
                awaitFrozenInTheWaitLoop()
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

    private companion object {
        /** Register R-1191: a liveness bound on a real hang, deliberately far larger than any
         * plausible scheduling delay - never a margin the assertion depends on. */
        const val FROZEN_WAIT_BOUND_MILLIS = 20_000L
        const val FROZEN_WAIT_POLL_MILLIS = 10L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
