package org.ort.pipeline.threading

import org.ort.data.entity.ThreadJoinReason

/**
 * FR-SPK-5's "configurable gap threshold". [gapThresholdMillis] has no measured default yet —
 * `spec/open-questions.md`'s Q16 labelling-protocol question ("does a 20-minute gap on the same
 * repeater continue it?") is still open — so ten minutes is a deliberately conservative,
 * documented placeholder, not a spec-mandated number, kept as a constructor parameter precisely
 * so a later session can retune it without touching [ThreadGrouper] itself.
 */
public data class ThreadGroupingConfig(val gapThresholdMillis: Long = DEFAULT_GAP_THRESHOLD_MILLIS) {
    public companion object {
        public const val DEFAULT_GAP_THRESHOLD_MILLIS: Long = 10 * 60 * 1000L
    }
}

/**
 * FR-SPK-5: pure and total — every call is a function of its three arguments alone (this unit's
 * own determinism rule: "grouping must be deterministic, and pure with respect to its inputs").
 * Frequency continuity plus inter-transmission gap is the whole structural rule the spec states;
 * FR-SPK-27/28's net detection and QSO/scanner classification live in [ThreadKindClassifier]
 * instead, applied only *after* this class has already decided to join or start a thread —
 * FR-SPK-28: kind is advisory decoration, never a second vote on whether to join
 * ("never structural").
 *
 * A thread never spans two capture-android segmenter boundaries this class did not itself decide
 * — it only groups transmissions the segmenter already closed (constitution III).
 */
public class ThreadGrouper(private val config: ThreadGroupingConfig = ThreadGroupingConfig()) {

    public fun decide(
        current: TransmissionClosure,
        prior: PriorThreadContext?,
        /**
         * FR-RUN-12 / constitution IV, at the threading layer: a capture gap nobody listened
         * through breaks continuity outright, whatever the wall-clock arithmetic below would
         * otherwise have allowed. The caller computes this (it can see `capture_gap` rows this
         * class has no access to and must not guess at from timestamps alone) — see
         * [RoomThreadRepository.unlistenedCaptureGapBetween].
         */
        unlistenedCaptureGapInBetween: Boolean,
    ): ThreadGroupingDecision {
        if (prior == null) {
            return ThreadGroupingDecision.StartNewThread(ThreadJoinReason.FIRST_TRANSMISSION_IN_SESSION)
        }
        if (unlistenedCaptureGapInBetween) {
            return ThreadGroupingDecision.StartNewThread(ThreadJoinReason.NEW_THREAD_UNLISTENED_CAPTURE_GAP)
        }

        val gapMillis = current.startedAtUtc - prior.lastEndedAtUtc
        if (gapMillis > config.gapThresholdMillis) {
            return ThreadGroupingDecision.StartNewThread(ThreadJoinReason.NEW_THREAD_GAP_EXCEEDED)
        }

        val sameFrequency = current.frequencyHz != null && current.frequencyHz == prior.frequencyHz
        val sameRigChannel = current.channelName != null && current.channelName == prior.channelName
        val bothFrequenciesUnknown = current.frequencyHz == null && prior.frequencyHz == null

        return when {
            sameFrequency ->
                ThreadGroupingDecision.JoinExistingThread(prior.threadId, ThreadJoinReason.SAME_FREQUENCY_WITHIN_GAP)
            sameRigChannel ->
                ThreadGroupingDecision.JoinExistingThread(prior.threadId, ThreadJoinReason.SAME_RIG_CHANNEL_WITHIN_GAP)
            bothFrequenciesUnknown ->
                ThreadGroupingDecision.JoinExistingThread(prior.threadId, ThreadJoinReason.NO_FREQUENCY_INFO_WITHIN_GAP)
            else -> ThreadGroupingDecision.StartNewThread(ThreadJoinReason.NEW_THREAD_FREQUENCY_CHANGED)
        }
    }
}
