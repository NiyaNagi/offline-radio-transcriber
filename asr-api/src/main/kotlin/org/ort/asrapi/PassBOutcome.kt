package org.ort.asrapi

import org.ort.asrapi.rules.RejectionRuleId

/**
 * What running Pass B over one segment produced (FR-ASR-6, FR-RUN-9). `Rejected` is a
 * **result**, not an accident: the rule that fired is recorded and the segment's audio is
 * untouched by this type — nothing here ever removes or truncates it (constitution III, P9;
 * AC-8's "audio retained" is a property of what this type does *not* do).
 */
public sealed interface PassBOutcome {
    public data class Accepted(val result: AsrResult) : PassBOutcome

    /** [partialResult] is the decode that failed a post-decode rule, or null if a pre-decode rule fired first. */
    public data class Rejected(val rule: RejectionRuleId, val detail: String, val partialResult: AsrResult?) :
        PassBOutcome

    /** The engine errored or timed out (FR-RUN-10a) — retryable, distinct from a correct rejection. */
    public data class Failed(val reason: String, val cause: Throwable?) : PassBOutcome
}
