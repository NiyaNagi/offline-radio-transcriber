package org.ort.app.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.ort.app.BuildConfig
import org.ort.app.MainActivity
import org.ort.app.analytics.SetupFunnelAnalytics
import org.ort.app.fieldreport.wiring.FieldReportAppWiring
import org.ort.app.permissions.PermissionsState
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.data.ModelRowStatus
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.data.rowsForSetupModelsStep
import org.ort.app.ui.data.rowsRequiringDownload
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.settings.SettingsPolling
import org.ort.app.ui.theme.OrtSystemBarStyle
import org.ort.app.ui.theme.OrtTheme
import org.ort.app.work.ModelDownloadSnapshot
import org.ort.app.work.ModelDownloadWorker
import org.ort.capture.android.AndroidAudioIo
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.CaptureGain
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.CaptureModePresets
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.rig.CaptureConfigurationStore
import org.ort.pipeline.rig.DefaultRigLinkBridge
import org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore
import org.ort.rig.NullRigModule
import org.ort.rig.RigTransportKind
import org.ort.rig.catalogue.RigCatalogue
import org.ort.rig.catalogue.RigCatalogueEntry
import org.ort.rig.descriptor.RigDescriptor
import org.ort.core.capture.RigTransportKind as PresetRigTransportKind

/**
 * The guided setup sequence (build-plan P8; ui-conformance-plan WP9, register R-080..R-084,
 * `Flow-Setup.dc.html`) — S01 through S12. `MainActivity` routes here whenever
 * [SetupStateMachine.isComplete] is false and hands back to it once `Start capture` is tapped
 * ([onStartCapture]), so the "start capture + launch the reader" logic stays exactly where
 * `MainActivity`'s own doc comment says it does (register R-080's router split).
 *
 * [EXTRA_STEP] (ui-conformance-plan WP9, round 3): the name of a [SetupStep] to open on directly,
 * read once in [onCreate] ([tryOpenAtRequestedStep]) — WP3's
 * `ReaderNavigator.openSetupInput()` names `INPUT` for F1's "Choose another input" and
 * `Settings-Capture`'s "Re-verify", both fired *during a running session*, when
 * [SetupStateMachine.isComplete] is already true and the ordinary [refreshStep] would hand
 * straight back to `MainActivity` without ever showing anything (exactly the gap that function's
 * own doc comment reported before this extra existed). Honored only "when the store allows it": a
 * requested step is opened only if every gate *before* it in [SetupStep]'s own declaration order
 * is already satisfied — i.e. it is at or before [SetupStateMachine.stepFor]'s own natural resume
 * point (or that point is `null`, meaning every gate has cleared). A request to skip ahead of an
 * unmet gate (e.g. `RADIO` while the microphone is still unresolved) is silently ignored in favour
 * of the ordinary resume flow — this extra can only offer a *reconfiguration* entry point into an
 * already-valid sequence, never a way around a verification the guide requires. Checked once, at
 * cold start, not on every [refreshStep] call: after the initial jump, normal forward/back
 * navigation and any later `onResume` re-check behave exactly as they did before this extra
 * existed.
 *
 * [EXTRA_FONT_SCALE] (ui-conformance-plan WP9, screenshot-tour seam for WP12): the screenshot tour
 * (package `org.ort.app.debug.tour`, under this app module's own `debug` source set) captures Setup
 * steps by launching this activity with [EXTRA_STEP] directly — but reaching font scale 2.0 the same
 * way would mean changing the *system* `font_scale` setting, which needs an activity relaunch to
 * take effect and starves the one emulator the tour and a validator's own manual walk both depend
 * on. Read once in [onCreate], the same way [EXTRA_STEP] is: when the intent carries this extra,
 * [LocalDensity] is overridden for the whole Setup composition with a [Density] at the *same* pixel
 * density the platform already reports ([LocalDensity.current] itself, read before the override —
 * never a hardcoded `1f`, which would be wrong on any real device) and the requested `fontScale`, so
 * every Setup screen renders at that scale in-process, no system setting touched and no relaunch
 * needed. Absence of the extra changes nothing at all — the composition is never wrapped, so an
 * ordinary launch still reads the real platform [LocalDensity] exactly as before this existed;
 * debug/tooling-only in effect (the tour is itself part of the `debug` source set, never compiled
 * into a release build), and harmless there regardless, since no launcher sets it.
 *
 * Every screen composable this class dispatches to stays a pure function of a view-state (guide's
 * own rule) — all `Context`/coroutine/IO work lives here, in the one place allowed to touch it.
 * `LargeClass` suppressed for the same reason [SetupStore]'s own `TooManyFunctions` suppression
 * is: this is deliberately the *one* place every guided-setup `Context`/coroutine/IO concern lives
 * (D33/P19 added four more screens' worth on top of the pre-existing eight), not a class that
 * grew by accident — splitting it would scatter that single-responsibility boundary across
 * several files with no natural seam between them, per this file's own class doc comment above.
 */
@Suppress("LargeClass")
public class SetupActivity : ComponentActivity() {

    private lateinit var store: SetupStore
    private lateinit var audioIo: AndroidAudioIo

    /** WPC2's contract (`:pipeline`) — what `RealCaptureService` actually reads at session start
     * (FR-CAP-12/13, AC-131). [syncCaptureConfiguration] pushes [store]'s current facts through
     * this on every mutation to the mode/route/rig axes; [SetupCaptureConfigurationAdapter] is the
     * pure translation. */
    private lateinit var captureConfigStore: CaptureConfigurationStore

    private val stepState = mutableStateOf<SetupStep?>(null)

    /**
     * R-802 (register, tour run 2): `refreshPairedDevices()` used to run only from
     * [onSelectRigTransport]'s forward path and the explicit `Refresh` action, so an operator
     * whose process died mid-S10b (or anyone the tour/debug seam cold-opens straight onto this
     * step via [EXTRA_STEP]) saw an empty paired-device list until they tapped `Refresh` — a real
     * defect, not just a tour gap, since [SetupStateMachine]'s `RIG_BLUETOOTH` gate is resumable by
     * design. Routing every assignment through this property (not just [navigateForward]'s) means
     * [refreshPairedDevices] runs the moment the step *becomes* `RIG_BLUETOOTH`, on every path that
     * can produce that value: [refreshStep]'s cold/gate resume, [tryOpenAtRequestedStep]'s debug
     * entry, [navigateForward]'s forward action, and [onBack]. [onSelectRigTransport]'s own explicit
     * call is redundant with this but harmless (a second, idempotent [RigLinkPort.pairedDevices]
     * read) — left as-is rather than special-cased apart from the rest.
     */
    private var step: SetupStep?
        get() = stepState.value
        set(value) {
            stepState.value = value
            // P28 follow-up (FR-ANL-2): the setup funnel's "step reached" event -- one line, no
            // other logic added to this class (this unit's own file-ownership restriction).
            if (value != null) SetupFunnelAnalytics.reached(value.name)
            if (value == SetupStep.RIG_BLUETOOTH) refreshPairedDevices()
        }
    private val backStack = ArrayDeque<SetupStep>()

    private var inputRoutes by mutableStateOf<List<InputRouteOption>>(emptyList())
    private var selectedInputId by mutableStateOf<String?>(null)
    private var selectedInputLabel by mutableStateOf<String?>(null)

    private var verifyState by mutableStateOf<RouteCheckState?>(null)
    private var verifyRunToken by mutableStateOf(0)

    /** P39: whether the operator has actually asked for a route check on [SetupStep.LISTEN]. Separate
     * from [verifyState] because the first emission is not instantaneous — without it the checklist
     * would flicker into existence a frame after the tap, which reads as the button having done
     * nothing. `false` on a cold resume, so a screen the operator has only ever scrolled shows the
     * route list alone rather than four empty rings (constitution I: never a checklist for a check
     * nobody started). */
    private var verifyRequested by mutableStateOf(false)

    private var levelState by mutableStateOf<LevelCheckState?>(null)
    private var levelRunToken by mutableStateOf(0)

    /**
     * R-1168: S07's gain slider position, in dB. Compose state rather than reading
     * [CaptureGain.decibels] directly at composition time — that is a plain `@Volatile` field
     * nothing recomposes on, so the slider would not move under the operator's finger. [CaptureGain]
     * remains the single source of truth for the *live* value the audio path reads;
     * [onGainChanged] writes both, and [onCreate] seeds this from what [CaptureGain] already holds.
     */
    private var gainDb by mutableStateOf(CaptureGain.MIN_GAIN_DB)

    private var rigStatusSnapshot by mutableStateOf(RigStatus.state)

    /** Set just before [RenderRadioVerified] routes an unexpected [RigStatus.State.Absent] back to
     * S09 — see that function's doc comment. Cleared the moment the operator acts on S09 again
     * ([onChooseRig]/[onRadioNotNow]/[onChangeRadio]), never left stale on a later, unrelated
     * visit to S09. */
    private var radioAbsentBanner by mutableStateOf<String?>(null)

    // --- D33/P19 (WPD): the rig catalogue, S09b/S10b state ---------------------------------------

    /** Descriptors accepted this session via S09's `Import it` (FR-RIG-19) — kept in-memory only;
     * persisting an imported descriptor across process death is `:rig`'s own asset-lifecycle
     * concern (FR-AST-7), not this screen's. */
    private var importedRigDescriptors by mutableStateOf<Set<RigDescriptor>>(emptySet())
    private var radioImportError by mutableStateOf<String?>(null)
    private var selectedRigTransportKind by mutableStateOf<RigTransportKind?>(null)
    private var rigBluetoothDevices by mutableStateOf<List<PairedDevice>>(emptyList())
    private var rigBluetoothSelectedAddress by mutableStateOf<String?>(null)
    private var rigLinkState by mutableStateOf<RigLinkState?>(null)
    private var rigLinkRunToken by mutableStateOf(0)

    /** R-903 (reviewer A2, run 3, design): the moment [onSelectRigBluetoothDevice] started this
     * attempt — S11's "identified and verified in N.N s" clause is measured from here to the
     * instant [rigLinkState] first reaches [RigLinkState.Verified] ([onRigLinkStateChanged]), never
     * fabricated. [org.ort.core.Clock.monotonicNanos], never [org.ort.core.Clock.wallMillis] — that
     * interface's own doc comment is explicit that wall time is for display/storage only and can
     * jump, so it is never honest for measuring a duration. `null` whenever no attempt is in flight
     * (reset on every fresh device selection so a later attempt's timing never inherits an earlier
     * one's start). [clock] is a settable seam (mirrors [isDebugBuild]'s own shape) purely for test
     * determinism. */
    private var rigLinkVerifyStartedAtNanos: Long? = null
    private var rigLinkVerifiedDurationSeconds by mutableStateOf<Double?>(null)
    internal var clock: org.ort.core.Clock = org.ort.core.SystemClock

    /** R-1013/R-1014 (WPD3): the capability labels never observed when the checklist concluded at
     * [RigLinkState.VerifyTimedOut] rather than [RigLinkState.Verified] — set only in
     * [onContinueRigBluetooth], read only by [RenderRadioVerified]. Reset alongside
     * [rigLinkVerifiedDurationSeconds] on every path that must never carry a stale or a
     * different-transport attempt's own fact into a later S11 visit (see each reset site's own
     * comment) — see [RadioVerifiedScreen]'s own `missingCapabilities` doc comment for the absolute
     * constraint this exists to satisfy. Not persisted in [SetupStore]: like
     * [rigLinkVerifiedDurationSeconds], a process-death resume has no way to recover which specific
     * capabilities were missing, so it honestly reads as fully verified after a resume rather than
     * fabricating a list — the same accepted trade-off R-903 already established for the duration. */
    private var rigLinkMissingCapabilities by mutableStateOf<List<String>>(emptyList())

    /** WPD's own `:app`-local seam (`RigLinkPort.kt`'s doc comment has the full account) — the
     * real link runs over WPC3's `RigLinkBridge` (`:pipeline`, merged `8e40041`), never
     * `:rig-bluetooth` directly (constitution VII); see [BridgeRigLinkPort]'s own doc comment.
     * Set once in [onCreate], from [DebugRigLinkPortOverride.activeOverride] first — the tour
     * cannot reach S10b's paired-device states over real Bluetooth hardware no AVD has
     * ([DebugRigLinkPortOverride]'s own doc comment). */
    private lateinit var rigLinkPort: RigLinkPort

    private val openDescriptorLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
            .getOrNull()
        if (text == null) {
            radioImportError = "Could not read that file."
            return@registerForActivityResult
        }
        RigCatalogue.import(text).fold(
            onSuccess = { descriptor ->
                importedRigDescriptors = importedRigDescriptors + descriptor
                radioImportError = null
            },
            onFailure = { error -> radioImportError = error.message ?: "That file is not a valid rig descriptor." },
        )
    }

    private val radioCatalogue: RigCatalogue get() = RigPickerCatalogue.build(importedRigDescriptors)

    /** WPC2: pushes [store]'s current mode/route/rig facts through [captureConfigStore] — the
     * contract `RealCaptureService` actually reads at session start. Called after every mutation
     * to those axes; a no-op before S00 is walked ([SetupCaptureConfigurationAdapter] returns
     * `null` until [SetupStore.captureMode] is set). [CaptureConfigurationStore.update] itself
     * decides pending-vs-immediate from whether capture is running (FR-CAP-12) — this call site
     * never needs to know which. */
    private fun syncCaptureConfiguration(manualFrequencyHz: Long? = inForceManualFrequencyHz()) {
        SetupCaptureConfigurationAdapter
            .toCaptureConfiguration(store, manualFrequencyHz)
            ?.let(captureConfigStore::update)
    }

    /**
     * **R-1167/AC-202: the frequency the operator actually set, wherever they set it.**
     *
     * `CaptureConfigurationStore.update` replaces the whole configuration, so every sync has to carry
     * this field through or destroy it. Since P39 moved the prompt to the log's own header, that store
     * — not [SetupStore] — is the source of truth, and re-entering setup must leave what is there
     * alone.
     *
     * The pending configuration is preferred over the current one deliberately. `update` writes to
     * *pending* while capture is running (FR-CAP-12, AC-131: a change applies at the next session), so
     * an operator who edits the frequency mid-session and then opens setup would otherwise have that
     * edit silently replaced by the value the running session started with.
     */
    private fun inForceManualFrequencyHz(): Long? =
        (captureConfigStore.pendingConfiguration() ?: captureConfigStore.current()).manualFrequencyHz

    /** The chosen rig's [RigCatalogueEntry], recovered from [SetupStore.rigId] rather than kept as
     * its own in-memory field — so a process death between S09 and S09b/S10b resumes correctly
     * ([SetupStateMachine]'s `RIG_TRANSPORT`/`RIG_BLUETOOTH` gates are resumable, unlike
     * [SetupStep.RADIO_USB]/[SetupStep.RADIO_VERIFIED]). */
    private fun currentRigEntry(): RigCatalogueEntry? = radioCatalogue.entries().firstOrNull { it.id == store.rigId }

    /** Test-only window into [step] — see `MainActivity.currentScreenForTest`'s identical pattern. */
    internal val currentStepForTest: SetupStep? get() = step

    /** Test-only window into [radioCatalogue] — a real `Activity`-level test cannot otherwise reach
     * a [RigCatalogueEntry] to hand to [onChooseRig] without duplicating [RigPickerCatalogue.build]
     * itself. */
    internal val radioCatalogueForTest: RigCatalogue get() = radioCatalogue

    /** Test-only window into [rigBluetoothDevices] — R-802's own regression proof needs to see
     * what a cold-opened S10b actually lists, not just that the screen renders something. */
    internal val rigBluetoothDevicesForTest: List<PairedDevice> get() = rigBluetoothDevices

    /** Test-only window into [rigLinkState] — the debug-extra follow-up's own regression proof
     * (connecting -> identified -> verified -> dropped) reads this directly rather than scraping
     * Compose semantics for each intermediate frame. */
    internal val rigLinkStateForTest: RigLinkState? get() = rigLinkState

    /** Test-only window into [rigBluetoothSelectedAddress] — proves [EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS]
     * actually reached this field, independent of whatever [rigLinkPort] then does with it. */
    internal val rigBluetoothSelectedAddressForTest: String? get() = rigBluetoothSelectedAddress

    /** Test-only window into [rigLinkVerifiedDurationSeconds] — R-903's own regression proof needs
     * to see the real measured value [onRigLinkStateChanged] computed. */
    internal val rigLinkVerifiedDurationSecondsForTest: Double? get() = rigLinkVerifiedDurationSeconds

    /** Test-only window into [rigLinkMissingCapabilities] — R-1014's own regression proof needs to
     * see exactly what [onContinueRigBluetooth] carried forward from a [RigLinkState.VerifyTimedOut]
     * link, not merely that S11 was reached. */
    internal val rigLinkMissingCapabilitiesForTest: List<String> get() = rigLinkMissingCapabilities

    /** Test-only window into [selectedRigTransportKind] — E2-E09's own regression proof needs to
     * see whether S09b's preset pre-select actually ran, not just that the screen renders. */
    internal val selectedRigTransportKindForTest: RigTransportKind? get() = selectedRigTransportKind

    /** Test-only window into [verifyState] — R-943's own regression proof needs to see that
     * [DebugRouteCheckOverride.activeOverride] actually reached S05's rendered state, not just
     * that [SetupStep.VERIFY] itself was reached. */
    internal val verifyStateForTest: RouteCheckState? get() = verifyState

    /** R-1168: test-only window into the slider position S07 is actually rendering. */
    internal val gainDbForTest: Int get() = gainDb

    // WPW (register, WPR2's own report): `FieldReportAppWiring.attachWindow(window)` was called
    // only from `ReaderActivity` — Setup was never wired at all, even though the operator's own
    // motivating incident (four onboarding defects, no evidence but a verbal description and one
    // photograph) happened *during* Setup, arguably the highest-value place in the app for a screen
    // frame. Wired exactly as `ReaderActivity.onCreate`/`onDestroy` already do — attach here, detach
    // in `onDestroy` below — no other change to this class (this round's own file-ownership map:
    // "the frame wiring only").
    override fun onDestroy() {
        super.onDestroy()
        FieldReportAppWiring.detachWindow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FieldReportAppWiring.attachWindow(window)
        enableEdgeToEdge(statusBarStyle = OrtSystemBarStyle, navigationBarStyle = OrtSystemBarStyle)
        store = SharedPreferencesSetupStore(getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, MODE_PRIVATE))
        captureConfigStore = SharedPreferencesCaptureConfigurationStore(
            getSharedPreferences(SharedPreferencesCaptureConfigurationStore.PREFS_NAME, MODE_PRIVATE),
        )
        rigLinkPort = DebugRigLinkPortOverride.activeOverride ?: BridgeRigLinkPort(DefaultRigLinkBridge(this))
        audioIo = AndroidAudioIo(this)
        // R-1168: the stored gain becomes the live one before any meter or capture opens a device
        // here. `OrtApplication` already does this at process start for the path that never enters
        // Setup at all; repeating it is idempotent and keeps this Activity correct when it is
        // launched directly (every `SetupActivityTest`, and every debug scenario, does exactly that).
        CaptureGainWiring.applyStoredGain(store)
        gainDb = CaptureGain.decibels
        selectedInputId = store.selectedInputId
        selectedInputLabel = store.selectedInputLabel
        reconcileOvernightSurvival()

        val fontScaleOverride = intent?.takeIf { it.hasExtra(EXTRA_FONT_SCALE) }?.getFloatExtra(EXTRA_FONT_SCALE, 1f)
        setContent {
            if (fontScaleOverride != null) {
                val platformDensity = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platformDensity.density, fontScaleOverride)) {
                    OrtTheme { RenderStep() }
                }
            } else {
                OrtTheme { RenderStep() }
            }
        }
        if (!tryOpenAtRequestedStep(intent?.getStringExtra(EXTRA_STEP))) refreshStep()
        applyDebugRigBluetoothAddressExtra()
    }

    /** See [EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS]'s own doc comment. Called once, after the step has
     * already resolved above — deliberately after, not folded into [tryOpenAtRequestedStep], since
     * this must also apply on the *ordinary* resume path (no [EXTRA_STEP] at all: a store already
     * gated on `RIG_BLUETOOTH`), not only the debug direct-entry one. */
    private fun applyDebugRigBluetoothAddressExtra() {
        if (!isDebugBuild()) return
        if (step != SetupStep.RIG_BLUETOOTH) return
        val address = intent?.getStringExtra(EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS) ?: return
        onSelectRigBluetoothDevice(address)
    }

    /** See [EXTRA_STEP]'s own doc comment. Returns `true` (consuming the request) only when the
     * named step is honored; `false` leaves [step] untouched so the caller falls back to the
     * ordinary [refreshStep]. */
    private fun tryOpenAtRequestedStep(requestedStepName: String?): Boolean {
        val requested = requestedStepName?.let { name -> SetupStep.entries.firstOrNull { it.name == name } }
            ?: return false
        val naturalNext = SetupStateMachine.stepFor(
            currentPermissionsState(),
            micPermanentlyDenied(),
            currentSnapshot(),
        )
        if (!requestIsHonourable(requested, naturalNext)) return false
        step = requested
        if (requested == SetupStep.LISTEN && inputRoutes.isEmpty()) refreshInputRoutes()
        if (requested == SetupStep.RIG_TRANSPORT) applyPresetRigTransportIfUnselected()
        return true
    }

    /**
     * Whether an [EXTRA_STEP] request may be honoured: it must be at or before the natural resume
     * point, **or** a live sub-state of the very stage we would resume on anyway.
     *
     * The second clause is not a loosening, and P39 made it necessary rather than merely tidy. The old
     * ladder gave every screen its own ordinal, so `ROUTE_MISMATCH` sat between `VERIFY` and `LEVEL`
     * and a request for it was honoured whenever the resume point was `LEVEL` or later. With
     * `INPUT`/`VERIFY`/`LEVEL` merged into [SetupStep.LISTEN], a bare ordinal comparison refuses
     * `ROUTE_MISMATCH` for exactly the operator it exists for — one whose route has just failed, so
     * whose natural resume point *is* `LISTEN`. Comparing stages instead says what was always meant:
     * a request can offer a reconfiguration entry into an already-valid sequence, and a halt reachable
     * from the stage you are on is part of that stage, never a way around a verification.
     */
    private fun requestIsHonourable(requested: SetupStep, naturalNext: SetupStep): Boolean {
        if (requested.ordinal <= naturalNext.ordinal) return true
        val total = currentTotalSteps()
        val stage = requested.indicatorIndex(total) ?: return false
        return stage == naturalNext.indicatorIndex(total)
    }

    /**
     * P22 (FR-AST-10..12, AC-188): [store.snapshot] alone cannot know what the manifest or the
     * installed-asset state currently is (that field's own doc comment) — this is the one place
     * that reads the real [ModelsController] state and folds it in before ever handing a snapshot
     * to [SetupStateMachine.stepFor]. Called from both [refreshStep] and [tryOpenAtRequestedStep],
     * so neither can drift from the other about what "required models installed" means.
     */
    private fun currentSnapshot(): SetupSnapshot {
        val modelsState = ModelsController.currentState(this)
        return store.snapshot().copy(
            requiredModelsInstalled = modelsState.rowsRequiringDownload().isEmpty(),
            rigModuleAvailable = rigModuleAvailable(),
        )
    }

    /**
     * P39 (D58): whether the rig branch may be entered at all — *is there a rig module with a real
     * CAT implementation to talk to?*
     *
     * **There is not, on any build shipped today**, and this function says so rather than pretending
     * otherwise: `:rig-usb`'s transport is not wired to `:app` and no descriptor module has a CAT
     * implementation behind it ([RigLinkPort]'s own doc comment), so the whole `RADIO` →
     * `RIG_TRANSPORT` → `RIG_BLUETOOTH` branch is unreachable through [SetupStateMachine.stepFor].
     * That is the honest rendering of the state the code is in — asking an operator which transport to
     * use for a radio nothing can command is a wizard page that cannot be answered correctly.
     *
     * [DebugRigLinkPortOverride] is the seam that keeps the branch *capturable*: a debug scenario that
     * installs a scripted link port is, for the tour's purposes, a rig module that exists, and the
     * screens behind it are still reached by [EXTRA_STEP] regardless. When a real CAT implementation
     * lands, this becomes a real query against the catalogue and the branch reappears with no other
     * change to the state machine.
     */
    private fun rigModuleAvailable(): Boolean = DebugRigLinkPortOverride.activeOverride != null

    /** P39: the indicator's denominator, derived rather than fixed — see [setupTotalSteps]. Read at
     * composition time so a model that finishes downloading mid-run does not leave a stale total, and
     * from the same [ModelsController] fact [currentSnapshot] gates `MODELS` on, so the count and the
     * ladder can never disagree. */
    private fun currentTotalSteps(): Int =
        setupTotalSteps(ModelsController.currentState(this).rowsRequiringDownload().isEmpty())

    /**
     * P22 (AC-189, constitution IV): reconciles [SetupStore.overnightSurvivalProven] against real
     * session evidence, once, before the first [tryOpenAtRequestedStep]/[refreshStep] call runs —
     * synchronous [runBlocking] bridge from this non-suspend call site, the same established
     * pattern [RoomActiveLexiconStore.current] already uses for a one-shot `:data` read. A no-op
     * unless setup has already completed once and survival is not yet proven (nothing to reconcile
     * on a fresh install, and nothing left to prove once it already latched true).
     *
     * **This function latches the proven half and nothing else. It must never clear
     * [SetupStore.overnightStepSeen] — R-1161.** It used to, and the comment that stood here
     * defended the reset on the grounds that the two Overnight actions "still only need to fire
     * once per launch ... with no risk of looping back to `SetupStep.OVERNIGHT` again within the
     * same launch." That reasoning is **true within a launch and false across launches**, and every
     * trip of this cycle *is* a new launch: [handBackToMainActivity] finishes this activity,
     * `MainActivity.route` finishes itself on the way back, and this `onCreate` then ran again and
     * re-armed the step the operator had just answered. Together with R-1104's fast-path check that
     * made the loop total and inescapable — the operator's own report was *"any button i press just
     * loops back to the running overnight page"*. Neither change was wrong alone; the defect lived
     * only in their composition, which is why no single unit test saw it. If you are tempted to put
     * the reset back: the flag is cleared by `MainActivity.overnightSurvivalStillUnproven`, once per
     * process, at the one place that also knows whether the operator has already been asked.
     *
     * AC-189's own requirement — the step **reappears** on every relevant subsequent launch — is
     * unaffected and is satisfied there; what AC-189 never asked for, and R-1104 over-implemented,
     * is refusing capture until survival is proven.
     *
     * This reruns Setup's own walk whenever [SetupActivity] is entered for any reason. **R-1104**:
     * an operator who completes setup once and always launches through `MainActivity`'s
     * already-permitted fast path never entered `SetupActivity` again, so this function alone never
     * ran for them — `MainActivity`'s own `overnightSurvivalStillUnproven` (that file's own doc
     * comment) now runs the identical check from that fast path and hands off here whenever
     * survival is still unproven, closing the gap this comment used to name as open.
     */
    private fun reconcileOvernightSurvival() {
        if (!store.setupComplete || store.overnightSurvivalProven) return
        val checker = DebugOvernightSurvivalOverride.activeOverride
            ?: RealOvernightSurvivalChecker(OrtDatabase.create(applicationContext).sessionDao())
        if (runBlocking { checker.hasProvenSurvival() }) {
            store.overnightSurvivalProven = true
        }
    }

    /**
     * R-1005b (device field report): returning from system Settings — the only route the S10b
     * `NoPermission` banner offered before [requestRigBluetoothPermissions] existed — never calls
     * `onRequestPermissionsResult` (that callback fires only for the in-app permission dialog), so
     * a Bluetooth permission granted from Settings while on [SetupStep.BLUETOOTH_PERMISSION] or
     * [SetupStep.RIG_BLUETOOTH] never re-evaluated anything until the operator hit `Refresh` by
     * hand. [refreshStep] alone is enough for both: [SetupStep.BLUETOOTH_PERMISSION] genuinely
     * advances past its own gate once `stepFor` sees the permission granted (`step = next` is then
     * a real change); [SetupStep.RIG_BLUETOOTH] most often recomputes to the *same* step (the
     * Bluetooth-link gate does not clear on a permission grant alone), where a naive read of the
     * `step` setter's `mutableStateOf` write would look like a no-op — but [refreshPairedDevices]
     * itself runs unconditionally inside that setter whenever the assigned value is
     * [SetupStep.RIG_BLUETOOTH] (this class's own doc comment on [step]), regardless of whether the
     * value actually changed, and it is *that* call's own [rigBluetoothDevices]/[rigLinkState]
     * writes — genuinely different `mutableStateOf` values now that permission is granted — which
     * make the screen visibly re-render. R-085's pre-existing mic case is unchanged.
     */
    override fun onResume() {
        super.onResume()
        if (shouldRefreshStepOnResume(step)) refreshStep()
        rigStatusSnapshot = RigStatus.state
    }

    /** [onResume]'s own condition, named rather than inlined so detekt's `ComplexCondition`
     * threshold is a non-issue rather than a reason to drop one of the four real cases -- every
     * one earns its place, per that function's own doc comment. */
    private fun shouldRefreshStepOnResume(step: SetupStep?): Boolean = step == SetupStep.MICROPHONE_DENIED ||
        step == null ||
        step == SetupStep.BLUETOOTH_PERMISSION ||
        step == SetupStep.RIG_BLUETOOTH

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // P39: the one request that is not a gate is the first-capture-start notification ask — its
        // answer changes nothing about which step to show, so it resumes the action it interrupted
        // rather than re-deriving a step (which would land back on Ready and lose the press).
        if (pendingStartCapture) {
            pendingStartCapture = false
            onStartCapture()
            return
        }
        refreshStep(pushCurrent = true)
    }

    // --- Step resolution --------------------------------------------------------------------

    /**
     * [pushCurrent] distinguishes a genuine forward action (Begin, a permission just resolved,
     * Skip, Not now, ...) — which leaves the step being left behind reachable via [onBack] — from
     * a cold resume (`onCreate`/`onResume` with no user action), which must never fabricate a
     * back-stack entry the operator did not actually navigate through.
     */
    /**
     * **P39 (D58), the second structural rule, and the half of R-1161 that lived here.** This function
     * used to have a `next == null` branch that set [SetupStore.setupComplete] and called
     * [handBackToMainActivity] — setup treating *"nothing left to do"* as an instruction to hand
     * control back to the router that had just sent it here. `MainActivity.route` could then send it
     * straight back, and two components that each defer to the other are a cycle by construction
     * rather than by accident.
     *
     * [SetupStateMachine.stepFor] is now non-null: "no gates remain" resolves to [SetupStep.READY],
     * a real destination with a real `Start capture` press, so there is no null branch left to hand
     * back from. This activity leaves only on an explicit operator action.
     */
    private fun refreshStep(pushCurrent: Boolean = false) {
        val permissions = currentPermissionsState()
        val next = SetupStateMachine.stepFor(permissions, micPermanentlyDenied(), currentSnapshot())
        if (pushCurrent) step?.let { if (it != next) backStack.addLast(it) }
        step = next
        if (next == SetupStep.LISTEN && inputRoutes.isEmpty()) refreshInputRoutes()
        if (next == SetupStep.RIG_TRANSPORT) applyPresetRigTransportIfUnselected()
    }

    private fun handBackToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun navigateForward(next: SetupStep) {
        step?.let { backStack.addLast(it) }
        step = next
    }

    internal fun onBack() {
        val previous = backStack.removeLastOrNull() ?: return
        step = previous
    }

    // --- Welcome (AC-166, AC-180, AC-203) ---------------------------------------------------------

    /**
     * **`Begin` is the acknowledgement for all three things Welcome now carries** (P39, D58).
     *
     * [SetupStore.jurisdictionNoticeSeen] and [SetupStore.analyticsConsentSeen] are written here, not
     * deleted: AC-166 and AC-180 were *amended* by D58, not dropped, and both still require the
     * content to be shown and acknowledged before capture. Folding them onto one screen changes where
     * the acknowledgement happens, never whether it does — and the two flags remain the record that it
     * did, for anything downstream that asks.
     *
     * [SetupStore.notificationsSkipped] is set here for a different and more mechanical reason, and it
     * is worth being explicit about it. `NOTIFICATIONS` is no longer a setup step (the ask moved to
     * first capture start), but `MainActivity` folds this flag into `PermissionsState.notificationsGranted`
     * and several callers of [org.ort.app.permissions.PermissionsFlow] still read that field. Writing
     * it once, here, means "setup is not waiting on the notification permission" — which is now simply
     * true — rather than leaving a flag nobody sets to quietly report that it is.
     */
    internal fun onBegin() {
        store.welcomeSeen = true
        store.jurisdictionNoticeSeen = true
        store.analyticsConsentSeen = true
        store.notificationsSkipped = true
        refreshStep(pushCurrent = true)
    }

    /** AC-180's two toggles, one tap from Welcome. They write straight through as they are flipped —
     * there is nothing to confirm, because nothing here gates anything (FR-ANL-10). */
    internal fun onToggleAnalyticsTier(tier: org.ort.telemetry.AnalyticsTier, enabled: Boolean) {
        org.ort.app.analytics.AnalyticsAppWiring.configureOnce(this)
        org.ort.app.analytics.AnalyticsAppWiring.controller.setTierEnabled(tier, enabled)
    }

    // --- Mode (D33, FR-CAP-8/FR-CAP-9; AC-204) ----------------------------------------------------

    /**
     * Choosing a mode presets both independent axes (FR-CAP-9): the audio route to the first
     * enumerated route of [CaptureModePresets.presetsFor]'s preferred [org.ort.core.capture.AudioRouteKind]
     * (present, not yet verified — [ListenScreen] shows it preselected), and the rig-control transport
     * to the preset's [org.ort.core.capture.CapturePreset.preferredRigTransportKind]. Both override
     * flags reset — a fresh mode choice starts with nothing overridden yet.
     *
     * **AC-204: the tap also fires the system microphone dialog, directly.** There is no explainer step
     * in front of it any more; the rationale is already on this screen
     * ([MICROPHONE_RATIONALE]) and the request follows the choice it exists to justify. A mic that is
     * already granted asks nothing and the flow simply advances — and a permanently denied one is
     * caught by [SetupStateMachine.stepFor]'s own `MICROPHONE_DENIED` gate on the way through, which
     * is a real halt rather than another ask.
     */
    internal fun onChooseMode(mode: CaptureMode) {
        store.captureMode = mode
        val preset = CaptureModePresets.presetsFor(mode)
        if (inputRoutes.isEmpty()) refreshInputRoutes() else applyPresetInputIfUnselected()
        store.modeOverriddenAudio = false
        store.rigTransport = preset.preferredRigTransportKind
        store.modeOverriddenRig = false
        syncCaptureConfiguration()
        if (!currentPermissionsState().recordAudioGranted && !micPermanentlyDenied()) {
            requestRecordAudio()
            return
        }
        refreshStep(pushCurrent = true)
    }

    // --- S02c Bluetooth permission (D33, FR-RIG-14) -----------------------------------------------

    internal fun requestBluetoothConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CODE)
        } else {
            // Below API 31 there is no dangerous BLUETOOTH_CONNECT permission to request at all —
            // the legacy BLUETOOTH permission is normal-protection and already granted.
            refreshStep()
        }
    }

    /** S02c's "Not now — use USB instead": the mode flip itself is what stops
     * [SetupStateMachine]'s `BLUETOOTH_PERMISSION` gate from firing again; [SetupStore
     * .bluetoothPermissionDeclined] records the decline defensively on top of that (that state
     * machine's own doc comment explains why both exist). Set *after* [onChooseMode] so the fresh
     * USB-mode preset does not reset it back to `false` the instant it is written. */
    internal fun onDeclineBluetoothPermission() {
        onChooseMode(CaptureMode.USB_RADIO)
        store.bluetoothPermissionDeclined = true
        SetupFunnelAnalytics.skipped(SetupStep.BLUETOOTH_PERMISSION.name)
    }

    // --- Microphone (AC-204) ----------------------------------------------------------------------

    /** Fired from the mode tap ([onChooseMode]) — never from an explainer screen of its own, which
     * P39 deleted. [SetupStore.micRequested] is the bookkeeping `shouldShowRequestPermissionRationale`
     * alone cannot supply: it is what tells "never asked" from "denied permanently". */
    internal fun requestRecordAudio() {
        store.micRequested = true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    internal fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    internal fun checkMicAgain() {
        refreshStep()
    }

    // --- Listen: routes, route check, level -------------------------------------------------------

    internal fun refreshInputRoutes() {
        inputRoutes = InputRouteEnumerator(this, audioIo).list()
        applyPresetInputIfUnselected()
    }

    /** R-902: the single place [presetInputRouteFor]'s own "exactly one match" pre-select actually
     * runs — called from every path that can bring S04 up with a real route list and nothing chosen
     * yet, not only the interactive [onChooseMode] tap: [refreshInputRoutes] itself (a cold/resumed
     * entry straight onto `INPUT`, or a debug scenario that seeds `SetupStore.captureMode` directly
     * without ever calling [onChooseMode]) and [onChooseMode]'s own already-enumerated-routes case.
     * A no-op once the operator (or a resumed [SetupStore.selectedInputId]) has already chosen
     * something — never overwrites a real choice, matching [onChooseMode]'s own prior behaviour. */
    private fun applyPresetInputIfUnselected() {
        if (selectedInputId != null) return
        val route = presetInputRouteFor(store.captureMode, inputRoutes) ?: return
        selectedInputId = route.id
        selectedInputLabel = route.label
        store.selectedInputId = route.id
        store.selectedInputLabel = route.label
    }

    /**
     * P39: picking a *different* route than the one already verified clears the verification on the
     * spot, which is what collapses [ListenScreen]'s route-check and level sections back down.
     * [SetupStore.clearInputVerification] is the same call the mismatch/re-verify paths already make —
     * constitution IV: a route the operator has since changed is exactly as unproven as one that never
     * passed, and the merged screen makes that visible rather than leaving a green checklist sitting
     * above a row nobody selected.
     */
    internal fun onSelectInput(id: String) {
        if (id != selectedInputId && store.inputVerified) {
            store.clearInputVerification()
            verifyState = null
            levelState = null
        }
        selectedInputId = id
        selectedInputLabel = inputRoutes.firstOrNull { it.id == id }?.label
    }

    /** Starts the route check **in place** (P39): the checklist expands on [SetupStep.LISTEN] rather
     * than navigating to a screen of its own. `navigateForward` is deliberately absent — there is
     * nowhere to go. */
    internal fun onStartVerify() {
        val id = selectedInputId ?: return
        store.selectedInputId = id
        store.selectedInputLabel = selectedInputLabel
        store.clearInputVerification()
        syncCaptureConfiguration()
        verifyState = null
        levelState = null
        verifyRunToken += 1
        verifyRequested = true
    }

    private fun selectedDescriptor(): AudioDeviceDescriptor? =
        audioIo.availableDevices().firstOrNull { it.id == selectedInputId }

    // --- Route check and the one hard halt ---------------------------------------------------

    /**
     * **Constitution IV, unchanged by P39 and this is the load-bearing line.** A
     * [RouteCheckState.Mismatch] still leaves [SetupStep.LISTEN] for [SetupStep.ROUTE_MISMATCH] — the
     * one deliberate hard halt in the product. Merging the verify screen into the listen screen moved
     * where the check is *shown*; it moved nothing about whether it blocks.
     *
     * A [RouteCheckState.Passed] is the only thing that ever writes [SetupStore.inputVerified], and
     * that flag is the only gate in [SetupStateMachine.stepFor] with no acknowledged escape.
     */
    internal fun onVerifyStateChanged(new: RouteCheckState) {
        verifyState = new
        if (new is RouteCheckState.Mismatch) {
            navigateForward(SetupStep.ROUTE_MISMATCH)
        } else if (new is RouteCheckState.Passed) {
            store.inputVerified = true
            store.verifiedNativeRateHz = new.nativeRateHz
            store.verifiedResamplerIdentity = new.resamplerDescription
            // R-1169: recorded alongside the other two facts about this open, for the same reason.
            store.verifiedAudioSource = new.audioSourceLabel
            // The level meter is only meaningful once the route is proven, and this is that moment —
            // the third section of the merged screen starts measuring as it appears.
            levelState = null
            levelRunToken += 1
        }
    }

    internal fun onChooseAnotherInput() {
        store.clearInputVerification()
        verifyState = null
        verifyRequested = false
        levelState = null
        step = SetupStep.LISTEN
    }

    internal fun onTryVerifyAgain() {
        verifyState = null
        verifyRunToken += 1
        verifyRequested = true
        step = SetupStep.LISTEN
    }

    // --- Level ----------------------------------------------------------------------------------

    internal fun onLevelStateChanged(new: LevelCheckState) {
        levelState = new
        if (new is LevelCheckState.Reading) {
            store.levelPeakDbfs = new.level.peakDbfs
            store.levelInBand = new.level.band == LevelBand.IN_BAND
        }
    }

    /**
     * R-1168 (register): the operator moved S07's gain slider. Two writes, both required — the
     * preference so the choice survives the process, and [CaptureGain] so it takes effect on the
     * very next `AndroidAudioIo.read`, which is what makes the meter the operator is watching move
     * in response. Anything less is the R-1171 pattern: a control that is persisted, rendered and
     * read by nobody.
     */
    internal fun onGainChanged(db: Int) {
        CaptureGain.setGainDb(db)
        gainDb = CaptureGain.decibels
        store.captureGainDb = CaptureGain.decibels
    }

    /**
     * `Continue` on [SetupStep.LISTEN] — R-1170/AC-201: never disabled for validation. The rule is
     * keep the button lit, validate on tap, and say what is missing; a quiet band at two in the
     * morning used to strand the operator with no forward, no back and no later.
     *
     * "Validate on tap" is this: the unresolved state is **written down** rather than blocked on, so
     * `Ready`'s Level row lights its amber `Fix` instead of the operator being told nothing. The write
     * is explicit rather than left to whatever [onLevelStateChanged] last saw, because the case that
     * matters is the one where it saw nothing at all (an input that never produced a reading), and a
     * stale `levelInBand` from an earlier route must not carry a green marker past a step that never
     * went green.
     *
     * **P39 adds the second half R-1170 asked for**: [SetupStore.levelAcknowledged]. Writing
     * `levelInBand = false` alone was enough while the next screen was reached by `navigateForward`,
     * which does not consult the state machine — under [refreshStep] it would route straight back
     * here forever. The acknowledged flag is what makes "unresolved" different from "not yet reached"
     * (see [SetupSnapshot.levelAcknowledged] for why the *route* gate has no such escape and must not
     * gain one).
     */
    internal fun onListenContinue() {
        if ((levelState as? LevelCheckState.Reading)?.level?.band != LevelBand.IN_BAND) {
            store.levelInBand = false
            store.levelAcknowledged = true
        }
        refreshStep(pushCurrent = true)
    }

    // --- S08 Overnight ----------------------------------------------------------------------

    internal fun onOpenBatterySetting() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "battery-exemption request denied", e)
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.w(TAG, "no handler for battery-exemption request", e)
        }
        store.overnightStepSeen = true
        refreshStep(pushCurrent = true)
    }

    /**
     * **R-1162**: [SetupStep.OVERNIGHT] can be shown *after* setup is already complete (that is what
     * `MainActivity`'s own AC-189 re-ask does), and a step in that position must offer a way back
     * into the app, not only a forward action. It cannot use [onBack]: a cold resume deliberately
     * calls `refreshStep(pushCurrent = false)` and pushes nothing onto [backStack] (that function's
     * own doc comment is right that it must not fabricate an entry the operator never navigated
     * through), so an exit here needs a real destination, and [handBackToMainActivity] is the only
     * honest one — the app the operator came from.
     *
     * Deliberately writes nothing. The operator declined to answer rather than answering, so
     * [SetupStore.overnightStepSeen] stays as it was and AC-189's reappearance stands for the next
     * process; what lets the router through on the way back is [OvernightNagState], which already
     * recorded that this process has asked. Offered only while [SetupStore.setupComplete] — on the
     * first-run walk there is genuinely nowhere to return *to*, which is what `onBack = null` on
     * this screen was always right about.
     */
    internal fun onReturnToAppFromOvernight() {
        handBackToMainActivity()
    }

    internal fun onSkipOvernight() {
        store.overnightStepSeen = true
        SetupFunnelAnalytics.skipped(SetupStep.OVERNIGHT.name)
        refreshStep(pushCurrent = true)
    }

    // --- S09 Radio picker (D33/P19 WPD: generated from :rig's RigCatalogue) -----------------------

    /**
     * R-344 (validator pass 4, halt), extended by D33/P19: the null module ("No radio — I will
     * enter the frequency") used to write [SetupStore.radioChoice] and call [refreshStep]
     * immediately, the same instant as the tap — since that alone satisfies
     * [SetupStateMachine.stepFor]'s `radioChoice == null -> RADIO` gate, setup advanced straight
     * past S12 with `manualFrequencyHz` still `null`, having never asked for a frequency at all
     * (confirmed reproduced 3× by the validator). Routed to the same frequency-entry screen
     * ([RadioUsbScreen], S10) the CAT-rig-with-no-support fallback already uses instead —
     * [SetupStore.radioChoice] is deliberately **not** written here for the null module; only
     * [onEnterFrequency] writes it, and only once a real value is confirmed, the same gate that
     * already protects the CAT-rig fallback path. A real rig moves to S09b ([SetupStep.RIG_TRANSPORT])
     * — never straight to S10/S11 the way the pre-catalogue three-row screen did, since which
     * transport to use is now its own decision (FR-RIG-13).
     */
    internal fun onChooseRig(entry: RigCatalogueEntry) {
        radioAbsentBanner = null
        radioImportError = null
        store.rigId = entry.id
        if (entry.id == NullRigModule.ID) {
            store.rigTransport = null
            syncCaptureConfiguration()
            rigStatusSnapshot = RigStatus.state
            navigateForward(SetupStep.RADIO_USB)
            return
        }
        store.radioChoice = if (entry.displayName.contains("TH-D75A")) {
            RadioChoice.TH_D75A
        } else {
            RadioChoice.OTHER_CAT_RIG
        }
        // D33/E2-E09: a freshly chosen rig always re-evaluates the preset from scratch -- clearing
        // the selection first means applyPresetRigTransportIfUnselected() (below) never skips
        // re-computation because a *previous* rig's own selection was still sitting here.
        selectedRigTransportKind = null
        applyPresetRigTransportIfUnselected()
        syncCaptureConfiguration()
        navigateForward(SetupStep.RIG_TRANSPORT)
    }

    /**
     * D33/E2-E09 (WPI's `setup-rig-transport-preset` scenario found this gap — the same rule R-902
     * already applies to S04): S09b's transport pre-selects the mode's own preset only when the
     * chosen rig's own catalogue entry actually declares support for it (constitution I — never
     * guessed for a rig that cannot do it) and only while nothing is chosen yet and the axis has
     * not already been overridden this session. Called from the interactive [onChooseRig] tap *and*
     * from every cold/resumed entry straight onto [SetupStep.RIG_TRANSPORT] ([refreshStep]/
     * [tryOpenAtRequestedStep]) — a debug scenario seeding [SetupStore.rigId] directly, or a
     * process-death resume — the same dual-call-site shape [onChooseMode]'s own S04 pre-select
     * (`applyPresetInputIfUnselected`) already established.
     */
    private fun applyPresetRigTransportIfUnselected() {
        if (selectedRigTransportKind != null || store.modeOverriddenRig) return
        val entry = currentRigEntry() ?: return
        val modePresetKind = store.captureMode?.let(CaptureModePresets::presetsFor)?.preferredRigTransportKind
        selectedRigTransportKind = presetRigTransportFor(modePresetKind, entry.transportCapabilities.keys)
    }

    internal fun onImportRig() {
        openDescriptorLauncher.launch(arrayOf("application/json", "*/*"))
    }

    internal fun onRadioNotNow() {
        store.radioChoice = RadioChoice.NONE
        store.rigId = NullRigModule.ID
        store.rigTransport = null
        radioAbsentBanner = null
        syncCaptureConfiguration()
        refreshStep(pushCurrent = true)
    }

    // --- S09b Rig transport (D33, FR-RIG-13/14/17) -------------------------------------------------

    internal fun onSelectRigTransport(kind: RigTransportKind) {
        selectedRigTransportKind = kind
    }

    internal fun onConnectRigTransport() {
        val kind = selectedRigTransportKind ?: return
        val presetKind = RigPickerCatalogue.toPresetKind(kind)
        store.rigTransport = presetKind
        val modePresetKind = store.captureMode?.let(CaptureModePresets::presetsFor)?.preferredRigTransportKind
        store.modeOverriddenRig = presetKind != modePresetKind
        syncCaptureConfiguration()
        if (kind == RigTransportKind.BLUETOOTH_SPP) {
            rigBluetoothSelectedAddress = null
            refreshPairedDevices()
            navigateForward(SetupStep.RIG_BLUETOOTH)
        } else {
            // R-903: the USB lane has no timed probe at all -- never carry a Bluetooth attempt's
            // own measured duration into an S11 visit this lane reaches directly.
            rigLinkVerifiedDurationSeconds = null
            // R-1014: the same reasoning -- never carry a prior Bluetooth attempt's own
            // missing-capability list into a USB-lane S11 visit either.
            rigLinkMissingCapabilities = emptyList()
            // Matches the pre-catalogue onChooseRadio's own dispatch: a genuinely Connected
            // RigStatus (today, only ever produced by the debug scenario simulator -- :rig-usb's
            // real transport is not wired to :app, RigLinkPort.kt's own doc comment) skips the
            // honest "no rig support" fallback and goes straight to S11.
            rigStatusSnapshot = RigStatus.state
            val next = if (rigStatusSnapshot is RigStatus.State.Absent) {
                SetupStep.RADIO_USB
            } else {
                SetupStep.RADIO_VERIFIED
            }
            navigateForward(next)
        }
    }

    internal fun onRigTransportBack() {
        step = SetupStep.RADIO
    }

    // --- S10b Rig Bluetooth link (D33/D34, FR-RIG-14/15) --------------------------------------------

    internal fun onSelectRigBluetoothDevice(address: String) {
        rigBluetoothSelectedAddress = address
        rigLinkState = null
        rigLinkRunToken += 1
        // R-903: a fresh attempt's own start -- never inherits a duration from a previous pick.
        rigLinkVerifyStartedAtNanos = clock.monotonicNanos()
        rigLinkVerifiedDurationSeconds = null
        // R-1014: a fresh attempt's own start -- never inherits a prior pick's own missing-capability
        // list either.
        rigLinkMissingCapabilities = emptyList()
    }

    /** R-903: [RenderRigBluetooth]'s own `LaunchedEffect` routes every [RigLinkState] emission
     * through here rather than assigning [rigLinkState] directly, so the one moment the checklist
     * first reaches [RigLinkState.Verified] is also the one moment S11's real, measured verify
     * duration gets computed — from [rigLinkVerifyStartedAtNanos], never recomputed on a later
     * recomposition even though [rigLinkState] itself does not change again after `Verified`. */
    internal fun onRigLinkStateChanged(new: RigLinkState) {
        rigLinkState = new
        if (new is RigLinkState.Verified && rigLinkVerifiedDurationSeconds == null) {
            rigLinkVerifyStartedAtNanos?.let { startedAt ->
                rigLinkVerifiedDurationSeconds = (clock.monotonicNanos() - startedAt) / NANOS_PER_SECOND
            }
        }
    }

    internal fun onRefreshRigBluetoothDevices() {
        refreshPairedDevices()
    }

    /**
     * R-1005a/R-1005b: the S10b `NoPermission` banner's new `Grant permission` action
     * ([RigBluetoothScreen]'s own doc comment) — requests both dangerous Bluetooth permissions
     * together (`BLUETOOTH_SCAN` was never declared before this; R-1005a's own manifest fix is
     * what makes requesting it here meaningful rather than a silent no-op). Below API 31 neither
     * is a dangerous, runtime-requested permission at all (the legacy `BLUETOOTH`/manifest-granted
     * `BLUETOOTH_ADMIN` cover the same ground) — [refreshPairedDevices] directly, mirroring
     * [requestBluetoothConnect]'s own identical branch for the S02c case.
     */
    internal fun requestRigBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                REQUEST_CODE,
            )
        } else {
            refreshPairedDevices()
        }
    }

    /**
     * R-1005c (device field report — a trapped operator): the real escape [RigBluetoothScreen]'s
     * new `Continue without connecting` wires to. Lands on FR-RIG-2's existing null-module/manual-
     * frequency path exactly the way an unsupported CAT rig already does ([RadioUsbScreen]'s own
     * doc comment) — [rigStatusSnapshot] is set to [RigStatus.State.Absent] (honest: no live rig
     * connection exists either way) so [SetupStep.RADIO_USB] renders its real manual-frequency
     * entry, never a fabricated checklist. [store.rigTransport] is cleared, not merely left as
     * `BLUETOOTH_SPP`: [SetupStateMachine.needsRigBluetoothLink] gates on exactly that field, and a
     * rig transport still recorded as Bluetooth would send the very next [refreshStep] straight
     * back to [SetupStep.RIG_BLUETOOTH] — the trap this action exists to end. [store.rigId] and
     * [store.radioChoice] are deliberately **not** cleared here: [onEnterFrequency] (the only
     * forward action [RadioUsbScreen] offers) sets [store.radioChoice] to [RadioChoice.NONE]
     * itself once a real frequency is entered, at which point the rig is honestly "no radio, manual
     * frequency" — the identical, already-verified state and Ready-screen row a genuine "no radio"
     * choice produces (`readyRowsFor`'s own `radioRow`). Setup then finishes with the rig unlinked
     * and retryable later: `store.rigId` survives that flip (an operator can always start over from
     * Ready's "Change" action, which clears it explicitly), and Settings' own `RIG_TRANSPORT`
     * re-entry point (`SettingsContent.kt`'s `onChangeRigLink`) already resolves against it directly
     * — `RigSupervisor.connect` itself degrades a `rigId` with no `rigTransportKind` straight to the
     * null module (confirmed by reading that file before writing this: `transportKind == null` is
     * checked before the rig id is even resolved against a descriptor), so this never risks a blank
     * re-entry screen or an unexpected live connection attempt in between.
     */
    internal fun onContinueWithoutRigLink() {
        store.rigTransport = null
        rigLinkVerifyStartedAtNanos = null
        rigLinkVerifiedDurationSeconds = null
        rigStatusSnapshot = RigStatus.State.Absent
        syncCaptureConfiguration()
        navigateForward(SetupStep.RADIO_USB)
    }

    /** FR-PLT-2/constitution I: [RigLinkPort.pairedDevices]' own [PairedDevicesResult.permissionGranted]
     * drives [RigLinkState.NoPermission] directly — the operator sees the "grant nearby devices"
     * banner the moment S10b (or a `Refresh`) finds the permission missing, never only after they
     * have already picked a device and tried to connect. */
    private fun refreshPairedDevices() {
        val result = rigLinkPort.pairedDevices()
        rigBluetoothDevices = result.devices
        rigLinkState = if (result.permissionGranted) null else RigLinkState.NoPermission
    }

    internal fun onPairRigBluetoothInSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    /**
     * R-1013/R-1014: the product decision is [RigLinkState.VerifyTimedOut] enables `Continue`,
     * [RigLinkState.IdentifyTimedOut] does not — [Identified] already proved this descriptor
     * matches the radio (it genuinely spoke); partial capability observation is normal operation
     * (a quiet band reports no signal strength, a VFO in use reports no memory channel), not a
     * fault worth blocking on. [rigLinkMissingCapabilities] carries exactly what was not seen
     * forward to S11 — never lost the moment this function returns, and never rendered there as a
     * full [RigLinkState.Verified] (constitution I; see [RadioVerifiedScreen]'s own doc comment).
     */
    internal fun onContinueRigBluetooth() {
        val address = rigBluetoothSelectedAddress ?: return
        val linkState = rigLinkState
        if (linkState !is RigLinkState.Verified && linkState !is RigLinkState.VerifyTimedOut) return
        store.rigBluetoothAddress = address
        store.rigBluetoothVerified = true
        // R-1005c follow-up: reaching this point at all means the operator selected and verified a
        // Bluetooth device, so the transport genuinely is Bluetooth SPP -- restored explicitly
        // (previously always already true by the time this ran) because onContinueWithoutRigLink
        // now makes it possible to arrive back here, via onBack, with store.rigTransport cleared.
        store.rigTransport = PresetRigTransportKind.BLUETOOTH_SPP
        rigLinkMissingCapabilities = (linkState as? RigLinkState.VerifyTimedOut)?.missingCapabilities.orEmpty()
        val entry = currentRigEntry()
        // Honest, not fabricated: no live per-band reading exists yet (the real DescriptorRigModule
        // poll loop cannot run from :app — RigLinkPort.kt's own doc comment). An empty band list,
        // never an invented one, is what S11 renders until a live session actually reports bands.
        rigStatusSnapshot = RigStatus.State.Connected(descriptor = entry?.displayName ?: "Rig", bands = emptyList())
        syncCaptureConfiguration()
        navigateForward(SetupStep.RADIO_VERIFIED)
    }

    internal fun onUseUsbInsteadForRig() {
        store.rigTransport = PresetRigTransportKind.USB_SERIAL
        rigStatusSnapshot = RigStatus.state
        syncCaptureConfiguration()
        navigateForward(SetupStep.RADIO_USB)
    }

    /**
     * S10's "Enter the frequency instead" — reached both from S09's third row (R-344) and straight
     * from a chosen CAT rig with no real support (see [RadioUsbScreen]'s own doc comment). [hz] is
     * `null` for a blank or unparseable entry ([parseMegahertzToHz]) — [RadioUsbScreen] itself now
     * disables this action's own button until [hz] would be real, but this function guards it too
     * (constitution I: never silently lose a fact) rather than trusting the UI alone — a `null` here
     * is a no-op, not a proceed-with-nothing.
     */
    internal fun onEnterFrequency(hz: Long?) {
        if (hz == null) return
        store.radioChoice = RadioChoice.NONE
        // P39/R-1167: the value goes to `CaptureConfigurationStore` and nowhere else. `SetupStore` no
        // longer carries it at all — two stores holding one fact is how the log header's edit would
        // have been silently overwritten, and a second copy nobody writes is R-1171's own pattern.
        // Passed explicitly here because this is the one place in setup that genuinely *changes* it,
        // as distinct from a sync that must preserve whatever is already there.
        syncCaptureConfiguration(manualFrequencyHz = hz)
        refreshStep(pushCurrent = true)
    }

    internal fun onRadioVerifiedContinue() {
        refreshStep(pushCurrent = true)
    }

    internal fun onChangeRadio() {
        store.radioChoice = null
        store.rigId = null
        store.rigTransport = null
        store.rigBluetoothAddress = null
        store.rigBluetoothVerified = false
        radioAbsentBanner = null
        // R-903: a later S11 visit (a different rig, or the same one over a different transport)
        // must never show a duration measured for a now-abandoned attempt.
        rigLinkVerifyStartedAtNanos = null
        rigLinkVerifiedDurationSeconds = null
        // R-1014: same reasoning -- a now-abandoned attempt's own missing-capability list must not
        // survive into whatever S11 visit comes next.
        rigLinkMissingCapabilities = emptyList()
        syncCaptureConfiguration()
        step = SetupStep.RADIO
    }

    /** S11's `Reconnect` on a [RigStatus.State.Stale] reading (register R-120..R-125) — re-reads
     * [RigStatus.state] rather than any real reconnect handshake, since `:rig`/`:rig-usb` are
     * unbuilt (there is nothing to actually command); this is honest re-polling, not a fabricated
     * action. Reaches [RigStatus.State.Connected] once the debug scenario (the only real producer
     * today) or, eventually, a real rig module reports current data again. */
    internal fun onReconnectRadio() {
        rigStatusSnapshot = RigStatus.state
        step = SetupStep.RADIO_VERIFIED
    }

    // --- Ready ------------------------------------------------------------------------------------

    /**
     * **The notification ask lives here now (P39, D58).** `NOTIFICATIONS` was a numbered setup stage
     * three screens before anything was captured; D58 moved it to *"the first capture start, in
     * context, where the persistent notification is about to appear"*, and this tap is that moment.
     *
     * The request is fired before the hand-back, from a visible screen the operator has just acted on,
     * so the system dialog arrives as an answer to something rather than out of a blank window.
     * [SetupStore.notificationsAskedAtCaptureStart] makes it exactly once, whatever the operator
     * answers: notifications never gate capture (R-002), so a refusal must cost nothing and asking a
     * second time would be worse than not asking again. [onRequestPermissionsResult] brings us back
     * here through [refreshStep], and this function then runs to completion.
     */
    internal fun onStartCapture() {
        if (shouldAskForNotifications()) {
            store.notificationsAskedAtCaptureStart = true
            pendingStartCapture = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
            return
        }
        store.setupComplete = true
        SetupFunnelAnalytics.completed(SetupStep.READY.name)
        handBackToMainActivity()
    }

    private fun shouldAskForNotifications(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !store.notificationsAskedAtCaptureStart &&
        !granted(Manifest.permission.POST_NOTIFICATIONS)

    /** Set only between firing the first-capture-start notification request and its result arriving —
     * see [onStartCapture]. Never persisted: a process death mid-dialog leaves the operator back on
     * `Ready` with `Start capture` still there to press, which is the honest recovery. */
    private var pendingStartCapture: Boolean = false

    /**
     * S12's `Install` (`Setup-Done.dc.html`): opens [ReaderActivity] at its `SETTINGS` destination
     * (`ReaderActivity.EXTRA_DESTINATION`, WP3 round 3) rather than blocking on `Start capture`
     * first — asset installation is a general app concern, reachable regardless of whether this
     * session has started yet. **Lands on Settings' root, not its `Assets` sub-screen directly**:
     * confirmed by reading `ui/settings/SettingsContent.kt` before writing this — it takes no
     * `initialScreen`/equivalent parameter yet (its `screen` state is entirely internal, the same
     * gap `ReaderNavigator.openSettingsStorage()`'s own doc comment already reports for the
     * identical reason), and WP3 defines no second extra to name a sub-screen. Does not `finish()`
     * this activity — the operator returns to `Ready` normally (the platform back stack), since
     * this is a lateral look-something-up, not setup completing.
     */
    internal fun onInstallModel() {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_DESTINATION, ReaderDestination.SETTINGS.name),
        )
    }

    private fun batteryExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    // --- shared -----------------------------------------------------------------------------

    private fun currentPermissionsState(): PermissionsState {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return PermissionsState(
            // R-1127/R-1107 (register): see `DebugMicPermissionOverride`'s own kdoc — a debug
            // scenario can make this report a genuine denial regardless of what `install.ps1`
            // actually granted, so `SetupStateMachine.stepFor`'s `MICROPHONE_DENIED` gate (which
            // requires both this and `micPermanentlyDenied()` below) can fire honestly in the tour.
            recordAudioGranted = !DebugMicPermissionOverride.active && granted(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS) ||
                store.notificationsSkipped,
            isIgnoringBatteryOptimizationsDiagnosticOnly = pm.isIgnoringBatteryOptimizations(packageName),
            // D33/S02c: below API 31 there is no dangerous BLUETOOTH_CONNECT permission at all --
            // the legacy BLUETOOTH permission is normal-protection and granted at install, so this
            // reports true unconditionally rather than ever prompting (PermissionsState's own doc
            // comment).
            // R-1179: see `DebugBluetoothPermissionOverride`'s own kdoc -- `install.ps1` grants
            // BLUETOOTH_CONNECT for the rig-Bluetooth rows, which made the one screen whose entire
            // purpose is to *request* it permanently unreachable. This seam lets a debug scenario make
            // the real permission-state function report an honest absence.
            bluetoothConnectGranted = !DebugBluetoothPermissionOverride.denied &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        granted(Manifest.permission.BLUETOOTH_CONNECT)
                    ),
        )
    }

    private fun micPermanentlyDenied(): Boolean {
        // R-1127/R-1107 — see `DebugMicPermissionOverride`'s own kdoc.
        if (DebugMicPermissionOverride.active) return true
        if (granted(Manifest.permission.RECORD_AUDIO)) return false
        val canShowRationale =
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        return store.micRequested && !canShowRationale
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** The one big `when` dispatching [step] to its screen composable. A member function (not a
     * top-level one) so it can read this activity's own state directly, the same way
     * `MainActivity`'s `setContent` block did before WP9. Each multi-line branch is its own small
     * private composable below, both to stay under detekt's complexity threshold and because each
     * one (VERIFY, LEVEL especially) has a real reason to be read on its own. P22 adds two more
     * branches (JURISDICTION_NOTICE, MODELS), pushing this past detekt's default threshold — the
     * same judgement call [SetupStateMachine.stepFor] already makes for the identical reason
     * (a flat per-step dispatch, not a method that grew complex by accident). */
    @Suppress("CyclomaticComplexMethod")
    @Composable
    private fun RenderStep() {
        val totalSteps = currentTotalSteps()
        when (step) {
            SetupStep.WELCOME -> RenderWelcome()
            SetupStep.MODE -> ModeScreen(
                onChoose = ::onChooseMode,
                totalSteps = totalSteps,
                onExitToApp = exitToAppOrNull(),
            )
            SetupStep.MICROPHONE_DENIED -> MicrophoneDeniedScreen(
                onOpenSettings = ::openAppSettings,
                onCheckAgain = ::checkMicAgain,
                onBack = ::onBack,
                onExitToApp = exitToAppOrNull(),
            )
            SetupStep.BLUETOOTH_PERMISSION -> BluetoothPermissionScreen(
                onAllow = ::requestBluetoothConnect,
                onNotNow = ::onDeclineBluetoothPermission,
                onExitToApp = exitToAppOrNull(),
            )
            SetupStep.LISTEN -> RenderListen(totalSteps)
            SetupStep.ROUTE_MISMATCH -> RenderRouteMismatch()
            // R-1162: the exit appears only once setup is already complete -- see
            // onReturnToAppFromOvernight's own doc comment for why the first-run walk has none.
            // P39: no longer reachable from `stepFor` at all (AC-189 as amended) — only from EXTRA_STEP
            // and from Ready's own overnight row.
            SetupStep.OVERNIGHT -> OvernightScreen(
                onOpenSetting = ::onOpenBatterySetting,
                onSkip = ::onSkipOvernight,
                onReturnToApp = if (store.setupComplete) ::onReturnToAppFromOvernight else null,
            )
            SetupStep.RADIO -> RenderRadioPicker()
            SetupStep.RIG_TRANSPORT -> RenderRigTransport()
            SetupStep.RADIO_USB -> RadioUsbScreen(
                rigStatus = rigStatusSnapshot,
                onBack = ::onBack,
                onEnterFrequency = ::onEnterFrequency,
            )
            SetupStep.RIG_BLUETOOTH -> RenderRigBluetooth()
            SetupStep.RADIO_VERIFIED -> RenderRadioVerified()
            SetupStep.MODELS -> RenderModels(totalSteps)
            SetupStep.READY -> RenderReady(totalSteps)
            null -> {}
        }
    }

    /**
     * AC-200: the exit back to the running app, supplied to every step that can be shown *after*
     * setup has once completed and `null` on a genuine first run, where there is nowhere to return to
     * and an exit would itself loop. One condition, one place — R-1162's own row asked for the set to
     * be enumerated rather than assumed to be one screen, and this is that enumeration.
     */
    private fun exitToAppOrNull(): (() -> Unit)? = if (store.setupComplete) ::handBackToMainActivity else null

    /**
     * AC-166, AC-180, AC-203. [org.ort.app.analytics.AnalyticsAppWiring.configureOnce] is idempotent
     * and safe to call from composition — `OrtApplication.onCreate` has always already run by the time
     * a real device reaches setup, but a Robolectric test's own `Application` never calls it, so this
     * calls it defensively, the same way `SettingsAnalyticsPolling.current` does.
     */
    @Composable
    private fun RenderWelcome() {
        org.ort.app.analytics.AnalyticsAppWiring.configureOnce(this)
        val controller = org.ort.app.analytics.AnalyticsAppWiring.controller
        var tier2 by remember { mutableStateOf(controller.isTierEnabled(org.ort.telemetry.AnalyticsTier.TIER_2)) }
        var tier3 by remember { mutableStateOf(controller.isTierEnabled(org.ort.telemetry.AnalyticsTier.TIER_3)) }
        WelcomeScreen(
            onBegin = ::onBegin,
            analytics = WelcomeAnalyticsState(
                tier2Enabled = tier2,
                tier3Enabled = tier3,
                destinationConfigured = org.ort.app.analytics.AnalyticsAppWiring.isDestinationConfigured(),
            ),
            onToggleTier2 = {
                onToggleAnalyticsTier(org.ort.telemetry.AnalyticsTier.TIER_2, it)
                tier2 = it
            },
            onToggleTier3 = {
                onToggleAnalyticsTier(org.ort.telemetry.AnalyticsTier.TIER_3, it)
                tier3 = it
            },
        )
    }

    /**
     * **[SetupStep.LISTEN] — the merged input / verify / level screen (P39).** The two live effects
     * that used to belong to two separate screens now run from one composable, each still keyed on its
     * own run token so a re-verify or a re-measure restarts cleanly.
     *
     * R-943: [DebugRouteCheckOverride.activeOverride] is read *ahead of* ever starting the real check —
     * the tour opens this step by a cold [EXTRA_STEP] launch, before any selection ever ran, so
     * [selectedDescriptor] is `null` and [RealRouteCheck] would never even start. A seeded override
     * emits once and [RealRouteCheck] never runs at all this composition.
     *
     * The level effect prefers a real, already-running [LevelStatus] over driving a second, competing
     * `AudioRecord` open of its own: [LevelStatus] is published only by `RealCaptureService`, so a live
     * [LevelStatus.State.Measured] means a real session already has this device open (an operator
     * revisiting setup while capture runs). [LevelStatus.state] is a plain `@Volatile` field, not
     * Compose-observable, so the poll — not a one-shot read — is what keeps the meter live.
     */
    @Composable
    private fun RenderListen(totalSteps: Int) {
        val chipState = presetChipStateFor(store.captureMode, store.modeOverriddenAudio, inputRoutes)
        // R-902: the same "exactly one match" rule presetInputRouteFor's own pre-select uses --
        // several equally-preferred routes are exactly as ambiguous for override-detection as they are
        // for pre-selection, never treated as if the first one enumerated were "the" preset.
        val presetRouteId = presetInputRouteFor(store.captureMode, inputRoutes)?.id
        val verified = store.inputVerified
        RunRouteCheckEffect()
        if (verified) RunLevelEffect()
        ListenScreen(
            state = ListenViewState(
                routes = inputRoutes,
                selectedId = selectedInputId,
                presetLabel = chipState.modeLabel,
                presetUnavailableText = chipState.presetUnavailableText,
                inputLabel = selectedInputLabel ?: "",
                inputVerified = verified,
                check = verifyState,
                checkRunning = verifyRequested,
                level = levelState,
                gainDb = gainDb,
            ),
            actions = ListenActions(
                onSelect = {
                    if (isAudioRouteOverride(store.captureMode, presetRouteId, it)) store.modeOverriddenAudio = true
                    onSelectInput(it)
                },
                onRefresh = ::refreshInputRoutes,
                onVerify = ::onStartVerify,
                onContinue = ::onListenContinue,
                onTryAgain = ::onTryVerifyAgain,
                onChooseAnotherInput = ::onChooseAnotherInput,
                onGainChange = ::onGainChanged,
            ),
            onBack = ::onBack,
            totalSteps = totalSteps,
            onExitToApp = exitToAppOrNull(),
        )
    }

    @Composable
    private fun RunRouteCheckEffect() {
        val override = DebugRouteCheckOverride.activeOverride
        val selection = selectedDescriptor()
        if (override != null) {
            LaunchedEffect(override) {
                verifyRequested = true
                onVerifyStateChanged(override)
            }
        } else if (selection != null && verifyRequested) {
            LaunchedEffect(verifyRunToken) {
                RealRouteCheck().run(audioIo, selection).collect { onVerifyStateChanged(it) }
            }
        }
    }

    @Composable
    private fun RunLevelEffect() {
        if (LevelStatus.state is LevelStatus.State.Measured) {
            LaunchedEffect(levelRunToken) {
                while (true) {
                    val measured = LevelStatus.state
                    if (measured is LevelStatus.State.Measured) {
                        val reading = levelReadingFrom(measured, LevelStatus.peakHistoryDbfs)
                        onLevelStateChanged(LevelCheckState.Reading(reading))
                    }
                    delay(LEVEL_STATUS_POLL_INTERVAL_MILLIS)
                }
            }
        } else {
            val selection = selectedDescriptor()
            if (selection != null) {
                LaunchedEffect(levelRunToken) {
                    RealLevelCheck().run(audioIo, selection).collect { onLevelStateChanged(it) }
                }
            }
        }
    }

    @Composable
    private fun RenderRadioPicker() {
        val presetLabel = store.captureMode?.operatorLabel?.takeUnless { store.modeOverriddenRig }
        val catalogue = radioCatalogue
        // R-815: the currently chosen/preset rig, else the catalogue's own verified entry --
        // RadioPickerViewState's own doc comment has the full account.
        val emphasizedEntryId = store.rigId ?: catalogue.entries().firstOrNull { it.verified }?.id
        RadioScreen(
            state = RadioPickerViewState(
                catalogue = catalogue,
                presetLabel = presetLabel,
                importError = radioImportError,
                emphasizedEntryId = emphasizedEntryId,
            ),
            onChoose = ::onChooseRig,
            onImport = ::onImportRig,
            onNotNow = ::onRadioNotNow,
            banner = radioAbsentBanner,
        )
    }

    @Composable
    private fun RenderRigTransport() {
        val entry = currentRigEntry() ?: return
        val modePresetKind = store.captureMode?.let(CaptureModePresets::presetsFor)?.preferredRigTransportKind
        val options = entry.transportCapabilities.keys
            .filter { it != RigTransportKind.NONE }
            .sortedBy { if (it == RigTransportKind.BLUETOOTH_SPP) 0 else 1 }
            .map { kind ->
                val isPreset = modePresetKind != null && RigPickerCatalogue.toPresetKind(kind) == modePresetKind
                RigTransportOption(
                    kind = kind,
                    label = RigPickerCatalogue.transportLabel(kind),
                    subLabel = rigTransportSubLabel(kind, isPreset),
                    capabilities = RigPickerCatalogue.capabilitiesFor(entry, kind),
                    costLine = rigTransportCostLine(kind),
                    isPreset = isPreset,
                )
            }
        RigTransportScreen(
            // R-941 (register, reviewer A3 run 4a): the same manufacturer-prefix strip R-845
            // (CF06) and R-903 (S11) already apply, reused rather than duplicated.
            state = RigTransportViewState(
                SettingsPolling.stripManufacturerPrefix(entry.displayName),
                options,
                selectedRigTransportKind,
            ),
            onSelect = ::onSelectRigTransport,
            onConnect = ::onConnectRigTransport,
            onBack = ::onRigTransportBack,
        )
    }

    @Composable
    private fun RenderRigBluetooth() {
        val entry = currentRigEntry()
        val address = rigBluetoothSelectedAddress
        if (address != null) {
            LaunchedEffect(rigLinkRunToken, address) {
                rigLinkPort.connect(address, entry?.id ?: "").collect(::onRigLinkStateChanged)
            }
        }
        RigBluetoothScreen(
            state = RigBluetoothViewState(
                // R-1016 (register): the same manufacturer-prefix strip R-941 (S09b), R-845 (CF06)
                // and R-903 (S11) already apply, reused rather than a second one written.
                rigDisplayName = entry?.let { SettingsPolling.stripManufacturerPrefix(it.displayName) } ?: "the rig",
                devices = rigBluetoothDevices,
                selectedAddress = rigBluetoothSelectedAddress,
                linkState = rigLinkState,
            ),
            onSelectDevice = ::onSelectRigBluetoothDevice,
            onPairInSettings = ::onPairRigBluetoothInSettings,
            onRefresh = ::onRefreshRigBluetoothDevices,
            onContinue = ::onContinueRigBluetooth,
            onContinueWithoutConnecting = ::onContinueWithoutRigLink,
            onUseUsbInstead = ::onUseUsbInsteadForRig,
            onRequestBluetoothPermission = ::requestRigBluetoothPermissions,
            onBack = ::onBack,
        )
    }

    /** R-222 (validator pass 2): looks up the chosen device's already-resolved
     * [InputRouteOption.typeLabel] from S04's own route list, rather than re-deriving it from the
     * coarser [org.ort.capture.android.AudioDeviceDescriptor.kind] `RouteCheckState.Mismatch`
     * itself carries — see [RouteMismatchScreen]'s own doc comment for why. Falls back to a plain
     * `"Unknown"` only when the route list is somehow empty at this point (never reachable through
     * the ordinary S04 -> S05 -> S06 flow, since [onStartVerify] cannot fire without a selection
     * already present in that same list) — never a crash, and never a fabricated specific type.
     *
     * **R-1127/R-282 (register).** A cold `EXTRA_STEP=ROUTE_MISMATCH` launch (the tour's own debug
     * entry — the same shape [RenderVerify] already handles for S05, R-943) never visits
     * [RenderVerify] at all, so [verifyState] was never set by a real check and this composable
     * used to render nothing. Backfills it from [DebugRouteCheckOverride.activeOverride] directly,
     * in a [LaunchedEffect] rather than through [onVerifyStateChanged] — that function also calls
     * [navigateForward], which would push a redundant `ROUTE_MISMATCH -> ROUTE_MISMATCH` backstack
     * entry when landing here cold already on this step.
     */
    @Composable
    private fun RenderRouteMismatch() {
        val override = DebugRouteCheckOverride.activeOverride
        if (verifyState == null && override != null) {
            LaunchedEffect(override) { verifyState = override }
        }
        val mismatch = verifyState as? RouteCheckState.Mismatch ?: return
        val selectedTypeLabel = inputRoutes.firstOrNull { it.id == mismatch.selected.id }?.typeLabel ?: "Unknown"
        RouteMismatchScreen(
            mismatch = mismatch,
            selectedTypeLabel = selectedTypeLabel,
            onChooseAnotherInput = ::onChooseAnotherInput,
            onTryAgain = ::onTryVerifyAgain,
            onBack = ::onChooseAnotherInput,
            onExitToApp = exitToAppOrNull(),
        )
    }

    /**
     * Validator finding (register R-120..R-125, halt): this used to cast straight to
     * [RigStatus.State.Connected] and `return` (rendering nothing) for anything else — a
     * [RigStatus.State.Stale] reading (the real `rig-lost` scenario, reached through
     * [onChooseRadio]) hit exactly that `return` and left the operator on a blank black screen.
     * [RadioVerifiedScreen] itself now renders `Connected`/`Stale` both honestly; the one case
     * still handled here, before ever calling it, is [RigStatus.State.Absent] — reachable only if
     * the rig status changes *between* [onChooseRadio]'s snapshot and this composition (e.g. an
     * `onResume` re-check while already on S11), since [onChooseRadio] itself already routes an
     * `Absent` reading to S10, never S11. "Routes back to S09 with a banner, never a blank
     * screen" (the finding's own words) is exactly what this `LaunchedEffect` does.
     */
    @Composable
    private fun RenderRadioVerified() {
        val status = rigStatusSnapshot
        if (status is RigStatus.State.Absent) {
            LaunchedEffect(Unit) {
                radioAbsentBanner = "The rig connection was lost before setup could verify it. " +
                    "Choose a radio again, or enter the frequency by hand."
                step = SetupStep.RADIO
            }
            return
        }
        val rigTransportKind = store.rigTransport?.let(RigPickerCatalogue::fromPresetKind)
        RadioVerifiedScreen(
            state = status,
            onContinue = ::onRadioVerifiedContinue,
            onChangeRadio = ::onChangeRadio,
            onReconnect = ::onReconnectRadio,
            transportLabel = store.rigTransport?.let {
                when (it) {
                    PresetRigTransportKind.USB_SERIAL -> "USB serial"
                    PresetRigTransportKind.BLUETOOTH_SPP -> "Bluetooth SPP"
                }
            },
            verifyDurationSeconds = rigLinkVerifiedDurationSeconds,
            sameCommandSetAsUsb = sameCommandSetAsUsb(
                currentRigEntry()?.transportCapabilities.orEmpty(),
                rigTransportKind,
            ),
            missingCapabilities = rigLinkMissingCapabilities,
        )
    }

    @Composable
    private fun RenderReady(totalSteps: Int) {
        val actions = ReadyActions(
            // P39: Input and Level are one screen now, so both amber rows lead to the same place —
            // which is also where an operator who has to fix either one would have to end up anyway.
            onFixInput = { step = SetupStep.LISTEN },
            onFixLevel = { step = SetupStep.LISTEN },
            onFixOvernight = { step = SetupStep.OVERNIGHT },
            onFixRadio = { step = SetupStep.RADIO },
            // R-285: the same clear-then-navigate callback S11's own "Change radio" already uses
            // ([RadioVerifiedScreen]), not the bare step jump [onFixRadio] is — this row is already
            // `ok`, not broken, so a stray leftover `radioAbsentBanner` must not appear on S09.
            onChangeRadio = ::onChangeRadio,
            onInstallModel = ::onInstallModel,
            onChangeMode = { step = SetupStep.MODE },
        )
        val modelsState = ModelsController.currentState(this)
        // AC-189: never the OS's own exemption flag alone -- see OvernightSurvivalState's own doc
        // comment.
        val overnightState = OvernightSurvivalState(
            batteryExemptDiagnostic = batteryExempt(),
            survivalProven = store.overnightSurvivalProven,
        )
        val rows = readyRowsFor(
            store,
            overnightState,
            rigStatusSnapshot,
            modelsState,
            actions,
            // P39/R-1167: from the store that owns it now, not from SetupStore -- see
            // inForceManualFrequencyHz's own doc comment.
            manualFrequencyHz = inForceManualFrequencyHz(),
        )
        ReadyScreen(
            state = ReadyViewState(rows),
            onStartCapture = ::onStartCapture,
            totalSteps = totalSteps,
            onExitToApp = exitToAppOrNull(),
        )
    }

    /** P22 (D43, FR-AST-10..12) — `SetupStep.MODELS`: whatever the detected tier requires that
     * this build did not bundle ([ModelsViewState.rowsRequiringDownload]), each row's real
     * download state read from [ModelDownloadWorker.observe]/[ModelDownloadWorker.currentSnapshot]
     * on a plain poll (matching this class's own [LEVEL_STATUS_POLL_INTERVAL_MILLIS] convention),
     * never a second, drifting progress model. `Continue` re-runs [refreshStep] exactly like every
     * other forward action in this class — [SetupStateMachine.stepFor]'s own `requiredModelsInstalled`
     * gate is the one true authority on whether this step is actually done. */
    @Composable
    private fun RenderModels(totalSteps: Int) {
        var wifiOnly by remember { mutableStateOf(true) }
        var rows by remember { mutableStateOf(initialModelsSetupRows(this)) }
        LaunchedEffect(Unit) {
            while (true) {
                rows = refreshedModelsSetupRows(this@SetupActivity)
                delay(LEVEL_STATUS_POLL_INTERVAL_MILLIS)
            }
        }
        ModelsSetupScreen(
            state = ModelsSetupViewState(rows = rows, wifiOnly = wifiOnly),
            onDownload = { id -> ModelDownloadWorker.start(this, id, wifiOnly) },
            onToggleWifiOnly = { wifiOnly = it },
            onContinue = { refreshStep(pushCurrent = true) },
            totalSteps = totalSteps,
            onExitToApp = exitToAppOrNull(),
        )
    }

    /** The MODELS step's first, synchronous paint — no `WorkManager` read needed for the very
     * first frame (nothing can be mid-download before this screen has ever been shown this
     * process), so this is a plain [ModelsController] read: every downloadable, tier-required
     * row is [ModelDownloadRowStatus.INSTALLED] if it already is, [ModelDownloadRowStatus.PENDING]
     * otherwise. [refreshedModelsSetupRows] is what layers real `WorkManager` state on top from
     * the first poll onward. [DebugModelsSetupOverride.activeOverride] is read first — see that
     * object's own kdoc for the real production gap it stands in for. */
    private fun initialModelsSetupRows(context: Context): List<ModelDownloadRowViewState> =
        DebugModelsSetupOverride.activeOverride ?: ModelsController.currentState(context)
            .rowsForSetupModelsStep().map { row ->
                ModelDownloadRowViewState(
                    id = row.id,
                    label = row.label,
                    sizeBytes = row.sizeBytes ?: 0L,
                    status = if (row.status == ModelRowStatus.INSTALLED) {
                        ModelDownloadRowStatus.INSTALLED
                    } else {
                        ModelDownloadRowStatus.PENDING
                    },
                )
            }

    /** [ModelDownloadWorker.currentSnapshot] layered onto [ModelsController]'s own real installed
     * state — installed always wins outright (a row `WorkManager` never heard of, because it was
     * bundled to begin with or side-loaded, must still read as done); otherwise this is exactly
     * what the operator's last [ModelDownloadWorker.start]/[org.ort.app.ui.setup.SetupActivity]
     * poll actually found. [DebugModelsSetupOverride.activeOverride] is read first, and — since an
     * overridden capture has no real `WorkManager` job behind it — returned as-is on every poll
     * rather than layered with a live snapshot that would only ever read `NotRunning`/`null` and
     * silently downgrade a `FAILED`/`DOWNLOADING` override row back to `PENDING`. */
    private suspend fun refreshedModelsSetupRows(context: Context): List<ModelDownloadRowViewState> {
        DebugModelsSetupOverride.activeOverride?.let { return it }
        return ModelsController.currentState(context).rowsForSetupModelsStep().map { row ->
            val sizeBytes = row.sizeBytes ?: 0L
            if (row.status == ModelRowStatus.INSTALLED) {
                ModelDownloadRowViewState(row.id, row.label, sizeBytes, ModelDownloadRowStatus.INSTALLED)
            } else {
                when (val snapshot = ModelDownloadWorker.currentSnapshot(context, row.id)) {
                    is ModelDownloadSnapshot.Downloading ->
                        ModelDownloadRowViewState(row.id, row.label, sizeBytes, ModelDownloadRowStatus.DOWNLOADING)
                    is ModelDownloadSnapshot.Failed ->
                        ModelDownloadRowViewState(
                            row.id,
                            row.label,
                            sizeBytes,
                            ModelDownloadRowStatus.FAILED,
                            snapshot.reason,
                        )
                    ModelDownloadSnapshot.Succeeded, ModelDownloadSnapshot.NotRunning, null ->
                        ModelDownloadRowViewState(row.id, row.label, sizeBytes, ModelDownloadRowStatus.PENDING)
                }
            }
        }
    }

    /** S09b's per-transport sub-line (`Setup-Rig-Transport.dc.html`) — the preset case names it
     * generically ("preset by your mode") since S09b has no knowledge of the specific mode's own
     * board copy; the non-preset case names the physical connection. */
    private fun rigTransportSubLabel(kind: RigTransportKind, isPreset: Boolean): String = when {
        isPreset && kind == RigTransportKind.BLUETOOTH_SPP -> "preset by your mode · pair in system settings first"
        isPreset -> "preset by your mode"
        kind == RigTransportKind.BLUETOOTH_SPP -> "pair in system settings first"
        kind == RigTransportKind.USB_SERIAL -> "USB-C to the radio's data port · CDC, no driver"
        else -> ""
    }

    private fun rigTransportCostLine(kind: RigTransportKind): String = when (kind) {
        RigTransportKind.BLUETOOTH_SPP -> "Drops more often than a cable — capture continues, frequency marked stale"
        RigTransportKind.USB_SERIAL -> "USB permission does not survive a re-plug"
        else -> ""
    }

    internal companion object {
        /** R-903: the divisor [onRigLinkStateChanged] uses to turn a [org.ort.core.Clock.monotonicNanos]
         * delta into the real, decimal seconds [radioVerifiedSubtitle] renders ("1.2 s"). */
        private const val NANOS_PER_SECOND: Double = 1_000_000_000.0

        /** See this class's own doc comment. The name of a [SetupStep] entry, e.g. `"INPUT"`. */
        const val EXTRA_STEP: String = "step"

        /** See this class's own doc comment. A `Float` `fontScale`, e.g. `2.0f`. Absent means
         * "render at the platform's own font scale" — never assume `1.0f` means the same thing as
         * absence; they differ whenever the platform's own real setting is not already `1.0`. */
        const val EXTRA_FONT_SCALE: String = "font_scale"

        /**
         * Debug-only (tour builder follow-up, `spec/e2e-capture-modes-plan.md` WPD): a paired
         * device's Bluetooth MAC address to pre-select on S10b, honored only when [isDebugBuild]
         * reports true — see this constant's doc, and [DebugRigLinkPortOverride]'s own class kdoc
         * for why one of these seams exists at all here. [SetupStore]'s `RIG_BLUETOOTH` gate is
         * resumable, but the connect -> identify -> verify checklist and its drop/failure banners
         * (E2-E10/E2-E11) only ever animate after [onSelectRigBluetoothDevice] runs, which is
         * normally a tap on a paired-device row (`RigBluetoothScreen`'s own `PairedDeviceRow`) — a
         * tap the screenshot tour (`app/src/debug/kotlin/org/ort/app/debug/tour`) cannot perform,
         * since it can only relaunch this activity with intent extras. Read once, in [onCreate],
         * immediately after the step resolves (whether via [EXTRA_STEP] or the ordinary
         * [refreshStep] resume) — a no-op unless that resolved step is exactly `RIG_BLUETOOTH`, so
         * launching at any other step (or a debug build with no `RIG_BLUETOOTH` gate pending)
         * ignores this extra entirely rather than fabricating a selection nothing asked for. In a
         * release build [isDebugBuild] reports false and the extra is never even read, matching
         * [DebugRigLinkPortOverride.activeOverride]'s own gating exactly.
         */
        const val EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS: String = "debug_rig_bluetooth_address"

        /** Test seam — see [EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS]'s own doc comment for why one is
         * needed, identically to [DebugRigLinkPortOverride.isDebugBuild]: Robolectric only ever
         * compiles this module's debug variant, so `BuildConfig.DEBUG` alone cannot prove "ignored
         * in a release build" from a unit test. Production code never assigns this. */
        @Volatile
        internal var isDebugBuild: () -> Boolean = { BuildConfig.DEBUG }

        const val TAG = "SetupActivity"
        const val REQUEST_CODE = 1002

        /** Matches `RealLevelCheck.DEFAULT_SAMPLE_INTERVAL_MILLIS` and `RealCaptureService`'s own
         * roughly-10Hz frame-read cadence (`LevelStatus`'s own doc comment) — polling any faster
         * would just re-read the same snapshot. */
        const val LEVEL_STATUS_POLL_INTERVAL_MILLIS = 200L
    }
}
