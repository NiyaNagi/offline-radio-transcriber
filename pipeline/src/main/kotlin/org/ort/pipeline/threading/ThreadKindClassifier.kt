package org.ort.pipeline.threading

import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource

/**
 * FR-SPK-27/28: advisory only — this class never influences whether a transmission joins a
 * thread (see [ThreadGrouper]'s own doc comment); it only labels a thread [ThreadGrouper] has
 * already decided to start or extend. FR-SPK-27's "one dominant voiceprint alternating with many
 * others on a stable frequency over a sustained period" is approximated here as: at least
 * [MIN_TRANSMISSIONS_FOR_NET] transmissions, at least [MIN_OTHER_VOICES_FOR_NET] distinct *other*
 * voices besides the dominant one, and the dominant voice present in at least
 * [DOMINANT_SHARE_FOR_NET] of them. All three thresholds are provisional — FR-SPK-27 names no
 * numbers — and are kept as named constants for exactly that reason: easy to find, easy to retune
 * once a real net is measured against this.
 */
public object ThreadKindClassifier {

    internal const val MIN_TRANSMISSIONS_FOR_NET: Int = 5
    internal const val MIN_OTHER_VOICES_FOR_NET: Int = 3
    internal const val DOMINANT_SHARE_FOR_NET: Double = 0.4

    /**
     * FR-SPK-28: a user-set or user-cleared marking ([ThreadKindSource.USER]) is never overwritten
     * here — automatic (re-)classification only ever runs while the marking is still
     * [ThreadKindSource.DETECTED], so "the user can set or clear the marking" (FR-SPK-28) actually
     * holds once a caller ever writes [ThreadKindSource.USER] (no such caller exists yet — that is
     * `:app` work this unit does not own).
     *
     * [voiceKeyCountsBeforeCurrent] and [transmissionCountBeforeCurrent] describe the thread as it
     * stood immediately before [current] joined; this function itself folds [current] in before
     * classifying, so a caller never has to pre-merge it. A thread already classified
     * [ThreadKind.NET] stays `NET` regardless of what the next voice looks like — a net is a fact
     * about the conversation shape already observed, not a rolling guess that can be talked back
     * out of once the evidence briefly looks thinner; only [ThreadKindSource.USER] ever reverses it.
     */
    public fun classify(
        previousKind: ThreadKind,
        previousKindSource: ThreadKindSource,
        voiceKeyCountsBeforeCurrent: Map<String, Int>,
        current: TransmissionClosure,
        transmissionCountBeforeCurrent: Int,
    ): ThreadKind {
        if (previousKindSource == ThreadKindSource.USER) return previousKind
        // A net, once genuinely detected, is a historical fact about the conversation shape
        // already observed -- later evidence that looks less net-like must not retroactively
        // downgrade it (only a user clearing the marking, handled above, ever does).
        if (previousKind == ThreadKind.NET) return ThreadKind.NET

        val updatedCounts = current.voiceKey?.let { key ->
            voiceKeyCountsBeforeCurrent + (key to (voiceKeyCountsBeforeCurrent[key] ?: 0) + 1)
        } ?: voiceKeyCountsBeforeCurrent
        val totalTransmissions = transmissionCountBeforeCurrent + 1

        if (isNet(updatedCounts, totalTransmissions)) return ThreadKind.NET

        return when (updatedCounts.size) {
            0, 1 -> ThreadKind.UNKNOWN
            2 -> ThreadKind.QSO
            else -> ThreadKind.SCANNER
        }
    }

    private fun isNet(voiceKeyCounts: Map<String, Int>, totalTransmissions: Int): Boolean {
        if (totalTransmissions < MIN_TRANSMISSIONS_FOR_NET) return false
        if (voiceKeyCounts.size < MIN_OTHER_VOICES_FOR_NET + 1) return false
        val dominant = voiceKeyCounts.values.maxOrNull() ?: return false
        return dominant.toDouble() / totalTransmissions >= DOMINANT_SHARE_FOR_NET
    }
}
