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

        val decoded = decode(audio, opts)
        if (decoded is DecodeOutcome.Failed) return decoded.outcome
        val result = (decoded as DecodeOutcome.Success).result

        for (rule in postRules) {
            when (val verdict = rule.evaluate(result)) {
                is RejectionVerdict.Reject -> return PassBOutcome.Rejected(verdict.rule, verdict.detail, result)
                RejectionVerdict.Accept -> Unit
            }
        }

        return PassBOutcome.Accepted(result)
    }

    private sealed interface DecodeOutcome {
        data class Success(val result: AsrResult) : DecodeOutcome
        data class Failed(val outcome: PassBOutcome.Failed) : DecodeOutcome
    }

    /**
     * The engine call, timeout-guarded (F13). Extracted so [process] itself keeps a single return
     * point per branch (detekt's `ReturnCount`) while still using a distinct `catch` per exception
     * type rather than an `instanceof` check inside one generic `catch` (detekt's
     * `InstanceOfCheckForException`) — [AsrUnavailableException] (register R-350) is the one real,
     * controlled reason this pipeline ever passes through verbatim (its `message` is always exactly
     * "ASR unavailable: ${reason}", a fixed string this file's own author wrote, never raw exception
     * internals); every other exception's own message MUST NOT reach a failure reason a screen
     * renders — it can carry a stack-trace fragment or a filesystem path from wherever the real
     * failure happened (constitution V) — so only this one type is special-cased, and every other
     * exception stays the fixed, generic message it always was.
     */
    private suspend fun decode(audio: FloatArray, opts: DecodeOptions): DecodeOutcome = try {
        DecodeOutcome.Success(withTimeout(timeoutMs) { engine.transcribe(audio, opts) })
    } catch (e: TimeoutCancellationException) {
        DecodeOutcome.Failed(PassBOutcome.Failed("engine timed out after ${timeoutMs}ms", e))
    } catch (e: AsrUnavailableException) {
        DecodeOutcome.Failed(PassBOutcome.Failed(e.message ?: "ASR unavailable", e))
    } catch (t: Throwable) {
        DecodeOutcome.Failed(PassBOutcome.Failed("engine threw during transcribe", t))
    }

    public companion object {
        public const val DEFAULT_TIMEOUT_MS: Long = 15_000
    }
}
