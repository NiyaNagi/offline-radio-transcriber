package org.ort.asrapi.rules

import org.ort.asrapi.AsrResult
import java.text.Normalizer

/**
 * Rule 5 (§8.2): rejects text matching a user-extensible blocklist of known-hallucination
 * phrases, normalised (case, diacritics, punctuation, collapsed whitespace) before matching so
 * "Thank you." and "thank you" are the same entry. Ships with the documented Whisper artifact
 * phrases (empty-audio hallucinations such as "Thanks for watching!" and its variants) per
 * §8.2; a caller may extend it (FR-ASR-5.5).
 */
public class BlocklistRule(phrases: Collection<String> = DEFAULT_PHRASES) : PostDecodeRejectionRule {
    override val id: RejectionRuleId = RejectionRuleId.BLOCKLIST

    private val normalizedPhrases: Set<String> = phrases.map(::normalize).toSet()

    override fun evaluate(result: AsrResult): RejectionVerdict {
        val normalized = normalize(result.text)
        return if (normalized.isNotBlank() && normalized in normalizedPhrases) {
            RejectionVerdict.Reject(id, "text matches known-hallucination phrase after normalisation")
        } else {
            RejectionVerdict.Accept
        }
    }

    public companion object {
        /** Documented Whisper hallucination artifacts (§8.2) — subtitle/outro phrases from its training data. */
        public val DEFAULT_PHRASES: Set<String> = setOf(
            "Thanks for watching!",
            "Thank you for watching!",
            "Please subscribe to my channel.",
            "Bye.",
            "you",
            ".",
        )

        public fun normalize(text: String): String {
            val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
            val stripped = decomposed.replace(Regex("\\p{M}"), "")
            return stripped.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim().replace(Regex("\\s+"), " ")
        }
    }
}
