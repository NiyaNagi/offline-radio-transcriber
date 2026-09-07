package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
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

    @Query("SELECT COUNT(*) FROM work_queue_item")
    public suspend fun count(): Int

    @Query("SELECT * FROM work_queue_item WHERE transmissionId = :transmissionId AND pass = :pass")
    public suspend fun findByTransmissionAndPass(transmissionId: String, pass: String): List<WorkQueueItemEntity>
}
