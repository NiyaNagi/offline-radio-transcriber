package org.ort.pipeline.threading

import org.ort.data.entity.ThreadJoinReason
import org.ort.data.entity.ThreadKind

/**
 * The persistence seam [ThreadGroupingCoordinator] reads and writes through — real
 * ([RoomThreadRepository], against `:data`), fake
 * ([org.ort.pipeline.threading.fake.FakeThreadRepository]) for every test in this package
 * (constitution II: "every model-bearing interface ships with a behavioural fake, in the same
 * change").
 */
public interface ThreadRepository {

    /**
     * The facts [transmissionId]'s own row already carries. `null` when there is nothing left to
     * do for it — either no such row exists, or (idempotency: [ThreadGroupingCoordinator] may be
     * called more than once for the same transmission, e.g. a retried Pass B attempt) it already
     * carries a `threadId` from an earlier call.
     */
    public suspend fun closureFor(transmissionId: String): TransmissionClosure?

    /**
     * The most recent transmission in [closure]'s session that is either on the same frequency or
     * the same rig channel as [closure] and already carries a `threadId` — `null` when none
     * exists (this is the first transmission on this frequency/channel in the session). Two
     * frequencies interleaved in one session — a dual-receive rig mixing both bands — each get
     * their own chain: a transmission on frequency A never sees a transmission on frequency B as
     * its prior, however recently that one closed.
     */
    public suspend fun priorContextFor(closure: TransmissionClosure): PriorThreadContext?

    /**
     * FR-RUN-12 / constitution IV: whether any recorded capture gap in [sessionId] overlaps
     * `[fromUtc, toUtc)` — silence nobody listened through, read from the real `capture_gap`
     * table, never inferred from the mere absence of a transmission.
     */
    public suspend fun unlistenedCaptureGapBetween(sessionId: String, fromUtc: Long, toUtc: Long): Boolean

    /**
     * Creates a new `Thread` row for [closure] alone and stamps [closure]'s own transmission with
     * its id and, per register R-1098, [reason] — the real [ThreadGrouper.decide] verdict this
     * transmission's own row now carries forward (`transmission.threadJoinReason`), never thrown
     * away once the caller has read it. Returns the new thread's id.
     */
    public suspend fun startThread(closure: TransmissionClosure, kind: ThreadKind, reason: ThreadJoinReason): String

    /**
     * Extends [threadId] with [closure] and stamps [closure]'s own transmission with it —
     * `endedAt`/`transmissionCount`/`participantStationIds`/`kind` all move forward, never
     * backward (constitution III: "nothing is deleted quietly" — no prior thread field is ever
     * cleared, only extended). Register R-1098: also stamps [reason], the real
     * [ThreadGrouper.decide] verdict for *this* transmission's own join, onto its own row
     * (`transmission.threadJoinReason`) — a fact about this transmission's join, not about the
     * thread as a whole, so it is never folded into [ThreadEntity][org.ort.data.entity.ThreadEntity]
     * itself the way `endedAt`/`kind` are.
     */
    public suspend fun appendToThread(
        threadId: String,
        closure: TransmissionClosure,
        kind: ThreadKind,
        reason: ThreadJoinReason,
    )
}
