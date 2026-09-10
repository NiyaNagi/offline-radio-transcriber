package org.ort.llm

import kotlinx.coroutines.flow.StateFlow

/**
 * The on-device LLM contract (FR-DIG-3, FR-DIG-3b, FR-ASR-15/16, D36). Post-hoc only: an
 * [LlmEngine] rescores ASR n-best output or drafts digest prose after Pass D/E have already
 * resolved callsigns from the audio — it never gets to assert an identity or an over's content
 * (D5, constitution I). Every implementation must map every exception to [LlmState.Failed] /
 * [LlmResult.Failed]; a caller must never see this contract throw.
 */
public interface LlmEngine {

    /** Published residency state — read by settings (FR-DIG-3b) and the gate (FR-DIG-5, AC-138). */
    public val state: StateFlow<LlmState>

    /**
     * Loads the model. Idempotent in spirit — a caller that finds [state] already [LlmState.Ready]
     * has no reason to call this again, but an implementation must not corrupt its own state if it
     * is called anyway. Never throws; a failure is reported as [LlmLoadResult.Failed] and mirrored
     * into [state] as [LlmState.Failed].
     */
    public suspend fun load(): LlmLoadResult

    /**
     * Generates against an already-loaded model. [LlmRequest.allowedCallsigns] is the resolved set
     * the caller supplies (FR-DIG-4) — engines with grammar-constrained decoding may enforce it
     * directly; a runtime that cannot (MediaPipe does not) relies on [CallsignShapeFilter] applied
     * to the returned text.
     */
    public suspend fun generate(request: LlmRequest): LlmResult

    /**
     * Frees resident memory and moves [state] to [LlmState.Unloaded] (FR-DIG-3b). Never throws —
     * a release happens precisely when something has already gone wrong or the caller no longer
     * wants the cost, so it must always succeed from the caller's point of view.
     */
    public fun release()
}

/** [LlmEngine.state] — a closed set, always non-optional (constitution I's discipline applied to model residency). */
public sealed interface LlmState {
    public data object Unloaded : LlmState
    public data object Loading : LlmState

    /** [residentBytesEstimate] backs AC-138's measured-resident-memory check. */
    public data class Ready(public val residentBytesEstimate: Long) : LlmState
    public data class Failed(public val reason: String) : LlmState
}

/** The outcome of [LlmEngine.load]. */
public sealed interface LlmLoadResult {
    public data object Loaded : LlmLoadResult
    public data class Failed(public val reason: String) : LlmLoadResult
}

/**
 * One generation request. [allowedCallsigns] is the *already-resolved* set for the thread this
 * prompt covers (FR-DIG-4, FR-DIG-12) — never a hint for the model to go looking for more.
 */
public data class LlmRequest(
    public val prompt: String,
    public val maxTokens: Int,
    public val allowedCallsigns: Set<String>,
)

/** The outcome of [LlmEngine.generate]. */
public sealed interface LlmResult {
    public data class Text(public val text: String) : LlmResult
    public data class Refused(public val reason: String) : LlmResult
    public data class Failed(public val reason: String) : LlmResult
}
