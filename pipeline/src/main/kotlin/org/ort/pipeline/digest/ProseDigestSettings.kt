package org.ort.pipeline.digest

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ort.llm.LlmEngine

/**
 * Durable persistence for the FR-DIG-3b toggle. `:app`'s CF04 settings screen (WPE) writes
 * through this, and [ProseDigestRunner] — which may run in a fresh process after the app was
 * killed, WorkManager's whole point — reads it directly rather than trusting any in-memory state
 * to have survived process death.
 */
public interface ProseDigestSettingsStore {
    public fun isEnabled(): Boolean
    public fun setEnabled(enabled: Boolean)
}

/**
 * The behavioural fake/default (constitution II) — process-lifetime only, never survives a
 * restart. [ProseDigestSettings]'s own no-arg constructor uses this, so a caller that has not
 * yet wired real persistence still gets FR-DIG-3b's default-enabled behaviour, honestly volatile.
 */
public class InMemoryProseDigestSettingsStore(initiallyEnabled: Boolean = DEFAULT_ENABLED) : ProseDigestSettingsStore {
    @Volatile
    private var enabled: Boolean = initiallyEnabled

    override fun isEnabled(): Boolean = enabled

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    public companion object {
        public const val DEFAULT_ENABLED: Boolean = true
    }
}

/**
 * The production store (D36, FR-DIG-3b): one `SharedPreferences` boolean, defaulting to
 * **enabled = true** — D36 makes the model present on every device, and FR-DIG-3b's
 * "disableable" is an opt-out, not an opt-in. `context.applicationContext` so a caller that
 * happens to hold an `Activity`/`Service` context does not leak it into this store's lifetime.
 */
public class SharedPreferencesProseDigestSettingsStore(context: Context) : ProseDigestSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, InMemoryProseDigestSettingsStore.DEFAULT_ENABLED)

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    public companion object {
        public const val PREFS_NAME: String = "prose_digest_settings"
        public const val KEY_ENABLED: String = "enabled"
    }
}

/**
 * FR-DIG-3b: the LLM is disableable outright, and disabling it frees resident memory rather than
 * merely hiding its output. `:app`'s CF04 toggle (and any other caller) reads [enabled] and calls
 * [setEnabled] — releasing the engine is *this* class's job, not the screen's, so the guarantee
 * holds regardless of which screen flips the switch, and [ProseDigestGate] reading [enabled]
 * always agrees with what the engine actually did.
 *
 * Backed by [store] (default: [InMemoryProseDigestSettingsStore], process-lifetime only) so the
 * flag survives process death when the caller supplies [SharedPreferencesProseDigestSettingsStore]
 * — [ProseDigestRunner] constructs its own instance in a possibly-fresh process and must see the
 * same persisted value WPE's CF04 toggle last wrote, never a reset-to-default in-memory guess.
 *
 * Deliberately does not itself call [LlmEngine.load] on enable: loading is
 * [ProseDigestGenerator]/the gate's job, gated by [ProseDigestGate] on its own five conjuncts —
 * flipping this back on must not, by itself, load a model on a device that is mid-capture or not
 * charging.
 */
public class ProseDigestSettings(private val store: ProseDigestSettingsStore = InMemoryProseDigestSettingsStore()) {
    private val _enabled = MutableStateFlow(store.isEnabled())
    public val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * Disabling releases [engine] and persists the new value **before** publishing it, so a
     * reader of [enabled] can never observe "disabled" while the engine is still resident (AC-140)
     * or while the persisted store still says "enabled".
     */
    public fun setEnabled(value: Boolean, engine: LlmEngine) {
        if (!value) engine.release()
        store.setEnabled(value)
        _enabled.value = value
    }
}
