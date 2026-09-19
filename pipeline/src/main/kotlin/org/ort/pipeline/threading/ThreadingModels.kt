package org.ort.pipeline.threading

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
 * Why [ThreadGrouper] joined or started a thread — kept as data rather than thrown away once the
 * decision is made (constitution I: "every machine conclusion MUST be inspectable"). The data
 * model this unit may touch has no per-thread "reason" column (`:data`'s schema is out of scope —
 * no migration), so this is where that explainability lives today; a future session wiring it
 * into a durable record or a log is a genuine follow-up, not a defect in this one.
 */
public enum class ThreadJoinReason {
    FIRST_TRANSMISSION_IN_SESSION,
    SAME_FREQUENCY_WITHIN_GAP,
    SAME_RIG_CHANNEL_WITHIN_GAP,
    NO_FREQUENCY_INFO_WITHIN_GAP,
    NEW_THREAD_FREQUENCY_CHANGED,
    NEW_THREAD_GAP_EXCEEDED,
    NEW_THREAD_UNLISTENED_CAPTURE_GAP,
}

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
