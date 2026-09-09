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

    @Query("SELECT * FROM work_queue_item WHERE state = 'READY' ORDER BY priority DESC, enqueuedAt ASC LIMIT :limit")
    public suspend fun selectReady(limit: Int): List<WorkQueueItemEntity>

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
        "UPDATE work_queue_item SET state = 'READY', leaseRunId = NULL, deadlineAt = NULL, startedAt = NULL " +
            "WHERE id = :id",
    )
    public suspend fun resetToReady(id: Long)

    @Query("DELETE FROM work_queue_item WHERE id = :id")
    public suspend fun deleteById(id: Long)

    /** Retries remain: back to `READY`, attempt and error recorded, ready to be leased again. */
    @Query(
        "UPDATE work_queue_item SET state = 'READY', attemptCount = :attemptCount, lastError = :error, " +
            "leaseRunId = NULL, deadlineAt = NULL, startedAt = NULL WHERE id = :id",
    )
    public suspend fun retryReady(id: Long, attemptCount: Int, error: String)

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
            "deadlineAt = NULL, startedAt = NULL WHERE id = :id",
    )
    public suspend fun requeueToReady(id: Long)

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
