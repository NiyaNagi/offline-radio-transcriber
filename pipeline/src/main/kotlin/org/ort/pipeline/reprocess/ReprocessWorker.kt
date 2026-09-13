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
 * [cancel], to the app being force-stopped, or to the process dying outright — leaves this attempt's
 * checkpoint exactly where it was: the `finally`-free `catch (e: CancellationException) { throw e }`
 * below never clears it, so [WorkManager]'s own automatic retry of interrupted work resumes rather
 * than silently drops progress ([d] in the register row is the operator's own deliberate stop,
 * handled by [cancel] itself explicitly discarding the checkpoint — see its own kdoc for why that
 * case, and only that case, must not resume).
 *
 * **Never reads as done when it is not (constitution I).** [Result.success] is returned only once
 * [ReprocessRunState.remaining] is empty — a `Data` reporting `done == total` is a fact about this
 * exact call, never asserted while a real remainder still exists.
 */
public class ReprocessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val allIds = inputData.getStringArray(KEY_TRANSMISSION_IDS)?.toList().orEmpty()
        val passes = inputData.getStringArray(KEY_PASSES)
            ?.mapNotNull { name -> runCatching { PassId.valueOf(name) }.getOrNull() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: ReprocessRunner.SUPPORTED_PASSES

        val checkpoint = ReprocessRunState(applicationContext.filesDir, id.toString())
        val remaining = checkpoint.remainingOrInit(allIds)
        val originalTotal = checkpoint.originalTotal(allIds.size)

        if (remaining.isEmpty()) {
            checkpoint.clear()
            return Result.success(progressData(originalTotal, originalTotal, null))
        }

        // R-1067: deliberately no try/catch around this block. If the collecting coroutine is
        // cancelled mid-run (WorkManager stopping this attempt for any reason not this class's own
        // choice — process death, the app force-stopped, an OS-level stop), the `CancellationException`
        // propagates out of doWork() uncaught, skipping checkpoint.clear() below entirely, so
        // whatever this attempt already marked done stays on disk for the next attempt to resume.
        // WorkManager itself then decides ENQUEUED-for-retry vs CANCELLED from *why* the coroutine
        // stopped — this class never converts that into a Result either way.
        val db = OrtDatabase.create(applicationContext)
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
            setProgress(progressData(doneSoFar, originalTotal, finishedId))
        }

        checkpoint.clear()
        return Result.success(progressData(originalTotal, originalTotal, null))
    }

    public companion object {
        public const val UNIQUE_WORK_NAME: String = "reprocess-run"
        public const val KEY_TRANSMISSION_IDS: String = "transmission_ids"
        public const val KEY_PASSES: String = "passes"
        public const val KEY_DONE: String = "done"
        public const val KEY_TOTAL: String = "total"
        public const val KEY_CURRENT_ID: String = "current_id"

        private fun progressData(done: Int, total: Int, currentId: String?): Data = Data.Builder()
            .putInt(KEY_DONE, done)
            .putInt(KEY_TOTAL, total)
            .putString(KEY_CURRENT_ID, currentId)
            .build()

        /**
         * Starts the operator's reprocess run, or no-ops onto the one already running
         * ([ExistingWorkPolicy.KEEP]) — register R-1067 (b): a second tap of "Improve all"/"Start"
         * can never create a second run.
         */
        public fun start(
            context: Context,
            transmissionIds: List<String>,
            passes: Set<PassId> = ReprocessRunner.SUPPORTED_PASSES,
        ) {
            val request = OneTimeWorkRequestBuilder<ReprocessWorker>()
                .setInputData(
                    Data.Builder()
                        .putStringArray(KEY_TRANSMISSION_IDS, transmissionIds.toTypedArray())
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
