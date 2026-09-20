package org.ort.app.analytics

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration

/**
 * The real `androidx.work.CoroutineWorker` adapter over [AnalyticsUploadRunner] (FR-ANL-7) —
 * mirrors `org.ort.pipeline.digest.ProseDigestRunner`'s own self-rescheduling one-time-work chain
 * exactly, including the `finally`-block re-enqueue so the chain survives any outcome. Requires
 * [NetworkType.CONNECTED] (WorkManager's own constraint decides whether the OS even wakes this
 * process); [AnalyticsUploadRunner.run] itself additionally refuses whenever
 * [org.ort.pipeline.capture.CaptureState.isCapturing] is true, so a connectivity change during an
 * active capture session never triggers an upload (FR-ANL-7, AC-177).
 */
public class AnalyticsUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            AnalyticsAppWiring.configureOnce(applicationContext)
            AnalyticsAppWiring.runUploadOnce()
            return Result.success()
        } finally {
            scheduleNext(applicationContext)
        }
    }

    public companion object {
        public const val UNIQUE_WORK_NAME: String = "analytics-upload"
        private val REPEAT_INTERVAL: Duration = Duration.ofHours(6)

        private fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Enqueues the first run — a no-op if the chain is already scheduled, safe to call from
         * every app launch (mirrors `ProseDigestRunner.schedule`'s own contract exactly). */
        public fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnalyticsUploadWorker>()
                .setConstraints(constraints())
                .setInitialDelay(REPEAT_INTERVAL)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        private fun scheduleNext(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnalyticsUploadWorker>()
                .setConstraints(constraints())
                .setInitialDelay(REPEAT_INTERVAL)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        public fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
