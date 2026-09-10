package org.ort.llm.mediapipe

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ort.llm.LlmEngine
import org.ort.llm.LlmLoadResult
import org.ort.llm.LlmRequest
import org.ort.llm.LlmResult
import org.ort.llm.LlmState
import java.io.File

/**
 * The MediaPipe LLM Inference (`com.google.mediapipe:tasks-genai`) implementation of [LlmEngine]
 * (D36). Loads **lazily** — never at construction — because construction happens whenever the
 * settings screen or the digest gate is wired up, long before [ProseDigestGate][
 * org.ort.pipeline.digest.ProseDigestGate] ever decides generation may run (FR-AST-3a: a T0/T1/T2
 * device must never load this at all).
 *
 * Every exception is mapped to [LlmState.Failed] / [LlmResult.Failed] — this contract must never
 * throw into a caller (constitution I: a crash here must read as "no summary today, and here is
 * why", never as a silent absence). [release] is doubly defensive: even `LlmInference.close()`
 * throwing is swallowed, because release is called precisely when something has already gone
 * wrong or the caller no longer wants the cost.
 *
 * Resident bytes are **estimated from the model file's size on disk** — the only cheap, honest
 * number available without querying the native runtime's own allocator, which `tasks-genai`
 * does not expose. This is stated as an estimate, never asserted as measured (constitution VI);
 * AC-138's real measured-resident-memory check is hardware row H11.
 *
 * The real on-device load (a real `.task` file, a real generation) is hardware row H11
 * (`results/e2e-audit/hardware-checklist.md`) — nothing in this module's Robolectric tests
 * exercises the real MediaPipe native runtime, only the paths this class takes before ever
 * reaching it (a missing model file, generation before a successful load).
 */
public class MediaPipeLlmEngine(
    private val context: Context,
    private val modelPath: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : LlmEngine {

    private val _state = MutableStateFlow<LlmState>(LlmState.Unloaded)
    override val state: StateFlow<LlmState> = _state.asStateFlow()

    private var inference: LlmInference? = null

    override suspend fun load(): LlmLoadResult {
        _state.value = LlmState.Loading
        val modelFile = File(modelPath)
        if (!modelFile.isFile) {
            val reason = "model file not found at $modelPath"
            _state.value = LlmState.Failed(reason)
            return LlmLoadResult.Failed(reason)
        }
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .build()
            inference = LlmInference.createFromOptions(context, options)
            _state.value = LlmState.Ready(modelFile.length())
            LlmLoadResult.Loaded
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            inference = null
            _state.value = LlmState.Failed(reason)
            LlmLoadResult.Failed(reason)
        }
    }

    override suspend fun generate(request: LlmRequest): LlmResult {
        val engine = inference ?: return LlmResult.Failed("engine not loaded")
        return try {
            val text = engine.generateResponse(request.prompt)
            LlmResult.Text(text)
        } catch (e: Exception) {
            LlmResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun release() {
        val current = inference
        inference = null
        if (current != null) {
            try {
                current.close()
            } catch (e: Exception) {
                // release must never throw (constitution I) — the engine is being torn down anyway.
            }
        }
        _state.value = LlmState.Unloaded
    }

    private companion object {
        const val DEFAULT_MAX_TOKENS = 512
    }
}
