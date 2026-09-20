package org.ort.pipeline.threading

import org.ort.core.Ulid
import org.ort.data.Converters
import org.ort.data.OrtDatabase
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.ThreadJoinReason
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource
import org.ort.data.execRaw

/**
 * The real [ThreadRepository] — built entirely from `:data` surfaces this build-plan unit does
 * not own and must not extend: [org.ort.data.dao.TransmissionDao] and
 * [org.ort.data.dao.CaptureGapDao]'s existing reads, [org.ort.data.dao.CatalogDao]'s existing
 * `ThreadEntity` insert/read, and [org.ort.data.execRaw] — the general raw-statement escape hatch
 * `:data` already publishes for exactly a caller like this one (that function's own doc comment:
 * "the rare caller ... that genuinely does need to run a raw statement Room's own generated DAOs
 * cannot express") — for the two writes no generated DAO method exists for yet: stamping
 * `transmission.threadId` and extending an existing `thread` row (both `@Insert`-only today). No
 * `:data` file is touched by this unit; see its own CHANGELOG entry for why that is the correct
 * call here rather than a shortcut.
 */
public class RoomThreadRepository(private val db: OrtDatabase) : ThreadRepository {

    override suspend fun closureFor(transmissionId: String): TransmissionClosure? {
        val transmission = db.transmissionDao().getById(transmissionId) ?: return null
        if (transmission.threadId != null) return null // already threaded -- see this file's own idempotency note
        return TransmissionClosure(
            transmissionId = transmission.id,
            sessionId = transmission.sessionId,
            samplePosition = transmission.samplePosition,
            startedAtUtc = transmission.startedAtUtc,
            endedAtUtc = transmission.endedAtUtc ?: transmission.startedAtUtc,
            frequencyHz = transmission.frequencyHz,
            channelName = transmission.channelName,
            stationId = transmission.stationId,
            voiceprintId = transmission.voiceprintId,
        )
    }

    override suspend fun priorContextFor(closure: TransmissionClosure): PriorThreadContext? {
        val sessionTransmissions = db.transmissionDao().listBySession(closure.sessionId)
        val priorTransmission = sessionTransmissions
            .asSequence()
            .filter { it.id != closure.transmissionId && it.threadId != null }
            .filter { it.samplePosition < closure.samplePosition }
            .filter { matches(it.frequencyHz, it.channelName, closure) }
            .maxByOrNull { it.samplePosition }
            ?: return null

        val threadId = priorTransmission.threadId!!
        val thread = db.catalogDao().getThread(threadId) ?: return null
        val threadTransmissions = sessionTransmissions.filter { it.threadId == threadId }
        val voiceKeyCounts = threadTransmissions
            .mapNotNull { it.voiceprintId ?: it.stationId }
            .groupingBy { it }
            .eachCount()

        return PriorThreadContext(
            threadId = threadId,
            frequencyHz = priorTransmission.frequencyHz,
            channelName = priorTransmission.channelName,
            lastEndedAtUtc = priorTransmission.endedAtUtc ?: priorTransmission.startedAtUtc,
            kind = thread.kind,
            kindSource = thread.kindSource,
            voiceKeyCounts = voiceKeyCounts,
            transmissionCount = threadTransmissions.size,
        )
    }

    /** FR-SPK-5: same frequency, or -- its own stated exception -- the Rig Module reporting the
     * same channel under a different frequency; with neither side carrying either fact (today's
     * production reality -- `channelName` is always `null`, see `RealCaptureService`), continuity
     * falls back to gap-only (both truly unknown, never guessed to be the "same" by fabricating a
     * match between one known and one unknown value). */
    private fun matches(frequencyHz: Long?, channelName: String?, closure: TransmissionClosure): Boolean = when {
        frequencyHz != null && closure.frequencyHz != null -> frequencyHz == closure.frequencyHz
        channelName != null && closure.channelName != null -> channelName == closure.channelName
        frequencyHz == null && closure.frequencyHz == null && channelName == null && closure.channelName == null -> true
        else -> false
    }

    override suspend fun unlistenedCaptureGapBetween(sessionId: String, fromUtc: Long, toUtc: Long): Boolean =
        db.captureGapDao().listBySession(sessionId).any { gap ->
            val gapEnd = gap.endedAt ?: Long.MAX_VALUE
            gap.startedAt < toUtc && gapEnd > fromUtc
        }

    override suspend fun startThread(
        closure: TransmissionClosure,
        kind: ThreadKind,
        reason: ThreadJoinReason,
    ): String {
        val threadId = Ulid.generate().value
        db.catalogDao().insert(
            ThreadEntity(
                id = threadId,
                sessionId = closure.sessionId,
                startedAt = closure.startedAtUtc,
                endedAt = closure.endedAtUtc,
                frequencyHz = closure.frequencyHz,
                transmissionCount = 1,
                participantStationIds = closure.stationId?.let { listOf(it) },
                digestText = null,
                kind = kind,
                kindSource = ThreadKindSource.DETECTED,
                participantOrder = closure.stationId?.let { listOf(it) },
            ),
        )
        stampTransmission(threadId, closure.transmissionId, reason)
        return threadId
    }

    override suspend fun appendToThread(
        threadId: String,
        closure: TransmissionClosure,
        kind: ThreadKind,
        reason: ThreadJoinReason,
    ) {
        val thread = db.catalogDao().getThread(threadId) ?: return
        val previousParticipants = thread.participantStationIds.orEmpty()
        val previousOrder = thread.participantOrder.orEmpty()
        val newParticipants = if (closure.stationId != null && closure.stationId !in previousParticipants) {
            previousParticipants + closure.stationId
        } else {
            previousParticipants
        }
        val newOrder = if (closure.stationId != null) previousOrder + closure.stationId else previousOrder

        db.execRaw(
            "UPDATE thread SET endedAt = ?, transmissionCount = ?, participantStationIds = ?, " +
                "participantOrder = ?, kind = ? WHERE id = ?",
            maxOf(thread.endedAt ?: Long.MIN_VALUE, closure.endedAtUtc),
            thread.transmissionCount + 1,
            Converters.fromStringList(newParticipants),
            Converters.fromStringList(newOrder),
            kind.name,
            threadId,
        )
        stampTransmission(threadId, closure.transmissionId, reason)
    }

    /**
     * Register R-1098: stamps both `transmission.threadId` (the pre-existing write) and
     * `transmission.threadJoinReason` (the real [ThreadGrouper.decide] verdict for this exact
     * transmission) in the same raw statement — one write, not two, so a caller can never observe
     * a row with a thread id but no reason (or the reverse) between them.
     */
    private suspend fun stampTransmission(threadId: String, transmissionId: String, reason: ThreadJoinReason) {
        db.execRaw(
            "UPDATE transmission SET threadId = ?, threadJoinReason = ? WHERE id = ?",
            threadId,
            reason.name,
            transmissionId,
        )
    }
}
