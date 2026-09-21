package org.ort.app.ui.settings

import org.ort.app.diagnostics.localsave.LocalSaveCategoryId
import org.ort.app.export.ExportRequestScope
import org.ort.app.fieldreport.bundle.FieldReportGatedCategory
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

    /** P27 (Wave G, NFR-6d, AC-167): the third-party licence notices screen —
     * [org.ort.app.ui.settings.SettingsLicensesScreen]. `SettingsRootScreen`'s own row for this
     * appends after the "About" section's own row (that file's doc comment says why: this screen id
     * is not one of [SettingsPolling.root]'s dynamic sections — its row is the one static addition
     * this unit's own file-ownership map allows). */
    LICENSES,

    /** P28 (D42, FR-ANL-1..14): the analytics tiers screen —
     * [org.ort.app.ui.settings.SettingsAnalyticsScreen]. Follows the identical pattern P27
     * established for [LICENSES]: `SettingsRootScreen`'s own row for this is a static addition to
     * the "Privacy" section, not a [SettingsPolling.root] dynamic row. */
    ANALYTICS,

    /** P30 (FR-STO-6, FR-STO-9, AC-170): the full database-and-audio backup and restore screen —
     * [org.ort.app.ui.settings.SettingsBackupScreen]. A new, dedicated screen rather than a
     * restore action folded into `SettingsStorageScreen.kt` — see that screen's own kdoc for why.
     * Follows the identical static-row-append pattern P27/P28 established for [LICENSES]/
     * [ANALYTICS]: appended to the "Records" section, beside Export, not a [SettingsPolling.root]
     * dynamic row. */
    BACKUP,

    /** P31 (FR-ALR-1..6): live alerts — [org.ort.app.ui.settings.SettingsAlertsScreen]. Follows
     * the identical static-row-append pattern P27/P28/P30 established for [LICENSES]/[ANALYTICS]/
     * [BACKUP]: appended to the "Capture" section (a live-monitoring concern, not a records or
     * privacy one), not a [SettingsPolling.root] dynamic row. */
    ALERTS,
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

/** WPR2 (FR-OBS-6..12): [SettingsDiagnosticsScreen]'s field-report section's own two actions,
 * bundled purely to keep that composable's own parameter list under detekt's threshold — the same
 * reason [SettingsCaptureToggleActions] exists. */
public data class FieldReportSectionActions(
    val onOpenFieldReport: () -> Unit = {},
    val onSetPublicDestinationGuardEnabled: (Boolean) -> Unit = {},
)

public data class SettingsCaptureToggleActions(
    val onToggleLevelWarn: (Boolean) -> Unit,
    val onToggleNoiseReduction: (Boolean) -> Unit,
    val onToggleBandPass: (Boolean) -> Unit,
)

/**
 * CF02 (`Settings-Capture.dc.html`, amended 2026-09-10): [mode]/[modeLabel]/[modeSubLine] back the
 * new leading Capture-mode row (FR-CAP-12) — real from
 * [org.ort.app.ui.data.CaptureModeFacts.currentMode]. Register R-821 (halt): [mode] is `null`, and
 * [modeLabel]/[modeSubLine] read "Not set"/"pick a mode to start capturing"
 * ([org.ort.app.ui.settings.SettingsPolling.NOT_SET_MODE_LABEL]/`NOT_SET_MODE_SUB_LINE`), exactly
 * when the operator has never chosen a mode at all — never [CaptureMode.LOCAL_MICROPHONE]'s own
 * label, which used to render here as a fabricated fact (constitution I) before setup had run.
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
    val mode: CaptureMode? = null,
    val modeLabel: String = "Not set",
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

/** [overCountLabel] (register R-835): real per-band over counts need the same band-at-start
 * attribution `org.ort.pipeline.rig.RigSupervisor.bandAtTransmissionStart` computes at record
 * time — re-deriving it here from a band's *current* frequency would silently mis-count a band
 * retuned mid-session, and no `:data`/`:pipeline` accessor exposes the real, already-attributed
 * count (`:data` is outside this package's ownership to add one to) — so this is always the
 * honest "not reported by this rig module" fallback today, never a fabricated or fragile guess
 * (constitution I). Flagged in this round's own report as the real gap: a `:pipeline`-side
 * `RigStatus.State.Connected.bandOverCounts`, or a `:data` DAO query, would resolve it properly. */
public data class SettingsRigBandViewState(
    val label: String,
    val frequencyLabel: String,
    val statusLabel: String,
    val squelchOpen: Boolean,
    val overCountLabel: String = NOT_REPORTED_BY_RIG_MODULE,
)

/** CF06's "Auto-information" row (`Settings-Rig.dc.html`'s `AI 1` row) — shown only when the
 * connected/stale descriptor's own bundled JSON declares an `unsolicited` push block at all (a
 * structural fact about that rig module, not a live runtime flag this build has no reader for —
 * see [SettingsRigViewState.autoInformation]'s own doc comment for why an unmatched/imported
 * descriptor omits this row rather than guessing). */
public data class SettingsRigAutoInformationViewState(val label: String, val subLine: String)

/** `Settings-Rig.dc.html` (amended 2026-09-10, FR-RIG-14/15). [connected] is `false` for both
 * `Absent` and `Stale` — [staleSinceLabel] distinguishes them (`null` when [connected] is `true` or
 * the rig has never connected at all).
 *
 * [descriptorLabel] (register R-845): the connected/stale descriptor's own display name with its
 * leading manufacturer word(s) dropped — "Kenwood TH-D75A" (`rig/src/main/resources/descriptors/
 * kenwood-thd75a.json`'s own `displayName`, unchanged since `RigStatus.State.Connected.descriptor`)
 * becomes "TH-D75A" here, never a second, separately-maintained title string. [transportLabel] is
 * real from `RigStatus.State.Connected.transportKind` (WPC2, merged `e464820`). [linkAddressLabel]
 * is real from `CaptureConfigurationStore.current().rigParams`
 * (`DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS`/`USB_VENDOR_ID`+`USB_PRODUCT_ID`) when
 * that session's own params carry one — `null` when they do not (an imported/generic descriptor
 * with no such param, or nothing connected yet), rendered honestly rather than guessed
 * (constitution I).
 *
 * Register R-835 additions, all real from the matched bundled [org.ort.rig.descriptor.RigDescriptor]
 * (by `RigStatus.State.Connected.descriptorId`/`Stale.lastKnown.descriptorId`) where one exists, and
 * [NOT_REPORTED_BY_RIG_MODULE] otherwise — never board-literal placeholder data
 * (`kenwood-thd75a.json` itself leaves USB `vid`/`pid` `null`, "still to verify" — this screen does
 * not invent the board's own mockup `vid 0x0451 pid 0x16a8` where the real descriptor has none):
 * [rigModuleLabel] (`<descriptor id> · built in · verified command set <caps>`, real
 * [org.ort.rig.RigCapability] names for the transport in use — not the board's own raw CAT
 * mnemonics, which no accessible source in this build carries); [otherTransportLabel] (the
 * descriptor's *other* declared transport, named plainly, with real vid/pid appended only when the
 * descriptor itself states them); [autoInformation] (`null` when the matched descriptor declares no
 * `unsolicited` push block at all — a structural absence, not an unreported fact); [batteryLabel]
 * (no real battery reader exists anywhere in `:pipeline` today — always the honest fallback);
 * [pollingClause] (`"reading both bands unpolled"` when the descriptor declares `unsolicited`, sent
 * on every connect per that field's own contract; `"polled every N s"` from the descriptor's own
 * real `poll.intervalMs` when it declares only that; the fallback when a descriptor is matched but
 * states neither, or none is matched at all).
 */
public data class SettingsRigViewState(
    val descriptorLabel: String,
    val connected: Boolean,
    val staleSinceLabel: String?,
    val bands: List<SettingsRigBandViewState>,
    val transportLabel: String? = null,
    val linkAddressLabel: String? = null,
    val rigModuleLabel: String = NOT_REPORTED_BY_RIG_MODULE,
    val otherTransportLabel: String? = null,
    val autoInformation: SettingsRigAutoInformationViewState? = null,
    val batteryLabel: String = NOT_REPORTED_BY_RIG_MODULE,
    val pollingClause: String = NOT_REPORTED_BY_RIG_MODULE,
)

/** R-835: the one honest fallback string every CF06 fact this build genuinely cannot supply reads
 * — never a different wording per row, so an operator (or a test) recognises "unreported" as one
 * consistent shape rather than several accidentally-different ones. */
public const val NOT_REPORTED_BY_RIG_MODULE: String = "not reported by this rig module"

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

/** P30 (FR-EXP-3): [SettingsExportScreen]'s own POTA export callback — a `data class` of one field
 * kept for the identical detekt `MatchingDeclarationName` reason [SettingsExportScreen.kt]'s own
 * kdoc states (that file's one top-level declaration is the `SettingsExportScreen` function
 * itself), and for parity with this file's sibling action-bundle classes. **R-1096:** this used to
 * also carry `onShareDigest`/`onShareThreadTranscript`/`onShareOverAudio` — moved to the actual
 * open screen each is about (digest, thread detail, transmission detail) once Settings turned out
 * to have no "which one" to share from; see [org.ort.app.export.ShareCoordinator]'s own kdoc. */
public data class SettingsExportShareActions(val onExportPota: (ExportRequestScope) -> Unit = {})

/** P30 (FR-STO-6, FR-STO-9): [SettingsBackupScreen]'s own action bundle — kept together for the
 * identical detekt `LongParameterList` reason [SettingsCaptureToggleActions]/[LocalSaveActions]
 * already exist for, and lives here rather than in `SettingsBackupScreen.kt` for the identical
 * `MatchingDeclarationName` reason [SettingsExportShareActions] above states. */
public data class SettingsBackupActions(
    val onSaveBackup: () -> Unit = {},
    val onPickRestoreFile: () -> Unit = {},
    val onConfirmRestore: () -> Unit = {},
    val onCancelRestore: () -> Unit = {},
)

/** P30 (FR-STO-6): [org.ort.app.backup.BackupPreview] reshaped for the screen — the real counts
 * and byte total `Save backup` is about to write, computed by the same producer the write itself
 * uses (`Save backup`'s own real handler in `SettingsContent.kt`), never a second, independently-
 * scoped estimate. [sizeLabel] is already formatted (`184 KB`/`1.4 MB`), matching every sibling
 * size label already in this package. */
public data class SettingsBackupPreviewViewState(
    val sessionCount: Int,
    val transmissionCount: Int,
    val correctionCount: Int,
    val audioFileCount: Int,
    val sizeLabel: String,
)

/** P30 (FR-STO-9, AC-170): one line of what a picked restore bundle *would* do — real counts from
 * a [org.ort.app.backup.BackupRestorePlan], reshaped for display. [hasConflicts] mirrors that
 * plan's own field so the screen never re-derives "any conflicts at all" a second way. */
public data class SettingsRestorePlanViewState(
    val sessionsToAddCount: Int,
    val sessionConflictCount: Int,
    val transmissionsToAddCount: Int,
    val transmissionConflictCount: Int,
    val correctionsToAddCount: Int,
    val correctionConflictCount: Int,
    val audioToAddCount: Int,
    val audioConflictCount: Int,
    val hasConflicts: Boolean,
)

/** P30's own state for [org.ort.app.ui.settings.SettingsBackupScreen]. [preview] is `null` only
 * while the real, async [SettingsBackupPreviewViewState] load is still resolving (the same
 * absent-signal shape every other async load in this package already uses — never a fabricated
 * zero). [restorePlan] is `null` until a bundle has been picked and analyzed; [restoreSummary] is
 * `null` until a restore has actually been applied, and holds the real added/skipped counts
 * [org.ort.app.backup.BackupRestoreResult] reports — never re-derived from [restorePlan] a second
 * way, since a restore may be applied against a device that changed between analyze and apply. */
public data class SettingsBackupViewState(
    val preview: SettingsBackupPreviewViewState?,
    val restorePlan: SettingsRestorePlanViewState? = null,
    val restoreSummary: String? = null,
)

/** P28 (D42, FR-ANL-1..14): [SettingsAnalyticsScreen]'s own state — [installIdLabel] is a
 * display-only, already-truncated string (never the raw id rendered as a claim to assert on;
 * `AC_179`'s own test reads [tier1Enabled]/[tier2Enabled]/[tier3Enabled] directly, never this
 * label). [destinationConfigured] is D48's honest "is anything actually deployed" fact — `false`
 * is the default state today. */
public data class SettingsAnalyticsViewState(
    val tier1Enabled: Boolean,
    val tier2Enabled: Boolean,
    val tier3Enabled: Boolean,
    val destinationConfigured: Boolean,
    val installIdLabel: String,
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
    /** WPR2 (FR-OBS-6..12): the field-report section's own state — `null` hides the whole section
     * (a release build, where the debug-only recorder never runs at all, FR-OBS-6). Defaulted so
     * `SettingsPolling.diagnostics`'s existing construction keeps compiling unchanged; `SettingsContent.kt`
     * attaches the real value itself (see that file's own `SettingsDiagnosticsSubScreen`). */
    val fieldReport: FieldReportSectionViewState? = null,
    /** WPDUMP: the unified local-save checklist's own state — `null` only while the async
     * [org.ort.app.diagnostics.localsave.LocalSaveBundleBuilder.preview] call is still resolving
     * (`SettingsDiagnosticsSubScreen`'s own load-in-progress moment, the same "render nothing until
     * ready" idiom already used one level up for this whole view state). Defaulted for the identical
     * "`SettingsPolling.kt`'s existing construction keeps compiling unchanged" reason [fieldReport]
     * is. */
    val localSave: LocalSaveSectionViewState? = null,
)

/** One row of the WPDUMP unified local-save checklist — real label/caption/size, [checked] already
 * folding in [available] (an unavailable row is never rendered checked, whatever the operator's
 * selection set otherwise carries), and [caption] already carrying the "why empty" reason appended
 * when `!available` (constitution I) — the screen renders this string verbatim, never re-deriving
 * or re-wording it. */
public data class LocalSaveCategoryRowViewState(
    val id: LocalSaveCategoryId,
    val label: String,
    val caption: String,
    val sizeLabel: String,
    val checked: Boolean,
    val available: Boolean,
)

/** WPDUMP: everything the unified checklist section renders — every row in board order, plus the
 * running total for exactly the currently-checked rows (recomputed, cheaply, client-side, every
 * time a checkbox changes — see `SettingsContent.kt`'s own `LocalSaveBundleBuilder.LocalSavePreview.totalBytes`
 * call). */
public data class LocalSaveSectionViewState(val rows: List<LocalSaveCategoryRowViewState>, val totalSizeLabel: String)

/** [SettingsDiagnosticsScreen]'s two WPDUMP local-save callbacks, bundled for the identical
 * detekt `LongParameterList` reason [FieldReportSectionActions] and
 * [SettingsDiagnosticsBundleActions] already exist for. */
public data class LocalSaveActions(
    val onToggle: (LocalSaveCategoryId, Boolean) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
)

/** `SettingsDiagnosticsScreen`'s field-report section (FR-OBS-6..12, D37/D38). */
public data class FieldReportSectionViewState(val publicGuardEnabled: Boolean)

/** FR-OBS-9's consent-screen file row — [category] is `null` for one of FR-OBS-8's ungated files. */
public data class FieldReportConsentFileViewState(
    val name: String,
    val sizeLabel: String,
    val category: FieldReportGatedCategory?,
)

/** The operator's current, per-upload toggle state (FR-OBS-9) — every field defaults `false`
 * ("each defaulting off"), and nothing here is ever persisted across a dismiss/reopen (AC-144). */
public data class FieldReportToggleState(
    val retainedAudio: Boolean = false,
    val voiceprintEmbeddings: Boolean = false,
    val screenFrames: Boolean = false,
)

/**
 * FR-OBS-9: everything the consent screen shows before every field-report upload. [destinationKnown]
 * `false` pairs with [destinationLabel] carrying the honest "not configured" message
 * (`SettingsContributeScreen.kt`'s own precedent for an upload client that does not exist yet)
 * rather than a fabricated repository name; [destinationPublic] is meaningless when
 * [destinationKnown] is `false` and callers must not read it in that case.
 */
public data class FieldReportConsentViewState(
    val files: List<FieldReportConsentFileViewState>,
    val totalSizeLabel: String,
    val destinationKnown: Boolean,
    val destinationLabel: String,
    val destinationPublic: Boolean,
    val publicGuardEnabled: Boolean,
    val toggles: FieldReportToggleState = FieldReportToggleState(),
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
/** P31 (FR-ALR-1): the three watch kinds FR-ALR-1 names, kept as this package's own UI-facing
 * enum rather than importing `org.ort.pipeline.alerts.AlertWatch`'s sealed class directly into a
 * view state — the same view/domain separation every other `Settings*ViewState` in this file
 * already keeps. */
public enum class SettingsAlertWatchKind { CALLSIGN, KEYWORD, FREQUENCY }

/** One row of [SettingsAlertsViewState.watches] — [label] is already formatted
 * ([org.ort.pipeline.alerts.AlertWatch.displayValue]), never re-derived here. */
public data class SettingsAlertWatchRowViewState(
    val id: String,
    val kind: SettingsAlertWatchKind,
    val label: String,
    val enabled: Boolean,
)

/**
 * P31 (FR-ALR-1, FR-ALR-2, FR-ALR-6): [SettingsAlertsScreen]'s own state.
 * [notificationsPermissionGranted] is D-honest (constitution I): `false` renders a banner stating
 * alerts cannot fire, rather than a toggle that silently does nothing (functional spec §7.19's own
 * "handle that honestly").
 */
public data class SettingsAlertsViewState(
    val alertsEnabled: Boolean,
    val notificationsPermissionGranted: Boolean,
    val watches: List<SettingsAlertWatchRowViewState>,
)

/** [SettingsAlertsScreen]'s own action bundle — the identical detekt `LongParameterList` reason
 * [SettingsBackupActions]/[SettingsExportShareActions] already exist for. [onEditWatch] carries
 * the whole [SettingsAlertWatchKind] alongside the id since a caller cannot otherwise tell which
 * of the three add/edit paths a bare id belongs to. */
public data class SettingsAlertsActions(
    val onToggleAlertsEnabled: (Boolean) -> Unit = {},
    val onToggleWatch: (id: String, enabled: Boolean) -> Unit = { _, _ -> },
    val onEditWatch: (id: String, kind: SettingsAlertWatchKind, newValue: String) -> Unit = { _, _, _ -> },
    val onRemoveWatch: (id: String) -> Unit = {},
    val onAddCallsignWatch: (String) -> Unit = {},
    val onAddKeywordWatch: (String) -> Unit = {},
    val onAddFrequencyWatch: (String) -> Unit = {},
)

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
