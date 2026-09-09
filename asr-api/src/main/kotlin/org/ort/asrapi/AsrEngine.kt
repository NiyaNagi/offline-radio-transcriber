package org.ort.asrapi

import org.ort.core.AssetRef

/** Pass B — offline transcription of a complete segment (technical design §8.1, FR-ASR-1). */
public interface AsrEngine {
    public suspend fun transcribe(audio: FloatArray, opts: DecodeOptions): AsrResult
}

/**
 * register R-350: the typed failure an [AsrEngine] implementation throws when there is genuinely
 * no engine available to run at all — `org.ort.pipeline.passb.UnavailableAsrEngine` (`:pipeline`)
 * is today's one real thrower, wired in exactly when `RealAsrEngineProvider` reports
 * `AsrEngineAvailability.Unavailable`. [RejectionPipeline.process] catches this type explicitly,
 * *before* its own generic `catch (t: Throwable)`, so [reason] reaches
 * [PassBOutcome.Failed]/`ReprocessStatus.Summary.failureReasons`/`WorkQueueItemEntity.lastError`
 * verbatim — before this existed, that catch-all replaced the real "no ASR model installed at …"
 * text with the generic "engine threw during transcribe", and R04's "Install" action (which greps
 * `failureReasons` for exactly this fact) could never fire for a genuinely missing model.
 * Deliberately a distinct, closed type rather than pattern-matching on
 * [IllegalStateException]/[Throwable.message]: any other exception a real engine throws — a
 * decode failure, an OOM, a native crash — must still fall through to the generic, path/stack-
 * trace-free bucket, never be mistaken for "no model installed."
 */
public class AsrUnavailableException(public val reason: String) : Exception("ASR unavailable: $reason")

/** Decode-time parameters (FR-ASR-4's hotword biasing, language, beam width, …). */
public data class DecodeOptions(
    val language: String? = null,
    val hotwords: HotwordSet = HotwordSet.EMPTY,
    val beamSize: Int = 4,
    val nBest: Int = 1,
)

/** The decode-time hotword bias set from the active lexicon slice (FR-ASR-4). Opaque here. */
public data class HotwordSet(val phrases: List<String>) {
    public companion object {
        public val EMPTY: HotwordSet = HotwordSet(emptyList())
    }
}

/** Pass A — streaming transducer, live partials (technical design §8.1, FR-ASR-2, M8). */
public interface StreamingAsrEngine {
    public fun stream(hotwords: HotwordSet): StreamSession
}

/** One open streaming decode. Partial hypotheses are never persisted (§8.3); only the final is. */
public interface StreamSession : AutoCloseable {
    public fun acceptFrame(frame: FloatArray)
    public val partials: kotlinx.coroutines.flow.StateFlow<AsrResult?>

    /** Finalises the session on segment close and returns the last hypothesis (§8.3). */
    public fun finalize(): AsrResult?
}

/** GTCRN-style denoiser ahead of ASR (technical design §8.1, M11, optional). */
public fun interface Enhancer {
    public fun enhance(audio: FloatArray): FloatArray
}

/** One decode hypothesis retained in the n-best list (technical design §8.1). */
public data class Hypothesis(val text: String, val logProb: Float, val tokens: List<TokenScore>)

/** Per-token score, kept for Pass D's per-token agreement signal (§8.3, FR-ASR-14). */
public data class TokenScore(val token: String, val logProb: Float, val startMs: Int, val endMs: Int)

/**
 * Pass B's raw output, before any [org.ort.asrapi.rules.RejectionRule] runs. `noSpeechProb` and
 * the full n-best list are retained from M3 onward even though nothing else consumes them until
 * M11 (§8.1) — cheap to keep, expensive to retrofit.
 */
public data class AsrResult(
    val text: String,
    val nBest: List<Hypothesis>,
    val noSpeechProb: Float?,
    val avgLogProb: Float?,
    val tokens: List<TokenScore>,
    val modelRef: AssetRef,
)
