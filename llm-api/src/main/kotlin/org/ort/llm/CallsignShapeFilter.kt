package org.ort.llm

/**
 * FR-DIG-4's fallback where the runtime has no grammar-constrained decoding — MediaPipe's
 * `LlmInference` has none, so this post-filter is the guard rather than an optimisation. Given
 * generated text and the resolved callsign set for the thread it was drafted from, it returns the
 * text unchanged if every callsign-*shaped* token in it is a member of that set, else a
 * [LlmResult.Refused] naming the offending token.
 *
 * `:llm-api` may depend only on `:core` (`ModuleGraph.allowed`) — `:lexicon`'s real callsign
 * grammar (`CallsignGrammar`) is unreachable from here, so this implements its own conservative
 * ITU-shaped regex rather than importing one. It deliberately mirrors, rather than reuses, the
 * shape `:lexicon`'s `LexiconImportValidator.CALLSIGN_SHAPE` already validates against
 * (`^[A-Z0-9]{1,3}[0-9][A-Z]{1,4}$`) — one to three letters/digits, a digit, one to four letters —
 * loosened only to be case-insensitive, since generated prose is not upper-cased the way a
 * lexicon record is.
 *
 * The shape is intentionally permissive: it will occasionally flag a token that was never meant
 * as a callsign (a band abbreviation like `20m`, an odd alphanumeric). That is the correct
 * direction to be wrong in (constitution I — "precision outranks recall... the system sacrifices
 * recall... and never asserts"): a hallucinated callsign reaching the reader is the failure this
 * exists to prevent; an occasional false-positive refusal only costs a prose summary for that
 * thread, and the deterministic digest is unaffected either way (FR-DIG-3a).
 */
public object CallsignShapeFilter {

    /** Conservative ITU call-sign shape, case-insensitive — see the class doc for why it is not shared code. */
    private val CALLSIGN_SHAPE = Regex("^[A-Za-z0-9]{1,3}[0-9][A-Za-z]{1,4}$")

    /** Tokens are whatever sits between runs of non-alphanumeric characters — punctuation never hides a token. */
    private val TOKEN_SPLIT = Regex("[^A-Za-z0-9]+")

    /**
     * @return the original [text] wrapped in [LlmResult.Text] if every callsign-shaped token it
     * contains is present (case-insensitively) in [allowedCallsigns], else [LlmResult.Refused]
     * naming the first offending token. Never returns [LlmResult.Failed] — a filter rejection is
     * not an engine failure.
     */
    public fun filter(text: String, allowedCallsigns: Set<String>): LlmResult {
        val allowedUpper = allowedCallsigns.map { it.uppercase() }.toSet()
        val offending = TOKEN_SPLIT.split(text)
            .filter { it.isNotBlank() }
            .firstOrNull { token -> CALLSIGN_SHAPE.matches(token) && token.uppercase() !in allowedUpper }
        return if (offending == null) {
            LlmResult.Text(text)
        } else {
            LlmResult.Refused(
                "generated text names \"$offending\", which is not among the resolved callsigns for this thread",
            )
        }
    }
}
