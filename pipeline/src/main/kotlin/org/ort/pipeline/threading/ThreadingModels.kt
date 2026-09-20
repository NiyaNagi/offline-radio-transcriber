package org.ort.pipeline.threading

import org.ort.data.entity.ThreadJoinReason
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource

/**
 * FR-SPK-5: everything one transmission's Pass B closure hands to [ThreadGrouper] — deliberately
 * exactly what threading needs and nothing more. No audio and no transcript text on purpose:
 * grouping runs *after* Pass B closes a transmission and never re-opens what the segmenter or
 * Pass B already decided (constitution III — thread grouping is not segmentation, and it is not a
 * second ASR pass either).
 */
public data class TransmissionClosure(
    val transmissionId: String,
    val sessionId: String,
    val samplePosition: Long,
    val startedAtUtc: Long,
    val endedAtUtc: Long,
    val frequencyHz: Long?,
    val channelName: String?,
    val stationId: String?,
    val voiceprintId: String?,
) {
    /**
     * FR-SPK-27's "one dominant voiceprint" tracking key — the voiceprint when one exists (a
     * voice can be tracked before any callsign ever resolves), the resolved station otherwise,
     * `null` when neither is known yet (an over this early cannot be counted as anybody in
     * particular, so it must not silently become "a new distinct participant").
     */
    public val voiceKey: String? get() = voiceprintId ?: stationId
}

/**
 * Why [ThreadGrouper] joined or started a thread (constitution I: "every machine conclusion MUST
 * be inspectable"). Register R-1098: this used to be kept as data only long enough for
 * [ThreadGroupingCoordinator] to read it and then discard it — `:data` had no column for it and
 * nothing ever logged it, so a machine conclusion the operator sees on every Log screen had no
 * durable trace of *why*. [ThreadGroupingCoordinator] now passes [ThreadGroupingDecision.reason]
 * to [ThreadRepository.startThread]/`.appendToThread`, which stamp it onto the transmission's own
 * row ([org.ort.data.entity.TransmissionEntity.threadJoinReason]) — see that column's own doc
 * comment for where it lives and why. [ThreadJoinReason] itself is defined in `:data`, not here —
 * see its own doc comment for why the module graph forces that direction.
 */
public sealed interface ThreadGroupingDecision {
    public val reason: ThreadJoinReason

    public data class JoinExistingThread(val threadId: String, override val reason: ThreadJoinReason) :
        ThreadGroupingDecision

    public data class StartNewThread(override val reason: ThreadJoinReason) : ThreadGroupingDecision
}

/**
 * What [ThreadGrouper] needs to know about the thread the most recent **matching** (same
 * frequency, or the same rig channel) prior transmission in this session joined — read fresh for
 * every decision, never cached across calls, so re-running against the same closed transmissions
 * always produces the same result (this unit's own determinism rule).
 */
public data class PriorThreadContext(
    val threadId: String,
    val frequencyHz: Long?,
    val channelName: String?,
    val lastEndedAtUtc: Long,
    val kind: ThreadKind,
    val kindSource: ThreadKindSource,
    /**
     * One entry per distinct [TransmissionClosure.voiceKey] already seen in the thread —
     * FR-SPK-27's own "one dominant voiceprint alternating with many others" needs a count per
     * voice, not just how many transmissions the thread has.
     */
    val voiceKeyCounts: Map<String, Int>,
    val transmissionCount: Int,
)
