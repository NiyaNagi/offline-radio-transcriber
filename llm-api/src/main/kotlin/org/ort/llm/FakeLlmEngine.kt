package org.ort.llm

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The behavioural fake every [LlmEngine] consumer's test runs against (constitution II — "every
 * model-bearing interface ships with a behavioural fake... a fake that cannot be told to fail,
 * hang or return a hallucination is a stub"). Scriptable to:
 *
 * - **hang** — [LoadBehavior.HANG] / [GenerateBehavior.HANG] suspend forever via
 *   [awaitCancellation]; the only way out is cancelling the caller's coroutine, exactly as a
 *   real engine wedged on a native call would behave.
 * - **fail to load** — [LoadBehavior.FAIL] returns [LlmLoadResult.Failed] and publishes
 *   [LlmState.Failed].
 * - **exceed its token budget** — [GenerateBehavior.EXCEED_TOKEN_BUDGET] returns text longer than
 *   [LlmRequest.maxTokens] asked for, so a caller that trusts the runtime to honour its own limit
 *   is the thing under test, not this fake.
 * - **deliberately emit a callsign not in the allowed set** — [GenerateBehavior.INVENT_CALLSIGN]
 *   names [inventedCallsign] regardless of [LlmRequest.allowedCallsigns], so
 *   [CallsignShapeFilter]'s rejection test (`FR_DIG_4_filter_rejects_invented_callsign`) has a
 *   real hallucination to catch rather than a scripted success.
 */
public class FakeLlmEngine : LlmEngine {

    public enum class LoadBehavior { SUCCEED, FAIL, HANG }
    public enum class GenerateBehavior { SUCCEED, HANG, EXCEED_TOKEN_BUDGET, INVENT_CALLSIGN, REFUSE, FAIL }

    public var loadBehavior: LoadBehavior = LoadBehavior.SUCCEED
    public var loadFailureReason: String = "fake load failure"
    public var residentBytesOnReady: Long = DEFAULT_RESIDENT_BYTES

    public var generateBehavior: GenerateBehavior = GenerateBehavior.SUCCEED
    public var scriptedText: String = "Nothing notable this session."
    public var inventedCallsign: String = "K9ZZZ"
    public var refusalReason: String = "fake scripted refusal"
    public var generateFailureReason: String = "fake generate failure"

    private val _state = MutableStateFlow<LlmState>(LlmState.Unloaded)
    override val state: StateFlow<LlmState> = _state.asStateFlow()

    public var loadCallCount: Int = 0
        private set
    public var generateCallCount: Int = 0
        private set
    public var releaseCallCount: Int = 0
        private set
    public var lastRequest: LlmRequest? = null
        private set

    override suspend fun load(): LlmLoadResult {
        loadCallCount++
        _state.value = LlmState.Loading
        return when (loadBehavior) {
            LoadBehavior.HANG -> awaitCancellation()
            LoadBehavior.FAIL -> {
                _state.value = LlmState.Failed(loadFailureReason)
                LlmLoadResult.Failed(loadFailureReason)
            }
            LoadBehavior.SUCCEED -> {
                _state.value = LlmState.Ready(residentBytesOnReady)
                LlmLoadResult.Loaded
            }
        }
    }

    override suspend fun generate(request: LlmRequest): LlmResult {
        generateCallCount++
        lastRequest = request
        return when (generateBehavior) {
            GenerateBehavior.HANG -> awaitCancellation()
            GenerateBehavior.EXCEED_TOKEN_BUDGET -> {
                // One "word" per requested token, plus a healthy overshoot — a real runtime that
                // honoured maxTokens could never produce this; a fake that never violates its own
                // contract would test nothing about a caller that blindly trusts the budget.
                val wordCount = request.maxTokens + EXCESS_TOKENS
                LlmResult.Text((1..wordCount).joinToString(" ") { "word$it" })
            }
            GenerateBehavior.INVENT_CALLSIGN ->
                LlmResult.Text("Worked $inventedCallsign on the local repeater, said 73.")
            GenerateBehavior.REFUSE -> LlmResult.Refused(refusalReason)
            GenerateBehavior.FAIL -> LlmResult.Failed(generateFailureReason)
            GenerateBehavior.SUCCEED -> LlmResult.Text(scriptedText)
        }
    }

    override fun release() {
        releaseCallCount++
        _state.value = LlmState.Unloaded
    }

    public companion object {
        public const val DEFAULT_RESIDENT_BYTES: Long = 128L * 1024 * 1024
        private const val EXCESS_TOKENS: Int = 20
    }
}
