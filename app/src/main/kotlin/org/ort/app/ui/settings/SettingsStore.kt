package org.ort.app.ui.settings

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * WP10 (register R-090, FR-CFG-1, FR-STO-3, FR-CON-1..4, FR-TIER-3): every setting the `Settings`
 * boards show, in one place — the app had no `Settings` root before this and therefore nowhere to
 * persist a setting a screen actually changes (`ModelsScreen` reads/writes files directly; it has
 * no on/off switches of its own). Follows `ui/setup/SetupStore.kt`'s exact shape (interface +
 * `SharedPreferences` impl with typed delegate properties + a plain in-memory fake for tests) —
 * that file is this package's nearest neighbour and the only precedent for a settings-shaped store
 * in this codebase.
 *
 * **Nothing here defaults on that the constitution requires off.** [contributionEnabled] and every
 * per-category contribution switch default `false` (constitution V / FR-CON-1: "off until the user
 * turns it on"); [autoPruneEnabled] defaults `false` (FR-STO-3a: automatic pruning is opt-in).
 * [audioBudgetGb] defaults `null` — no budget set is a real, distinct state from "unlimited"
 * (FR-STO-3: "unlimited" is itself an explicit choice, not the absence of one), so a caller must
 * never treat `null` as either 0 or infinity without saying which it means.
 */
public interface SettingsStore {
    // --- Privacy / contribution (FR-CON-1..8, constitution V) ---------------------------------
    public var contributionEnabled: Boolean
    public var contributeAudioOfLabelled: Boolean
    public var contributeTranscriptsAndCorrections: Boolean
    public var contributeRejectedSegments: Boolean
    public var contributeResolverStatistics: Boolean

    // --- Storage / retention (FR-STO-3..5) -----------------------------------------------------
    /** A budget on gated transmission audio, in gigabytes; `null` = no budget set yet (distinct
     * from an explicit "unlimited" choice, which no board offers as a control yet — see this
     * package's report). */
    public var audioBudgetGb: Int?
    public var autoPruneEnabled: Boolean

    // --- Tier override (FR-TIER-3) ---------------------------------------------------------------
    /** [org.ort.core.Tier.name], or `null` for "let the phone choose" (the detected tier, adjusted
     * automatically for thermal/backlog per FR-TIER-4). */
    public var tierOverrideName: String?

    // --- Capture / input (Settings-Capture board) ------------------------------------------------
    public var noiseReductionEnabled: Boolean
    public var bandPassFilterEnabled: Boolean
    public var levelWarnEnabled: Boolean
    public var manualFrequencyMhz: String?

    /**
     * CF11 (`Settings-Mode.dc.html`, FR-CAP-12, AC-131): [org.ort.core.capture.CaptureMode.name]
     * the operator picked while a session was live — recorded here, honestly, as a real but
     * **not yet enforced** fact (constitution I: a provisional record must never be presented as
     * more than it is), pending WPC2's `CaptureConfigurationStore` (`spec/e2e-capture-modes-plan.md`
     * §"Seam for the package still in flight"), which is what will actually apply it to the next
     * session. `null` when nothing is pending — the ordinary case, and always the case while idle
     * (idle picks re-enter setup directly rather than staging anything).
     */
    public var pendingCaptureModeName: String?
}

/** The real, `SharedPreferences`-backed [SettingsStore]. */
public class SharedPreferencesSettingsStore(private val prefs: SharedPreferences) : SettingsStore {

    override var contributionEnabled: Boolean by BooleanPref(KEY_CONTRIBUTION_ENABLED, default = false)
    override var contributeAudioOfLabelled: Boolean by BooleanPref(KEY_CONTRIBUTE_AUDIO, default = false)
    override var contributeTranscriptsAndCorrections: Boolean
        by BooleanPref(KEY_CONTRIBUTE_TRANSCRIPTS, default = false)
    override var contributeRejectedSegments: Boolean by BooleanPref(KEY_CONTRIBUTE_REJECTED, default = false)
    override var contributeResolverStatistics: Boolean by BooleanPref(KEY_CONTRIBUTE_RESOLVER_STATS, default = false)

    override var audioBudgetGb: Int? by IntPref(KEY_AUDIO_BUDGET_GB)
    override var autoPruneEnabled: Boolean by BooleanPref(KEY_AUTO_PRUNE, default = false)

    override var tierOverrideName: String? by StringPref(KEY_TIER_OVERRIDE)

    override var noiseReductionEnabled: Boolean by BooleanPref(KEY_NOISE_REDUCTION, default = true)
    override var bandPassFilterEnabled: Boolean by BooleanPref(KEY_BAND_PASS, default = false)
    override var levelWarnEnabled: Boolean by BooleanPref(KEY_LEVEL_WARN, default = true)
    override var manualFrequencyMhz: String? by StringPref(KEY_MANUAL_FREQUENCY_MHZ)
    override var pendingCaptureModeName: String? by StringPref(KEY_PENDING_CAPTURE_MODE)

    private inner class BooleanPref(val key: String, val default: Boolean) :
        kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            prefs.edit { putBoolean(key, value) }
        }
    }

    private inner class StringPref(val key: String) : kotlin.properties.ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getString(key, null)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) {
            prefs.edit { putString(key, value) }
        }
    }

    private inner class IntPref(val key: String) : kotlin.properties.ReadWriteProperty<Any?, Int?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) =
            if (prefs.contains(key)) prefs.getInt(key, 0) else null
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int?) {
            prefs.edit { if (value == null) remove(key) else putInt(key, value) }
        }
    }

    public companion object {
        public const val PREFS_NAME: String = "org.ort.app.settings"
        public const val KEY_CONTRIBUTION_ENABLED: String = "contribution_enabled"
        public const val KEY_CONTRIBUTE_AUDIO: String = "contribute_audio"
        public const val KEY_CONTRIBUTE_TRANSCRIPTS: String = "contribute_transcripts"
        public const val KEY_CONTRIBUTE_REJECTED: String = "contribute_rejected"
        public const val KEY_CONTRIBUTE_RESOLVER_STATS: String = "contribute_resolver_stats"
        public const val KEY_AUDIO_BUDGET_GB: String = "audio_budget_gb"
        public const val KEY_AUTO_PRUNE: String = "auto_prune_enabled"
        public const val KEY_TIER_OVERRIDE: String = "tier_override"
        public const val KEY_NOISE_REDUCTION: String = "noise_reduction_enabled"
        public const val KEY_BAND_PASS: String = "band_pass_enabled"
        public const val KEY_LEVEL_WARN: String = "level_warn_enabled"
        public const val KEY_MANUAL_FREQUENCY_MHZ: String = "manual_frequency_mhz"
        public const val KEY_PENDING_CAPTURE_MODE: String = "pending_capture_mode"
    }
}

/** The behavioural fake (constitution II) — a plain in-memory [SettingsStore] for tests, matching
 * `InMemorySetupStore`'s shape: one constructor parameter per property, each independently
 * defaulted to the same default the real store uses. */
@Suppress("LongParameterList")
public class InMemorySettingsStore(
    override var contributionEnabled: Boolean = false,
    override var contributeAudioOfLabelled: Boolean = false,
    override var contributeTranscriptsAndCorrections: Boolean = false,
    override var contributeRejectedSegments: Boolean = false,
    override var contributeResolverStatistics: Boolean = false,
    override var audioBudgetGb: Int? = null,
    override var autoPruneEnabled: Boolean = false,
    override var tierOverrideName: String? = null,
    override var noiseReductionEnabled: Boolean = true,
    override var bandPassFilterEnabled: Boolean = false,
    override var levelWarnEnabled: Boolean = true,
    override var manualFrequencyMhz: String? = null,
    override var pendingCaptureModeName: String? = null,
) : SettingsStore
