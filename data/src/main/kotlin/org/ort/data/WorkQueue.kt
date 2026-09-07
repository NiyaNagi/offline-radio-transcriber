package org.ort.data

import androidx.room.withTransaction
import kotlinx.coroutines.withTimeoutOrNull
import org.ort.core.Clock
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState

/** What running one leased item produced — this module's minimal vocabulary, not `:pipeline`'s `PassOutcome`. */
public sealed interface PassRunOutcome {
    /** The pass finished; [finalState] is the transmission state to commit (`COMPLETE` or `REJECTED`). */
    public data class Finished(val finalState: TransmissionState) : PassRunOutcome

    /** The pass ran and threw or returned an error (FR-RUN-9). */
    public data class Errored(val message: String) : PassRunOutcome
}

/**
 * technical design §7.1's durable work queue, with run-id leasing, per-item deadlines and
 * bounded retry (functional spec §7.14 FR-RUN-2, FR-RUN-8..10a). Every state change that
 * touches both the queue and the transmission commits **in one transaction**
 * ([androidx.room.withTransaction]), which is what makes AC-47 ("kill mid-pass, identical
 * results") and AC-99 ("a hung pass is cancelled, the queue keeps draining") true together.
 */
public class WorkQueue(
    private val db: OrtDatabase,
    private val clock: Clock,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val queueDao get() = db.workQueueDao()
    private val transmissionDao get() = db.transmissionDao()

    /** FR-RUN-2 → AC-45: a segment is enqueued regardless of whether any pass can currently run. */
    public suspend fun enqueue(transmissionId: String, pass: PassId, priority: Int = 0): Long = queueDao.insert(
        WorkQueueItemEntity(
            transmissionId = transmissionId,
            pass = pass,
            state = WorkQueueState.READY,
            priority = priority,
            enqueuedAt = clock.wallMillis(),
        ),
    )

    /**
     * Leases up to [limit] ready items under [runId], setting each one's deadline from
     * [deadlineMillisFor] (FR-RUN-10a), and moves its transmission `CAPTURED`/`FAILED` →
     * `PROCESSING`. A transmission with no legal transition to `PROCESSING` (already
     * `PROCESSING`, e.g. from a second pass on the same transmission) is left alone.
     */
    public suspend fun leaseBatch(
        runId: String,
        limit: Int,
        deadlineMillisFor: (WorkQueueItemEntity) -> Long,
    ): List<WorkQueueItemEntity> = db.withTransaction {
        val ready = queueDao.selectReady(limit)
        val now = clock.wallMillis()
        ready.map { item ->
            val deadline = now + deadlineMillisFor(item)
            queueDao.lease(item.id, runId, now, deadline)
            if (transmissionDao.canTransition(item.transmissionId, TransmissionState.PROCESSING)) {
                transmissionDao.requireLegalTransition(item.transmissionId, TransmissionState.PROCESSING)
            }
            item.copy(state = WorkQueueState.LEASED, leaseRunId = runId, startedAt = now, deadlineAt = deadline)
        }
    }

    /**
     * Crash recovery on launch (FR-RUN-8 → AC-47): any lease not held by [currentRunId] belongs
     * to a run that died. The item returns to `READY` and its transmission to `CAPTURED`, so
     * the next lease re-runs the (idempotent) pass with identical results.
     */
    public suspend fun recoverStaleLeases(currentRunId: String): Int = db.withTransaction {
        val stale = queueDao.selectLeasedNotRunId(currentRunId)
        for (item in stale) {
            queueDao.resetToReady(item.id)
            if (transmissionDao.canTransition(item.transmissionId, TransmissionState.CAPTURED)) {
                transmissionDao.requireLegalTransition(item.transmissionId, TransmissionState.CAPTURED)
            }
        }
        stale.size
    }

    /**
     * The pass succeeded: the row is deleted (the queue is not a history — §7.1) and the
     * transmission commits [finalState].
     */
    public suspend fun completePass(item: WorkQueueItemEntity, finalState: TransmissionState): Unit =
        db.withTransaction {
            require(finalState == TransmissionState.COMPLETE || finalState == TransmissionState.REJECTED) {
                "completePass(...) commits COMPLETE or REJECTED, got $finalState"
            }
            queueDao.deleteById(item.id)
            transmissionDao.requireLegalTransition(item.transmissionId, finalState)
        }

    /**
     * The pass errored or timed out (FR-RUN-9). Under [maxAttempts] the item returns to `READY`
     * for a later lease — the transmission is left `PROCESSING` (retry is invisible outside the
     * queue). At the bound, the item becomes terminally `FAILED` (outside the partial unique
     * index's active-state set, so it can still be re-enqueued later — AC-51) and the
     * transmission follows it to `FAILED`.
     */
    public suspend fun failPass(item: WorkQueueItemEntity, error: String): Unit = db.withTransaction {
        val attempts = item.attemptCount + 1
        if (attempts >= maxAttempts) {
            queueDao.markFailed(item.id, attempts, error)
            transmissionDao.requireLegalTransition(item.transmissionId, TransmissionState.FAILED)
        } else {
            queueDao.retryReady(item.id, attempts, error)
        }
    }

    /**
     * Runs [execute] for a leased [item] under its own deadline, cancelling and failing it as a
     * timeout if the deadline passes (FR-RUN-10a → AC-99) — the coroutine is cancelled, the item
     * is marked and the caller is free to lease and drain the next item immediately. Commits the
     * outcome via [completePass]/[failPass] either way.
     */
    public suspend fun runLeased(item: WorkQueueItemEntity, execute: suspend () -> PassRunOutcome): PassRunOutcome {
        val now = clock.wallMillis()
        val remaining = (item.deadlineAt ?: now) - now
        val outcome = if (remaining <= 0) null else withTimeoutOrNull(remaining) { execute() }
        if (outcome == null) {
            failPass(item, "timeout")
            return PassRunOutcome.Errored("timeout")
        }
        when (outcome) {
            is PassRunOutcome.Finished -> completePass(item, outcome.finalState)
            is PassRunOutcome.Errored -> failPass(item, outcome.message)
        }
        return outcome
    }

    public companion object {
        public const val DEFAULT_MAX_ATTEMPTS: Int = 5
    }
}
