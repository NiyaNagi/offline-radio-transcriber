package org.ort.asrapi.rules

import org.ort.asrapi.AsrResult
import org.ort.asrapi.SegmentCandidate

/**
 * The six hallucination controls of technical design §8.2 (FR-ASR-5 -> AC-7), named so a
 * rejection can be recorded and explained (FR-ASR-6 -> AC-8, constitution I). Order is fixed:
 * cheapest first, so a segment that fails an early check never reaches the model at all.
 */
public enum class RejectionRuleId {
    TOO_SHORT,
    VAD_NO_SPEECH,
    NO_SPEECH_PROB,
    REPETITION,
    BLOCKLIST,
    COMPRESSION_RATIO,
    ;

    /** Declaration order doubles as evaluation order (§8.2's table). */
    public companion object {
        public val EVALUATION_ORDER: List<RejectionRuleId> = entries.toList()
    }
}

/** The outcome of one [RejectionRule] check. */
public sealed interface RejectionVerdict {
    public data object Accept : RejectionVerdict
    public data class Reject(val rule: RejectionRuleId, val detail: String) : RejectionVerdict
}

/**
 * Rules 1-2 run before any model call — no [AsrResult] exists yet, so evaluating one never
 * invokes Pass B (§8.2: "no model runs"). This is what makes `too_short` and `vad_no_speech`
 * genuinely the cheapest rejects rather than merely first in a list that still decodes everything.
 */
public interface PreDecodeRejectionRule {
    public val id: RejectionRuleId
    public fun evaluate(candidate: SegmentCandidate): RejectionVerdict
}

/** Rules 3-6 run against the decoded [AsrResult]. */
public interface PostDecodeRejectionRule {
    public val id: RejectionRuleId
    public fun evaluate(result: AsrResult): RejectionVerdict
}
