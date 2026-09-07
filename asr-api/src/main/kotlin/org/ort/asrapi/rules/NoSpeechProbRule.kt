package org.ort.asrapi.rules

import org.ort.asrapi.AsrResult

/**
 * Rule 3 (§8.2): rejects a decode whose `no_speech_prob` exceeds [ceiling]. Whisper's own
 * default is 0.60; the design requires this be **refitted against the development noise tape**
 * (§8.2, "Thresholds are fitted, not inherited") rather than adopted from upstream. As of this
 * change no development noise tape exists in this repository (Q2/Q16 — see spec/open-questions.md
 * and CHANGELOG.md), so [DEFAULT_CEILING] remains Whisper's convention, unrefitted, and this is
 * recorded rather than silently presented as measured (constitution VI).
 */
public class NoSpeechProbRule(private val ceiling: Double = DEFAULT_CEILING) : PostDecodeRejectionRule {
    override val id: RejectionRuleId = RejectionRuleId.NO_SPEECH_PROB

    override fun evaluate(result: AsrResult): RejectionVerdict {
        val p = result.noSpeechProb ?: return RejectionVerdict.Accept
        return if (p > ceiling) {
            RejectionVerdict.Reject(id, "no_speech_prob=$p exceeds ceiling=$ceiling")
        } else {
            RejectionVerdict.Accept
        }
    }

    public companion object {
        /** Whisper's conventional default (technical design §8.2) — see class doc: unrefitted. */
        public const val DEFAULT_CEILING: Double = 0.60
    }
}
