package org.ort.pipeline.reprocess

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import org.ort.core.PassId
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus

/**
 * register R-1067 (FR-REP-9, FR-REP-11): the operator-started reprocess run's real lifecycle,
 * outliving `Improve-Running`'s own composition — `ImproveContent.kt`'s own kdoc named the gap this
 * closes: *"Resuming a genuinely in-flight run across a process-surviving recreation would need the
 * run itself to live somewhere longer-lived than a composition ... a `WorkManager`, or similar."*
 * The same real `androidx.work.CoroutineWorker`-over-a-pure-runner shape [ProseDigestRunner] already
 * established in this package's sibling — [ReprocessRunner] itself is untouched, exactly as
 * constitution II asks: this class only wires it to a lifecycle a screen cannot cancel by leaving.
 *
 * **Unique work, no constraints, deliberately.** [start] enqueues under [UNIQUE_WORK_NAME] with
 * [ExistingWorkPolicy.KEEP] — a second tap ([b] in the register row) is a no-op onto the run
 * already going, never a duplicate. Unlike [ProseDigestRunner]'s own passive, charging-and-idle-
 * gated schedule (FR-DIG-5, a background job nobody is watching), this is FR-REP-5's **on-device
 * reprocess action** — the operator tapped "Improve all" *now*; [ReprocessRunner]'s own kdoc already
 * calls a reprocess run "a foreground, user-watched ... run, not live capture's unwatched background
 * queue." A `setRequiresDeviceIdle`/`setRequiresCharging` constraint would silently defer exactly
 * the action the operator just asked to start, which FR-REP-5 does not describe as deferrable.
 * `ReprocessRunner`'s own capture-priority yield ([ReprocessRunner.isCaptureBusy]) is the real,
 * finer-grained backoff this run needs while running — a WorkManager constraint is the wrong grain
 * for it (it would gate *starting*, not *yielding mid-run*).
 *
 * **Checkpointed, never re-reads work already accounted for.** [ReprocessRunState] persists which
 * of this attempt's own transmission ids are still outstanding; a worker attempt WorkManager
 * restarts after this process dies (never scheduling this class performs itself) resumes exactly
 * that remainder — [c] in the register row. Progress reported through [setProgress] and the final
 * [androidx.work.ListenableWorker.Result]'s own `Data` is the **cumulative** count against the
 * run's original total, not [ReprocessRunner.run]'s own per-attempt count (which restarts at 0 for
 * whatever subset a resumed attempt is handed) — see [ReprocessRunState]'s own kdoc.
 *
 * **Operator cancel only, never a lifecycle accident.** Cancelling the coroutine
 * [ReprocessRunner.run]'s `Flow` collection is running in — [WorkManager]'s own reaction to
 * [cancel], to the app being force-stopped, to the process dying outright, or to the system
 * stopping this attempt for exceeding its execution limit (see the decision below) — leaves this
 * attempt's checkpoint exactly where it was: [doWork] has deliberately no `try`/`catch` around the
 * collection loop, so the `CancellationException` propagates uncaught, skipping
 * `checkpoint.clear()` entirely, and [WorkManager]'s own automatic retry of interrupted work
 * resumes rather than silently drops progress ([d] in the register row is the operator's own
 * deliberate stop, handled by [cancel] itself explicitly discarding the checkpoint — see its own
 * kdoc for why that case, and only that case, must not resume).
 *
 * **Never reads as done when it is not (constitution I).** [Result.success] is returned only once
 * [ReprocessRunState.remaining] is empty — a `Data` reporting `done == total` is a fact about this
 * exact call, never asserted while a real remainder still exists.
 *
 * **Round 2 (coordinator item 2a) — decision: stop-and-reschedule, not a foreground service.**
 * WorkManager stops a plain background `CoroutineWorker` after roughly ten minutes of execution; a
 * night of real overs through Whisper can easily run longer. The alternative — `setForeground` with
 * a persistent notification, `FOREGROUND_SERVICE_DATA_SYNC` (API 34) — is not built here, on
 * purpose:
 * 1. **Correctness does not depend on it.** [ReprocessRunState] already makes a stop-and-resume
 *    cycle exactly as safe as an uninterrupted run — no double-processing (`c` in the register
 *    row), no data lost, honest cumulative progress either way. A foreground service would only
 *    change *how promptly* a stopped attempt resumes, never *whether* the eventual result is
 *    correct — FR-REP-11's own bar ("never leave a record in a worse state") is met regardless.
 * 2. **ColorOS is documented to kill even foreground services under aggressive battery states**
 *    (AGENTS.md: "liveness is proven by heartbeat, never by `isIgnoringBatteryOptimizations()`") —
 *    a foreground service reduces the *chance* of a mid-run stop, it does not eliminate the need to
 *    handle one honestly, so the checkpoint and the [ReprocessRunSnapshot.Waiting] board state
 *    below are required either way; a foreground service would be additive risk-reduction on top
 *    of a mechanism that already has to exist.
 * 3. **A persistent notification is a real, permanent UI surface** (channel, icon, cancel action)
 *    that belongs to `:app`, not `:pipeline`, and is a feature in its own right — manifest
 *    permissions, `platformGuards`, and a notification design are all out of one round's scope for
 *    a decision that does not change correctness.
 *
 * The honest cost of this choice: on ColorOS, a stopped attempt's resume can be deferred
 * arbitrarily by the OS's own background-execution throttling. [ReprocessRunSnapshot.Waiting] (see
 * that type's own kdoc) is what keeps the board from lying about it in the meantime — "waiting to
 * resume, N of M done," never fake live progress and never a premature Done.
 */
public class ReprocessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val allIds = inputData.getStringArray(KEY_TRANSMISSION_IDS)?.toList().orEmpty()
        val headline = inputData.getString(KEY_HEADLINE) ?: DEFAULT_HEADLINE
        val passes = inputData.getStringArray(KEY_PASSES)
            ?.mapNotNull { name -> runCatching { PassId.valueOf(name) }.getOrNull() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: ReprocessRunner.SUPPORTED_PASSES

        val db = OrtDatabase.create(applicationContext)
        val checkpoint = ReprocessRunState(applicationContext.filesDir, id.toString())
        val checkpointRemaining = checkpoint.remainingOrInit(allIds)
        val originalTotal = checkpoint.originalTotal(allIds.size)

        // Round 4 (coordinator review, constitution I/FR-REP-11): a real device trace found the
        // checkpoint recording an id as done while the transmission row was still a genuine
        // reprocess candidate -- a kill between an interrupted attempt and its resume, before this
        // round's `TransmissionDao.markProcessedAtTier` closed the two-statement race that produced
        // it. Never trust the checkpoint alone for "already handled": re-verify every id it
        // considers done against the real DB, and hand any still-genuine candidate back to this
        // attempt -- `ReprocessRunner`'s own idempotency (its class kdoc) makes redoing one safe.
        val staleCandidateIds = staleCandidateIds(db, allIds, checkpointRemaining)
        val remaining = reconcileRemaining(allIds, checkpointRemaining, staleCandidateIds)

        if (remaining.isEmpty()) {
            checkpoint.clear()
            return Result.success(finishedData(originalTotal, originalTotal, realDoneCount(db, allIds), headline))
        }

        // Round 4 (coordinator item 3): an honest starting point published before any item is
        // touched -- a reattach landing before `runner.run`'s own first progress emission (paused,
        // or simply not yet scheduled) must never read WorkManager's still-empty progress `Data` as
        // "0 of 0." The real total (and the real group label -- item 2) are known now; say so.
        setProgress(progressData(originalTotal - remaining.size, originalTotal, currentId = null, headline))

        // R-1067: deliberately no try/catch around this block. If the collecting coroutine is
        // cancelled mid-run (WorkManager stopping this attempt for any reason not this class's own
        // choice — process death, the app force-stopped, an OS-level stop), the `CancellationException`
        // propagates out of doWork() uncaught, skipping checkpoint.clear() below entirely, so
        // whatever this attempt already marked done stays on disk for the next attempt to resume.
        // WorkManager itself then decides ENQUEUED-for-retry vs CANCELLED from *why* the coroutine
        // stopped — this class never converts that into a Result either way.
        val busyThreshold = ReprocessRunner.BUSY_SHED_LEVEL_THRESHOLD
        val runner = ReprocessRunner(
            db = db,
            filesDir = applicationContext.filesDir,
            runId = id.toString(),
            isCaptureBusy = {
                (CaptureState.isCapturing && ShedStatus.currentLevel >= busyThreshold) || ReprocessPauseControl.paused
            },
        )
        runner.run(remaining, passes).collect { progress ->
            val finishedId = progress.currentId
            if (finishedId != null) checkpoint.markDone(finishedId)
            val doneSoFar = originalTotal - checkpoint.remaining().size
            setProgress(progressData(doneSoFar, originalTotal, finishedId, headline))
        }

        checkpoint.clear()
        // Round 4 (coordinator item 1): the finished-while-away count comes from what the DB really
        // shows for every id this run owned, never a blind trust of the checkpoint -- see
        // `realDoneCount`'s own kdoc. `done`/`total` here stay "attempts concluded" (unchanged,
        // `ImproveRunner.run`'s own documented "the last emission has done == total" contract, which
        // `RunningPage`'s live board and `RealImproveRunnerTest` both depend on) -- the DB-verified
        // count is carried separately, read only by Root's own "finished while you were away" line.
        return Result.success(finishedData(originalTotal, originalTotal, realDoneCount(db, allIds), headline))
    }

    public companion object {
        public const val UNIQUE_WORK_NAME: String = "reprocess-run"
        public const val KEY_TRANSMISSION_IDS: String = "transmission_ids"
        public const val KEY_PASSES: String = "passes"
        public const val KEY_DONE: String = "done"
        public const val KEY_TOTAL: String = "total"
        public const val KEY_CURRENT_ID: String = "current_id"

        /** Round 4 (coordinator item 2): the real group label the operator started this run
         * against ("All groups", "Captured at tier 1", ...) — [start]'s own caller-supplied value,
         * carried through every [progressData]/[finishedData] `Data` blob so a board that reattaches
         * to this run via nothing but [WorkInfo] (a fresh composition, `transmissionIds = emptyList()`
         * — see `ImproveContent.kt`'s own `reattachToRunningWork`) shows the *real* scope, never the
         * generic placeholder that shape used to fall back to. */
        public const val KEY_HEADLINE: String = "headline"

        /** Only used when [KEY_HEADLINE] is genuinely absent from a run's own input data — never
         * true for any run this class itself enqueues via [start], which always supplies one; a
         * defensive fallback only, for a hypothetical stale work request from before this constant
         * existed. */
        internal const val DEFAULT_HEADLINE: String = "Improving"

        /** Round 2 (coordinator item 1): a real, self-stamped completion time, `System
         * .currentTimeMillis()` at the moment [doWork] actually finished — never fabricated, and
         * the only honest way `Improve`'s root can say "a run finished ... and how many overs it
         * did" for a run nobody was watching when it ended (its own [ReprocessStatus.Summary] is
         * process-memory only and does not survive a process death the way this `Data`, carried in
         * [WorkInfo.outputData], does). */
        public const val KEY_FINISHED_AT_MILLIS: String = "finished_at_millis"

        /**
         * Round 4 (coordinator item 1): separate from [KEY_DONE]/[KEY_TOTAL], which stay "attempts
         * concluded this run" — [ImproveRunner.run]'s own documented "the last emission has
         * `done == total`" contract, which `Improve-Running`'s own live board and every existing
         * caller of [observe] already depend on, and which a Failed-but-genuinely-attempted item must
         * still satisfy (it *was* processed, just not improved). This field is the DB-verified count
         * of ids [realDoneCount] confirms are no longer reprocess candidates — read only by Root's
         * own "a run finished while you were away" line ([ReprocessRunSnapshot.Finished
         * .improvedCount]), which is the one claim that must never over-state what the DB really
         * shows (see that field's own kdoc for the real device trace).
         */
        public const val KEY_IMPROVED_COUNT: String = "improved_count"

        private fun progressData(done: Int, total: Int, currentId: String?, headline: String): Data = Data.Builder()
            .putInt(KEY_DONE, done)
            .putInt(KEY_TOTAL, total)
            .putString(KEY_CURRENT_ID, currentId)
            .putString(KEY_HEADLINE, headline)
            .build()

        private fun finishedData(done: Int, total: Int, improvedCount: Int, headline: String): Data = Data.Builder()
            .putInt(KEY_DONE, done)
            .putInt(KEY_TOTAL, total)
            .putInt(KEY_IMPROVED_COUNT, improvedCount)
            .putLong(KEY_FINISHED_AT_MILLIS, System.currentTimeMillis())
            .putString(KEY_HEADLINE, headline)
            .build()

        /**
         * Round 4 (coordinator item 1): the real, DB-verified count of [allIds] no longer flagged
         * [org.ort.data.entity.TransmissionEntity.isReprocessCandidate] — never the checkpoint's own
         * bookkeeping alone. `Improve`'s root reads exactly this same flag to decide "N overs can get
         * better" (`ImprovePolling`), so this is the one query that can never disagree with it: a
         * "finished, M of N processed" claim built any other way (the checkpoint's own `remaining()`
         * size, or a blind `originalTotal`) could over-claim if a real write for one of [allIds]
         * never actually landed — the exact device-traced defect [staleCandidateIds] and
         * [reconcileRemaining] exist to recover from; this is the same honesty check applied one more
         * time at the end, independent of whether reconciliation already caught it.
         */
        private suspend fun realDoneCount(db: OrtDatabase, allIds: List<String>): Int {
            if (allIds.isEmpty()) return 0
            val stillCandidates = db.transmissionDao().listByIds(allIds).count { it.isReprocessCandidate }
            return allIds.size - stillCandidates
        }

        /**
         * Round 4 (coordinator item 1): which of [allIds] the checkpoint already considers done
         * (`allIds - checkpointRemaining`) but the DB still shows as a genuine, unresolved
         * [org.ort.data.entity.TransmissionEntity.isReprocessCandidate] — the real device-traced
         * shape of the bug (a kill between an interrupted attempt and its resume left three ids with
         * `isReprocessCandidate = 0` but `processedTier` never stamped, which is now impossible going
         * forward — see [org.ort.data.dao.TransmissionDao.markProcessedAtTier]'s own kdoc — but this
         * re-verification is the defense against any id the checkpoint marked done through a path
         * that never durably wrote at all, not just that one now-closed race).
         */
        private suspend fun staleCandidateIds(
            db: OrtDatabase,
            allIds: List<String>,
            checkpointRemaining: List<String>,
        ): Set<String> {
            val checkpointDone = allIds.toSet() - checkpointRemaining.toSet()
            if (checkpointDone.isEmpty()) return emptySet()
            return db.transmissionDao().listByIds(checkpointDone.toList())
                .filter { it.isReprocessCandidate }
                .map { it.id }
                .toSet()
        }

        /**
         * Round 4 (coordinator item 1): the ids this attempt must actually hand to [ReprocessRunner]
         * — [checkpointRemaining] itself, plus any id in [staleCandidates] (a checkpoint-says-done id
         * the DB still shows as a genuine candidate), preserving [allIds]'s own order for a stable,
         * honest re-attempt sequence. A pure function purely so [staleCandidateIds]' real device-
         * traced shape can be exercised directly, with no `WorkManager`/`CoroutineWorker` involved.
         */
        internal fun reconcileRemaining(
            allIds: List<String>,
            checkpointRemaining: List<String>,
            staleCandidates: Set<String>,
        ): List<String> {
            if (staleCandidates.isEmpty()) return checkpointRemaining
            val remainingSet = checkpointRemaining.toSet()
            return allIds.filter { it in remainingSet || it in staleCandidates }
        }

        /**
         * Starts the operator's reprocess run, or no-ops onto the one already running
         * ([ExistingWorkPolicy.KEEP]) — register R-1067 (b): a second tap of "Improve all"/"Start"
         * can never create a second run.
         */
        public fun start(
            context: Context,
            transmissionIds: List<String>,
            headline: String,
            passes: Set<PassId> = ReprocessRunner.SUPPORTED_PASSES,
        ) {
            val request = OneTimeWorkRequestBuilder<ReprocessWorker>()
                .setInputData(
                    Data.Builder()
                        .putStringArray(KEY_TRANSMISSION_IDS, transmissionIds.toTypedArray())
                        .putString(KEY_HEADLINE, headline)
                        .putStringArray(KEY_PASSES, passes.map { it.name }.toTypedArray())
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * The run's own live progress — every update [ReprocessWorker] reports via [setProgress],
         * ending with the final one carried in the finished [WorkInfo.outputData]. Backed by
         * [WorkManager]'s own [WorkInfo] rather than any state a screen owns (register R-1067's own
         * "observing rather than owning"), so it reflects the real run regardless of which, if any,
         * screen is currently collecting it, and regardless of an Activity recreation in between —
         * a fresh collector simply receives [WorkManager]'s own latest tracked state immediately.
         * Completes once the tracked [WorkInfo] reaches a finished state
         * ([WorkInfo.State.isFinished]) — success, failure, or an operator [cancel].
         */
        public fun observe(context: Context): Flow<ReprocessProgress> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
                .mapNotNull { infos -> infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull() }
                .transformWhile { info ->
                    emit(info.toProgress())
                    !info.state.isFinished
                }

        private fun WorkInfo.toProgress(): ReprocessProgress {
            val data = if (state.isFinished) outputData else progress
            val done = data.getInt(KEY_DONE, progress.getInt(KEY_DONE, 0))
            val total = data.getInt(KEY_TOTAL, progress.getInt(KEY_TOTAL, 0))
            val currentId = data.getString(KEY_CURRENT_ID) ?: progress.getString(KEY_CURRENT_ID)
            return ReprocessProgress(done, total, currentId)
        }

        /**
         * Round 2 (coordinator items 1 and 2b): the richer state `Improve` needs to tell a
         * genuinely running attempt apart from one merely `ENQUEUED` (WorkManager's own state for
         * "not currently executing" — a fresh start about to be picked up, *or* a stopped attempt
         * requeued for retry, indistinguishable from `WorkInfo.State` alone) from a finished one
         * nobody was watching end. See [ReprocessRunSnapshot]'s own kdoc for what each case means
         * to the board.
         */
        public fun observeSnapshot(context: Context): Flow<ReprocessRunSnapshot> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
                .map { infos -> infos.toReprocessRunSnapshot() }

        /**
         * The operator's own explicit stop — register R-1067 (d). Discards this run's checkpoint
         * first (reading the real [WorkInfo] id(s) currently enqueued under [UNIQUE_WORK_NAME],
         * never guessing one), so a cancelled run can never be mistaken for one WorkManager should
         * resume: unlike a stop this class did not choose (see the class kdoc), an operator cancel
         * is terminal by definition — the next "Improve all" recomputes a fresh candidate list from
         * `:data` (`ImprovePolling.root`) rather than resuming what was deliberately stopped.
         */
        public suspend fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            // The Flow-backed query, not the raw `ListenableFuture`-returning
            // `getWorkInfosForUniqueWork` -- kept off this module's main compile classpath
            // (`:pipeline/build.gradle.kts` links `work-runtime-ktx`, not guava's
            // `ListenableFuture` shim directly), and there is already a Flow-based reader for
            // exactly this query (see [observe]).
            val infos = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).first()
            infos.forEach { info -> ReprocessRunState(context.filesDir, info.id.toString()).clear() }
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}

/**
 * Round 2 (coordinator items 1, 2b): the honest, richer shape `Improve`'s own board needs —
 * `WorkInfo.State` alone conflates "just started, not yet picked up" with "a stopped attempt
 * WorkManager has requeued for retry" (both are [WorkInfo.State.ENQUEUED]), and neither the screen
 * nor the operator can tell those apart from the raw state name. [Waiting] names that ambiguity
 * honestly rather than showing fake live progress or a premature [Finished] — see
 * `ImproveRunningViewState.waitingToResume`'s own doc comment for how the board renders it.
 *
 * [Finished] is built only from [ReprocessWorker.KEY_DONE]/[KEY_TOTAL]/[KEY_FINISHED_AT_MILLIS] —
 * real facts [ReprocessWorker] itself stamped into [WorkInfo.outputData] — never the richer
 * [ReprocessStatus.Summary] (transcripts/attributions changed, rejected, failed), which is
 * process-memory only and cannot be honestly reconstructed once nobody was watching when the run
 * ended (constitution I: a plain, real count beats a fabricated diff).
 */
public sealed interface ReprocessRunSnapshot {
    /** No unique-name work exists, or the last one ended in [WorkInfo.State.FAILED]/
     * [WorkInfo.State.CANCELLED] — nothing for `Improve`'s root to reattach to. */
    public data object NotRunning : ReprocessRunSnapshot

    /** [WorkInfo.State.ENQUEUED] with real, already-recorded progress — a fresh start not yet
     * picked up (`done == 0`), or a stopped attempt requeued for retry (`done` reflects whatever
     * the checkpoint already finished). Either way, honestly not currently executing. [headline] —
     * round 4 (coordinator item 2) — is the real group label the operator started this run against
     * ([ReprocessWorker.start]'s own caller-supplied value), never a generic placeholder. */
    public data class Waiting(public val done: Int, public val total: Int, public val headline: String) :
        ReprocessRunSnapshot

    /** [WorkInfo.State.RUNNING] — genuinely executing right now. */
    public data class Running(
        public val done: Int,
        public val total: Int,
        public val currentId: String?,
        public val headline: String,
    ) : ReprocessRunSnapshot

    /**
     * [WorkInfo.State.SUCCEEDED] — the real, final counts this attempt reported, and the real
     * wall-clock moment [ReprocessWorker] itself stamped them, never guessed. [done]/[total] are
     * "attempts concluded" (may include an id that genuinely Failed, never improved — the same
     * meaning [ReprocessWorker.observe]'s own `done == total` contract already carries).
     *
     * [improvedCount] (round 4, coordinator item 1) is the different, narrower fact a real device
     * trace found this class needed: a checkpoint that recorded an id as done while its DB row was
     * still a genuine [org.ort.data.entity.TransmissionEntity.isReprocessCandidate] — a kill between
     * an interrupted attempt and its resume, before `TransmissionDao.markProcessedAtTier` closed the
     * two-statement race that produced it — meant "N of M overs processed" over-claimed what the DB
     * really showed. `Improve`'s root reads this field, not [done], for that line; [ImprovePolling]'s
     * own "N overs can get better" reads the identical DB flag, so the two can never honestly
     * disagree.
     */
    public data class Finished(
        public val done: Int,
        public val total: Int,
        public val finishedAtMillis: Long?,
        public val headline: String,
        public val improvedCount: Int,
    ) : ReprocessRunSnapshot
}

internal fun WorkInfo.toReprocessRunSnapshot(): ReprocessRunSnapshot = when (state) {
    WorkInfo.State.ENQUEUED -> ReprocessRunSnapshot.Waiting(
        done = progress.getInt(ReprocessWorker.KEY_DONE, 0),
        total = progress.getInt(ReprocessWorker.KEY_TOTAL, 0),
        headline = progress.getString(ReprocessWorker.KEY_HEADLINE) ?: ReprocessWorker.DEFAULT_HEADLINE,
    )
    WorkInfo.State.RUNNING -> ReprocessRunSnapshot.Running(
        done = progress.getInt(ReprocessWorker.KEY_DONE, 0),
        total = progress.getInt(ReprocessWorker.KEY_TOTAL, 0),
        currentId = progress.getString(ReprocessWorker.KEY_CURRENT_ID),
        headline = progress.getString(ReprocessWorker.KEY_HEADLINE) ?: ReprocessWorker.DEFAULT_HEADLINE,
    )
    WorkInfo.State.SUCCEEDED -> ReprocessRunSnapshot.Finished(
        done = outputData.getInt(ReprocessWorker.KEY_DONE, 0),
        total = outputData.getInt(ReprocessWorker.KEY_TOTAL, 0),
        finishedAtMillis = outputData.getLong(ReprocessWorker.KEY_FINISHED_AT_MILLIS, -1L).takeIf { it >= 0 },
        headline = outputData.getString(ReprocessWorker.KEY_HEADLINE) ?: ReprocessWorker.DEFAULT_HEADLINE,
        // Falls back to `done` (never negative) only for a hypothetical output `Data` from before
        // this field existed -- never true for a `Result` this class itself returns today.
        improvedCount = outputData.getInt(
            ReprocessWorker.KEY_IMPROVED_COUNT,
            outputData.getInt(ReprocessWorker.KEY_DONE, 0),
        ),
    )
    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, WorkInfo.State.BLOCKED -> ReprocessRunSnapshot.NotRunning
}

/** Same "most relevant entry" convention [ReprocessWorker.observe] already uses: the one still
 * live, or else the most recent, or [ReprocessRunSnapshot.NotRunning] if nothing is tracked at all. */
internal fun List<WorkInfo>.toReprocessRunSnapshot(): ReprocessRunSnapshot {
    val info = firstOrNull { !it.state.isFinished } ?: lastOrNull() ?: return ReprocessRunSnapshot.NotRunning
    return info.toReprocessRunSnapshot()
}
