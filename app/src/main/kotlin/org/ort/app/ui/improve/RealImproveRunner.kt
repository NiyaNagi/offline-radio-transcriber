package org.ort.app.ui.improve

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.ort.data.OrtDatabase
import org.ort.pipeline.reprocess.ReprocessRunner

/**
 * register R-091 (WP11d): the real [ImproveRunner] [FakeImproveRunner]'s own kdoc named as
 * missing — `:pipeline`'s `org.ort.pipeline.reprocess.ReprocessRunner` now exists and this is the
 * thin adapter over it, exactly matching [ImproveRunner]'s contract (`Flow<ImproveRunProgress>`,
 * cooperative cancellation — a collector that stops collecting stops [ReprocessRunner] too, since
 * `Flow`'s own backpressure suspends its `emit` calls). Only `done`/`total` cross this boundary;
 * [org.ort.pipeline.reprocess.ReprocessStatus] carries the richer `Running(done, total, currentId)`/
 * `Paused`/`Done(summary)` state a status surface can read directly instead, without collecting
 * this class's own returned `Flow` (see that object's kdoc).
 *
 * **Not yet wired into `ImproveContent.kt`.** That file constructs `FakeImproveRunner(context)`
 * directly (`remember { FakeImproveRunner(context) }`) rather than through `OrtApplication`/DI —
 * there is nothing there this package's brief grants it to edit (`ImproveContent.kt` is WP10's own
 * row). Swapping the fake for this class is one line, left for the lead/WP10 to make — see this
 * package's report.
 */
public class RealImproveRunner(private val context: Context) : ImproveRunner {

    override fun run(transmissionIds: List<String>): Flow<ImproveRunProgress> {
        val appContext = context.applicationContext
        val db = OrtDatabase.create(appContext)
        val reprocessRunner = ReprocessRunner(db = db, filesDir = appContext.filesDir)
        return reprocessRunner.run(transmissionIds).map { progress ->
            ImproveRunProgress(done = progress.done, total = progress.total)
        }
    }
}
