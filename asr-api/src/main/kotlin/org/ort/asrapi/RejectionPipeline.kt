package org.ort.asrapi

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.ort.asrapi.rules.BlocklistRule
import org.ort.asrapi.rules.CompressionRatioRule
import org.ort.asrapi.rules.NoSpeechProbRule
import org.ort.asrapi.rules.PostDecodeRejectionRule
import org.ort.asrapi.rules.PreDecodeRejectionRule
import org.ort.asrapi.rules.RejectionVerdict
import org.ort.asrapi.rules.RepetitionRule
import org.ort.asrapi.rules.TooShortRule
import org.ort.asrapi.rules.VadNoSpeechRule

/**
 * Runs the six hallucination controls in the cheapest-first order technical design §8.2 fixes,
 * short-circuiting on the first rejection so a pre-decode reject genuinely never invokes
 * [engine] (FR-RUN-9). The engine call itself is timeout-guarded: a hang becomes
 * [PassBOutcome.Failed] rather than blocking the pipeline forever (F13's engine-timeout
 * requirement).
 */
public class RejectionPipeline(
    private val engine: AsrEngine,
    private val preRules: List<PreDecodeRejectionRule> = listOf(TooShortRule(), VadNoSpeechRule()),
    private val postRules: List<PostDecodeRejectionRule> = listOf(
        NoSpeechProbRule(),
        RepetitionRule(),
        BlocklistRule(),
        CompressionRatioRule(),
    ),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    init {
        val ids = (preRules.map { it.id } + postRules.map { it.id }).toSet()
        require(ids.size == 6) { "the rejection pipeline must carry exactly the six named controls, got: $ids" }
    }

    public suspend fun process(candidate: SegmentCandidate, audio: FloatArray, opts: DecodeOptions): PassBOutcome {
        for (rule in preRules) {
            when (val verdict = rule.evaluate(candidate)) {
                is RejectionVerdict.Reject -> return PassBOutcome.Rejected(verdict.rule, verdict.detail, null)
                RejectionVerdict.Accept -> Unit
            }
        }

        val result = try {
            withTimeout(timeoutMs) { engine.transcribe(audio, opts) }
        } catch (e: TimeoutCancellationException) {
            return PassBOutcome.Failed("engine timed out after ${timeoutMs}ms", e)
        } catch (t: Throwable) {
            return PassBOutcome.Failed("engine threw during transcribe", t)
        }

        for (rule in postRules) {
            when (val verdict = rule.evaluate(result)) {
                is RejectionVerdict.Reject -> return PassBOutcome.Rejected(verdict.rule, verdict.detail, result)
                RejectionVerdict.Accept -> Unit
            }
        }

        return PassBOutcome.Accepted(result)
    }

    public companion object {
        public const val DEFAULT_TIMEOUT_MS: Long = 15_000
    }
}
