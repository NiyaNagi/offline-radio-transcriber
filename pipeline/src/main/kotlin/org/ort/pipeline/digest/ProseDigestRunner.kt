package org.ort.pipeline.digest

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.ort.data.OrtDatabase
import org.ort.llm.mediapipe.MediaPipeLlmEngine
import java.time.Duration

/**
 * The real `androidx.work.CoroutineWorker` adapter over [ProseDigestWorkRunner] (FR-DIG-3,
 * FR-DIG-5, AC-87, D36). All decision logic lives in [ProseDigestWorkRunner]; this class only
 * wires real Android/`:data`/`:llm-mediapipe` dependencies and reports the outcome.
 *
 * **Scheduling.** A self-rescheduling chain of **unique one-time work** (name [UNIQUE_WORK_NAME])
 * rather than `PeriodicWorkRequest`: [schedule] enqueues the first run with
 * [ExistingWorkPolicy.KEEP] (a no-op if the chain is already running — calling it from every app
 * launch is intended and safe), and [doWork] itself re-enqueues the next run
 * [REPEAT_INTERVAL] later with [ExistingWorkPolicy.REPLACE] in a `finally` block, so the chain
 * continues regardless of this run's outcome. Chosen over `PeriodicWorkRequest` deliberately: a
 * periodic request's minimum 15-minute floor and its documented constraint-combination quirks are
 * both sidestepped by the simpler, unrestricted one-time-request-with-constraints primitive,
 * which is also the shape that naturally composes with "stop and don't just keep spinning" —
 * every enqueue, first or rescheduled, carries the same [Constraints]: `setRequiresCharging(true)`
 * and `setRequiresDeviceIdle(true)`. The OS's own Doze/charging accounting decides whether the
 * process is even woken at all — never a wakelock this app holds itself.
 *
 * `WorkManager`'s `requiresDeviceIdle` constraint fires only inside a genuine Doze maintenance
 * window, which is rare or OEM-delayed on exactly the reference device this project targets
 * (ColorOS — constitution IV's own case study). [ProseDigestGate], re-evaluated inside
 * [ProseDigestWorkRunner] via [AndroidProseDigestDeviceSignals], is what covers that gap with the
 * app's own, looser idle definition — see that class's own doc comment for the stated rule. The
 * OS constraint decides *whether this code runs at all*; the gate decides whether it is *still*
 * eligible once it has (constitution IV: never trust a single upstream signal for something this
 * consequential).
 *
 * A missing bundled model ([LlmModelLocator.locate] returning `null`) is reported as
 * [androidx.work.ListenableWorker.Result.success] — nothing to do yet is not a failure
 * (constitution I: an absent, not-yet-installed asset is a stated state, never an error to retry
 * forever).
 */
public class ProseDigestRunner(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val filesDir = applicationContext.filesDir
            val modelFile = LlmModelLocator.locate(filesDir) ?: return Result.success()

            val db = OrtDatabase.create(applicationContext)
            val store = RoomProseSummaryStore(db)
            val engine = MediaPipeLlmEngine(applicationContext, modelFile.absolutePath)
            val settings = ProseDigestSettings(SharedPreferencesProseDigestSettingsStore(applicationContext))
            val signals = AndroidProseDigestDeviceSignals(applicationContext)
            val source = ThreadDigestSource(db, store)

            val runner = ProseDigestWorkRunner(
                signals = signals,
                settings = settings,
                engine = engine,
                store = store,
                source = { source.pendingThreads() },
                modelId = LlmModelLocator.MODEL_ID,
            )

            return when (runner.run()) {
                is ProseDigestRunOutcome.NotEligible,
                is ProseDigestRunOutcome.Completed,
                is ProseDigestRunOutcome.StoppedMidRun,
                -> Result.success()
                is ProseDigestRunOutcome.EngineLoadFailed -> Result.failure()
            }
        } finally {
            scheduleNext(applicationContext)
        }
    }

    public companion object {
        public const val UNIQUE_WORK_NAME: String = "prose-digest"
        private val REPEAT_INTERVAL: Duration = Duration.ofHours(1)

        private fun constraints(): Constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .build()

        /** Enqueues the first run. A no-op if the chain is already scheduled — safe to call from every app launch. */
        public fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProseDigestRunner>()
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Re-arms the chain [REPEAT_INTERVAL] from now — called from [doWork] itself, in a `finally`, every run. */
        private fun scheduleNext(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProseDigestRunner>()
                .setConstraints(constraints())
                .setInitialDelay(REPEAT_INTERVAL)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Stops the chain outright — e.g. when [ProseDigestSettings] is disabled, so no further
         * wake is even scheduled.
         */
        public fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
