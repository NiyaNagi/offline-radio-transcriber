package org.ort.app.ui.settings

/**
 * WP10 (register R-090, FR-CFG-1): every view-state the `Settings` root and its nine sub-screens
 * render. Every screen composable in this package is a pure function of one of these — polling and
 * `Context` stay in [SettingsPolling], never in a screen (ui-conformance-plan's builder rule).
 */
public enum class SettingsScreenId { CAPTURE, RIG, TIER, STORAGE, ASSETS, EXPORT, CONTRIBUTE, DIAGNOSTICS, ABOUT }

public data class SettingsRowViewState(val label: String, val subLine: String, val screen: SettingsScreenId)
public data class SettingsSectionViewState(val label: String, val rows: List<SettingsRowViewState>)
public data class SettingsRootViewState(val sections: List<SettingsSectionViewState>)

/** `Settings-Capture.dc.html`: input/level facts come from `InputStatus`/`LevelStatus` (WP11c) —
 * this is the one row R-090's brief said would be written before those holders existed. */
public data class SettingsCaptureViewState(
    val inputLabel: String,
    val inputSubLine: String,
    val levelLabel: String,
    val levelSubLine: String,
    val levelWarnEnabled: Boolean,
    val noiseReductionEnabled: Boolean,
    val bandPassEnabled: Boolean,
    val manualFrequencyMhz: String?,
)

public data class SettingsRigBandViewState(
    val label: String,
    val frequencyLabel: String,
    val statusLabel: String,
    val squelchOpen: Boolean,
)

/** `Settings-Rig.dc.html`. [connected] is `false` for both `Absent` and `Stale` — [staleSinceLabel]
 * distinguishes them (`null` when [connected] is `true` or the rig has never connected at all). */
public data class SettingsRigViewState(
    val descriptorLabel: String,
    val connected: Boolean,
    val staleSinceLabel: String?,
    val bands: List<SettingsRigBandViewState>,
)

public data class SettingsTierViewState(
    val currentTierLabel: String,
    val maxTierLabel: String,
    val overrideLabel: String,
    val isOverridden: Boolean,
)

public data class SettingsStorageCategoryViewState(val label: String, val bytes: Long)

public data class SettingsStorageViewState(
    val usedBytes: Long,
    val budgetGb: Int?,
    val deviceFreeBytes: Long,
    val categories: List<SettingsStorageCategoryViewState>,
    val nightsLeftLabel: String?,
    val autoPruneEnabled: Boolean,
)

public data class SettingsExportViewState(
    val tonightOverCount: Int,
    val tonightSpanLabel: String,
    val allSessionCount: Int,
    val allOverCount: Int,
)

public data class SettingsContributeCategoryViewState(val label: String, val subLine: String, val enabled: Boolean)

public data class SettingsContributeViewState(
    val contributionEnabled: Boolean,
    val categories: List<SettingsContributeCategoryViewState>,
    /** Constitution V's closed list, verbatim — the four categories that never leave the device
     * under any setting here. */
    val neverIncluded: List<String>,
)

public data class SettingsDiagnosticsFileViewState(val name: String, val description: String)

public data class SettingsDiagnosticsViewState(
    val aliveLabel: String,
    val realTimeFactorLabel: String,
    /** `null` — no aggregate failed-pass-count query exists yet; never a fabricated number. */
    val failedPassCount: Int?,
    val files: List<SettingsDiagnosticsFileViewState>,
)

public data class SettingsAboutViewState(
    val appVersionLabel: String,
    val androidVersionLabel: String,
    val minSdkLabel: String,
)

/** Constitution III/V, verbatim — the sentence [SettingsContributeViewState.neverIncluded] and
 * `Settings-Export`'s own "never exported" note both restate. Kept as one named constant so both
 * screens quote the same words rather than drifting apart. */
public val NEVER_LEAVES_DEVICE: List<String> = listOf(
    "Voiceprints and embeddings",
    "Names and notes you gave stations",
    "Station knowledge — who is a regular where, and when",
    "Your location, precise or coarse",
)
