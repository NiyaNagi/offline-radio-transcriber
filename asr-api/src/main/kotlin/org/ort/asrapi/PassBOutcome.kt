package org.ort.asrapi

import org.ort.asrapi.rules.RejectionRuleId

/**
 * What running Pass B over one segment produced (FR-ASR-6, FR-RUN-9). `Rejected` is a
 * **result**, not an accident: the rule that fired is recorded and the segment's audio is
 * untouched by this type — nothing here ever removes or truncates it (constitution III, P9;
 * AC-8's "audio retained" is a property of what this type does *not* do).
 */
public sealed interface PassBOutcome {

    /**
     * [inertControls] (register R-1121) names every hallucination control that reached
     * [org.ort.asrapi.rules.RejectionVerdict.Indeterminate] on this pass — a control that could
     * not evaluate at all, distinct from one that ran and cleared the segment. Empty when every
     * control that ran could actually check something. This is what makes a permanently inert
     * control (today: [org.ort.asrapi.rules.RejectionRuleId.NO_SPEECH_PROB], because the wired
     * decoder's binding exposes no confidence signal) visible on every single pass's own result,
     * rather than a fact only discoverable by reading the decoder's source.
     */
    public data class Accepted(val result: AsrResult, val inertControls: Set<RejectionRuleId> = emptySet()) :
        PassBOutcome

    /** [partialResult] is the decode that failed a post-decode rule, or null if a pre-decode rule fired first. */
    public data class Rejected(
        val rule: RejectionRuleId,
        val detail: String,
        val partialResult: AsrResult?,
        val inertControls: Set<RejectionRuleId> = emptySet(),
    ) : PassBOutcome

    /** The engine errored or timed out (FR-RUN-10a) — retryable, distinct from a correct rejection. */
    public data class Failed(val reason: String, val cause: Throwable?) : PassBOutcome
}
