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
 *
 * **[autoPruneEnabled]'s scope narrowed under WPARC (register R-1037, FR-STO-3e, D40):
 * over audio never auto-deletes, by any means — no setting turns pruning on for it.** This flag
 * predates that decision and today has no wired deletion effect on over audio at all (no
 * executor for it was ever built — `computeNextDeletion`
 * ([org.ort.pipeline.capture.computeNextDeletion]) only ever *previews* what a future one would
 * do); it is kept, unchanged in shape, only so a value already written by an operator's device is
 * not silently discarded. Reaching the over-audio budget instead produces a persistent,
 * poll-cheap warning ([org.ort.pipeline.capture.overAudioBudgetState], AC-157) that survives a
 * restart and deletes nothing. The continuous archive's own automatic oldest-first pruning
 * (FR-STO-3d, [archiveEnabled]/[archiveBudgetGb] below) is unconditional once the archive budget
 * is reached — D39's point is a bounded training corpus, not another opt-in — and is therefore
 * **not** gated by this flag either.
 *
 * **[archiveEnabled]/[archiveBudgetGb] (WPARC, FR-SEG-9, FR-STO-3d, D39)** are the continuous
 * archive's own budget, independent of [audioBudgetGb] — see
 * [org.ort.pipeline.capture.ArchiveSettingsStore]'s own kdoc for why a *second*, `:pipeline`-side
 * store type exists reading/writing this exact same persisted pair: `:pipeline` cannot depend on
 * `:app` (module graph), so `RealCaptureService` reads these keys through that type instead of
 * this one, over the same on-disk preferences file. Unlike [audioBudgetGb], [archiveBudgetGb] is
 * never "no budget set" — D39 always has a real, positive default (60 GB).
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

    // --- Continuous archive (FR-SEG-9, FR-STO-3d, D39) -----------------------------------------
    /** Defaults `true` — D39: "the continuous archive ... defaults ON". */
    public var archiveEnabled: Boolean

    /** Defaults `60` (GB) — D39's stated budget. Never `null`: unlike [audioBudgetGb], this
     * budget is never in a "not set" state. */
    public var archiveBudgetGb: Int

    // --- Tier override (FR-TIER-3) ---------------------------------------------------------------
    /** [org.ort.core.Tier.name], or `null` for "let the phone choose" (the detected tier, adjusted
     * automatically for thermal/backlog per FR-TIER-4). */
    public var tierOverrideName: String?

    // --- Capture / input (Settings-Capture board) ------------------------------------------------
    public var noiseReductionEnabled: Boolean
    public var bandPassFilterEnabled: Boolean
    public var levelWarnEnabled: Boolean
    public var manualFrequencyMhz: String?
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

    // WPARC (FR-SEG-9, FR-STO-3d, D39): same keys/defaults as
    // org.ort.pipeline.capture.SharedPreferencesArchiveSettingsStore -- the same on-disk file,
    // read/written from either side of the module boundary (see this file's own kdoc).
    override var archiveEnabled: Boolean by BooleanPref(KEY_ARCHIVE_ENABLED, default = true)
    override var archiveBudgetGb: Int
        get() = if (prefs.contains(KEY_ARCHIVE_BUDGET_GB)) prefs.getInt(KEY_ARCHIVE_BUDGET_GB, 60) else 60
        set(value) = prefs.edit { putInt(KEY_ARCHIVE_BUDGET_GB, value) }

    override var tierOverrideName: String? by StringPref(KEY_TIER_OVERRIDE)

    override var noiseReductionEnabled: Boolean by BooleanPref(KEY_NOISE_REDUCTION, default = true)
    override var bandPassFilterEnabled: Boolean by BooleanPref(KEY_BAND_PASS, default = false)
    override var levelWarnEnabled: Boolean by BooleanPref(KEY_LEVEL_WARN, default = true)
    override var manualFrequencyMhz: String? by StringPref(KEY_MANUAL_FREQUENCY_MHZ)

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
        public const val KEY_ARCHIVE_ENABLED: String = "archive_enabled"
        public const val KEY_ARCHIVE_BUDGET_GB: String = "archive_budget_gb"
        public const val KEY_TIER_OVERRIDE: String = "tier_override"
        public const val KEY_NOISE_REDUCTION: String = "noise_reduction_enabled"
        public const val KEY_BAND_PASS: String = "band_pass_enabled"
        public const val KEY_LEVEL_WARN: String = "level_warn_enabled"
        public const val KEY_MANUAL_FREQUENCY_MHZ: String = "manual_frequency_mhz"
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
    override var archiveEnabled: Boolean = true,
    override var archiveBudgetGb: Int = 60,
    override var tierOverrideName: String? = null,
    override var noiseReductionEnabled: Boolean = true,
    override var bandPassFilterEnabled: Boolean = false,
    override var levelWarnEnabled: Boolean = true,
    override var manualFrequencyMhz: String? = null,
) : SettingsStore
