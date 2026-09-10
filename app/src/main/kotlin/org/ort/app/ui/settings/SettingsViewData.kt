package org.ort.app.ui.settings

import org.ort.core.capture.CaptureMode

/**
 * WP10 (register R-090, FR-CFG-1): every view-state the `Settings` root and its nine sub-screens
 * render. Every screen composable in this package is a pure function of one of these — polling and
 * `Context` stay in [SettingsPolling], never in a screen (ui-conformance-plan's builder rule).
 *
 * [MODE] (WPE, `spec/e2e-capture-modes-plan.md`, CF11/`Settings-Mode.dc.html`, FR-CAP-8/9/12/13,
 * AC-131) — the settings re-entry into the capture mode picker, reachable from CF02's `Change`.
 */
public enum class SettingsScreenId {
    CAPTURE,
    RIG,
    TIER,
    STORAGE,
    ASSETS,
    EXPORT,
    CONTRIBUTE,
    DIAGNOSTICS,
    ABOUT,
    MODE,
}

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
/** R-133 (round 8): [SettingsContent]'s two cross-package drill-in callbacks (R-132's
 * `onOpenLevelMeter`, this round's `onReviewSession`) bundled purely so the private
 * `SettingsSubScreen` it hands them to stays under detekt's `LongParameterList` threshold —
 * `SettingsContent`'s own *public* signature is unchanged (unbundled, two separate defaulted
 * params) so `OrtNavHost.kt`'s existing named-argument call site keeps compiling untouched; this
 * bundle exists only internal to this package's own dispatch, the same reason
 * [SettingsCaptureToggleActions] does. */
internal data class SettingsCrossPackageActions(
    val onOpenLevelMeter: () -> Unit,
    val onReviewSession: (sessionId: String) -> Unit,
    // WPE (CF11): CF02's own `Change` → CF11 — not truly cross-package, but joins this bundle for
    // the identical detekt-threshold reason, once adding it as a bare parameter to
    // `SettingsSubScreen` pushed that function's own count over the limit.
    val onOpenModeSettings: () -> Unit = {},
)

public data class SettingsCaptureToggleActions(
    val onToggleLevelWarn: (Boolean) -> Unit,
    val onToggleNoiseReduction: (Boolean) -> Unit,
    val onToggleBandPass: (Boolean) -> Unit,
)

/**
 * CF02 (`Settings-Capture.dc.html`, amended 2026-09-10): [mode]/[modeLabel]/[modeSubLine] back the
 * new leading Capture-mode row (FR-CAP-12) — real from
 * [org.ort.app.ui.data.CaptureModeFacts.currentMode], which always has a value
 * ([org.ort.pipeline.rig.CaptureConfiguration.DEFAULT] on a fresh install — a stated default, never
 * an absent fact, constitution I).
 */
public data class SettingsCaptureViewState(
    val inputLabel: String,
    val inputSubLine: String,
    val levelLabel: String,
    val levelSubLine: String,
    val levelWarnEnabled: Boolean,
    val noiseReductionEnabled: Boolean,
    val bandPassEnabled: Boolean,
    val manualFrequencyMhz: String?,
    val mode: CaptureMode = CaptureMode.LOCAL_MICROPHONE,
    val modeLabel: String = CaptureMode.LOCAL_MICROPHONE.operatorLabel,
    val modeSubLine: String = "",
)

/** CF11 (`Settings-Mode.dc.html`, FR-CAP-8, FR-CAP-9, FR-CAP-12, FR-CAP-13, AC-131) — one radio row
 * of the three-mode picker. [current] marks [org.ort.app.ui.data.CaptureModeFacts.currentMode];
 * [pending] marks [org.ort.app.ui.data.CaptureModeFacts.pendingMode] — picked while a session was
 * live, not yet the session-recorded [current] mode (`org.ort.pipeline.rig.CaptureConfigurationStore`,
 * WPC2). */
public data class SettingsModeRowViewState(
    val mode: CaptureMode,
    val descriptionLabel: String,
    val current: Boolean,
    val pending: Boolean = false,
)

/** CF11's "What the mode set" section — each row real from `InputStatus`/`RigStatus`. */
public data class SettingsModeSetRowViewState(val label: String, val subLine: String)

public data class SettingsModeViewState(
    val rows: List<SettingsModeRowViewState>,
    val sessionLive: Boolean,
    val audioRoute: SettingsModeSetRowViewState,
    val rigLink: SettingsModeSetRowViewState,
)

public data class SettingsRigBandViewState(
    val label: String,
    val frequencyLabel: String,
    val statusLabel: String,
    val squelchOpen: Boolean,
)

/** `Settings-Rig.dc.html` (amended 2026-09-10, FR-RIG-14/15). [connected] is `false` for both
 * `Absent` and `Stale` — [staleSinceLabel] distinguishes them (`null` when [connected] is `true` or
 * the rig has never connected at all).
 *
 * [transportLabel] is real from `RigStatus.State.Connected.transportKind` (WPC2, merged `e464820`).
 * [linkAddressLabel] is real from `CaptureConfigurationStore.current().rigParams`
 * (`DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS`/`USB_VENDOR_ID`+`USB_PRODUCT_ID`) when
 * that session's own params carry one — `null` when they do not (an imported/generic descriptor
 * with no such param, or nothing connected yet), rendered honestly rather than guessed
 * (constitution I). */
public data class SettingsRigViewState(
    val descriptorLabel: String,
    val connected: Boolean,
    val staleSinceLabel: String?,
    val bands: List<SettingsRigBandViewState>,
    val transportLabel: String? = null,
    val linkAddressLabel: String? = null,
)

public data class SettingsTierViewState(
    val currentTierLabel: String,
    val maxTierLabel: String,
    val overrideLabel: String,
    val isOverridden: Boolean,
)

public data class SettingsStorageCategoryViewState(val label: String, val bytes: Long)

/**
 * R-133 (register, round 8): `Settings-Storage.dc.html`'s "Next deletion: <date>" row —
 * [org.ort.pipeline.capture.NextDeletion] (WP11c, `:pipeline`) reshaped for the screen, `null`
 * exactly when [org.ort.pipeline.capture.computeNextDeletion] itself returns `null` (nothing would
 * be pruned right now — never an empty placeholder standing in for that fact).
 */
public data class SettingsNextDeletionViewState(
    val sessionId: String,
    /** [org.ort.pipeline.capture.NextDeletion.predictedAtMillis] formatted — "would happen now",
     * per that field's own doc comment, not a future forecast. */
    val predictedDateLabel: String,
    /** The session's own start — "audio from <this date>" — [org.ort.pipeline.capture.NextDeletion.startedAtMillis]. */
    val sessionDateLabel: String,
    val overCount: Int,
    val sizeLabel: String,
)

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
    /** R-133 (round 8): real, from `:pipeline`'s `computeNextDeletion` — see
     * [SettingsNextDeletionViewState]'s own doc comment. */
    val nextDeletion: SettingsNextDeletionViewState? = null,
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

/** R-137 (round 9): [sizeLabel] is now real — WP11e's `DiagnosticsBundleBuilder.preview` renders
 * this exact file's real bytes (the same render `write` uses, never a separate stat — see that
 * object's own doc comment for why that equality matters for a scrubbed log). */
public data class SettingsDiagnosticsFileViewState(val name: String, val description: String, val sizeLabel: String)

public data class SettingsDiagnosticsViewState(
    val aliveLabel: String,
    val realTimeFactorLabel: String,
    /** `null` — no aggregate failed-pass-count query exists yet; never a fabricated number. */
    val failedPassCount: Int?,
    val files: List<SettingsDiagnosticsFileViewState>,
    /** R-137 (round 9): the board's own "In the bundle · N files · X.X MB" header total — real,
     * `DiagnosticsBundleBuilder.preview`'s own `BundlePreview.totalBytes`, never the board's
     * illustrative "2.1 MB". */
    val totalSizeLabel: String,
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
