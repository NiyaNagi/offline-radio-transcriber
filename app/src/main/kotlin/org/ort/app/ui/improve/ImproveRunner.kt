package org.ort.app.ui.improve

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.ort.data.OrtDatabase

/**
 * R-091 (FR-REP-5/6/9/11): the reprocess-driving seam `Improve-Running` polls. No reprocess/
 * "Pass B/C" scheduling mechanism exists anywhere in `:pipeline` today (no `WorkManager`, no
 * `CoroutineWorker`, no `WorkRequest` — grepped the whole tree; `RealCaptureService.kt`'s own kdoc
 * says so explicitly: "There is no WorkManager job ... this is M8/M10 work"). Building one is
 * `:pipeline` work, outside this package's `ui` row — this interface is what a real one would
 * satisfy; [FakeImproveRunner] is the behavioural fake constitution II requires ship in the same
 * change as any model-bearing (here: pipeline-bearing) interface.
 *
 * [FakeImproveRunner] performs exactly one real, honest side effect — clearing
 * [org.ort.data.entity.TransmissionEntity.isReprocessCandidate] via the same
 * `TransmissionDao.setReprocessCandidate` write path [org.ort.app.ui.data.ModelsController] already
 * uses elsewhere — and invents no transcript, attribution or callsign content. `Improve-Running`'s
 * "changed so far" list and `Improve-Done`'s sample diff are consequently **not rendered by this
 * package**: showing a fabricated before/after transcript would be exactly the fake success
 * constitution I forbids, worse for a "headline capability" (P12) than showing nothing. See this
 * package's report for exactly what a real [ImproveRunner] needs from `:pipeline` to make that
 * content real.
 */
public fun interface ImproveRunner {
    /** Emits one [ImproveRunProgress] per transmission processed, in order; the last emission has
     * `done == total`. Cooperative: a collector that stops collecting stops the run — this is how
     * "Cancel"/navigating away halts it, with no separate cancel token needed. */
    public fun run(transmissionIds: List<String>): Flow<ImproveRunProgress>
}

public data class ImproveRunProgress(val done: Int, val total: Int)

/** The behavioural fake (constitution II) — the only [ImproveRunner] this build ships, since no
 * real reprocessing pipeline exists yet. Real database, real write, no fabricated content. */
public class FakeImproveRunner(
    private val context: Context,
    /** A small per-item delay so a real collector can pause or cancel mid-run (`flow {}`'s `emit`
     * suspends until the collector is ready for the next value — a cold flow's natural backpressure
     * is what makes `Improve-Running`'s Pause/Cancel genuinely stop work, not just stop display). */
    private val perItemDelayMillis: Long = 40L,
) : ImproveRunner {
    override fun run(transmissionIds: List<String>): Flow<ImproveRunProgress> = flow {
        val db = OrtDatabase.create(context.applicationContext)
        transmissionIds.forEachIndexed { index, id ->
            db.transmissionDao().setReprocessCandidate(id, false)
            if (perItemDelayMillis > 0) delay(perItemDelayMillis)
            emit(ImproveRunProgress(done = index + 1, total = transmissionIds.size))
        }
        if (transmissionIds.isEmpty()) emit(ImproveRunProgress(done = 0, total = 0))
    }
}
