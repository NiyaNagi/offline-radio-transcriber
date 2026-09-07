package org.ort.asrapi.rules

import org.ort.asrapi.AsrResult

/**
 * Rule 4 (§8.2): rejects text where a 4-gram repeats at least [minRepeats] times, or where
 * repeated 4-grams make up more than [maxRepeatedTokenFraction] of the tokens — the classic
 * Whisper hallucination shape ("go go go go go go...").
 */
public class RepetitionRule(
    private val nGram: Int = DEFAULT_NGRAM,
    private val minRepeats: Int = DEFAULT_MIN_REPEATS,
    private val maxRepeatedTokenFraction: Double = DEFAULT_MAX_REPEATED_FRACTION,
) : PostDecodeRejectionRule {
    override val id: RejectionRuleId = RejectionRuleId.REPETITION

    override fun evaluate(result: AsrResult): RejectionVerdict {
        val tokens = result.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size < nGram) return RejectionVerdict.Accept

        val grams = (0..tokens.size - nGram).map { i -> tokens.subList(i, i + nGram).joinToString(" ") }
        val counts = grams.groupingBy { it }.eachCount()
        val worst = counts.maxByOrNull { it.value } ?: return RejectionVerdict.Accept

        if (worst.value >= minRepeats) {
            return RejectionVerdict.Reject(
                id,
                "4-gram \"${worst.key}\" repeats ${worst.value} times (>= $minRepeats)",
            )
        }

        val repeatedTokenCount = counts.entries.filter { it.value > 1 }.sumOf { it.value * nGram }
        val fraction = repeatedTokenCount.toDouble() / tokens.size.toDouble()
        return if (fraction > maxRepeatedTokenFraction) {
            RejectionVerdict.Reject(
                id,
                "repeated 4-grams cover %.0f%% of tokens (> %.0f%%)".format(
                    fraction * 100,
                    maxRepeatedTokenFraction * 100,
                ),
            )
        } else {
            RejectionVerdict.Accept
        }
    }

    public companion object {
        public const val DEFAULT_NGRAM: Int = 4
        public const val DEFAULT_MIN_REPEATS: Int = 3
        public const val DEFAULT_MAX_REPEATED_FRACTION: Double = 0.45
    }
}
