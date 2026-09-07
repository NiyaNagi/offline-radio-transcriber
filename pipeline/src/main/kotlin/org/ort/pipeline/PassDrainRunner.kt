package org.ort.pipeline

import org.ort.data.WorkQueue
import org.ort.data.entity.WorkQueueItemEntity

/**
 * The orchestrator-side watchdog (FR-RUN-10a → AC-99). `:data`'s [WorkQueue.runLeased] already
 * carries the per-item deadline and cancels a hung coroutine at it (build-plan P5); this class
 * is the thin piece that actually belongs to `:pipeline`: leasing a batch and running each
 * leased item's [Pass] **sequentially, continuing past a failure or a timeout** — a pass that
 * hangs must not stop the rest of the batch from draining.
 */
public class PassDrainRunner(private val queue: WorkQueue, private val runId: String) {

    /**
     * Leases up to [limit] ready items with [deadlineMillis] each, and runs [pass] against every
     * one in turn. Returns every outcome, in lease order — a hang at item N is cancelled and
     * recorded as [org.ort.data.PassRunOutcome.Errored] and item N+1 still runs.
     */
    public suspend fun drainBatch(
        limit: Int,
        deadlineMillis: Long,
        pass: Pass,
    ): List<Pair<WorkQueueItemEntity, org.ort.data.PassRunOutcome>> {
        val leased = queue.leaseBatch(runId, limit) { deadlineMillis }
        return leased.map { item -> item to queue.runLeased(item) { pass.run(item) } }
    }
}
