package org.ort.app.ui.improve

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.ort.data.OrtDatabase
import org.ort.pipeline.reprocess.ReprocessRunSnapshot

/**
 * R-091 (FR-REP-5/6/9/11): the reprocess-driving seam `Improve-Running` polls. [FakeImproveRunner]
 * is the behavioural fake constitution II requires ship alongside any model-bearing interface;
 * [RealImproveRunner] is the real one, now backed by `org.ort.pipeline.reprocess.ReprocessWorker`
 * (register R-1067 — see that class's own kdoc).
 *
 * [FakeImproveRunner] performs exactly one real, honest side effect — clearing
 * [org.ort.data.entity.TransmissionEntity.isReprocessCandidate] via the same
 * `TransmissionDao.setReprocessCandidate` write path [org.ort.app.ui.data.ModelsController] already
 * uses elsewhere — and invents no transcript, attribution or callsign content. `Improve-Running`'s
 * "changed so far" list and `Improve-Done`'s sample diff are consequently **not rendered by this
 * package**: showing a fabricated before/after transcript would be exactly the fake success
 * constitution I forbids, worse for a "headline capability" (P12) than showing nothing.
 *
 * **R-1067: no longer a `fun interface`.** A real run now lives in `ReprocessWorker`, a lifecycle
 * the screen observes rather than owns — a collector that merely stops collecting [run]'s `Flow`
 * (navigating away, an Activity recreation, a font-scale or dark-mode change, ColorOS killing the
 * Activity in the background) must **not** stop a real run any more; that dependency was exactly
 * R-1067's bug (disclosed while fixing R-1063 — the page survived recreation, but the run itself
 * did not). [cancel] is now the one, explicit, operator-only way to stop a real run.
 * [FakeImproveRunner] has no persistent run outside its own `Flow` to stop, so its [cancel] is a
 * documented no-op — it and the debug scenario simulator that uses it are not this register row's
 * concern.
 */
public interface ImproveRunner {
    /**
     * Starts the run if none is already active — a real implementation's [run] is idempotent, so a
     * second call while one is already going (a second tap, or a fresh composition re-attaching
     * after recreation) never starts a duplicate (register R-1067b) — and returns a `Flow`
     * observing its progress; the last emission has `done == total`.
     *
     * **No longer cooperative for stopping the work itself (R-1067).** A collector that stops
     * collecting must leave a real run running; use [cancel] to actually stop one.
     */
    public fun run(transmissionIds: List<String>): Flow<ImproveRunProgress>

    /**
     * The operator's own explicit stop (register R-1067d) — the only way a real run ends before
     * `done == total`. Never called as a side effect of navigating away or a screen recreation;
     * `ImproveContent.kt`'s own `RunningPage` calls this only from its Cancel action.
     */
    public suspend fun cancel()

    /**
     * Round 2 (coordinator item 1): the real run's own state, observed **without** starting one —
     * unlike [run], which both starts (idempotently) and observes. `ImproveContent.kt`'s top level
     * reads this once on every (re)composition — not only across an Activity recreation, but a
     * plain drawer-away-and-back — to reattach to a run already `Waiting`/`Running`, or to learn one
     * finished while this screen was gone, without inventing a fabricated summary either way (see
     * `org.ort.pipeline.reprocess.ReprocessRunSnapshot`'s own kdoc). [FakeImproveRunner] never backs
     * a persistent run, so it always reports [ReprocessRunSnapshot.NotRunning].
     */
    public fun observeState(): Flow<ReprocessRunSnapshot>
}

public data class ImproveRunProgress(val done: Int, val total: Int)

/** The behavioural fake (constitution II) — ships alongside [RealImproveRunner] for tests and the
 * debug scenario simulator. Real database, real write, no fabricated content. */
public class FakeImproveRunner(
    private val context: Context,
    /** A small per-item delay so a real collector can pause or cancel mid-run (`flow {}`'s `emit`
     * suspends until the collector is ready for the next value — a cold flow's natural backpressure
     * is what makes a collector that stops collecting this fake's own run genuinely stop it; unlike
     * [RealImproveRunner] this fake has no persistent lifecycle of its own for R-1067 to matter to).
     *
     * R-143 (round 4, System validator): raised from 40ms — at 40ms, a small group (the
     * `field-tier1` scenario seeded one over before this round) finished before an emulator
     * screenshot script's own settle wait could ever observe `Improve-Running` — the screen was
     * real but effectively unreachable. 150ms keeps a modest group's run visible for a couple of
     * seconds without making a real, larger reprocess run tediously slow; `ImprovePollingTest`
     * still passes `0L` where a test wants the run to finish immediately.
     */
    private val perItemDelayMillis: Long = 150L,
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

    /** No-op — see this class's own kdoc and [ImproveRunner.cancel]'s. */
    override suspend fun cancel() {
        // Intentionally empty: this fake's own `Flow` already stops the moment its collector does
        // (a cold flow, no persistent backing) — nothing further to tear down.
    }

    /** Always [ReprocessRunSnapshot.NotRunning] — see this class's own kdoc and
     * [ImproveRunner.observeState]'s. */
    override fun observeState(): Flow<ReprocessRunSnapshot> = flowOf(ReprocessRunSnapshot.NotRunning)
}
