package org.ort.asrapi.rules

import org.ort.asrapi.SegmentCandidate

/**
 * Rule 1 (§8.2): a segment shorter than [minDurationMs] is rejected without any model running.
 * Default 250 ms, matching `ResolvedConfig.segmentMinSpeechMs`'s default — the segmenter (P4)
 * already enforces this floor structurally; this rule exists so the control is independently
 * demonstrable here too (AC-7), and so a caller composing the full six-rule pipeline in one
 * place doesn't have to trust that upstream duration enforcement never regresses silently.
 */
public class TooShortRule(private val minDurationMs: Int = DEFAULT_MIN_DURATION_MS) : PreDecodeRejectionRule {
    override val id: RejectionRuleId = RejectionRuleId.TOO_SHORT

    override fun evaluate(candidate: SegmentCandidate): RejectionVerdict = if (candidate.durationMs < minDurationMs) {
        RejectionVerdict.Reject(id, "segment is ${candidate.durationMs} ms, below the $minDurationMs ms floor")
    } else {
        RejectionVerdict.Accept
    }

    public companion object {
        public const val DEFAULT_MIN_DURATION_MS: Int = 250
    }
}
