package org.ort.pipeline.digest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ort.llm.LlmEngine

/**
 * FR-DIG-3b: the LLM is disableable outright, and disabling it frees resident memory rather than
 * merely hiding its output. `:app`'s CF04 toggle (and any other caller) reads [enabled] and calls
 * [setEnabled] — releasing the engine is *this* class's job, not the screen's, so the guarantee
 * holds regardless of which screen flips the switch, and [ProseDigestGate] reading [enabled]
 * always agrees with what the engine actually did.
 *
 * Deliberately does not itself call [LlmEngine.load] on enable: loading is
 * [ProseDigestGenerator]/the gate's job, gated by [ProseDigestGate] on its own five conjuncts —
 * flipping this back on must not, by itself, load a model on a device that is mid-capture or not
 * charging.
 */
public class ProseDigestSettings(initiallyEnabled: Boolean = true) {
    private val _enabled = MutableStateFlow(initiallyEnabled)
    public val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * Disabling releases [engine] **before** publishing the new value, so a reader of [enabled]
     * can never observe "disabled" while the engine is still resident (AC-140).
     */
    public fun setEnabled(value: Boolean, engine: LlmEngine) {
        if (!value) engine.release()
        _enabled.value = value
    }
}
