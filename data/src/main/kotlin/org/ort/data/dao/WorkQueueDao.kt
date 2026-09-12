package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.ort.data.entity.WorkAttemptEntity
import org.ort.data.entity.WorkQueueItemEntity

/**
 * technical design §7.1. Raw queue mechanics only — leasing, deadlines and bounded retry are
 * orchestrated transactionally by [org.ort.data.WorkQueue], which pairs each of these with the
 * transmission's state change.
 */
@Dao
public interface WorkQueueDao {

    @Insert
    public suspend fun insert(item: WorkQueueItemEntity): Long

    @Query("SELECT * FROM work_queue_item WHERE id = :id")
    public suspend fun getById(id: Long): WorkQueueItemEntity?

    /**
     * Register R-1002 (halt): excludes a `READY` row whose [WorkQueueItemEntity.retryNotBeforeMillis]
     * is still in the future — [org.ort.data.WorkQueue.failPass]'s backoff ladder, enforced at the
     * one place every real lease goes through, not by trusting a caller to wait.
     */
    @Query(
        "SELECT * FROM work_queue_item WHERE state = 'READY' " +
            "AND (retryNotBeforeMillis IS NULL OR retryNotBeforeMillis <= :now) " +
            "ORDER BY priority DESC, enqueuedAt ASC LIMIT :limit",
    )
    public suspend fun selectReady(limit: Int, now: Long): List<WorkQueueItemEntity>

    @Query(
        "UPDATE work_queue_item SET state = 'LEASED', leaseRunId = :runId, startedAt = :startedAt, " +
            "deadlineAt = :deadlineAt WHERE id = :id",
    )
    public suspend fun lease(id: Long, runId: String, startedAt: Long, deadlineAt: Long)

    /** A lease whose run id is not the current run crashed mid-pass (FR-RUN-8). */
    @Query("SELECT * FROM work_queue_item WHERE state = 'LEASED' AND leaseRunId != :currentRunId")
    public suspend fun selectLeasedNotRunId(currentRunId: String): List<WorkQueueItemEntity>

    @Query("SELECT * FROM work_queue_item WHERE state = 'LEASED' AND deadlineAt IS NOT NULL AND deadlineAt < :now")
    public suspend fun selectExpired(now: Long): List<WorkQueueItemEntity>

    @Query(
        "UPDATE work_queue_item SET state = 'READY', leaseRunId = NULL, deadlineAt = NULL, startedAt = NULL, " +
            "retryNotBeforeMillis = NULL WHERE id = :id",
    )
    public suspend fun resetToReady(id: Long)

    @Query("DELETE FROM work_queue_item WHERE id = :id")
    public suspend fun deleteById(id: Long)

    /**
     * Retries remain: back to `READY`, attempt and error recorded, ready to be leased again once
     * [retryNotBeforeMillis] (register R-1002's backoff ladder — [org.ort.data.WorkQueueBackoff])
     * has passed. [selectReady] is the only reader of that column.
     */
    @Query(
        "UPDATE work_queue_item SET state = 'READY', attemptCount = :attemptCount, lastError = :error, " +
            "leaseRunId = NULL, deadlineAt = NULL, startedAt = NULL, retryNotBeforeMillis = :retryNotBeforeMillis " +
            "WHERE id = :id",
    )
    public suspend fun retryReady(id: Long, attemptCount: Int, error: String, retryNotBeforeMillis: Long)

    /** Retries exhausted (FR-RUN-10): terminal — outside the partial unique index's active-state set. */
    @Query(
        "UPDATE work_queue_item SET state = 'FAILED', attemptCount = :attemptCount, lastError = :error, " +
            "leaseRunId = NULL, deadlineAt = NULL WHERE id = :id",
    )
    public suspend fun markFailed(id: Long, attemptCount: Int, error: String)

    /**
     * FR-RUN-9 / FR-REP-8: terminally `FAILED` items eligible for a fresh run, optionally
     * narrowed to one [pass] and/or errors starting with [lastErrorPrefix] (e.g. "no ASR model
     * installed" once F-008's model-install action fires this). Both filters are `null`-able —
     * a `null` filter matches everything, so the default call requeues every `FAILED` item.
     */
    @Query(
        "SELECT * FROM work_queue_item WHERE state = 'FAILED' " +
            "AND (:pass IS NULL OR pass = :pass) " +
            "AND (:lastErrorPrefix IS NULL OR lastError LIKE :lastErrorPrefix || '%')",
    )
    public suspend fun selectFailed(pass: String?, lastErrorPrefix: String?): List<WorkQueueItemEntity>

    /**
     * FR-RUN-9 / FR-REP-8: gives a `FAILED` item a fresh run. `attemptCount` resets to 0 so the
     * bounded retry counter in [org.ort.data.WorkQueue.failPass] starts over; `lastError` is left
     * untouched — the prior failure stays reachable until a new one overwrites it (constitution
     * III, "nothing is deleted quietly").
     */
    @Query(
        "UPDATE work_queue_item SET state = 'READY', attemptCount = 0, leaseRunId = NULL, " +
            "deadlineAt = NULL, startedAt = NULL, retryNotBeforeMillis = NULL WHERE id = :id",
    )
    public suspend fun requeueToReady(id: Long)

    /**
     * Register R-1002 (halt): every currently-`READY` item of [pass] — the set
     * [org.ort.data.WorkQueue.deferReady] moves to `DEFERRED` the moment a direct capability probe
     * (never a parsed exception message — constitution II) reports that pass's engine unavailable,
     * so none of them is ever leased against a fault already known, this run, to be permanent.
     */
    @Query("SELECT * FROM work_queue_item WHERE state = 'READY' AND pass = :pass")
    public suspend fun selectReadyForPass(pass: String): List<WorkQueueItemEntity>

    /**
     * Register R-1002: `DEFERRED` sits outside `selectReady`'s `state = 'READY'` filter, so a
     * deferred item is never leased — but it is still one of `idx_wq_active`'s active states, so a
     * second `enqueue` for the same `(transmissionId, pass)` still correctly finds it rather than
     * creating a duplicate row. `attemptCount` and `lastError`'s prior value are untouched — a
     * defer never spends an attempt (constitution III, "nothing is deleted quietly"); [reason]
     * overwrites `lastError` with why, so the operator sees *that* something happened, not silence.
     */
    @Query("UPDATE work_queue_item SET state = 'DEFERRED', lastError = :reason WHERE id = :id")
    public suspend fun deferItem(id: Long, reason: String)

    /** Register R-1002: every item of [pass] parked `DEFERRED` by [deferItem]. */
    @Query("SELECT * FROM work_queue_item WHERE state = 'DEFERRED' AND pass = :pass")
    public suspend fun selectDeferredForPass(pass: String): List<WorkQueueItemEntity>

    /**
     * Register R-1002: the capability came back — [org.ort.data.WorkQueue.undeferToReady] moves a
     * `DEFERRED` item straight back to `READY`, immediately leasable (no backoff: it never failed,
     * so it has nothing to back off from). `attemptCount` and `lastError` are left exactly as they
     * were before the defer, same "nothing erased" rule [deferItem] follows.
     */
    @Query("UPDATE work_queue_item SET state = 'READY', retryNotBeforeMillis = NULL WHERE id = :id")
    public suspend fun undeferItem(id: Long)

    @Query("SELECT COUNT(*) FROM work_queue_item")
    public suspend fun count(): Int

    @Query("SELECT * FROM work_queue_item WHERE transmissionId = :transmissionId AND pass = :pass")
    public suspend fun findByTransmissionAndPass(transmissionId: String, pass: String): List<WorkQueueItemEntity>

    /** Register R-426: written by [org.ort.data.WorkQueue.failPass] — see [WorkAttemptEntity]'s own doc comment. */
    @Insert
    public suspend fun insert(attempt: WorkAttemptEntity): Long

    /** Register R-426, `Fail-Pass.dc.html`: every failed attempt at [itemId], oldest first — the
     * order the mockup lists them in, ending with the retry-limit line the caller renders itself. */
    @Query("SELECT * FROM work_attempt WHERE itemId = :itemId ORDER BY attemptNo")
    public suspend fun attemptsFor(itemId: Long): List<WorkAttemptEntity>
}
