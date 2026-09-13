package org.ort.app.ui.improve

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.ort.pipeline.reprocess.ReprocessRunSnapshot
import org.ort.pipeline.reprocess.ReprocessWorker

/**
 * register R-091 (WP11d): the real [ImproveRunner] adapter. **R-1067 addendum**: previously
 * constructed and drove `org.ort.pipeline.reprocess.ReprocessRunner` directly inside [run]'s own
 * `Flow` — cooperative cancellation then meant the run stopped the instant its collecting
 * `LaunchedEffect` did, which happens on every Activity recreation (rotation, font scale, dark
 * mode, or ColorOS killing the Activity in the background). Now a thin adapter over
 * [ReprocessWorker] instead: [run] *starts* the run (idempotently — [ReprocessWorker.start]'s own
 * unique work means a second call while one is active never duplicates it) and returns a `Flow`
 * that only *observes* [ReprocessWorker.observe] — collecting stops watching, never stops the run.
 *
 * Deliberately no `androidx.work.*` import here, or anywhere else in `:app` **main** code — every
 * WorkManager type this adapter needs stays inside `:pipeline` (already a dependency of this
 * module), so no *main*-source `app/build.gradle.kts` dependency was needed to wire this up;
 * [ReprocessWorker]'s own companion functions return and accept only plain `:pipeline` types
 * ([org.ort.pipeline.reprocess.ReprocessProgress], transmission ids, [org.ort.core.PassId]). A
 * test-only `work-testing` dependency was still needed in `app/build.gradle.kts` for this class's
 * own and `ImproveContentActivityTest`'s tests to run a test `WorkManager` at all under
 * Robolectric — see that file's own comment.
 */
public class RealImproveRunner(private val context: Context) : ImproveRunner {

    override fun run(transmissionIds: List<String>, headline: String): Flow<ImproveRunProgress> {
        val appContext = context.applicationContext
        ReprocessWorker.start(appContext, transmissionIds, headline)
        return ReprocessWorker.observe(appContext).map { progress ->
            ImproveRunProgress(done = progress.done, total = progress.total)
        }
    }

    override suspend fun cancel() {
        ReprocessWorker.cancel(context.applicationContext)
    }

    override fun observeState(): Flow<ReprocessRunSnapshot> =
        ReprocessWorker.observeSnapshot(context.applicationContext)
}
