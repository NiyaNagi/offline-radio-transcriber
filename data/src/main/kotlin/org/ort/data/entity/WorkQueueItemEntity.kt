package org.ort.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.ort.core.PassId

/** technical design §7.1. No `DONE` — a completed item is deleted; the pass result is the durable record. */
public enum class WorkQueueState { READY, LEASED, FAILED, DEFERRED }

/**
 * technical design §7.1's durable on-disk queue (FR-RUN-2 → AC-45). The uniqueness constraint
 * over `(transmissionId, pass)` covers **active** states only — added by hand in
 * [org.ort.data.OrtDatabase] as a partial index, because Room's `@Index` cannot express the
 * `WHERE` clause that makes a completed or finally-failed pass re-enqueueable.
 */
@Entity(
    tableName = "work_queue_item",
    indices = [Index("state", "priority", "enqueuedAt")],
)
public data class WorkQueueItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transmissionId: String,
    val pass: PassId,
    val state: WorkQueueState,
    val priority: Int,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val shedLevel: Int = 0,
    val leaseRunId: String? = null,
    val deadlineAt: Long? = null,
    val enqueuedAt: Long,
    val startedAt: Long? = null,
)
