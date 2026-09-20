package org.ort.pipeline.threading.fake

import org.ort.core.Ulid
import org.ort.data.entity.ThreadJoinReason
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource
import org.ort.pipeline.threading.PriorThreadContext
import org.ort.pipeline.threading.ThreadRepository
import org.ort.pipeline.threading.TransmissionClosure

/**
 * The behavioural fake for [ThreadRepository] (constitution II): an in-memory session, scriptable
 * with plain [TransmissionClosure]s and capture-gap windows, with no `:data`/Room dependency at
 * all — every [org.ort.pipeline.threading.ThreadGroupingCoordinator] test in this package runs on
 * a plain JVM against this, never Robolectric. Every call is recorded so a test can assert what
 * this repository was actually asked, not just the resulting thread.
 */
public class FakeThreadRepository : ThreadRepository {

    private data class ThreadRecord(
        var frequencyHz: Long?,
        var channelName: String?,
        var endedAtUtc: Long,
        var kind: ThreadKind,
        var kindSource: ThreadKindSource,
        val voiceKeyCounts: MutableMap<String, Int> = mutableMapOf(),
        var transmissionCount: Int = 0,
        val transmissionIds: MutableList<String> = mutableListOf(),
    )

    private val closures: MutableMap<String, TransmissionClosure> = mutableMapOf()
    private val threadIdByTransmission: MutableMap<String, String> = mutableMapOf()
    private val threads: MutableMap<String, ThreadRecord> = mutableMapOf()

    /** Register R-1098: the real [ThreadJoinReason] [startThread]/[appendToThread] stamped for each
     * transmission — the same fact [org.ort.pipeline.threading.RoomThreadRepository] now writes to
     * `transmission.threadJoinReason`, kept here so a test can assert it without a real database. */
    private val joinReasonByTransmission: MutableMap<String, ThreadJoinReason> = mutableMapOf()

    /** `sessionId` -> list of `[fromUtc, toUtc)` unlistened capture-gap windows. */
    private val captureGaps: MutableMap<String, MutableList<LongRange>> = mutableMapOf()

    public val closureForCalls: MutableList<String> = mutableListOf()
    public val startThreadCalls: MutableList<TransmissionClosure> = mutableListOf()
    public val appendToThreadCalls: MutableList<Pair<String, TransmissionClosure>> = mutableListOf()

    /** Seeds [closure] as an already-persisted, not-yet-threaded transmission. */
    public fun seedClosure(closure: TransmissionClosure) {
        closures[closure.transmissionId] = closure
    }

    /** Seeds an unlistened capture gap covering `[fromUtc, toUtc)` in [sessionId]. */
    public fun seedCaptureGap(sessionId: String, fromUtc: Long, toUtc: Long) {
        captureGaps.getOrPut(sessionId) { mutableListOf() } += fromUtc until toUtc
    }

    /** The thread id [transmissionId] ended up with, or `null` if it was never threaded. */
    public fun threadIdFor(transmissionId: String): String? = threadIdByTransmission[transmissionId]

    /** Register R-1098: the real [ThreadJoinReason] recorded for [transmissionId]'s own join, or
     * `null` if it was never threaded. */
    public fun joinReasonFor(transmissionId: String): ThreadJoinReason? = joinReasonByTransmission[transmissionId]

    /** How many transmissions [threadId] carries right now, or `null` if it does not exist. */
    public fun transmissionCountOf(threadId: String): Int? = threads[threadId]?.transmissionCount

    public fun kindOf(threadId: String): ThreadKind? = threads[threadId]?.kind

    public fun kindSourceOf(threadId: String): ThreadKindSource? = threads[threadId]?.kindSource

    /** Test-only: lets a test simulate a user override that must survive automatic classification. */
    public fun forceKind(threadId: String, kind: ThreadKind, source: ThreadKindSource) {
        threads[threadId]?.let {
            it.kind = kind
            it.kindSource = source
        }
    }

    override suspend fun closureFor(transmissionId: String): TransmissionClosure? {
        closureForCalls += transmissionId
        if (threadIdByTransmission.containsKey(transmissionId)) return null // already threaded
        return closures[transmissionId]
    }

    override suspend fun priorContextFor(closure: TransmissionClosure): PriorThreadContext? {
        val priorTransmissionId = closures.values
            .filter { it.transmissionId != closure.transmissionId && it.sessionId == closure.sessionId }
            .filter { threadIdByTransmission.containsKey(it.transmissionId) }
            .filter { matches(it, closure) }
            .filter { it.samplePosition < closure.samplePosition }
            .maxByOrNull { it.samplePosition }
            ?.transmissionId
            ?: return null

        val threadId = threadIdByTransmission.getValue(priorTransmissionId)
        val thread = threads.getValue(threadId)
        val priorTransmission = closures.getValue(priorTransmissionId)
        return PriorThreadContext(
            threadId = threadId,
            frequencyHz = priorTransmission.frequencyHz,
            channelName = priorTransmission.channelName,
            lastEndedAtUtc = priorTransmission.endedAtUtc,
            kind = thread.kind,
            kindSource = thread.kindSource,
            voiceKeyCounts = thread.voiceKeyCounts.toMap(),
            transmissionCount = thread.transmissionCount,
        )
    }

    private fun matches(a: TransmissionClosure, b: TransmissionClosure): Boolean = when {
        a.frequencyHz != null && b.frequencyHz != null -> a.frequencyHz == b.frequencyHz
        a.channelName != null && b.channelName != null -> a.channelName == b.channelName
        a.frequencyHz == null && b.frequencyHz == null && a.channelName == null && b.channelName == null -> true
        else -> false
    }

    override suspend fun unlistenedCaptureGapBetween(sessionId: String, fromUtc: Long, toUtc: Long): Boolean =
        captureGaps[sessionId].orEmpty().any { window -> window.first < toUtc && window.last + 1 > fromUtc }

    override suspend fun startThread(
        closure: TransmissionClosure,
        kind: ThreadKind,
        reason: ThreadJoinReason,
    ): String {
        startThreadCalls += closure
        val threadId = Ulid.generate().value
        threads[threadId] = ThreadRecord(
            frequencyHz = closure.frequencyHz,
            channelName = closure.channelName,
            endedAtUtc = closure.endedAtUtc,
            kind = kind,
            kindSource = ThreadKindSource.DETECTED,
            transmissionCount = 1,
        ).also { record ->
            closure.voiceKey?.let { record.voiceKeyCounts[it] = 1 }
            record.transmissionIds += closure.transmissionId
        }
        threadIdByTransmission[closure.transmissionId] = threadId
        joinReasonByTransmission[closure.transmissionId] = reason
        return threadId
    }

    override suspend fun appendToThread(
        threadId: String,
        closure: TransmissionClosure,
        kind: ThreadKind,
        reason: ThreadJoinReason,
    ) {
        appendToThreadCalls += threadId to closure
        val record = threads.getValue(threadId)
        record.endedAtUtc = maxOf(record.endedAtUtc, closure.endedAtUtc)
        record.transmissionCount += 1
        record.kind = kind
        closure.voiceKey?.let { key -> record.voiceKeyCounts[key] = (record.voiceKeyCounts[key] ?: 0) + 1 }
        record.transmissionIds += closure.transmissionId
        threadIdByTransmission[closure.transmissionId] = threadId
        joinReasonByTransmission[closure.transmissionId] = reason
    }
}
