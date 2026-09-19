package org.ort.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.ort.app.ui.data.ModelActionResult
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelsController
import org.ort.net.HttpRangeClient
import org.ort.net.ModelFetchSpec
import org.ort.net.real.RealHttpRangeClient
import java.io.File

/**
 * P22 (D43, FR-AST-10..12): the setup MODELS step's real download mechanism — a **foreground**
 * `WorkManager` job (AC-184) around the existing, unmodified `:net` primitives
 * ([org.ort.net.ModelAcquisition]'s resumable fetch, sha256 verification, atomic rename), reached
 * here through [ModelsController.download] exactly the way `Settings-Assets`' own "Download"
 * action already does — this worker adds *lifecycle* (foreground survival, Wi-Fi-only by default,
 * a notification), never a second copy of the fetch/verify/install logic. Resumability (AC-185)
 * and checksum rejection (AC-186) are therefore already proven by [ModelAcquisition][org.ort.net.ModelAcquisition]'s
 * own tests; what this class's own tests must prove is that a killed-and-relaunched *worker
 * attempt* still resumes rather than restarts, since [ModelAcquisition.fetch][org.ort.net.ModelAcquisition.fetch]'s
 * own `.part`-file resume only works if this class reruns the identical call with the identical
 * destination, which it always does (the same [ModelFetchSpec][org.ort.net.ModelFetchSpec] the
 * catalog always produces for a given [ModelId]).
 *
 * **Wi-Fi-only by default, overridable per download (AC-187)** — a `WorkManager`
 * [Constraints.setRequiredNetworkType] on the enqueued request, not a change to [HttpRangeClient]
 * or [ModelsController.download] itself: network-type gating is `WorkManager`'s own job (it defers
 * *starting* the worker at all until the constraint holds), not this module's to duplicate.
 *
 * **A checksum mismatch rejects and re-queues (AC-186)** — [ModelsController.download] already
 * returns [ModelActionResult.Failure] for that case without installing anything
 * ([org.ort.net.ModelAcquisition.fetch]'s own `verifyAndInstall`); this worker reports that as
 * [Result.retry] rather than [Result.failure], so `WorkManager`'s own backoff re-attempts the
 * fetch from a clean `.part` state — never activating the bad bytes, and never leaving the
 * operator stuck on a permanent failure for what may be a transient corrupt transfer.
 */
public class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /** Test seams (mirror [org.ort.app.ui.setup.SetupActivity]'s own `clock`/`rigLinkPort`
     * pattern): a real `androidx.work.testing.TestListenableWorkerBuilder` constructs this class
     * via its public two-arg constructor, so a test sets these fields on the built instance before
     * calling [doWork] — production never assigns any of them, always taking the real client and
     * the real, generated [ModelCatalog]. [specFor]/[isBundled] exist for the identical reason
     * [ModelsController.download] itself exposes them: today's committed manifest bundles every
     * entry, so a test proving this worker's own download mechanism (rather than its bundled-entry
     * refusal) needs a fixture entry that reads as genuinely downloadable — exactly the `play`
     * variant's own future shape (P23, D43/D44), not yet landed. */
    internal var httpRangeClient: HttpRangeClient = RealHttpRangeClient()
    internal var specFor: (ModelId, File) -> ModelFetchSpec? = ModelCatalog::specFor
    internal var isBundled: (ModelId) -> Boolean = { ModelCatalog.entry(it).bundled }

    override suspend fun doWork(): Result {
        val idName = inputData.getString(KEY_MODEL_ID)
        val id = idName?.let { name -> runCatching { ModelId.valueOf(name) }.getOrNull() }
            ?: return Result.failure(failureData("no valid model id in this work request"))

        setForeground(foregroundInfo(id))

        val outcome = ModelsController.download(
            applicationContext,
            id,
            client = httpRangeClient,
            specFor = specFor,
            isBundled = isBundled,
        )
        return when (outcome) {
            is ModelActionResult.Success -> Result.success()
            is ModelActionResult.Failure -> {
                // AC-186: a checksum mismatch is exactly this reason text
                // (org.ort.net.ModelAcquisition.verifyAndInstall) -- re-queue via WorkManager's own
                // retry rather than reporting a permanent failure for what a clean re-fetch can fix.
                if (outcome.reason.contains("checksum mismatch")) {
                    Result.retry()
                } else {
                    Result.failure(failureData(outcome.reason))
                }
            }
        }
    }

    private fun failureData(reason: String): Data = Data.Builder().putString(KEY_FAILURE_REASON, reason).build()

    /** AC-184: the foreground notification `setForeground` requires — one persistent channel,
     * created idempotently (Android no-ops re-creating an existing channel with the same id). */
    private fun foregroundInfo(id: ModelId): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading ${id.label}")
            .setContentText("Setup needs this model before capture can start")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(notificationId(id), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId(id), notification)
        }
    }

    private fun notificationId(id: ModelId): Int = NOTIFICATION_ID_BASE + id.ordinal

    public companion object {
        internal const val KEY_MODEL_ID: String = "model_id"
        internal const val KEY_FAILURE_REASON: String = "failure_reason"
        internal const val CHANNEL_ID: String = "ort.model-download"
        private const val NOTIFICATION_ID_BASE = 4200

        /** One unique work name per [ModelId] — concurrent downloads of different models are
         * allowed (each is its own foreground job); a second request for the *same* model
         * no-ops onto the one already running ([ExistingWorkPolicy.KEEP]), the identical
         * "a second tap is not a duplicate" convention
         * [org.ort.pipeline.reprocess.ReprocessWorker.UNIQUE_WORK_NAME] already establishes. */
        public fun uniqueWorkName(id: ModelId): String = "model-download-${id.name}"

        /**
         * AC-187: [wifiOnly] (default `true`) becomes [NetworkType.UNMETERED]; the operator's
         * explicit per-download override becomes [NetworkType.CONNECTED] (any network, including
         * metered) — never a silent, permanent relaxation of the default. Split out of [start] so
         * a test can inspect the built request's own constraints without needing a real, initialized
         * `WorkManager` instance just to check what [start] would have enqueued.
         */
        internal fun buildRequest(id: ModelId, wifiOnly: Boolean) = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(Data.Builder().putString(KEY_MODEL_ID, id.name).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        /** Test-only public alias for [buildRequest] — `internal` alone is enough for visibility,
         * but named distinctly so a test call site reads as deliberate, matching this package's own
         * `*ForTest` convention (e.g. `SetupActivity.currentStepForTest`). */
        public fun buildRequestForTest(id: ModelId, wifiOnly: Boolean) = buildRequest(id, wifiOnly)

        public fun start(context: Context, id: ModelId, wifiOnly: Boolean = true) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueWorkName(id), ExistingWorkPolicy.KEEP, buildRequest(id, wifiOnly))
        }

        /** The operator's own explicit cancel — never called automatically; a running download
         * is otherwise left to WorkManager's own retry/backoff. */
        public fun cancel(context: Context, id: ModelId) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(id))
        }

        /** [id]'s current download state, from `WorkManager`'s own [WorkInfo] — never a second,
         * drifting progress model. `null` means no download for [id] has ever been enqueued this
         * install. */
        public fun observe(context: Context, id: ModelId): Flow<ModelDownloadSnapshot?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(uniqueWorkName(id))
                .map { infos -> infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull() }
                .map { info -> info?.toSnapshot() }

        /** A one-shot, current read of [observe] — used by a screen that polls rather than
         * collects continuously (matching [org.ort.app.ui.setup.SetupActivity]'s own
         * `LEVEL_STATUS_POLL_INTERVAL_MILLIS`-style polling elsewhere in this package). */
        public suspend fun currentSnapshot(context: Context, id: ModelId): ModelDownloadSnapshot? =
            observe(context, id).first()

        private fun WorkInfo.toSnapshot(): ModelDownloadSnapshot = when (state) {
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> ModelDownloadSnapshot.Downloading
            WorkInfo.State.SUCCEEDED -> ModelDownloadSnapshot.Succeeded
            WorkInfo.State.FAILED -> ModelDownloadSnapshot.Failed(
                outputData.getString(KEY_FAILURE_REASON) ?: "download failed",
            )
            WorkInfo.State.BLOCKED -> ModelDownloadSnapshot.Downloading
            WorkInfo.State.CANCELLED -> ModelDownloadSnapshot.NotRunning
        }
    }
}

/** The setup MODELS screen's own honest read of a download's state — see
 * [ModelDownloadWorker.observe]. A closed set (constitution I): never a bare boolean hiding
 * whether "not downloading" means "never started" or "just failed". */
public sealed interface ModelDownloadSnapshot {
    public data object Downloading : ModelDownloadSnapshot
    public data object Succeeded : ModelDownloadSnapshot
    public data class Failed(public val reason: String) : ModelDownloadSnapshot
    public data object NotRunning : ModelDownloadSnapshot
}
