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

/** Register R-426 (`Fail-Pass.dc.html`): a leased attempt either ran out its own deadline
 * ([org.ort.data.WorkQueue.runLeased]'s timeout path) or errored — the two ways
 * [org.ort.data.WorkQueue.failPass] is ever called. */
public enum class WorkAttemptOutcome { FAILED, TIMEOUT }

/**
 * Register R-426: one durable row per failed attempt at a [WorkQueueItemEntity], written by
 * [org.ort.data.WorkQueue.failPass] — the single place both a direct caller and
 * [org.ort.data.WorkQueue.runLeased]'s timeout path end up. `attemptCount` only ever increments on
 * a failure (a success deletes the queue row outright — technical design §7.1, "the queue is not a
 * history"), so "attempt" in this table's own sense already means "failed attempt"; `Fail-Pass.dc.html`
 * only ever renders failures anyway (each with its timestamp and reason), so this deliberately does
 * not also log the eventual success — there is no attempt number a success would occupy.
 *
 * `itemId` is a plain reference to [WorkQueueItemEntity.id], **not** a Room `@ForeignKey`: the
 * whole point of this table (constitution III, "nothing is deleted quietly") is that a row here
 * outlives its queue item once that item is later deleted on success, retried past what a UI still
 * shows, or requeued from `FAILED` — an `onDelete = CASCADE` foreign key would erase exactly the
 * history this table exists to keep.
 *
 * `reason` carries the same short, human-readable text [org.ort.data.WorkQueue.failPass]'s own
 * `error: String` parameter already carries into [WorkQueueItemEntity.lastError] — confirmed at
 * every real call site ([org.ort.pipeline.reprocess.ReprocessRunner], `PassB.kt`) to be a message
 * string or an exception's class name, **never** a stack trace (constitution: no raw diagnostic
 * text a UI would have to sanitize).
 */
@Entity(
    tableName = "work_attempt",
    indices = [Index("itemId", "attemptNo")],
)
public data class WorkAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val attemptNo: Int,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val outcome: WorkAttemptOutcome,
    val reason: String,
)
