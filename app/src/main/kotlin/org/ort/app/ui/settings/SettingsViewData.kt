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
/** The three [org.ort.app.ui.components.ToggleRow] callbacks [org.ort.app.ui.settings.SettingsCaptureScreen]
 * owns, bundled to keep that composable's own parameter list under detekt's threshold — the same
 * reason `ui/navigation`'s `NavHostCallbacks` bundle exists. Lives here (a plain data holder, not a
 * screen) rather than in `SettingsCaptureScreen.kt` because detekt's `MatchingDeclarationName` rule
 * wants a file's one top-level class to share the file's name; that file's own top-level
 * declaration is the `SettingsCaptureScreen` function. */
public data class SettingsCaptureToggleActions(
    val onToggleLevelWarn: (Boolean) -> Unit,
    val onToggleNoiseReduction: (Boolean) -> Unit,
    val onToggleBandPass: (Boolean) -> Unit,
)

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
    /** R-133 (register, round 4 System validator): `StorageForecast.THREE_NIGHTS_THRESHOLD`, the
     * real early-warning threshold FR-STO-3/R-105 already computes against — not a board literal. */
    val warnAtNightsLeft: Int,
    /** R-133: the same "100 MB" `:pipeline`'s `FailureMapper`/`Fail-Storage` already show for
     * `RealCaptureService`'s hard floor — that constant is `internal` to `:pipeline` and not
     * importable here, so this repeats the literal already shipped elsewhere for the same fact
     * rather than inventing a new one. */
    val hardFloorLabel: String = "100 MB",
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
    val neverIncluded: List<NeverLeavesDeviceItem>,
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
    /** R-138 (register, round 7): the real sherpa-onnx version this build depends on
     * (`gradle/libs.versions.toml`'s `sherpaOnnx` entry, via `BuildConfig.SHERPA_ONNX_VERSION` —
     * never hardcoded here). ONNX Runtime and usb-serial-for-android have no version-catalog entry
     * of their own to read this same way — neither is an actual Gradle dependency of this build
     * yet (confirmed against `gradle/libs.versions.toml` before writing this) — so their rows stay
     * the honest, version-free statements they already were rather than inventing a number. */
    val sherpaOnnxVersionLabel: String,
)

/** One row of [NEVER_LEAVES_DEVICE] — a title and, where `Settings-Contribute.dc.html` gives one,
 * the sub-line explaining why (R-136, round 4 System validator: the title and sub-line had been
 * merged into a single truncated string before this — "Voiceprints and embeddings" for
 * "Voiceprints", "Station knowledge — who is a regular where, and when" for "Station knowledge"
 * plus its own longer sub-line — losing the board's actual per-item wording). */
public data class NeverLeavesDeviceItem(val title: String, val subLine: String? = null)

/** Constitution III/V, `Settings-Contribute.dc.html` verbatim (R-136) — [SettingsContributeViewState.neverIncluded]
 * quotes this exactly; `Settings-Export`'s own single-sentence "never exported" note is a separate,
 * differently-worded restatement `Settings-Export.dc.html` itself gives, so it is not built from
 * this list. */
public val NEVER_LEAVES_DEVICE: List<NeverLeavesDeviceItem> = listOf(
    NeverLeavesDeviceItem("Voiceprints", "a voice is a biometric · it is used here and only here"),
    NeverLeavesDeviceItem("Names and notes you gave stations"),
    NeverLeavesDeviceItem(
        "Station knowledge",
        "who is a regular where, when they are around — the patterns this phone has learned",
    ),
    NeverLeavesDeviceItem(
        "Your location, precise or coarse",
        "signal reports are stripped too — S-meter readings can place a receiver",
    ),
)
